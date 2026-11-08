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
    private val music: MusicPlayer? = null,
    /** 开始一次收音；返回 false 表示当前环境听不了（没权限 / 正在播报） */
    private val startListening: () -> Boolean = { false }
) {

    private val main = Handler(Looper.getMainLooper())

    val all: List<Supply> = listOf(speak(), stop(), volume(), musicCtrl(), recognize())

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

    // ------------------------------------------------------------------ ASR

    private fun recognize(): Supply = object : Supply {
        override val name = "audio/asr_recognize"
        override val summary = "开始语音识别（打开麦克风听一句）。结果会直接发给助手。"
        override fun invoke(args: BusArgs): BusReply {
            val started = runCatching { startListening() }.getOrDefault(false)
            return if (started) {
                BusReply.ok("我在听，说吧。")
            } else {
                BusReply.fail("现在没法开麦克风，检查一下录音权限。")
            }
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
