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
     * @param strict **整句模式**：为 true 时，只有「短句 + 单一意图」才允许本地认领。
     *
     *        这一条是给语音输入加的。以前只要是句子里**含有**某个关键词就抢着执行：
     *        「把亮度调暗一点然后放首歌」会被当成单纯调亮度，后半句直接丢掉。
     *        语音识别出来的又是一整段话，这种误判特别显眼。
     *        严格模式下这类复合句返回 null，交给大模型把整句拆开依次做——
     *        网络可用时精度优先；离线时上层仍会用宽松模式，保证本机指令还能用。
     * @return 命中返回 [Hit]；认不出（或不敢确定）返回 null，表示「交给大模型」
     */
    fun route(
        raw: String,
        now: LocalDateTime = LocalDateTime.now(),
        appMatcher: ((String) -> Boolean)? = null,
        strict: Boolean = false
    ): Hit? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 太长的话一律交给模型：本地规则是为短指令写的，长句里往往带着条件、转折。
        // 但「发消息」常常带一整句话的内容，不能因为内容长就丢给模型——
        // 离线时模型不可用，用户会直接收到「没联网」，消息根本发不出去。
        val cap = if (looksLikeSendMessage(text)) 120 else 40
        if (text.length > cap) return null

        val hit = sendMessage(text)
            ?: messageDiagnostic(text)
            ?: wakeDiagnostic(text)
            ?: torch(text)
            ?: brightness(text)
            ?: volume(text)
            ?: radio(text)
            ?: openApp(text, appMatcher)
            ?: clock(text)
            ?: timer(text)
            ?: alarm(text, now)
            ?: math(text)
            ?: return null

        // 整句模式：复合意图、或者一长串话里只捞到一个关键词，都不许本地拍板
        if (strict && !acceptsLongText(hit.service)) {
            if (looksComposite(text)) return null
            if (text.length > QUICK_CAP) return null
        }
        return hit
    }

    /**
     * 只有「发消息」的参数本身就是用户原话，句子里出现「然后」「顺便」很正常，
     * 不能因为像复合句就丢掉——否则「给妈妈发微信说然后我晚点到」这种就发不出去了。
     *
     * 提醒类刻意**不**豁免：那多半真的是「先提醒我一件事，然后再做另一件事」，
     * 让大模型拆开做更稳；离线时上层用的是宽松模式，照样能建提醒。
     */
    private fun acceptsLongText(service: String): Boolean = service == "msg/send_text"

    /**
     * 像不像「一句话里有两件事」。
     *
     * 只认明确的连接词，不做语义猜测：宁可多交给大模型一次，也不要少做一半。
     */
    private fun looksComposite(text: String): Boolean = COMPOSITE.containsMatchIn(text)

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
     * 发消息：「给妈妈发微信说我晚点到」以及一大堆换语序的说法。
     *
     * 以前只认 `给X发微信说Y` 一个正则，用户说「发微信给妈妈说我晚点到」
     * 「帮我发条消息给妈妈：我晚点到」就会漏给大模型；离线时模型不可用，
     * 用户只会看到「没联网」，消息压根发不出去。现在改成：
     * **先确认这像发消息 → 抽联系人 → 抽内容 → 抽平台**，语序无关。
     *
     * 内容为空（「给妈妈发个微信」）仍然不认领——那需要先让模型写一句话。
     */
    private fun sendMessage(text: String): Hit? {
        if (!looksLikeSendMessage(text)) return null

        val to = SEND_TO.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (to.isEmpty() || to.length > 12) return null

        val content = extractSendContent(text, to)
        if (content.isEmpty() || content.length > 100) return null

        val key = if (QQ_WORD.containsMatchIn(text)) "qq" else "wechat"
        return Hit(
            "msg/send_text",
            BusArgs.of("platform" to key, "to" to to, "content" to content)
        )
    }

    /** 像发消息吗：要有「发/回」+ 收件人 + 平台或「消息」字样，三者缺一不可 */
    private fun looksLikeSendMessage(text: String): Boolean {
        if (!text.contains("发") && !text.contains("回")) return false
        if (!text.contains("给") && !text.contains("发给")) return false
        return PLATFORM_WORD.containsMatchIn(text) ||
            text.contains("消息") || text.contains("信息") || text.contains("短信")
    }

    /**
     * 抽内容：优先「说/讲/告诉/内容是/冒号」引导的那句；
     * 没有引导词时（「给妈妈发微信我晚点到」），取「发」之后的残余，
     * 把平台词、量词、收件人、句首标点一层层剥掉。
     */
    private fun extractSendContent(text: String, to: String): String {
        val cued = SEND_CONTENT_CUE.find(text)?.groupValues?.get(1)
            ?: SEND_CONTENT_COLON.find(text)?.groupValues?.get(1)
        val raw = cued ?: run {
            val idx = maxOf(text.lastIndexOf('发'), text.lastIndexOf('回'))
            if (idx < 0 || idx == text.length - 1) return ""
            text.substring(idx + 1)
        }
        var body = raw.trim()
        // 剥掉「一条/个/送」等量词和平台词，可能叠着来（发一条微信给…）
        repeat(3) {
            val before = body
            body = body
                .removePrefix("给$to").removePrefix("给 $to")
                .replaceFirst(Regex("^(?:一?条|个|送|一句|一下)\\s*"), "")
                .replaceFirst(Regex("^(?:微信|QQ|qq|消息|信息|短信|发给)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .trimStart('，', ',', '：', ':', '、')
                .trim()
            if (body == before) return@repeat
        }
        return body.trim().trim('。', '！', '!', '？', '?', '，', ',', ' ', '~', '～')
    }

    /**
     * 「为什么发不出消息」「能发微信吗」「检查一下发消息」。
     *
     * 用户报障时最需要的是**能自己问出原因**，不然只能看到一句笼统的失败提示。
     * 这条必须排在 [sendMessage] 后面，且只认明确的诊断措辞，
     * 免得把「给妈妈发微信说我晚点到」抢走。
     */
    private fun messageDiagnostic(text: String): Hit? {
        if (!SEND_DIAG.containsMatchIn(text)) return null
        if (!text.contains("发") && !text.contains("消息")) return null
        val key = if (QQ_WORD.containsMatchIn(text)) "qq" else "wechat"
        return Hit("msg/query_can_send", BusArgs.of("platform" to key))
    }

    // ------------------------------------------------------------------ 唤醒诊断

    /**
     * 「唤醒用不了」「小豆小豆没反应」「怎么开启语音唤醒」。
     *
     * 唤醒是唯一只能由用户在设置里手动打开的能力，出问题时没有别的入口能看状态。
     * 留一条口语入口，问一句就把开关 / 服务 / 权限 / 离线包四层一次说清。
     * 只认带「唤醒/小豆/叫不醒」且带诊断措辞的句子，不会抢「打开手电筒」这类指令。
     */
    private fun wakeDiagnostic(text: String): Hit? {
        val mentions = text.contains("唤醒") || text.contains("小豆") || text.contains("叫不醒")
        if (!mentions) return null
        if (!WAKE_DIAG.containsMatchIn(text)) return null
        return Hit("kws/status", BusArgs.EMPTY)
    }

    // ------------------------------------------------------------------ 规则表

    /**
     * 一句话里明显有第二件事的连接词。
     *
     * 「还有」也收进来：「打开手电筒还有把音量调到一半」就是两件事。
     * 不用担心误伤「还有多少电」——本地根本没有电量规则，那句本来就走大模型。
     */
    private val COMPOSITE = Regex(
        "然后|接着|顺便|另外|同时|并且|以及|还要|还有|再帮我|帮我再|" +
            "但是|不过|可是|如果|要是|的话|因为|所以|要不然|否则|" +
            "先.{0,12}再"
    )

    /** 整句模式下，短于这个长度才允许本地直接拍板；再长就交给大模型通读 */
    private const val QUICK_CAP = 24

    private val PERCENT = Regex("(\\d{1,3})\\s*%")

    private val OPEN_APP = Regex("^(?:请|帮我|麻烦)?\\s*(?:打开|启动|运行|进入)\\s*(.{1,10}?)(?:吧|呗|呀|啊)?[。！!]?$")

    private val PLATFORM_WORD = Regex("微信|wechat|weixin|QQ|qq|企鹅", RegexOption.IGNORE_CASE)
    private val QQ_WORD = Regex("QQ|qq|企鹅", RegexOption.IGNORE_CASE)

    /** 收件人：「给X」「发给X」「给X发」都行；在动词/标点/平台词前收住，不把内容吞进来 */
    private val SEND_TO = Regex(
        "(?:给|发给|跟|和)\\s*([^，,。\\s：:发回说讲]{1,12}?)\\s*" +
            "(?=发|回|说|讲|告诉|，|,|：|:|$|微信|QQ|qq|消息|信息)"
    )

    /** 「说我晚点到」「告诉他十分钟后到」「内容是xxx」 */
    private val SEND_CONTENT_CUE = Regex(
        "(?:[，,]\\s*)?(?:说|讲|告诉(?:他|她)?|内容是?|写)[：:]?\\s*(.+)$"
    )

    /** 「：我晚点到」 */
    private val SEND_CONTENT_COLON = Regex("[：:]\\s*(.+)$")

    /** 明确的报障/自检措辞，不含这些就不抢给诊断服务 */
    private val SEND_DIAG = Regex(
        "为什么|咋发|怎么发不|发不(出|了|上去)|发不了|不能发|没法发|" +
            "能发|可以发|能不能发|检查一下|检查下|自检|诊断|查一下发|看看发"
    )

    /** 唤醒诊断的措辞；「怎么开启」也算——问怎么开的人，最该先知道现在为什么没开 */
    private val WAKE_DIAG = Regex(
        "为什么|咋|怎么|用不了|用不上|不能用|没反应|没动静|叫不醒|叫不动|不灵|" +
            "检查|查一下|自检|诊断|看看|失败|不行|开启|打开"
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
