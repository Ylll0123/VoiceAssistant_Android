package com.example.voiceassistant.nlu

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.im.ImContent
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
            ?: clearContext(text)
            ?: cloudToggle(text)
            ?: torch(text)
            ?: brightness(text)
            ?: volume(text)
            ?: music(text)
            ?: radio(text)
            ?: call(text)
            ?: navigate(text)
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
            text.contains("免打扰") || text.contains("出声") ||
            // 「把铃声调小」里没有「音量」两个字，但确实是调音量
            text.contains("铃声") || text.contains("来电")
        if (!mentions) return null

        // 哪一路：铃声 / 闹钟 / 媒体。以前一律只动媒体，「把铃声调小」就像没反应
        val stream = when {
            text.contains("铃声") || text.contains("来电") -> "ring"
            text.contains("闹钟") -> "alarm"
            else -> "music"
        }

        // 「取消静音」必须排在「静音」前面判，否则一个字都还没说完就被当成静音了
        if (text.contains("取消静音") || text.contains("解除静音") ||
            text.contains("取消免打扰") || text.contains("打开声音")
        ) {
            return Hit("audio/volume_ctrl", BusArgs.of("action" to "unmute", "stream" to stream))
        }
        if (text.contains("静音") || text.contains("免打扰")) {
            return Hit("audio/volume_ctrl", BusArgs.of("action" to "mute", "stream" to stream))
        }

        volumePercent(text)?.let { percent ->
            return Hit(
                "audio/volume_ctrl",
                BusArgs.of("percent" to percent.toString(), "stream" to stream)
            )
        }
        val action = when {
            text.contains("大") || text.contains("高") -> "up"
            text.contains("小") || text.contains("低") -> "down"
            else -> return null
        }
        return Hit("audio/volume_ctrl", BusArgs.of("action" to action, "stream" to stream))
    }

    /**
     * 从一句话里读目标音量。
     *
     * 三种写法都要认：`30%`、「音量调到 30」、「调到五十」。
     * 一开始只认带百分号的，用户说「音量调到 30」就整个掉给大模型——离线时直接失败，
     * 表现就是「音量设置不好用」。
     */
    private fun volumePercent(text: String): Int? {
        PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return it.coerceIn(0, 100)
        }
        VOLUME_DIGITS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 0..100) return it
        }
        VOLUME_CHINESE.find(text)?.groupValues?.get(1)?.let { raw ->
            TimeWords.chineseNumber(raw)?.let { if (it in 0..100) return it }
        }
        return null
    }

    /**
     * 「播放音乐」「暂停」「下一首」。
     *
     * 只认带音乐动作的说法，不抢「打开网易云」这类指令（那条走 [openApp]）；
     * 单独一个「暂停」也不认——它更可能是在说计时器。
     */
    private fun music(text: String): Hit? {
        if (text.length > 18) return null

        val play = listOf(
            "播放音乐", "放音乐", "放首歌", "放个歌", "来首歌", "来点音乐",
            "播放歌曲", "听歌", "放点音乐", "开始播放", "播首歌"
        )
        if (play.any { text.contains(it) }) {
            return Hit("audio/music_ctrl", BusArgs.of("action" to "play"))
        }

        val pause = listOf("暂停音乐", "暂停播放", "别放了", "停止播放", "关掉音乐", "把音乐停")
        if (pause.any { text.contains(it) }) {
            return Hit("audio/music_ctrl", BusArgs.of("action" to "pause"))
        }

        if (listOf("下一首", "下一曲", "切歌", "换一首", "换首歌").any { text.contains(it) }) {
            return Hit("audio/music_ctrl", BusArgs.of("action" to "next"))
        }
        if (listOf("上一首", "上一曲", "前一首").any { text.contains(it) }) {
            return Hit("audio/music_ctrl", BusArgs.of("action" to "previous"))
        }
        return null
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
     * 发消息：「给妈妈发微信说我晚点到」，以及换了语序的一大堆说法。
     *
     * ## 为什么又放宽了一次
     * 上一版要求「有『发/回』+ 有『给』+ 有平台词」三条同时成立，于是
     * 「跟妈妈说一声晚点到」「微信告诉妈妈我晚点到」这类最自然的话全漏给大模型；
     * 离线时模型不可用，用户只会看到「没联网」，消息根本发不出去。
     *
     * 现在分两条路进：
     * 1. **带平台词/消息词**：只要还能抽出收件人，就认领——动词是什么不重要；
     * 2. **不带平台词**：「跟/和/对 X 说一声…」这种**明确托话**的说法，默认发微信。
     *
     * 第二条刻意只认「说一声 / 说一下 / 说一句 / 带个话 / 转告」这几个明确的托话标记：
     * 「跟我说说天气」里也有「跟…说」，但它不是托话，不能让它落到发消息上。
     *
     * 内容为空（「给妈妈发个微信」）仍然不认领——那需要先让模型拟一句，
     * 正好接 `gen/short_sentence`。
     */
    private fun sendMessage(text: String): Hit? {
        if (!looksLikeSendMessage(text)) return null

        val to = (SEND_TO.find(text) ?: TALK_TO.find(text))
            ?.groupValues?.get(1)?.trim().orEmpty()
        if (to.isEmpty() || to.length > 12) return null

        // 内容统一交给 ImContent 洗：两条来路（本地/模型）必须是同一套规则
        val content = ImContent.clean(extractSendContent(text, to))
        if (content.isEmpty()) return null

        var args = BusArgs.of("to" to to, "content" to content)
        /*
         * 只在用户**真的说了平台**时才传 platform。
         *
         * 以前一律填 wechat，等于把「没说」当成「说微信」，常用 QQ 的人每次都被带错应用。
         * 现在留空，由 msg/send_text 用上一次真正用过的平台兜底。
         */
        if (PLATFORM_WORD.containsMatchIn(text)) {
            val key = if (QQ_WORD.containsMatchIn(text)) "qq" else "wechat"
            args = args.plus("platform", key)
        }
        return Hit("msg/send_text", args)
    }

    /** 像发消息吗：要么带平台词/消息词且能抽出收件人，要么是明确的托话句式 */
    private fun looksLikeSendMessage(text: String): Boolean {
        val platform = PLATFORM_WORD.containsMatchIn(text)
        val messageWord = text.contains("消息") || text.contains("信息") || text.contains("短信")
        if (platform || messageWord) return SEND_TO.containsMatchIn(text)
        return TALK_TO.containsMatchIn(text)
    }

    /**
     * 抽内容：优先「说/讲/告诉/内容是/冒号」引导的那句；
     * 没有引导词时（「给妈妈发微信我晚点到」），取「发」之后的残余，
     * 把平台词、量词、收件人、句首标点一层层剥掉。
     */
    private fun extractSendContent(text: String, to: String): String {
        /*
         * 「给妈妈发微信问她吃饭了吗」——问句也是要发出去的正文。
         *
         * 以前只认「说 / 讲 / 告诉」，遇到「问」就整个漏掉，内容退化成
         * 「微信给妈妈问她吃饭了吗」这种残句，模型还会把它当成一个要回答的问题。
         * 现在「问」自成一档，连「问她 / 问他」一起吃掉，只留下真正要说的话。
         */
        val cued = SEND_CONTENT_ASK.find(text)?.groupValues?.get(1)
            ?: SEND_CONTENT_CUE.find(text)?.groupValues?.get(1)
            ?: SEND_CONTENT_COLON.find(text)?.groupValues?.get(1)
        val raw = cued ?: run {
            // 「跟妈妈说一声晚点到」：内容从托话标记之后开始
            val talk = TALK_MARKERS.map { text.lastIndexOf(it) }.maxOrNull() ?: -1
            val verb = maxOf(text.lastIndexOf('发'), text.lastIndexOf('回'))
            if (talk > verb) {
                text.substring(talk)
            } else {
                if (verb < 0 || verb == text.length - 1) return ""
                text.substring(verb + 1)
            }
        }
        var body = raw.trim()
        // 剥掉「一条/个/送/说一声」等量词和平台词，可能叠着来（发一条微信给…）
        repeat(3) {
            val before = body
            body = body
                .removePrefix("给$to").removePrefix("给 $to")
                .replaceFirst(Regex("^(?:一?条|个|送|一句|一下|一声|句|个话)\\s*"), "")
                .replaceFirst(Regex("^(?:说一声|说一下|说一句|说句|转告|带个话|捎句话?)\\s*"), "")
                .replaceFirst(Regex("^(?:微信|QQ|qq|消息|信息|短信|发给)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .trimStart('，', ',', '：', ':', '、')
                .trim()
            if (body == before) return@repeat
        }
        // 只去掉句末的口语拖音，**问号保留**——「你吃饭了吗？」原样发出去才对
        return body.trim().trim('，', ',', ' ', '~', '～')
    }

    // ------------------------------------------------------------------ 打电话与导航

    /**
     * 「打电话给妈妈」「给妈妈打个电话」「拨打 13800000000」。
     *
     * 抽不出人就**不认领**——「帮我打个电话」这种得先问一句打给谁，
     * 本地没有提问能力，交给大模型更合适。
     */
    private fun call(text: String): Hit? {
        /*
         * 「打电话」「打个电话」「打 110」都要认。
         *
         * 只列「打电话」一个词是不够的：「给妈妈打个电话」里夹了个「个」，
         * 于是整句掉给大模型，离线时又变成「打不出去」。
         */
        val dialVerb = text.contains("拨打") || text.contains("呼叫") ||
            text.contains("打给") || text.contains("拨给")
        val callPhrase = text.contains("电话") && (text.contains("打") || text.contains("拨"))
        val shortDial = (text.startsWith("打") || text.startsWith("拨")) &&
            PHONE_DIGITS.containsMatchIn(text)
        if (!dialVerb && !callPhrase && !shortDial) return null
        // 「别打电话」「电话静音」不是要拨号
        if (text.contains("别打") || text.contains("不用打") || text.contains("静音")) return null

        val name = CALL_NAME.find(text)?.groupValues?.get(1)?.trim()
            ?.trim('。', '！', '!', '，', ',', '吧', '呗')
            ?.takeIf { it.isNotEmpty() }
        if (name != null) return Hit("phone/call", BusArgs.of("to" to name))

        val number = PHONE_DIGITS.find(text)?.value?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        /*
         * 「打 100 分」「打 8 折」里也有数字和「打」。
         * 号码后面紧跟量词，说明这句在说别的事，不是拨号。
         */
        val tail = text.substringAfter(number).trimStart()
        if (NOT_A_NUMBER_TAIL.any { tail.startsWith(it) }) return null
        return Hit("phone/call", BusArgs.of("to" to number))
    }

    /** 「导航到公司」「带我去机场」「怎么去高铁站」 */
    private fun navigate(text: String): Hit? {
        if (NAV_WORDS.none { text.contains(it) }) return null
        // 「导航怎么用」「为什么导航不动」是问句，不是要出发
        if (text.contains("怎么用") || text.contains("为什么") || text.contains("用不了")) return null

        val dest = NAV_TO.find(text)?.groupValues?.get(1)?.trim()
            ?.trim('。', '！', '!', '，', ',', '吧', '呗', '呢')
        if (dest.isNullOrEmpty() || dest.length > 20) return null

        val mode = when {
            text.contains("步行") || text.contains("走路") || text.contains("走过去") -> "walk"
            text.contains("骑行") || text.contains("骑车") || text.contains("自行车") -> "bike"
            text.contains("公交") || text.contains("地铁") || text.contains("坐车") -> "transit"
            else -> "drive"
        }
        return Hit("phone/navigate", BusArgs.of("to" to dest, "mode" to mode))
    }

    // ------------------------------------------------------------------ 上下文与开关

    /** 「清空对话」「忘掉之前说的」「重新开始」 */
    private fun clearContext(text: String): Hit? {
        if (CLEAR_WORDS.none { text.contains(it) }) return null
        // 「清空闹钟」是删数据、「重新开始计时」是计时器，都不是清上下文
        if (text.contains("闹钟") || text.contains("提醒") || text.contains("待办") ||
            text.contains("计时") || text.contains("倒计时")
        ) {
            return null
        }
        return Hit("context/clear", BusArgs.EMPTY)
    }

    /**
     * 「关掉云端」「别联网了」「打开 AI」。
     *
     * 只在**明确开关措辞 + 明确对象**同时出现时认领：
     * 「网络不好」里也有「网络」，不能因为这两个字就把云端关掉。
     */
    private fun cloudToggle(text: String): Hit? {
        val target = text.contains("云端") || text.contains("大模型") ||
            text.contains("联网") || text.contains("智能")
        if (!target) return null
        val off = listOf("关掉", "关闭", "别用", "不用", "停掉", "禁用")
        val on = listOf("打开", "开启", "启用", "恢复")
        return when {
            off.any { text.contains(it) } -> Hit("llm/toggle", BusArgs.of("on" to "false"))
            on.any { text.contains(it) } -> Hit("llm/toggle", BusArgs.of("on" to "true"))
            else -> null
        }
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

    /** 「音量调到 30」「声音 80」——不带百分号也要认 */
    private val VOLUME_DIGITS = Regex(
        "(?:音量|声音|调到|调成|调至|设为|设成|改到)\\D{0,4}?(\\d{1,3})"
    )

    /** 「音量调到五十」 */
    private val VOLUME_CHINESE = Regex(
        "(?:音量|声音|调到|调成|调至|设为|设成|改到)\\D{0,4}?([零〇一二两三四五六七八九十]{1,3})"
    )

    private val OPEN_APP = Regex("^(?:请|帮我|麻烦)?\\s*(?:打开|启动|运行|进入)\\s*(.{1,10}?)(?:吧|呗|呀|啊)?[。！!]?$")

    private val PLATFORM_WORD = Regex("微信|wechat|weixin|QQ|qq|企鹅", RegexOption.IGNORE_CASE)
    private val QQ_WORD = Regex("QQ|qq|企鹅", RegexOption.IGNORE_CASE)

    /** 收件人：「给X」「发给X」「给X发」都行；在动词/标点/平台词前收住，不把内容吞进来 */
    private val SEND_TO = Regex(
        "(?:给|发给|跟|和|对)\\s*([^，,。\\s：:发回说讲]{1,12}?)\\s*" +
            "(?=发|回|说|讲|告诉|，|,|：|:|$|微信|QQ|qq|消息|信息)"
    )

    /** 「跟妈妈说一声…」——没有平台词时靠这个认出「托话」 */
    private val TALK_TO = Regex(
        "(?:跟|和|对)\\s*([^，,。\\s：:]{1,12}?)\\s*" +
            "(?=说一声|说一下|说一句|说句|转告|带个话|捎句话?)"
    )

    /** 托话标记本身：抽内容时从这里开始，而不是从「发」 */
    private val TALK_MARKERS = listOf("说一声", "说一下", "说一句", "说句", "转告", "带个话", "捎句话")

    /**
     * 打电话的收件人。
     *
     * 收在拨号动词/标点前：「给妈妈打个电话」里名字只到「妈妈」，
     * 不把「打个电话」一起吞进去。
     */
    private val CALL_NAME = Regex(
        "(?:给|打给|拨给|找|联系)\\s*([^，,。\\s]{1,12}?)\\s*" +
            "(?=打|拨|电话|吧|呗|，|,|$|一下)"
    )

    /**
     * 直接说的号码：「拨打 138 0000 0000」。
     *
     * 允许 3 位以上就收：10086、95588、110、119 这些短号也要能拨，
     * 一开始写成「至少 6 位」，结果「拨打10086」掉给大模型，模型嘴上说帮你拨、
     * 实际什么都没发生——正是要避免的那种假成功。
     */
    private val PHONE_DIGITS = Regex("[+]?\\d[\\d\\s\\-]{1,18}\\d")

    /** 号码后面跟这些量词，说明是在说分数/折扣/价钱，不是拨号 */
    private val NOT_A_NUMBER_TAIL = listOf("分", "折", "块", "元", "人", "次", "个", "秒", "分钟", "小时")

    /** 导航的触发词 */
    private val NAV_WORDS = listOf("导航", "带我去", "怎么去", "怎么走", "去往")

    /** 导航的目的地：从触发词后面收，到「吧/呢/怎么走/句末」为止 */
    private val NAV_TO = Regex(
        "(?:导航到|导航去|导航|带我去|怎么去|怎么走到|去往)\\s*([^，,。\\s]{1,20}?)\\s*" +
            "(?:吧|呗|呢|怎么走|的路线|$)"
    )

    /** 清空上下文的口语说法 */
    private val CLEAR_WORDS = listOf(
        "清空对话", "清空聊天", "清除对话", "清除聊天",
        "删掉对话", "删掉聊天", "忘掉之前", "忘掉上面", "重新开始"
    )

    /** 「问她吃饭了吗」——问句也是正文，「问她/问他」一起吃掉 */
    private val SEND_CONTENT_ASK = Regex(
        "(?:问(?:一下|一声|她|他|它)?)[：:]?\\s*(.+)$"
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
