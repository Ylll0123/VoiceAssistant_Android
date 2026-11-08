package com.example.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 服务层：**语音播报（TTS）**。
 *
 * 只负责「把一段文字念出来」，念完（或被取消）时通过 [Listener] 通知上层。
 *
 * ## 「播报了但没声音」的四个真实原因
 * 以前这里只做了一件事：`setLanguage(中文)`，失败就 `setLanguage(默认)` 然后当作没问题。
 * 结果是一句话被引擎默默吞掉，界面看起来一切正常。现在四种情况分别处理：
 *
 * | 情况 | 以前 | 现在 |
 * | --- | --- | --- |
 * | 引擎根本没有 | 静默 | [Status.NO_ENGINE]，界面引导去装 |
 * | 引擎在、没中文语音包 | 静默 | 自动换一台能念中文的引擎；都不行则 [Status.NO_CHINESE] |
 * | 媒体音量为 0 | 静默 | [Status.MUTED]，直接告诉用户「调大音量」 |
 * | 引擎吞掉这一句不回调 | 卡在「正在回答」 | 看门狗按字数估时收尾 |
 *
 * ## 挑声音而不是挑语言
 * `setLanguage` 只是「请求」，引擎可以答应得很好却念不出来。真正的判据是
 * `engine.voices` 里有没有 `language == "zh"` 的声音——有就显式指定它。
 *
 * ## 线程
 * TextToSpeech 必须在主线程创建和调用，本类按此约定使用。
 */
class TtsManager(context: Context) {

    interface Listener {
        /** 一段回复播报完毕（或被取消） */
        fun onSpeakingFinished()
    }

    /** 播报能力的真实状态；[READY] 之外的三种都念不出声 */
    enum class Status {
        /** 能念中文 */
        READY,

        /** 有引擎，但没有中文语音包：念了也没声 */
        NO_CHINESE,

        /** 一个语音引擎都没有 */
        NO_ENGINE,

        /** 能念，但媒体音量是 0 */
        MUTED
    }

    var listener: Listener? = null

    /** 是否允许出声（由设置里的开关控制） */
    var speakEnabled: Boolean = true

    /** 是否正在播报 */
    var isSpeaking: Boolean = false
        private set

    /** 当前实际生效的语音引擎包名；空串表示系统默认 */
    var enginePackage: String = ""
        private set

    /** 播报能力状态；[speak] 前后都可能变，界面读它给提示 */
    var status: Status = Status.READY
        private set

    /** 状态从「能念」变成「念不出」时回调一次，由界面决定要不要弹引导 */
    var onProblem: ((Status) -> Unit)? = null

    /** 从系统设置里换过语音引擎之后，下次进应用要重新探测 */
    private var probeOnResume = false

    /** 上次重建引擎的时间，避免每次回到界面都重来一遍 */
    private var lastProbeAt = 0L

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null

    /** 初始化失败：之后的 [speak] 直接返回 false，不再挂起 */
    private var broken = false

    /** 还要试的引擎（默认引擎没中文时，换一台试试） */
    private val candidates = ArrayDeque<String>()

    /** 已经点过名的引擎，避免反复试同一台 */
    private val tried = mutableSetOf<String>()

    /** 播报看门狗：引擎吞掉这句话时替它收尾，见 [armWatchdog] */
    private var watchdog: Runnable? = null

    /** 初始化看门狗：引擎绑上了却永不回调时替它收尾，见 [armInitTimer] */
    private var initTimer: Runnable? = null

    /** 上一次因为「念不出」提示过的是哪种状态，避免每句话都弹一次 */
    private var notified: Status? = null

    init {
        create(enginePackage)
    }

    // ------------------------------------------------------------------ 对外

    /**
     * 播报一段文字。
     *
     * @return 是否真的交给了引擎。返回 false 时上层**不必**自己收尾——
     *   要么已经存成待念、要么已经通过 [onProblem] 说明原因。
     *   界面状态不依赖它，只影响这句有没有声音。
     */
    fun speak(text: String): Boolean {
        if (!speakEnabled) return false
        val cleaned = clean(text)
        if (cleaned.isEmpty()) return false

        val engine = tts
        if (!ttsReady || engine == null) {
            // 引擎还没初始化好：存着，init 回调里再念（那时会再走一遍本方法）
            if (broken) {
                reportProblem(if (status == Status.READY) Status.NO_ENGINE else status)
                return false
            }
            pendingSpeak = cleaned
            return false
        }

        // 音量是 0 时引擎会「正常播报」，只是人听不见——这不是引擎的错，得说清楚
        if (mediaVolumeZero()) {
            reportProblem(Status.MUTED)
            return false
        }
        // 之前因为音量被拦下，现在音量回来了：恢复成能念
        if (status == Status.MUTED) {
            status = Status.READY
            notified = null
        }

        isSpeaking = true
        val stamp = System.currentTimeMillis()
        var queued = 0
        // 长回答切段送：TextToSpeech 单句上限约 4000 字，超了会被截断；分段还能更快出声
        chunksOf(cleaned).forEachIndexed { index, chunk ->
            val id = "reply-$stamp-$index"
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = runCatching { engine.speak(chunk, mode, null, id) }
                .getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.ERROR) {
                if (index == 0) {
                    Log.w(TAG, "播报入队失败，本轮不念了")
                    isSpeaking = false
                    reportProblem(if (status == Status.READY) Status.NO_CHINESE else status)
                    return false
                }
            } else {
                queued++
            }
        }
        if (queued == 0) return false
        // 没有中文语音包时引擎会「照收不误」，实际一片安静——第一句就告诉用户
        if (status != Status.READY) reportProblem(status)
        armWatchdog(cleaned.length)
        return true
    }

    /** 试听：不管「语音播报」开关开没开都出声，设置页里用它确认效果 */
    fun preview(text: String) {
        val keep = speakEnabled
        speakEnabled = true
        speak(text)
        speakEnabled = keep
    }

    /** 停止播报 */
    fun stop() {
        cancelWatchdog()
        isSpeaking = false
        tts?.stop()
    }

    /** 切换到指定语音引擎；传空串表示系统默认 */
    fun useEngine(packageName: String) {
        if (packageName == enginePackage) return
        cancelWatchdog()
        val old = tts
        tts = null
        ttsReady = false
        broken = false
        isSpeaking = false
        runCatching { old?.stop(); old?.shutdown() }
        enginePackage = packageName
        tried.clear()
        candidates.clear()
        create(packageName)
    }

    /** 释放资源 */
    fun release() {
        cancelWatchdog()
        cancelInitTimer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        isSpeaking = false
        pendingSpeak = null
        mainHandler.removeCallbacksAndMessages(null)
        listener = null
        onProblem = null
    }

    /**
     * 从系统设置里改过语音引擎后回来：重查一次，别一直停在「没中文语音包」。
     *
     * 没有明确去过设置、但当前状态也确实是「念不出」时，同样给一次机会——
     * 用户可能是在别处装好了语音包。两次尝试之间留 [RETRY_GAP_MS]，
     * 免得每次回到界面都重建一次引擎。
     */
    fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        val needRetry = probeOnResume || (status != Status.READY && now - lastProbeAt > RETRY_GAP_MS)
        if (!needRetry) return
        if (status == Status.READY && !probeOnResume) return
        probeOnResume = false
        lastProbeAt = now
        useEngineForce(enginePackage)
    }

    /**
     * 跳系统「文字转语音」输出设置：换引擎、下载语音包都在这一页。
     *
     * 这一页没有公开常量（[TextToSpeech.Engine] 只有「检查/安装数据」两个动作），
     * 系统设置里注册的是 `com.android.settings.TTS_SETTINGS`。真打不开就依次退到
     * 「安装语音数据」和「无障碍设置」，总有一个能落地。
     */
    fun openSystemSettings(): Boolean {
        probeOnResume = true
        val intents = listOf(
            Intent(TTS_SETTINGS_ACTION),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { appContext.startActivity(intent); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** 媒体音量是 0 时把它抬到能听清的一档（约 60%） */
    fun raiseMediaVolume(): Boolean = runCatching {
        val am = audioManager ?: return false
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (max * 0.6f).toInt().coerceAtLeast(1),
            0
        )
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 内部：初始化

    private fun create(engine: String) {
        tried += engine
        if (engine.isNotEmpty()) enginePackage = engine
        tts = if (engine.isEmpty()) {
            TextToSpeech(appContext, onInit)
        } else {
            TextToSpeech(appContext, onInit, engine)
        }
        armInitTimer()
    }

    /**
     * 初始化看门狗。
     *
     * 实测过一种很难查的情况：引擎**绑上了**（logcat 里 `Connected to TTS engine`），
     * 但 `OnInitListener` 永远不回调——语音包没下载完、引擎服务卡住都会这样。
     * 那时 `tts` 既不就绪也不失败，[speak] 只能把话存进 `pendingSpeak` 等一个永远不来的回调，
     * 表现就是「语音播报完全没反应，也不报错」。
     *
     * 超时后换一台引擎再试，都不行就标记 [Status.NO_ENGINE]，
     * 让界面能说出「不是我不念，是这台设备没有能用的语音引擎」。
     */
    private fun armInitTimer() {
        cancelInitTimer()
        val task = Runnable {
            if (ttsReady || broken) return@Runnable
            Log.w(TAG, "TTS 初始化超时（${INIT_TIMEOUT_MS}ms 没有回调）")
            if (tryNextEngine()) return@Runnable
            broken = true
            ttsReady = false
            status = Status.NO_ENGINE
            val pending = pendingSpeak
            pendingSpeak = null
            if (pending != null) reportProblem(Status.NO_ENGINE)
        }
        initTimer = task
        mainHandler.postDelayed(task, INIT_TIMEOUT_MS)
    }

    private fun cancelInitTimer() {
        initTimer?.let { mainHandler.removeCallbacks(it) }
        initTimer = null
    }

    /** 强制重建（用于「系统设置里换过引擎」之后） */
    private fun useEngineForce(engine: String) {
        cancelWatchdog()
        cancelInitTimer()
        val old = tts
        tts = null
        ttsReady = false
        broken = false
        runCatching { old?.stop(); old?.shutdown() }
        tried.clear()
        candidates.clear()
        enginePackage = engine
        create(engine)
    }

    private val onInit = TextToSpeech.OnInitListener { result ->
        cancelInitTimer()
        val engine = tts
        if (result != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "TextToSpeech 初始化失败: $result")
            if (tryNextEngine()) return@OnInitListener
            // 没引擎就别再挂起待念文本了：挂起不会有任何回调，界面会一直等
            broken = true
            ttsReady = false
            pendingSpeak = null
            // 先只记状态：启动时弹一句「没有引擎」只会让人莫名其妙，
            // 等真的要念的那一刻再通过 [onProblem] 说明
            status = Status.NO_ENGINE
            return@OnInitListener
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engine.setSpeechRate(1.0f)

        if (!applyChineseVoice(engine)) {
            // 这台引擎念不了中文：换一台试试，全都不行就如实标记
            if (tryNextEngine()) return@OnInitListener
            Log.w(TAG, "所有语音引擎都没有中文语音包")
            ttsReady = true // 还能念英文，别把功能整体关掉
            status = Status.NO_CHINESE
            finishInit(engine)
            return@OnInitListener
        }

        ttsReady = true
        status = Status.READY
        finishInit(engine)
    }

    private fun finishInit(engine: TextToSpeech) {
        attachProgress(engine)
        // 没指定过引擎时把实际用的记下来，便于界面上显示
        if (enginePackage.isEmpty()) {
            enginePackage = runCatching { engine.defaultEngine }.getOrNull().orEmpty()
        }
        pendingSpeak?.let {
            pendingSpeak = null
            speak(it)
        }
    }

    /**
     * 显式挑一条中文声音。
     *
     * 先看 `voices`（最可靠）；没有 voices 的老引擎再退回 `setLanguage`，
     * 返回值 >= 0 才算真的支持。
     */
    private fun applyChineseVoice(engine: TextToSpeech): Boolean {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val chinese = voices.filter { it.locale.language.equals("zh", true) }
        Log.i(TAG, "voices=${voices.size} zh=${chinese.size}")
        if (chinese.isNotEmpty()) {
            val best = chinese.maxByOrNull { voice ->
                // 普通话优先，其次看引擎自报的质量 / 是否联网
                var score = 0
                if (voice.locale.country.equals("CN", true)) score += 4
                if (voice.locale == Locale.SIMPLIFIED_CHINESE) score += 4
                if (!voice.isNetworkConnectionRequired) score += 2
                score - voice.quality
            } ?: return false
            val ok = runCatching { engine.voice = best }.isSuccess
            if (ok) {
                Log.i(TAG, "使用中文声音: ${best.name}")
                return true
            }
        }

        val available = runCatching { engine.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        Log.i(TAG, "中文可用性=$available")
        if (available >= TextToSpeech.LANG_AVAILABLE) {
            return runCatching {
                engine.setLanguage(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * 换下一台引擎试试。
     *
     * 很多手机同时装着厂商语音和 Google 语音，其中一台没中文、另一台有。
     * 最多再试两台，避免启动时把时间耗在反复 bind 上。
     */
    private fun tryNextEngine(): Boolean {
        if (candidates.isEmpty()) {
            candidates += installedEngines().filterNot { it in tried }.take(MAX_ENGINE_TRIES)
        }
        val next = candidates.removeFirstOrNull() ?: return false
        Log.i(TAG, "这台引擎念不了中文，改试 $next")
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        create(next)
        return true
    }

    /** 系统里装了哪些语音引擎（不必创建实例，直接问 PackageManager） */
    @Suppress("DEPRECATION")
    private fun installedEngines(): List<String> = runCatching {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.queryIntentServices(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
            )
        } else {
            appContext.packageManager.queryIntentServices(intent, 0)
        }
        list.mapNotNull { it.serviceInfo?.packageName }.distinct()
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ 内部：播报

    private fun attachProgress(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) = notifyFinished()

            @Deprecated("系统回调，仅用于兜底")
            override fun onError(utteranceId: String?) = notifyFinished()

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "播报失败: $errorCode")
                notifyFinished()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) = notifyFinished()
        })
    }

    private fun notifyFinished() {
        cancelWatchdog()
        mainHandler.post {
            if (!isSpeaking) return@post
            isSpeaking = false
            listener?.onSpeakingFinished()
        }
    }

    /**
     * 给这一句配上「兜底计时器」。
     *
     * 有些设备（语音包缺失、引擎被杀）会把 [TextToSpeech.speak] 悄悄吞掉：
     * 不报错、也不回调 `onDone`，[isSpeaking] 就永远停在 true。
     * 这里按字数估一个宽松的上限，到点还没回音就当作念完收尾。
     */
    private fun armWatchdog(length: Int) {
        cancelWatchdog()
        val task = Runnable {
            if (!isSpeaking) return@Runnable
            Log.w(TAG, "播报超时未回调，按念完处理")
            runCatching { tts?.stop() }
            notifyFinished()
        }
        watchdog = task
        mainHandler.postDelayed(task, TtsTiming.watchdogMs(length))
    }

    private fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    private fun reportProblem(next: Status) {
        status = next
        if (notified == next) return
        notified = next
        onProblem?.invoke(next)
    }

    private fun mediaVolumeZero(): Boolean =
        runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 }
            .getOrDefault(false)

    /** 去掉表情和空白：emoji 念出来是「红心 红心」这种，很难听 */
    private fun clean(text: String): String =
        text.replace(Regex("[\\p{So}\\p{Cn}]"), "").replace(Regex("\\s+"), " ").trim()

    /** 按句读切段，每段不超过 [MAX_CHUNK] 字；没有标点的长串硬切 */
    private fun chunksOf(text: String): List<String> {
        if (text.length <= MAX_CHUNK) return listOf(text)
        val out = ArrayList<String>(text.length / MAX_CHUNK + 1)
        val current = StringBuilder()
        for (ch in text) {
            current.append(ch)
            val boundary = ch in SENTENCE_ENDS && current.length >= MIN_CHUNK
            if (boundary || current.length >= MAX_CHUNK) {
                out += current.toString().trim()
                current.setLength(0)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "TtsManager"

        /** 系统「文字转语音」输出设置页（没有公开常量） */
        const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

        /** 单段上限：远小于引擎的 4000 字限制，长回答也能分句早点出声 */
        const val MAX_CHUNK = 240

        /** 太短的句子不值得单独送一次，等下一个断句 */
        const val MIN_CHUNK = 24

        /** 最多再试两台别的引擎 */
        const val MAX_ENGINE_TRIES = 2

        /** 引擎绑上了却不回调初始化，等这么久就换下一台 */
        const val INIT_TIMEOUT_MS = 4_000L

        /** 两次自动重探之间的最小间隔 */
        const val RETRY_GAP_MS = 20_000L

        val SENTENCE_ENDS = charArrayOf('。', '！', '？', '；', '\n', '，', '!', '?', ';', ',')
    }
}
