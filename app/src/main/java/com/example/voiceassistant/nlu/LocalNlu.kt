package com.example.voiceassistant.nlu

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.service.KnownApps
import java.time.LocalDateTime

/**
 * 本地意图识别：**先在这儿过一遍，认得出就直接调服务，认不出才去问大模型**。
 *
 * ```
 * 用户这句话 ──▶ LocalNlu.route()
 *                  ├─ 命中 → ServiceBus.call(service, args) → 立刻有结果（不联网、不花钱、不等）
 *                  └─ null → AiChat.ask()（大模型 + 工具调用）
 * ```
 *
 * ## 为什么只认这么几种
 * v3 的教训：本地规则一旦「大概猜」，就会出现「问天气答成日程」这种硬伤。
 * 所以这里只认**句式固定、参数明确、错了也很明显**的指令：开关灯、开应用、调音量、
 * 问时间、算算式、定闹钟、发消息。剩下的（翻译、解释、写文案、闲聊、知识问答）
 * 全部原样交给大模型——它本来就更擅长，也不该被本地规则截胡。
 *
 * ## 判定顺序
 * 从上往下第一个 `!= null` 的规则赢。顺序是刻意排的：
 * 「打开手电筒」必须先被手电筒规则吃掉，否则会被「打开 XX」当成打开应用。
 *
 * 全部是纯函数（[now] 由外部传入），单测里可以直接钉住每句话的去向。
 */
object LocalNlu {

    /** 一次本地命中：调哪个服务、带什么参数 */
    data class Hit(val service: String, val args: BusArgs)

    /**
     * 识别一句话。
     *
     * @param appMatcher 「这个名字是不是一个真实安装了的应用」。
     *        常用表之外的应用（原神、学习通……）要靠它判断，不传就只认 [KnownApps]。
     *        用回调注入而不是直接查 `PackageManager`，这一层才能继续当纯函数测。
     * @return 命中返回 [Hit]；认不出（或不敢确定）返回 null，表示「交给大模型」
     */
    fun route(
        raw: String,
        now: LocalDateTime = LocalDateTime.now(),
        appMatcher: ((String) -> Boolean)? = null
    ): Hit? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 太长的话一律交给模型：本地规则是为短指令写的，长句里往往带着条件、转折
        if (text.length > 40) return null

        return torch(text)
            ?: brightness(text)
            ?: volume(text)
            ?: radio(text)
            ?: openApp(text, appMatcher)
            ?: clock(text)
            ?: timer(text)
            ?: alarm(text, now)
            ?: math(text)
            ?: sendMessage(text)
    }

    // ------------------------------------------------------------------ 设备

    /** 「打开手电筒」「把手电关了」 */
    private fun torch(text: String): Hit? {
        if (!text.contains("手电")) return null
        val on = when {
            text.contains("关") || text.contains("灭") || text.contains("别开") -> false
            text.contains("开") || text.contains("亮") -> true
            else -> return null
        }
        return Hit("device/flashlight", BusArgs.of("action" to if (on) "on" else "off"))
    }

    /** 「亮度调到 60%」「屏幕太暗了」「调亮一点」 */
    private fun brightness(text: String): Hit? {
        val mentions = text.contains("亮度") || text.contains("屏幕") || text.contains("背光")
        if (!mentions) return null
        if (text.contains("手电")) return null

        val percent = PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null) {
            return Hit("device/brightness", BusArgs.of("percent" to percent.coerceIn(1, 100).toString()))
        }
        val action = when {
            text.contains("最亮") || text.contains("亮一点") || text.contains("调亮") ||
                text.contains("太暗") || text.contains("亮些") -> "up"
            text.contains("最暗") || text.contains("暗一点") || text.contains("调暗") ||
                text.contains("太亮") || text.contains("暗些") -> "down"
            else -> return null
        }
        return Hit("device/brightness", BusArgs.of("action" to action))
    }

    /** 「音量调到 30%」「大声点」「静音」 */
    private fun volume(text: String): Hit? {
        // 「打开手电」里没有音量的事，先摘出去
        if (text.contains("手电")) return null

        // 「别念了 / 闭嘴」是停止播报，不是改音量。这条必须最先判，
        // 而且不能被下面的 mentions 挡住——「别念了」里根本没有音量相关的字
        if (listOf("别念", "别读", "停止播报", "闭嘴", "安静", "别说了", "别出声").any { text.contains(it) }) {
            return Hit("audio/tts_stop", BusArgs.EMPTY)
        }

        val mentions = text.contains("音量") || text.contains("声音") ||
            text.contains("大声") || text.contains("小声") || text.contains("静音") ||
            text.contains("免打扰") || text.contains("出声")
        if (!mentions) return null
        // 「取消静音」必须排在「静音」前面判，否则一个字都还没说完就被当成静音了
        if (text.contains("取消静音") || text.contains("解除静音") ||
            text.contains("取消免打扰") || text.contains("打开声音")
        ) {
            return Hit("audio/volume_ctrl", BusArgs.of("action" to "unmute"))
        }
        if (text.contains("静音") || text.contains("免打扰")) {
            return Hit("audio/volume_ctrl", BusArgs.of("action" to "mute"))
        }

        val percent = PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null) {
            return Hit("audio/volume_ctrl", BusArgs.of("percent" to percent.coerceIn(0, 100).toString()))
        }
        val action = when {
            text.contains("大") || text.contains("高") -> "up"
            text.contains("小") || text.contains("低") -> "down"
            else -> return null
        }
        return Hit("audio/volume_ctrl", BusArgs.of("action" to action))
    }

    /** 「打开 wifi」「关掉蓝牙」 */
    private fun radio(text: String): Hit? {
        val wifi = text.contains("wifi", ignoreCase = true) ||
            text.contains("无线网") || text.contains("无线网络")
        val bluetooth = text.contains("蓝牙")

        if (wifi) {
            val on = SWITCH_ON(text) ?: return null
            return Hit("device/wifi_ctrl", BusArgs.of("action" to if (on) "on" else "off"))
        }
        if (bluetooth) {
            val on = SWITCH_ON(text) ?: return null
            return Hit("device/bluetooth_ctrl", BusArgs.of("action" to if (on) "on" else "off"))
        }
        return null
    }

    /** 「打开微信」「启动 B 站」「打开原神」 */
    private fun openApp(text: String, matcher: ((String) -> Boolean)?): Hit? {
        val match = OPEN_APP.find(text) ?: return null
        val target = match.groupValues[1].trim()
            .trim('。', '！', '!', '，', ',', '吧', '呗', '呀', '啊')
            .trim()
        if (target.isEmpty() || target.length > 12) return null

        // 常用表先归一：「打开设置页面」→「设置」
        KnownApps.canonical(target)?.let { app ->
            return Hit("device/open_app", BusArgs.of("name" to app))
        }

        /*
         * 表里没有的，交给本机的应用目录判断。
         *
         * 这一关不能省：没有 matcher 时（单测、或目录还没准备好），
         * 「打开这个思路」会被当成应用名，那就把一句正经问题截胡了。
         * 宁可漏判交给大模型，也不能错判。
         */
        if (matcher == null || !matcher(target)) return null
        return Hit("device/open_app", BusArgs.of("name" to target))
    }

    // ------------------------------------------------------------------ 时间与日程

    /** 「现在几点」「今天几号」「星期几」 */
    private fun clock(text: String): Hit? {
        val hit = text.contains("几点") || text.contains("什么时间") ||
            text.contains("现在时间") || text.contains("几号") ||
            text.contains("星期几") || text.contains("礼拜几") || text.contains("周几") ||
            text.contains("今天日期") || text.contains("今天的日期")
        if (!hit) return null
        // 「八点提醒我」不是问时间
        if (text.contains("提醒") || text.contains("叫我") || text.contains("闹钟")) return null
        val kind = when {
            text.contains("几号") || text.contains("日期") -> "date"
            text.contains("星期") || text.contains("礼拜") || text.contains("周几") -> "weekday"
            else -> "time"
        }
        return Hit("tool/time", BusArgs.of("kind" to kind))
    }

    /** 「10 分钟后叫我」「计时半小时」「倒计时 3 分钟」——固定时长的，本地算得准 */
    private fun timer(text: String): Hit? {
        val wantsTimer = text.contains("计时") || text.contains("倒计时") ||
            text.contains("分钟后") || text.contains("小时后") || text.contains("秒后")
        if (!wantsTimer) return null

        val millis = TimeWords.durationMillis(text) ?: return null
        if (millis <= 0) return null

        // 「10 分钟后提醒我写作业」→ 名字取「写作业」；没有就算了，叫「计时」
        val label = TIMER_LABEL.find(text)?.groupValues?.get(1)?.trim()
            ?.trim('，', ',', '。', '的', ' ')?.takeIf { it.isNotEmpty() }
            ?: AFTER_DURATION.find(text)?.groupValues?.get(1)?.trim()
                ?.replace(Regex("^(提醒我|提醒|叫我|喊我)"), "")
                ?.trim('，', ',', '。', '的', ' ')?.takeIf { it.isNotEmpty() }
            ?: "计时"
        val minutes = (millis / 60_000.0)
        return Hit(
            "schedule/add_alarm",
            BusArgs.of(
                "minutes" to if (minutes == minutes.toLong().toDouble()) minutes.toLong().toString()
                else String.format("%.2f", minutes),
                "content" to label
            )
        )
    }

    /** 「提醒我八点交作业」「明天早上七点叫我」——能解析出绝对时间的才认领 */
    private fun alarm(text: String, now: LocalDateTime): Hit? {
        val wants = text.contains("提醒") || text.contains("闹钟") || text.contains("叫我") ||
            text.contains("喊我")
        if (!wants) return null
        val target = TimeWords.parse(text, now) ?: return null

        // 内容 = 原句去掉唤醒词、去掉时间词
        var content = text
            .replace(Regex("^(请|帮我|麻烦你?|你)"), "")
            .replace(Regex("提醒我|提醒一下我|提醒一下|提醒|叫我|喊我|定个闹钟|设个闹钟|闹钟"), "")
            .replace(DATE_PART, "")
        content = CLOCK_PART.replace(content, "")
        // 「早上」「晚上」这类时段词也是时间的一部分，留着会让提醒叫「早上」
        content = PERIOD_PART.replace(content, "")
        content = content.trim('，', ',', '。', ' ', '的', '在', '到')
        if (content.isEmpty()) content = "提醒"

        val at = String.format(
            "%04d-%02d-%02d %02d:%02d",
            target.year, target.monthValue, target.dayOfMonth, target.hour, target.minute
        )
        return Hit("schedule/add_alarm", BusArgs.of("content" to content, "at" to at))
    }

    // ------------------------------------------------------------------ 工具

    /** 「帮我算 12 乘 8」——纯算式，本地算完直接答 */
    private fun math(text: String): Hit? {
        if (!Calc.looksLikeMath(text)) return null
        return Hit("tool/calc", BusArgs.of("expression" to text))
    }

    // ------------------------------------------------------------------ 消息

    /**
     * 「给妈妈发微信说我晚点到」
     *
     * 内容为空（「给妈妈发个微信」）时不认领——那需要先让模型写一句话，
     * 属于「生成内容」的范畴，交给大模型更合适。
     */
    private fun sendMessage(text: String): Hit? {
        if (!text.contains("发")) return null
        val match = SEND_MSG.find(text) ?: return null
        val to = match.groupValues[1].trim()
        val platform = match.groupValues[2].trim().ifEmpty { "wechat" }
        val content = match.groupValues[3].trim().trim('。', '！', '!', '？', '?')

        if (to.isEmpty() || to.length > 12) return null
        if (content.isEmpty() || content.length > 100) return null

        val key = when {
            platform.contains("q", ignoreCase = true) -> "qq"
            else -> "wechat"
        }
        return Hit(
            "msg/send_text",
            BusArgs.of("platform" to key, "to" to to, "content" to content)
        )
    }

    // ------------------------------------------------------------------ 规则表

    private val PERCENT = Regex("(\\d{1,3})\\s*%")

    private val OPEN_APP = Regex("^(?:请|帮我|麻烦)?\\s*(?:打开|启动|运行|进入)\\s*(.{1,10}?)(?:吧|呗|呀|啊)?[。！!]?$")

    private val SEND_MSG = Regex(
        "给\\s*([^，,。\\s]{1,12}?)\\s*(?:发|回)(?:个|一条|条)?\\s*(微信|qq|QQ)?\\s*(?:说|讲|一句|一下)?[：:]?\\s*(.*)"
    )

    /** 「计时 10 分钟」里「计时」后面那截当作名字 */
    private val TIMER_LABEL = Regex("(?:计时|倒计时)(?:一下)?\\s*([^，,。]{0,8})")

    /** 「10 分钟后……」里时长之后那截：用来当计时/提醒的名字 */
    private val AFTER_DURATION = Regex("(?:分钟|小时|秒钟?)后\\s*(.{1,12})")

    private val CLOCK_PART = Regex("(\\d{1,2}|[一二三四五六七八九十两]{1,3})\\s*[点時时]\\s*(\\d{1,2}|半|一刻|三刻)?")

    private val DATE_PART = Regex("(今天|今日|明天|明日|后天|大后天|今晚|今早|今夜|周[一二三四五六日天]|星期[一二三四五六日天]|礼拜[一二三四五六日天])")

    /** 时段词：和 [CLOCK_PART] 一起去掉，剩下的才是「要提醒的事」 */
    private val PERIOD_PART = Regex("(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|晚间|今晚|明早)")

    private fun SWITCH_ON(text: String): Boolean? = when {
        text.contains("关") || text.contains("断") || text.contains("停") -> false
        text.contains("开") || text.contains("启") || text.contains("连") -> true
        else -> null
    }
}
