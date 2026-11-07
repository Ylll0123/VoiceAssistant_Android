package com.example.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 服务层：**语音识别（ASR）**。
 *
 * 只负责「把声音变成一串候选文本」，不认识业务、不做判断。
 * 识别结果通过 [Listener] 交回上层，由调度核心决定怎么答。
 *
 * ## 多候选
 * 系统识别会按置信度返回多条结果（这里最多要 5 条）。
 * 首选被听错时，后面几条往往是对的（「打开日立」/「打开日历」），
 * 所以整组候选一起交给上层，而不是只留第一条。
 *
 * ## 线程
 * SpeechRecognizer 必须在主线程创建和调用，本类按此约定使用。
 */
class AsrManager(context: Context) {

    interface Listener {
        /** 麦克风已就绪，可以开始说话 */
        fun onReady()

        /** 边说边识别的中间结果，用于实时展示 */
        fun onPartialResult(text: String)

        /** 说话结束，正在等识别结果 */
        fun onEndOfSpeech()

        /**
         * 识别结束，拿到最终文本。
         *
         * @param text 系统给出的首选结果（置信度最高）
         * @param candidates 全部候选，按置信度从高到低
         */
        fun onFinalResult(text: String, candidates: List<String>)

        /** 出错，message 为可直接展示给用户的中文提示 */
        fun onError(message: String)
    }

    var listener: Listener? = null

    /** 是否正在录音识别 */
    var isListening: Boolean = false
        private set

    private val appContext: Context = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    /** 最后一条中间结果：引擎超时不给最终结果时，拿它兜底 */
    private var lastPartial: String = ""

    /** 系统是否提供语音识别服务 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** 开始一次识别；调用前需已获得 RECORD_AUDIO 权限 */
    fun start() {
        if (isListening) return
        if (!isAvailable()) {
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            /*
             * 停顿判定：以前是 1.2 秒，短到一句话说到一半换口气就被判「说完了」，
             * 用户看到的现象就是「只识别到几个字」。
             *
             * 现在给到 2.6 秒：正常说话的句中停顿（1～2 秒）都能接上，
             * complete 与 possiblyComplete 之间再留 0.6 秒余量，
             * 让引擎有机会把后半句补全再收口。
             */
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_END_MS
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLY_SILENCE_MS
            )
            // 太短的「嗯」不急着结束，否则一句长话刚开口就被收走
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                MIN_LENGTH_MS
            )
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }

        lastPartial = ""
        isListening = true
        speechRecognizer.startListening(intent)
    }

    /** 手动结束识别（用户再点一次麦克风时） */
    fun stop() {
        if (!isListening) return
        recognizer?.stopListening()
    }

    /** 直接取消本次识别，不回调结果（界面退到后台时用） */
    fun cancel() {
        if (!isListening) return
        isListening = false
        lastPartial = ""
        recognizer?.cancel()
    }

    /** 释放资源 */
    fun release() {
        recognizer?.destroy()
        recognizer = null
        isListening = false
        lastPartial = ""
        listener = null
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
            /*
             * 超时 / 没匹配上，但已经听到了半句：把它当结果交出去。
             *
             * 这两条错误最常见的成因不是「用户没说」，而是「说话超过了引擎的等待时间」。
             * 直接回「没听到声音」，用户刚才说的那半句就白说了——
             * 这也是「只能识别到某个词」的另一种表现。
             */
            if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                error == SpeechRecognizer.ERROR_NO_MATCH
            ) {
                val kept = lastPartial.trim()
                if (kept.length >= 2) {
                    listener?.onFinalResult(kept, listOf(kept))
                    return
                }
            }
            listener?.onError(errorMessage(error))
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                .orEmpty()
            // 候选按置信度排，但**不一定按完整度排**；这里挑最完整的那句
            val best = AsrPick.choose(candidates, lastPartial)
            if (best.isEmpty()) {
                listener?.onError("没听清，再说一次？")
            } else {
                val all = if (best in candidates) candidates else listOf(best) + candidates
                listener?.onFinalResult(best, all)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isNotEmpty()) {
                lastPartial = text
                listener?.onPartialResult(text)
            }
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

    private companion object {
        const val MAX_RESULTS = 5

        /** 完全静音多久算「说完了」。给足句中换气的余地 */
        const val SILENCE_END_MS = 2_600L

        /** 「可能说完了」的判定点，比上一条早 0.6 秒 */
        const val POSSIBLY_SILENCE_MS = 2_000L

        /** 最短录音时长：避免刚开口就被收走 */
        const val MIN_LENGTH_MS = 1_200L
    }
}
