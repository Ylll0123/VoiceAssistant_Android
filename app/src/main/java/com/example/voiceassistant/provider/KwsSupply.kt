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
import com.example.voiceassistant.data.AppSettings
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
        start(), stop(), setWakeWord(), setSensitivity(), setSessionTimeout(), status()
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
            /*
             * 走讯飞离线唤醒时**不要求系统识别服务**：
             * 那条链路自己是「录音 → VAD → 唤醒」，和手机有没有识别服务没关系。
             * 以前一刀切地判系统识别，装了讯飞的机器也会被这句话挡在门外。
             */
            val settings = AppSettings(app)
            val wantIfly = settings.wakeEngine == AppSettings.ENGINE_IFLY && settings.iflyReady()
            if (!wantIfly &&
                !runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)
            ) {
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

    // ------------------------------------------------------------------ 自查

    /**
     * 唤醒为什么叫不醒。
     *
     * 「语音唤醒无法使用」这类反馈，靠开关位置是看不出来的：
     * 开关是开的，可能是服务没起来、权限被收回、系统缺中文识别包，或者引擎一直报错。
     * 这里一次性把该看的状态都摊开，并给一句能落地的建议。
     */
    private fun status(): Supply = object : Supply {
        override val name = "kws/status"
        override val summary =
            "自查唤醒词为什么叫不醒：开关、服务、录音权限、系统识别服务、最近一次错误码。"

        override fun invoke(args: BusArgs): BusReply {
            val points = mutableListOf<String>()
            points += if (WakeConfig.enabled) "监听开关是开的" else "监听开关是关的"
            points += if (WakeWordService.instance != null) "监听服务在跑" else "监听服务没起来"

            /*
             * 先说清「现在到底跑的是哪套引擎」。
             *
             * 用户可能选了讯飞、但鉴权失败自动退回了系统识别——不说这件事，
             * 他看到的现象和「选了系统识别」完全一样，就会以为设置没生效。
             */
            points += if (WakeConfig.engine == AppSettings.ENGINE_IFLY) {
                "引擎是讯飞离线唤醒"
            } else {
                "引擎是系统语音识别"
            }
            val engineNote = WakeConfig.engineNote.trim()
            if (engineNote.isNotEmpty()) points += "引擎备注：$engineNote"

            val hasMic = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            points += if (hasMic) "有录音权限" else "没有录音权限"

            val hasEngine = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }
                .getOrDefault(false)
            points += if (hasEngine) "系统识别服务可用" else "系统没有语音识别服务"

            /*
             * 「最近听到什么」是排查叫不醒的关键分叉：
             * 一直没内容 = 引擎没拿到人声（权限 / 中文包 / 麦克风被占）；
             * 有内容但没命中 = 唤醒词或灵敏度的问题。两者给的建议完全不同。
             */
            val heard = WakeConfig.lastHeard.trim()
            val heardAgo = if (WakeConfig.lastHeardAt <= 0) -1
            else ((System.currentTimeMillis() - WakeConfig.lastHeardAt) / 1000).toInt()
            points += if (heard.isEmpty()) {
                "还没听到过任何声音"
            } else {
                "最近听到「$heard」" + if (heardAgo in 0..600) "（${heardAgo}秒前）" else "（有点久了）"
            }
            points += "灵敏度 ${WakeConfig.sensitivity}（${if (WakeConfig.strict) "严格" else "宽松"}匹配）"

            val error = WakeWordService.lastError
            val suggestion = when {
                !hasMic -> "去系统设置里给它录音权限，再把开关关一下重开。"

                WakeConfig.engine == AppSettings.ENGINE_IFLY ->
                    "讯飞离线唤醒在跑：叫「${WakeConfig.wakeWord}」就行。" +
                        "叫不醒就去设置里把阈值往下调（数值越小越灵敏，1450 是默认）。" +
                        "第一次启动要联网做一次鉴权。"

                !hasEngine -> "这台手机没有语音识别服务，唤醒词用不了，只能点麦克风说话。"
                !WakeConfig.enabled -> "把设置里的「语音唤醒」打开。"
                WakeWordService.instance == null ->
                    "开关是开的但服务没起来：到系统设置里允许自启动和后台运行，再把开关关一下重开。"

                error == 12 || error == 13 ->
                    "系统缺中文识别包（错误码 $error）：去系统设置里下载离线语音包，或者连上网络再试。"

                error == 1 || error == 2 || error == 11 ->
                    "识别需要联网，现在网络不通（错误码 $error）。"

                error == 9 -> "录音权限被系统收回了，重新给一次。"

                // 听到了但没命中：不是权限问题，往上调灵敏度最快
                heard.isNotEmpty() ->
                    "能听到声音但没认成唤醒词。把设置里的「唤醒灵敏度」调到高，" +
                        "或者把唤醒词换成四个字、声调起伏大一点的（比如「小豆同学」）。"

                else -> "开着就能叫「${WakeConfig.wakeWord}」，说完整一点更稳。"
            }
            return BusReply.ok(points.joinToString("，") + "。$suggestion")
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
