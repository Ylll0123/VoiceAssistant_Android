package com.example.voiceassistant.kws

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    /** 被界面主动暂停（例如用户在长按说话、或界面在前台） */
    private var paused = false

    /** 会话窗口结束时间；> now 表示现在不用听唤醒词 */
    private var sessionUntil = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        WakeConfig.enabled = true
        ensureRecognizer()
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
        main.removeCallbacksAndMessages(null)
    }

    /** 界面离开后恢复 */
    fun resume() {
        paused = false
        scheduleLoop(600)
    }

    // ------------------------------------------------------------------ 循环

    private fun scheduleLoop(delayMs: Long) {
        main.removeCallbacks(loop)
        main.postDelayed(loop, delayMs)
    }

    private val loop: Runnable = Runnable {
        if (!WakeConfig.enabled || paused) return@Runnable
        // 会话窗口内不抢麦克风
        if (System.currentTimeMillis() < sessionUntil) {
            main.postDelayed(loop, 1_000)
            return@Runnable
        }
        startListening()
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "系统没有可用的语音识别服务，唤醒不可用")
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
        }
    }

    private fun startListening() {
        if (listening) return
        val engine = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // 尽量走离线识别：唤醒词就那么几个字，没必要每次都联网
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            // 短句：1 秒静音就收尾，否则循环一轮要等很久
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                900L
            )
        }
        listening = true
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
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
            // NO_MATCH / SPEECH_TIMEOUT 是常态（没人说话），不算错，歇一下接着听
            scheduleLoop(if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) QUIET_RETRY_MS else RETRY_MS)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            listening = false
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

            val hit = candidates.firstOrNull { WakeWord.matches(it, WakeConfig.wakeWord, WakeConfig.strict) }
            if (hit != null) {
                onWake(hit)
            } else {
                scheduleLoop(QUIET_RETRY_MS)
            }
        }
    }

    /** 命中唤醒词：进入会话窗口、通知界面、暂停自己 */
    private fun onWake(heard: String) {
        val now = System.currentTimeMillis()
        sessionUntil = now + WakeConfig.sessionMs
        WakeConfig.lastWakeAt = now

        AssistantBus.publish(WakeDetected(WakeConfig.wakeWord, now))

        // 麦克风让给对话链路，窗口结束后自己回来
        main.removeCallbacks(loop)
        main.postDelayed(loop, WakeConfig.sessionMs + 500)
    }

    // ------------------------------------------------------------------ 通知

    private fun startForegroundCompat() {
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
        private const val NOTIFICATION_ID = 0x9E01

        /** 活着的那一个；界面用它暂停/恢复，避免麦克风打架 */
        @Volatile
        var instance: WakeWordService? = null
            private set

        /** 没人说话时歇久一点，省电 */
        private const val QUIET_RETRY_MS = 800L

        /** 出错时稍等再试 */
        private const val RETRY_MS = 1_500L
    }
}
