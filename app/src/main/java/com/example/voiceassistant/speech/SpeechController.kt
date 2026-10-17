package com.example.voiceassistant.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 语音输入 + 语音播报的封装。
 *
 * - 输入：系统 SpeechRecognizer（中文），支持边说边出结果（部分结果）
 * - 输出：系统 TextToSpeech（中文）
 *
 * 注意：SpeechRecognizer / TextToSpeech 都必须在主线程创建与调用，
 * 因此本类只在 Activity 的主线程环境中使用。
 */
class SpeechController(context: Context) {

    interface Listener {
        /** 麦克风已就绪，可以开始说话 */
        fun onReady()

        /** 边说边识别的中间结果，用于实时展示 */
        fun onPartialResult(text: String)

        /** 识别结束，拿到最终文本 */
        fun onFinalResult(text: String)

        /** 说话结束，正在等识别结果 */
        fun onEndOfSpeech()

        /** 出错，message 为可直接展示给用户的中文提示 */
        fun onError(message: String)

        /**
         * 一段回复播报完毕（或被取消）。
         * 连续对话模式靠这个回调决定何时重新打开麦克风，
         * 避免麦克风把助手自己的声音录进去。
         */
        fun onSpeakingFinished()
    }

    var listener: Listener? = null

    /** 是否正在录音识别 */
    var isListening: Boolean = false
        private set

    /** 是否允许语音播报（由界面上的喇叭按钮控制） */
    var speakEnabled: Boolean = true

    /** 系统是否提供语音识别服务 */
    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    /** 是否正在播报 */
    var isSpeaking: Boolean = false
        private set

    private val appContext: Context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null

    init {
        // TextToSpeech 初始化是异步的，就绪前的播报请求会先暂存
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    Log.w(TAG, "中文语音包不可用，退回默认语言")
                    tts?.setLanguage(Locale.getDefault())
                    ttsReady = true
                }
                pendingSpeak?.let {
                    speak(it)
                    pendingSpeak = null
                }
                attachUtteranceListener()
            } else {
                Log.w(TAG, "TextToSpeech 初始化失败: $status")
            }
        }
    }

    // ---------------------------------------------------------------- 语音识别

    /** 开始一次识别；调用前需已获得 RECORD_AUDIO 权限 */
    fun startListening() {
        if (isListening) return
        if (!isRecognitionAvailable()) {
            listener?.onError("这台设备没有可用的语音识别服务")
            return
        }

        val speechRecognizer = recognizer
            ?: SpeechRecognizer.createSpeechRecognizer(appContext).also { recognizer = it }

        speechRecognizer.setRecognitionListener(recognitionListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 说完后停顿约 1.2 秒自动结束，避免一直等着
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1200L
            )
        }

        isListening = true
        speechRecognizer.startListening(intent)
    }

    /** 手动结束识别（用户再点一次麦克风时） */
    fun stopListening() {
        if (!isListening) return
        recognizer?.stopListening()
    }

    /** 直接取消本次识别，不回调结果（界面退到后台时用） */
    fun cancelListening() {
        if (!isListening) return
        isListening = false
        recognizer?.cancel()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            isListening = true
            listener?.onReady()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            listener?.onEndOfSpeech()
        }

        override fun onError(error: Int) {
            isListening = false
            listener?.onError(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) {
                listener?.onError("没听清，再说一次？")
            } else {
                listener?.onFinalResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) listener?.onPartialResult(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，检查一下麦克风"
        SpeechRecognizer.ERROR_CLIENT -> "识别被中断了，再试一次"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有录音权限，无法语音输入"
        SpeechRecognizer.ERROR_NETWORK -> "网络不太好，语音识别失败了"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时，再说一次试试"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次？"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务正忙，稍等一下"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错了，稍后再试"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，点麦克风再说一次"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "系统不支持中文语音识别"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "中文语音识别暂不可用"
        else -> "语音识别出错（$error），可以先用文字输入"
    }

    // ---------------------------------------------------------------- 语音播报

    /** 监听播报结束：连续对话靠它决定何时重新打开麦克风 */
    private fun attachUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }

            override fun onDone(utteranceId: String?) {
                notifySpeakingFinished()
            }

            @Deprecated("系统回调，仅用于兜底")
            override fun onError(utteranceId: String?) {
                notifySpeakingFinished()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "播报失败: $errorCode")
                notifySpeakingFinished()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                notifySpeakingFinished()
            }
        })
    }

    private fun notifySpeakingFinished() {
        mainHandler.post {
            if (!isSpeaking) return@post
            isSpeaking = false
            listener?.onSpeakingFinished()
        }
    }

    /** 播报一段文字；自动去掉表情符号，避免读出来很奇怪 */
    fun speak(text: String) {
        if (!speakEnabled) return
        val cleaned = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim()
        if (cleaned.isEmpty()) return
        if (!ttsReady) {
            pendingSpeak = cleaned
            return
        }
        isSpeaking = true
        tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "reply-${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        if (!isSpeaking) {
            tts?.stop()
            return
        }
        tts?.stop()
        isSpeaking = false
    }

    /** Activity 销毁时释放资源 */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        isSpeaking = false
        pendingSpeak = null
        mainHandler.removeCallbacksAndMessages(null)
        listener = null
    }

    private companion object {
        const val TAG = "SpeechController"
    }
}
