package com.example.voiceassistant.provider

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.kws.WakeConfig
import com.example.voiceassistant.kws.WakeWord
import com.example.voiceassistant.kws.WakeWordService

/**
 * 唤醒域：`kws/…`
 *
 * ```
 * kws/start              常驻监听（前台服务 + 常驻通知）
 * kws/stop               关掉，省电
 * kws/set_wake_word      改唤醒词
 * kws/set_sensitivity    灵敏度（越高越容易被叫醒，也越容易误唤醒）
 * kws/set_session_timeout 会话窗口时长（命中后多久内不用再喊）
 * ```
 *
 * 这几个服务的参数都落进 [WakeConfig]，因为服务可能还没启动——
 * 「先设唤醒词、再开监听」是正常顺序，配置得先存得住。
 */
class KwsSupply(private val context: Context) {

    private val app: Context = context.applicationContext

    val all: List<Supply> = listOf(
        start(), stop(), setWakeWord(), setSensitivity(), setSessionTimeout()
    )

    // ------------------------------------------------------------------ 开关

    private fun start(): Supply = object : Supply {
        override val name = "kws/start"
        override val summary = "开启唤醒词监听（会常驻一个前台服务，费电）。参数 wake_word 可以顺便改唤醒词。"

        override fun invoke(args: BusArgs): BusReply {
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return BusReply.fail("没有录音权限，叫不醒我。先去系统设置里给一下。")
            }
            // 有些机型（尤其精简 ROM）根本没有语音识别服务，起服务也白起。
            // 这里先判掉，免得开关亮着、实际上什么都听不到。
            if (!runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)) {
                return BusReply.fail("这台设备没有可用的语音识别服务，唤醒词用不了。")
            }
            args.str("wake_word")?.let { WakeConfig.wakeWord = it.trim() }

            val intent = Intent(app, WakeWordService::class.java)
            val ok = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
                true
            }.getOrDefault(false)

            return if (ok) {
                WakeConfig.enabled = true
                BusReply.ok("唤醒词监听开了。以后叫「${WakeConfig.wakeWord}」就能叫醒我。")
            } else {
                BusReply.fail("唤醒服务起不来，可能是系统限制了后台麦克风。")
            }
        }
    }

    private fun stop(): Supply = object : Supply {
        override val name = "kws/stop"
        override val summary = "关闭唤醒词监听，省电。"

        override fun invoke(args: BusArgs): BusReply {
            runCatching { app.stopService(Intent(app, WakeWordService::class.java)) }
            WakeConfig.enabled = false
            return BusReply.ok("唤醒词监听关了。")
        }
    }

    // ------------------------------------------------------------------ 参数

    private fun setWakeWord(): Supply = object : Supply {
        override val name = "kws/set_wake_word"
        override val summary = "改唤醒词。参数 word，建议 2-4 个字，例如「小豆小豆」。"

        override fun invoke(args: BusArgs): BusReply {
            val word = args.str("word")?.trim() ?: return BusReply.fail("要设成什么唤醒词？")
            if (word.length < 2) return BusReply.fail("唤醒词太短了容易误唤醒，至少两个字吧。")
            if (word.length > 6) return BusReply.fail("唤醒词太长了，两个到四个字最好叫。")
            WakeConfig.wakeWord = word
            return BusReply.ok("唤醒词改成「$word」了。")
        }
    }

    private fun setSensitivity(): Supply = object : Supply {
        override val name = "kws/set_sensitivity"
        override val summary =
            "调唤醒灵敏度。参数 value（0-100）：高了容易叫醒也容易误唤醒，低了正好相反。"

        override fun invoke(args: BusArgs): BusReply {
            val value = args.int("value") ?: return BusReply.fail("灵敏度要设成多少？0 到 100。")
            WakeConfig.sensitivity = value.coerceIn(0, 100)
            val note = if (WakeConfig.strict) "（要求完整说出唤醒词）" else "（说前两个字也能叫醒）"
            return BusReply.ok("灵敏度设成 ${WakeConfig.sensitivity} 了$note。")
        }
    }

    private fun setSessionTimeout(): Supply = object : Supply {
        override val name = "kws/set_session_timeout"
        override val summary =
            "会话窗口时长：叫醒之后这段时间内可以连续说话，不用再喊唤醒词。" +
                "参数 seconds（10-300）。"

        override fun invoke(args: BusArgs): BusReply {
            val seconds = args.int("seconds") ?: return BusReply.fail("窗口设多少秒？")
            val clamped = seconds.coerceIn(10, 300)
            WakeConfig.sessionMs = clamped * 1_000L
            return BusReply.ok("会话窗口设成 $clamped 秒了。")
        }
    }

    /** 给界面用的：当前配置的人话描述 */
    fun currentConfig(): String =
        "唤醒词「${WakeConfig.wakeWord}」，窗口 ${WakeConfig.sessionMs / 1000} 秒，" +
            "灵敏度 ${WakeConfig.sensitivity}"

    companion object {
        /** 默认状态下没被改过 */
        fun isDefaultWord(): Boolean = WakeConfig.wakeWord == WakeWord.DEFAULT
    }
}
