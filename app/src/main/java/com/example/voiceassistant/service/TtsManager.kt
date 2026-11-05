package com.example.voiceassistant.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 服务层：**语音播报（TTS）**。
 *
 * 只负责「把一段文字念出来」，并在念完（或被取消）时通过 [Listener] 通知上层——
 * 连续对话就是靠这个回调决定何时重新打开麦克风的，
 * 否则麦克风会把助手自己的声音录进去。
 *
 * ## 引擎切换
 * 换语音引擎必须**重建** [TextToSpeech] 实例，否则设置不生效（见 [useEngine]）。
 *
 * ## 朗读前的清理
 * [speak] 会去掉表情符号：emoji 念出来是「红心 红心」这种，很难听。
 *
 * ## 线程
 * TextToSpeech 必须在主线程创建和调用，本类按此约定使用。
 */
class TtsManager(context: Context) {

    interface Listener {
        /** 一段回复播报完毕（或被取消） */
        fun onSpeakingFinished()
    }

    var listener: Listener? = null

    /** 是否允许出声（由界面上的喇叭按钮控制） */
    var speakEnabled: Boolean = true

    /** 是否正在播报 */
    var isSpeaking: Boolean = false
        private set

    /** 当前使用的语音引擎包名；空串表示系统默认 */
    var enginePackage: String = ""
        private set

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null

    /** 引擎彻底不可用（初始化失败）：之后的 [speak] 直接返回 false，不再挂起 */
    private var broken = false

    /** 播报看门狗：引擎吞掉这句话时替它收尾，见 [armWatchdog] */
    private var watchdog: Runnable? = null

    init {
        create(enginePackage)
    }

    /**
     * 播报一段文字。
     *
     * @return 是否真的交给了引擎。返回 false 时上层**必须**自己收尾
     *   （把状态栏改回「空闲」并重开麦克风）——否则界面会永远停在「正在回答…」。
     */
    fun speak(text: String): Boolean {
        if (!speakEnabled) return false
        val cleaned = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (cleaned.isEmpty()) return false
        val engine = tts
        if (!ttsReady || engine == null) {
            // 引擎还没初始化好，先存着，init 回调里再念（那时会再走一遍本方法并武装看门狗）
            if (broken) return false
            pendingSpeak = cleaned
            return false
        }
        isSpeaking = true
        val utteranceId = "reply-${System.currentTimeMillis()}"
        val result = runCatching {
            engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            // 引擎收到了却念不出来（语音包缺失、服务被杀）：不会有任何回调
            Log.w(TAG, "播报入队失败，本轮不念了")
            isSpeaking = false
            return false
        }
        armWatchdog(utteranceId, cleaned.length)
        return true
    }

    /**
     * 试听：无论「语音播报」开关是否打开都出声。
     * 设置页里换完语音引擎后用它当场确认效果。
     */
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
        create(packageName)
    }

    /** 机器上安装的所有语音合成引擎 */
    fun availableEngines(): List<EngineInfo> {
        val probe = TextToSpeech(appContext, null)
        return runCatching {
            probe.engines.orEmpty().map { info ->
                EngineInfo(
                    label = info.label.ifBlank { info.name },
                    packageName = info.name
                )
            }
        }.onFailure { Log.w(TAG, "读取语音引擎列表失败", it) }
            .getOrDefault(emptyList())
            .also { runCatching { probe.shutdown() } }
    }

    /** 当前生效的引擎安装包名（空串表示系统默认） */
    fun currentEngine(): String = enginePackage

    /** 释放资源 */
    fun release() {
        cancelWatchdog()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        isSpeaking = false
        pendingSpeak = null
        mainHandler.removeCallbacksAndMessages(null)
        listener = null
    }

    // ------------------------------------------------------------------ 内部

    private fun create(engine: String) {
        tts = if (engine.isEmpty()) {
            TextToSpeech(appContext, onInit)
        } else {
            TextToSpeech(appContext, onInit, engine)
        }
    }

    private val onInit = TextToSpeech.OnInitListener { status ->
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "TextToSpeech 初始化失败: $status")
            // 没引擎就别再挂起待念文本了：挂起不会有任何回调，界面会一直等
            broken = true
            pendingSpeak = null
            return@OnInitListener
        }
        val result = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
            result != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            Log.w(TAG, "中文语音包不可用，退回默认语言")
            engine.setLanguage(Locale.getDefault())
            ttsReady = true
        }
        attachProgress(engine)
        pendingSpeak?.let {
            speak(it)
            pendingSpeak = null
        }
    }

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
     * 不报错、也不回调 `onDone`，[isSpeaking] 就永远停在 true，
     * 界面卡在「正在回答…」、连续对话也不再重开麦克风。
     * 这里按字数估一个宽松的上限，到点还没回音就当作念完收尾。
     */
    private fun armWatchdog(utteranceId: String, length: Int) {
        cancelWatchdog()
        val task = Runnable {
            if (!isSpeaking) return@Runnable
            Log.w(TAG, "播报超时未回调，按念完处理: $utteranceId")
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

    /** 一个可选的语音合成引擎 */
    data class EngineInfo(val label: String, val packageName: String)

    private companion object {
        const val TAG = "TtsManager"
    }
}
