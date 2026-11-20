package com.example.voiceassistant.provider

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.service.AudioController
import com.example.voiceassistant.service.MusicPlayer
import com.example.voiceassistant.service.TtsManager

/**
 * 音频域：`audio/…`
 *
 * ## 为什么 TTS 也算「服务」
 * 它不是「用户要的功能」，而是**别的服务要用的出口**：
 * 提醒响了要播报、念一段文字都得通过 `audio/tts_speak` 出声。
 * 这样「怎么念」只有一处实现。
 *
 * ## 为什么没有 ASR 服务
 * 曾经有个 `audio/asr_recognize`（语义是「开始听」），但两条入口都用不上它：
 * 用户已经在说话了，不需要助手再主动开麦；识别结果本来就是异步回调回界面，
 * 挂在总线上只会多一层「谁该等谁」的歧义。现在开麦只有一条路：长按说话。
 */
class AudioSupply(
    private val tts: TtsManager?,
    private val audio: AudioController,
    private val music: MusicPlayer? = null,
    /**
     * 上一条回答的原文。
     *
     * 给「再说一遍」用：用户没听清时最自然的一句就是「再说一遍」，
     * 这时候不该让他重新组织一遍问题，也不该让模型重新答一遍——
     * 直接把刚才那句话再念一次，快且一字不差。由界面注入。
     */
    private val lastAnswer: () -> String? = { null }
) {

    private val main = Handler(Looper.getMainLooper())

    private companion object {
        /** 「再说一遍」最多念这么多字 */
        const val REPEAT_MAX = 260
    }

    val all: List<Supply> = listOf(speak(), stop(), volume(), musicCtrl())

    // ------------------------------------------------------------------ TTS

    private fun speak(): Supply = object : Supply {
        override val name = "audio/tts_speak"
        override val summary =
            "把一段文字念出来（语音播报）。参数 text 是要念的内容；" +
                "用户说「再说一遍」时传 repeat=true，念上一条回答。"

        override fun invoke(args: BusArgs): BusReply {
            val engine = tts ?: return BusReply.fail("这台设备没有语音播报功能。")

            val repeat = args.bool("repeat")
            // 明写了 text 就念 text；只说「再说一遍」才回头找上一条
            val text = args.str("text") ?: run {
                val last = if (repeat) lastAnswer()?.takeIf { it.isNotBlank() } else null
                last?.let { clip(it) } ?: return if (repeat) {
                    BusReply.fail("上一条我没有可以念的文字。")
                } else {
                    BusReply.fail("没有要念的内容。")
                }
            }
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

    /** 「再说一遍」时不要整段重念：太长的回答截到两句，快一点 */
    private fun clip(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= REPEAT_MAX) flat else flat.take(REPEAT_MAX) + "……"
    }

    // ------------------------------------------------------------------ 音量

    /**
     * 音量：媒体 / 铃声 / 闹钟三条流分开调。
     *
     * 以前一律只动媒体音量，所以「把铃声调小」听起来就是没反应。
     * 现在还会**读回来核对**：系统没接受（固定音量、被免打扰策略挡住）时，
     * 如实说「没调成，现在是几成」，而不是回一句「已调好」。
     */
    private fun volume(): Supply = object : Supply {
        override val name = "audio/volume_ctrl"
        override val summary =
            "调手机音量。参数 action：up / down / mute / unmute / current，" +
                "或者 percent（0-100）。参数 stream 指定调哪条：music（媒体，默认）/ ring（铃声）/ alarm（闹钟）。"

        override fun invoke(args: BusArgs): BusReply {
            val stream = streamOf(args.str("stream"))
            val label = streamLabel(stream)

            if (audio.isVolumeFixed()) {
                return BusReply.fail("这台设备的音量是系统固定的，应用改不了，只能按音量键调。")
            }

            return when {
                args.int("percent") != null -> {
                    val percent = args.int("percent")!!.coerceIn(0, 100)
                    if (audio.setVolumePercent(percent, stream)) {
                        // 回读到的百分比才是真的：档位是整数，30% 会落到 26% 那一档
                        BusReply.ok("${label}调好了，现在 ${audio.volumePercent(stream)}%。")
                    } else {
                        BusReply.fail(
                            "${label}没调成，现在是 ${audio.volumePercent(stream)}%。" +
                                if (stream == AudioManager.STREAM_RING) {
                                    "铃声要改的话，可能得允许「勿扰模式」权限。"
                                } else {
                                    "可能被系统或其他应用占着。"
                                }
                        )
                    }
                }

                args.str("action") == "mute" -> {
                    if (audio.setMuted(true, stream)) BusReply.ok("${label}已静音。")
                    else BusReply.fail("${label}静音没成功，系统可能不让我改这一路。")
                }

                args.str("action") == "unmute" -> {
                    if (audio.setMuted(false, stream)) BusReply.ok("${label}已取消静音。")
                    else BusReply.fail("${label}取消静音没成功。")
                }

                args.str("action") == "up" -> {
                    audio.adjustVolume(true, stream)
                    BusReply.ok("${label}调大了，现在 ${audio.volumePercent(stream)}%。")
                }

                args.str("action") == "down" -> {
                    audio.adjustVolume(false, stream)
                    BusReply.ok("${label}调小了，现在 ${audio.volumePercent(stream)}%。")
                }

                args.str("action") == "current" -> BusReply.ok("${label}现在 ${audio.volumePercent(stream)}%。")
                else -> BusReply.fail("要调大还是调小？")
            }
        }
    }

    // ------------------------------------------------------------------ 音乐

    private fun musicCtrl(): Supply = object : Supply {
        override val name = "audio/music_ctrl"
        override val summary =
            "放歌。参数 action：play（播放 / 打开音乐应用开始放，默认）/ pause（暂停）/ " +
                "next（下一首）/ previous（上一首）。"

        override fun invoke(args: BusArgs): BusReply {
            val player = music ?: return BusReply.fail("这台设备没有音乐控制。")
            val action = MusicPlayer.Action.of(args.str("action")) ?: MusicPlayer.Action.PLAY
            val outcome = player.handle(action)
            return if (outcome.ok) BusReply.ok(outcome.message) else BusReply.fail(outcome.message)
        }
    }

    private fun streamOf(raw: String?): Int = when (raw?.trim()?.lowercase()) {
        "ring", "ringer", "铃声", "来电" -> AudioManager.STREAM_RING
        "alarm", "闹钟" -> AudioManager.STREAM_ALARM
        else -> AudioManager.STREAM_MUSIC
    }

    private fun streamLabel(stream: Int): String = when (stream) {
        AudioManager.STREAM_RING -> "铃声"
        AudioManager.STREAM_ALARM -> "闹钟音量"
        else -> "媒体音量"
    }
}
