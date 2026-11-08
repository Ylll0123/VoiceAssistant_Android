package com.example.voiceassistant.kws

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.voiceassistant.R
import com.example.voiceassistant.bus.AssistantBus
import com.example.voiceassistant.bus.Event
import com.example.voiceassistant.network.NetworkState
import com.example.voiceassistant.ui.VoiceAssistantActivity

/** 唤醒命中事件：界面收到开麦、播「我在」 */
data class WakeDetected(val word: String, val at: Long) : Event

/**
 * 唤醒词的常驻监听（**前台服务**）。
 *
 * ## 为什么必须是前台服务
 * Android 从 8.0 起，后台应用开麦克风会被直接掐掉；12 起连「启动前台服务」都受限。
 * 想让「叫一声就醒」，只能挂一个带常驻通知的前台服务，让用户知道麦克风在被监听。
 *
 * ## 循环是怎么转的
 * ```
 * startListening ──▶ onResults / onError ──▶ 判断有没有唤醒词
 *        ▲                                        │
 *        └──────── 等 600ms（防抖）◀──────────────┘
 * ```
 * 会话窗口内**主动停掉循环**：麦克风是独占的，循环不停，用户长按说话就抢不到。
 *
 * ## 功耗实话实说
 * 这不是低功耗 KWS。系统识别引擎一起来，CPU 和麦克风都在工作，
 * 挂着唤醒词比不挂明显费电。所以界面上给了一键关闭（`kws/stop`），也建议只在充电时开。
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** 连续失败次数：攒够就重建识别器（引擎会卡在坏状态里，光 cancel 救不回来） */
    private var consecutiveErrors = 0

    /**
     * 联网识别一直失败时改走离线。
     *
     * 国内机器连不上 Google 语音服务是常态：`isOnline()` 明明为 true，
     * 识别请求却一直 ERROR_NETWORK，表现同样是「开关开着、叫不醒」。
     * 连续两次网络类错误就切离线，有离线包就能继续用。
     */
    private var forceOffline = false

    /** 连续的网络类错误计数 */
    private var networkErrors = 0

    /** 被界面主动暂停（例如用户在长按说话、或界面在前台） */
    private var paused = false

    /** 会话窗口结束时间；> now 表示现在不用听唤醒词 */
    private var sessionUntil = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        /*
         * 从设置里把灵敏度 / 提示音读回来。
         *
         * WakeConfig 是纯内存单例，进程重建后是默认值；
         * 而用户明明在设置里调过——不在这里回灌，就会出现
         * 「设置显示高灵敏、实际按默认在跑」这种查不出来的错位。
         */
        runCatching {
            val saved = com.example.voiceassistant.data.AppSettings(this)
            WakeConfig.sensitivity = com.example.voiceassistant.kws.WakeWord
                .sensitivityOfLevel(saved.wakeSensitivity)
            WakeConfig.beepEnabled = saved.wakeBeep
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        /*
         * 前台服务起不来就直接收摊，不要留一个「开关亮着、其实什么都没干」的服务。
         * Android 12+ 在后台启动前台服务会被拒，14 起没有麦克风类型权限也会抛异常——
         * 这些情况下用户看到的应该是开关自己弹回去，而不是永远叫不醒。
         */
        if (!startForegroundCompat()) {
            WakeConfig.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ensureRecognizer()) {
            WakeConfig.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        WakeConfig.enabled = true
        paused = false
        scheduleLoop(0)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        WakeConfig.enabled = false
        instance = null
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        listening = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 对外控制

    /** 让位给真正的语音输入：暂停监听 */
    fun pause() {
        paused = true
        stopListening()
        main.removeCallbacks(loop)
    }

    /**
     * 恢复监听。
     *
     * **幂等**：没暂停时什么也不做。因为界面会从好几条路径调它
     * （识别结束、回答完、切回前台），重复排队只会让循环越来越慢。
     */
    fun resume() {
        if (!WakeConfig.enabled) return
        if (!paused) return
        paused = false
        // 留 400ms：上一轮 cancel 之后系统还没把麦克风还回来，
        // 立刻 startListening 十有八九是 ERROR_RECOGNIZER_BUSY
        scheduleLoop(RESUME_DELAY_MS)
    }

    // ------------------------------------------------------------------ 循环

    private fun scheduleLoop(delayMs: Long) {
        main.removeCallbacks(loop)
        main.postDelayed(loop, delayMs)
    }

    private val loop: Runnable = Runnable {
        if (!WakeConfig.enabled) return@Runnable

        /*
         * 暂停时**不要退出循环**，而是隔一秒再看。
         *
         * 以前这里直接 return，等于把循环掐死了：只要有一次 pause 之后没走到 resume
         * （比如用户按着说话直接退出应用），唤醒就永远醒不过来，
         * 表现是「开关还开着，但再也不响应」。
         */
        if (paused) {
            main.postDelayed(loop, 1_000)
            return@Runnable
        }

        // 会话窗口内不抢麦克风
        if (System.currentTimeMillis() < sessionUntil) {
            main.postDelayed(loop, 1_000)
            return@Runnable
        }
        startListening()
    }

    private fun ensureRecognizer(): Boolean {
        if (recognizer != null) return true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "系统没有可用的语音识别服务，唤醒不可用")
            return false
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
        return true
    }

    private fun startListening() {
        if (listening) return
        val engine = recognizer ?: return
        // 上一轮可能还没彻底收尾。先 cancel 再开，否则会一直 ERROR_RECOGNIZER_BUSY，
        // 表现就是「开关是开着的，但怎么叫都没反应」
        runCatching { engine.cancel() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            /*
             * 只在**本来就没网**、或者联网识别一直失败时才要求离线识别。
             *
             * 以前是永远带 EXTRA_PREFER_OFFLINE=true：没装中文离线包的机器
             * （国产 ROM、精简系统很常见）每一轮都直接失败，开关亮着却永远叫不醒。
             * 有网就让引擎走在线识别，准确率高得多；连不上再压离线，成不成就看本机包。
             */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                (forceOffline || !NetworkState.isOnline())
            ) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            // 短句：静音多久收尾，跟着灵敏度走（高灵敏更快收口，循环更密）
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                WakeWord.silenceMs(WakeConfig.sensitivity)
            )
        }
        listening = true
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            noteError(0)
            scheduleLoop(RETRY_MS)
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching { recognizer?.cancel() }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            listening = false

            // 权限被撤了就别硬撑，否则只是不停地报同一个错
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                Log.w(TAG, "录音权限没了，唤醒停止")
                WakeConfig.enabled = false
                stopSelf()
                return
            }
            if (!noteError(error)) return

            // 网络类错误：连续两次就切离线识别，别在「连不上」上空转
            if (error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
            ) {
                networkErrors++
                if (networkErrors >= 2 && !forceOffline) {
                    forceOffline = true
                    Log.w(TAG, "联网识别连不上，改用离线识别")
                    recycleRecognizer()
                }
            }

            when (error) {
                // 引擎还没从上一次收尾里缓过来：多等一会儿，别把循环压得更紧
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> scheduleLoop(BUSY_RETRY_MS)

                // 没人说话 / 说的不是唤醒词，属于常态，歇一下接着听
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> scheduleLoop(QUIET_RETRY_MS)

                else -> scheduleLoop(RETRY_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            listening = false
            consecutiveErrors = 0
            networkErrors = 0
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            /*
             * 「最近听到什么」比任何参数都重要：
             * - 这里一直是空 → 引擎根本没拿到人声（权限 / 中文包 / 麦克风被占）；
             * - 有内容但没命中 → 唤醒词或灵敏度的问题，方向完全不同。
             * 之前排查「叫不醒」全靠猜，就是因为这一行没打出来。
             */
            if (candidates.isNotEmpty()) {
                WakeConfig.lastHeard = candidates.first()
                WakeConfig.lastHeardAt = System.currentTimeMillis()
            }

            val strict = WakeConfig.strict
            val hit = candidates.firstOrNull { WakeWord.matches(it, WakeConfig.wakeWord, strict) }
            if (hit != null) {
                Log.i(TAG, "听到唤醒词：$hit（候选 ${candidates.joinToString("/")}）")
                onWake(hit)
            } else {
                if (candidates.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "听到「${candidates.first()}」，不是唤醒词「${WakeConfig.wakeWord}」" +
                            "（${if (strict) "严格" else "宽松"}匹配，灵敏度 ${WakeConfig.sensitivity}）"
                    )
                }
                scheduleLoop(WakeWord.quietRetryMs(WakeConfig.sensitivity))
            }
        }
    }

    /**
     * 记一次失败；连续失败太多就把识别器整个重建。
     *
     * 有些引擎（尤其国产 ROM 自带那套）出错后会卡在坏状态：
     * cancel 不报错、startListening 也不报错，但再也不会有结果回来。
     * 只有 destroy + create 才能恢复，表现上就是「不重启 App 永远叫不醒」。
     *
     * @return false 表示已经彻底停了，调用方不要再排下一轮
     */
    private fun noteError(error: Int): Boolean {
        lastError = error
        lastErrorAt = System.currentTimeMillis()
        consecutiveErrors++
        Log.w(TAG, "识别失败 error=$error（连续 $consecutiveErrors 次）")
        if (consecutiveErrors >= RECYCLE_AFTER) {
            recycleRecognizer()
            return WakeConfig.enabled
        }
        return true
    }

    private fun recycleRecognizer() {
        Log.w(TAG, "识别器连续失败，重建一个")
        consecutiveErrors = 0
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (!ensureRecognizer()) {
            Log.w(TAG, "重建失败，唤醒停止")
            WakeConfig.enabled = false
            stopSelf()
        }
    }

    /**
     * 应答提示音。
     *
     * 用 [ToneGenerator] 而不是 MediaPlayer：不占音频焦点、不需要资源文件、
     * 100ms 就结束，不会在打断用户正在听的音乐上留下痕迹。
     */
    private fun beep() {
        if (!WakeConfig.beepEnabled) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
            main.postDelayed({ runCatching { tone.release() } }, 400)
        }
    }

    /** 命中唤醒词：进入会话窗口、通知界面、暂停自己 */
    private fun onWake(heard: String) {
        val now = System.currentTimeMillis()
        sessionUntil = now + WakeConfig.sessionMs
        WakeConfig.lastWakeAt = now
        beep()

        if (WakeConfig.uiAlive) {
            AssistantBus.publish(WakeDetected(WakeConfig.wakeWord, now))
        } else {
            /*
             * 界面已经没了，进程内事件发出去也没人接。
             *
             * 又不能在后台直接 startActivity——Android 10 起后台启动界面会被系统拦，
             * 唯一的合法通道是通知。用户点一下进来，界面恢复后再叫一次就能接着说。
             */
            notifyHeard()
        }

        // 麦克风让给对话链路，窗口结束后自己回来
        main.removeCallbacks(loop)
        main.postDelayed(loop, WakeConfig.sessionMs + 500)
    }

    /** 界面不在时，用一条通知告诉用户「我听见了」 */
    private fun notifyHeard() {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createHeardChannel(manager)
            val open = PendingIntent.getActivity(
                this,
                // requestCode 必须和常驻通知的不一样：两个 PendingIntent 的 Intent 只差 extras，
                // 用同一个 code + UPDATE_CURRENT 会互相覆盖，常驻通知也会变成「点了就开麦」
                1,
                Intent(this, VoiceAssistantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(WakeConfig.EXTRA_WAKE_OPEN, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, HEARD_CHANNEL_ID)
                .setContentTitle(getString(R.string.wake_notification_title))
                .setContentText(getString(R.string.wake_hit_text))
                .setSmallIcon(R.drawable.ic_menu)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
            manager.notify(HEARD_NOTIFICATION_ID, notification)
        }
    }

    private fun createHeardChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(HEARD_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                HEARD_CHANNEL_ID,
                getString(R.string.wake_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    // ------------------------------------------------------------------ 通知

    private fun startForegroundCompat(): Boolean = runCatching {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    }.getOrElse { error ->
        Log.w(TAG, "前台服务起不来：${error.message}")
        false
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VoiceAssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.wake_notification_title))
            .setContentText(getString(R.string.wake_notification_text, WakeConfig.wakeWord))
            .setSmallIcon(R.drawable.ic_menu)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wake_channel_name),
                // 低优先级：不响铃、不弹横幅，只留一个「麦克风在用」的提示
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word"

        /** 「叫醒了但界面不在」时用的高优先级通知 */
        private const val HEARD_CHANNEL_ID = "wake_hit"

        private const val NOTIFICATION_ID = 0x9E01
        private const val HEARD_NOTIFICATION_ID = 0x9E02

        /** 活着的那一个；界面用它暂停/恢复，避免麦克风打架 */
        @Volatile
        var instance: WakeWordService? = null
            private set

        /** 最近一次识别错误码，给 [com.example.voiceassistant.provider.KwsSupply] 自查用 */
        @Volatile
        var lastError: Int = 0

        /** 最近一次出错的时间 */
        @Volatile
        var lastErrorAt: Long = 0L

        /** 连续失败这么多次就重建识别器 */
        private const val RECYCLE_AFTER = 5

        /** 没人说话时歇久一点，省电 */
        private const val QUIET_RETRY_MS = 800L

        /** 出错时稍等再试 */
        private const val RETRY_MS = 1_500L

        /** 引擎还没缓过来：等够再试，避免把循环压成空转 */
        private const val BUSY_RETRY_MS = 2_000L

        /** 让出麦克风后，等系统真正释放再回来 */
        private const val RESUME_DELAY_MS = 400L
    }
}
