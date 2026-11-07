package com.example.voiceassistant.provider

import android.os.Handler
import android.os.Looper
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.service.AudioController
import com.example.voiceassistant.service.TtsManager

/**
 * 音频域：`audio/…`
 *
 * ## 为什么 TTS 和 ASR 也算「服务」
 * 它们不是「用户要的功能」，而是**别的服务要用的出口**：
 * 提醒响了要播报、发完消息要回一句「已发送」
 * 全都通过 `audio/tts_speak` 出声。这样「怎么念」只有一处实现。
 *
 * ## ASR 为什么只能「触发」不能「返回文本」
 * 语音识别是异步的：调用瞬间麦克风才刚打开，结果要等用户说完。
 * 所以 `audio/asr_recognize` 的语义是「开始听」，识别结果从原来的回调链回界面，
 * 不在这里阻塞等待——阻塞会让整条总线上所有调用一起卡住。
 */
class AudioSupply(
    private val tts: TtsManager?,
    private val audio: AudioController,
    /** 开始一次收音；返回 false 表示当前环境听不了（没权限 / 正在播报） */
    private val startListening: () -> Boolean = { false }
) {

    private val main = Handler(Looper.getMainLooper())

    val all: List<Supply> = listOf(speak(), stop(), volume(), recognize())

    // ------------------------------------------------------------------ TTS

    private fun speak(): Supply = object : Supply {
        override val name = "audio/tts_speak"
        override val summary = "把一段文字念出来（语音播报）。参数 text 是要念的内容。"

        override fun invoke(args: BusArgs): BusReply {
            val text = args.str("text") ?: return BusReply.fail("没有要念的内容。")
            val engine = tts ?: return BusReply.fail("这台设备没有语音播报功能。")
            // TextToSpeech 只能在主线程调用，总线可能在后台线程
            main.post { engine.preview(text) }
            return BusReply.ok("好的，我念一下。")
        }
    }

    private fun stop(): Supply = object : Supply {
        override val name = "audio/tts_stop"
        override val summary = "立刻停止朗读。"

        override fun invoke(args: BusArgs): BusReply {
            tts?.let { main.post { it.stop() } }
            return BusReply.ok("好，不念了。")
        }
    }

    // ------------------------------------------------------------------ 音量

    private fun volume(): Supply = object : Supply {
        override val name = "audio/volume_ctrl"
        override val summary =
            "调音量。参数 action：up / down / mute / unmute，或者 percent（0-100）。"

        override fun invoke(args: BusArgs): BusReply = when {
            args.int("percent") != null -> {
                val percent = args.int("percent")!!.coerceIn(0, 100)
                audio.setVolumePercent(percent)
                BusReply.ok("音量调到 $percent% 了。")
            }
            args.str("action") == "mute" -> {
                audio.setMuted(true)
                BusReply.ok("已静音。")
            }
            args.str("action") == "unmute" -> {
                audio.setMuted(false)
                BusReply.ok("已取消静音。")
            }
            args.str("action") == "up" -> {
                audio.adjustVolume(true)
                BusReply.ok("音量调大了。")
            }
            args.str("action") == "down" -> {
                audio.adjustVolume(false)
                BusReply.ok("音量调小了。")
            }
            args.str("action") == "current" -> BusReply.ok("现在音量是 ${audio.volumePercent()}%。")
            else -> BusReply.fail("要调大还是调小？")
        }
    }

    // ------------------------------------------------------------------ ASR

    private fun recognize(): Supply = object : Supply {
        override val name = "audio/asr_recognize"
        override val summary = "开始语音识别（打开麦克风听一句）。结果会显示在对话里。"

        override fun invoke(args: BusArgs): BusReply {
            val started = runCatching { startListening() }.getOrDefault(false)
            return if (started) {
                BusReply.ok("我在听，说吧。")
            } else {
                BusReply.fail("现在没法开麦克风，检查一下录音权限。")
            }
        }
    }
}
