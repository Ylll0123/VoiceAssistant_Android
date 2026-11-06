package com.example.voiceassistant.fuzzy

import com.example.voiceassistant.assistant.ChineseNumber
import com.example.voiceassistant.reminder.Reminder
import com.example.voiceassistant.tool.ReminderTime
import com.example.voiceassistant.tool.TeachGuard
import java.time.ZoneId

/**
 * 模糊匹配容错引擎：**ASR 原文 → 标准化意图**。
 *
 * 放在语音链路和对话管理器之间，是因为 ASR 出来的文本天然带错：
 * 口音、吞字、同音字、语序颠倒。用精确字符串匹配去认「提醒」，
 * 用户说十次会失败三次——这正是本模块存在的理由。
 *
 * ## 四层策略，从轻到重
 * | 层 | 手段 | 解决什么 | 成本 |
 * |---|---|---|---|
 * | ① | [TextNormalizer] 归一 + 词表纠错 | 全角、语气词、同音字（山点→三点） | 极低 |
 * | ② | 规则模板 + 槽位 | 「提醒我X」「改到X」「取消X」 | 低 |
 * | ③ | [Levenshtein] 相似度 | 单字/多字错、短指令变体 | 低 |
 * | ④ | 拒绝阈值 [ACCEPT] | 拦下「今天天气怎么样」这类非指令 | 零 |
 *
 * ## 置信度是这套设计的核心
 * - `≥ [HIGH]`：直接执行，不再追问（用户会觉得「它听懂了」）；
 * - `[ACCEPT], [HIGH])`：对话管理器追问确认，宁可多问一句也不猜错；
 * - `< [ACCEPT]`：当作没听懂，交给普通问答链路——**绝不因为沾了个「提醒」
 *   就去建一条提醒**（「提醒有什么意义」这种句子就是活生生的反例）。
 *
 * ## 为什么不上词向量 / 小模型做纠错
 * 为了几个错别字加载 embedding 模型，内存和首字延迟都吃不消，
 * 而实测下来 90% 的错都能被前两层吃掉。真遇到语序完全颠倒的说法，
 * 交给对话管理器反问一句，体验也比「猜错还自信」好。
 */
class FuzzyEngine(
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /**
     * 识别一句话。
     *
     * @param raw ASR 原文或用户输入
     * @param now 当前时刻（注入保证「明天八点」这类断言可测）
     */
    fun recognize(raw: String, now: Long = clock()): IntentResult {
        val origin = raw.trim()
        if (origin.isEmpty()) return unknown(origin, "", "")

        val fixed = TextNormalizer.normalizeAndFix(origin)
        val text = fixed.text
        if (text.isEmpty()) return unknown(origin, text, text, fixed.fixes)

        // 教学指令（「记住：提醒我喝水」）是教说法，不是让助手真去建提醒
        if (TeachGuard.isTeaching(origin)) return unknown(origin, text, text, fixed.fixes)

        val match = rules(text, now) ?: bySimilarity(text) ?: return unknown(
            origin, text, text, fixed.fixes
        )

        return IntentResult(
            kind = match.kind,
            confidence = match.confidence,
            raw = origin,
            normalized = TextNormalizer.normalize(origin),
            corrected = text,
            slots = match.slots,
            fixes = fixed.fixes
        )
    }

    // ------------------------------------------------------------------ 规则层

    /** 一个候选：意图 + 置信度 + 槽位 */
    private data class Match(
        val kind: IntentKind,
        val confidence: Double,
        val slots: Slots = Slots()
    )

    private fun rules(text: String, now: Long): Match? {
        timer(text, now)?.let { return it }
        reminder(text, now)?.let { return it }
        return null
    }

    /**
     * 计时 / 倒计时。
     *
     * 「定个十分钟的闹钟」两边的词都沾，必须先判：有时长才是计时，
     * 有时刻（「明天八点的闹钟」）才是提醒。
     */
    private fun timer(text: String, now: Long): Match? {
        if (CANCEL_TIMER_CUES.any { text.contains(it) }) return Match(IntentKind.CANCEL_TIMER, 0.92)

        val duration = ReminderTime.duration(text, now)
        val slots = Slots(timeAt = duration?.at ?: 0L, timeLabel = duration?.label.orEmpty())

        if (text.contains("计时") || text.contains("倒计时")) {
            return Match(IntentKind.SET_TIMER, if (duration != null) 0.92 else 0.82, slots)
        }
        val clockWord = text.contains("闹钟") || text.contains("定时")
        if (clockWord && duration != null && !text.contains("提醒")) {
            return Match(IntentKind.SET_TIMER, 0.86, slots)
        }
        return null
    }

    /** 提醒域：新建 / 查看 / 修改 / 取消 / 清空 / 再提醒 */
    private fun reminder(text: String, now: Long): Match? {
        val hasDayWord = DAY_WORDS.any { text.contains(it) }
        val time = ReminderTime.resolve(text, now, zone)
        val hasClock = time != null

        // ① 查看清单
        if (LIST_CUES.any { text.contains(it) }) return Match(IntentKind.LIST_REMIND, 0.9)

        // ② 清空
        val cancelVerb = isCancelShape(text)
        if (cancelVerb && (text.contains("清空") || text.contains("全部"))) {
            return Match(IntentKind.CLEAR_REMIND, 0.9)
        }

        // ③ 取消某一条
        if (cancelVerb) {
            return Match(
                IntentKind.CANCEL_REMIND,
                if (text.contains("第") || text.any { it.isDigit() }) 0.92 else 0.86,
                Slots(index = indexOf(text))
            )
        }

        // ④ 再提醒一次（「十分钟后再提醒我」是新建，不是 snooze）
        if (SNOOZE_CUES.any { text.contains(it) } && ReminderTime.duration(text, now) == null) {
            return Match(IntentKind.SNOOZE_REMIND, 0.85)
        }

        // ⑤ 改时间
        update(text, now, hasDayWord)?.let { return it }

        // ⑥ 新建提醒
        if (text.contains("提醒我")) {
            return Match(
                IntentKind.SET_REMIND,
                0.92,
                setSlots(text, time, now)
            )
        }
        // 「定个明天八点的闹钟」没有「提醒」两个字，但有时刻就是提醒
        if (text.contains("闹钟") && hasClock) {
            return Match(IntentKind.SET_REMIND, 0.86, setSlots(text, time, now))
        }

        val otherCue = text.contains("叫我") || text.contains("喊我") || text.contains("记得")
        if (text.contains("提醒") || otherCue) {
            // 只有「有时刻 / 有日期 / 有该提醒的内容」才算指令，
            // 否则「提醒有什么意义」这种句子会被误判成建提醒
            if (hasClock || hasDayWord || otherCue) {
                return Match(IntentKind.SET_REMIND, 0.84, setSlots(text, time, now))
            }
        }
        return null
    }

    /** 改时间：绝对时刻（改到四点）或相对位移（推迟一小时） */
    private fun update(text: String, now: Long, hasDayWord: Boolean): Match? {
        val verb = UPDATE_VERBS.firstOrNull { text.contains(it) } ?: return null

        // 「推迟 / 提前」优先按相对位移算：目标时间 = 原时间 ± 时长
        val shift = shiftOf(text, now)
        if (shift != 0L) {
            return Match(IntentKind.UPDATE_REMIND, 0.9, Slots(shiftMs = shift))
        }

        val tail = text.substring(text.indexOf(verb) + verb.length)
        val time = ReminderTime.resolve(tail, now, zone) ?: ReminderTime.resolve(text, now, zone)
        if (time == null && !hasDayWord) return null
        return Match(
            IntentKind.UPDATE_REMIND,
            0.9,
            Slots(timeAt = time?.at ?: 0L, timeLabel = time?.label.orEmpty())
        )
    }

    /** 「推迟一小时」= +3600000；「提前半小时」= -1800000 */
    private fun shiftOf(text: String, now: Long): Long {
        val backward = BACKWARD_CUES.any { text.contains(it) }
        val forward = FORWARD_CUES.any { text.contains(it) }
        if (!backward && !forward) return 0L
        val duration = ReminderTime.duration(text, now) ?: return 0L
        val delta = duration.at - now
        return if (forward) -delta else delta
    }

    private fun setSlots(text: String, time: ReminderTime.Resolved?, now: Long): Slots = Slots(
        timeAt = time?.at ?: 0L,
        timeLabel = time?.label.orEmpty(),
        content = contentOf(text),
        repeat = repeatOf(text)
    )

    /** 要提醒什么：把时间表达和指令词都剥掉，剩下的才是内容 */
    private fun contentOf(text: String): String {
        var value = ReminderTime.stripTime(text)
        for (word in CONTENT_NOISE.sortedByDescending { it.length }) {
            value = value.replace(word, "")
        }
        return value.trim('，', ',', '。', '.', '！', '!', '：', ':', '、', ' ')
    }

    private fun repeatOf(text: String): Reminder.Repeat = when {
        text.contains("每天") || text.contains("每日") || text.contains("天天") ->
            Reminder.Repeat.DAILY
        text.contains("每周") || text.contains("每星期") || text.contains("每个星期") ->
            Reminder.Repeat.WEEKLY
        else -> Reminder.Repeat.NONE
    }

    /**
     * 是不是「取消」句法。
     *
     * 关键在**位置**：取消类的词必须出现在「提醒」前面。
     * 「提醒我不要迟到」里的「不要」是内容的一部分，
     * 只按键词判断会把这句当成「取消提醒」，用户的提醒就凭空消失了。
     */
    private fun isCancelShape(text: String): Boolean {
        val remindAt = text.indexOf("提醒")
        if (remindAt < 0) return false
        return CANCEL_VERBS.any { verb ->
            val at = text.indexOf(verb)
            at in 0 until remindAt
        }
    }

    /** 「取消提醒 2」「取消第二条」 */
    private fun indexOf(text: String): Int {
        Regex("第?([0-9]{1,2})").find(text)?.groupValues?.get(1)
            ?.toIntOrNull()?.let { return it }
        Regex("第([一二三四五六七八九十]{1,3})").find(text)?.groupValues?.get(1)
            ?.let { ChineseNumber.parse(it)?.toInt() }?.let { return it }
        return 0
    }

    // ------------------------------------------------------------------ 相似度层

    /**
     * 最后一层：跟模板比相似度。
     *
     * 归一化之后仍认不出来的短指令（多半是错了好几个字），
     * 用编辑距离找最像的模板。只对**短句**启用——长句子跟模板的相似度
     * 天然很低，硬比没有意义，还会把正常提问拉进指令域。
     */
    private fun bySimilarity(text: String): Match? {
        if (text.length > MAX_SIMILARITY_LEN) return null
        var best: Match? = null
        for ((template, kind) in TEMPLATES) {
            val similarity = Levenshtein.similarity(text, template)
            if (similarity < SIMILARITY_MIN) continue
            val confidence = 0.5 + 0.3 * similarity
            if (best == null || confidence > best.confidence) {
                best = Match(kind, confidence)
            }
        }
        return best
    }

    private fun unknown(
        raw: String,
        normalized: String,
        corrected: String,
        fixes: List<String> = emptyList()
    ) = IntentResult(
        kind = IntentKind.UNKNOWN,
        confidence = 0.0,
        raw = raw,
        normalized = normalized,
        corrected = corrected,
        fixes = fixes
    )

    companion object {

        /** 低于这个置信度就当没听懂，绝不硬猜 */
        const val ACCEPT = 0.55

        /** 高于这个置信度可以直接执行 */
        const val HIGH = 0.8

        /** 相似度层只处理短句（超过这个长度就不比了） */
        private const val MAX_SIMILARITY_LEN = 10

        /** 相似度层的入网门槛 */
        private const val SIMILARITY_MIN = 0.72

        private val CANCEL_TIMER_CUES = listOf("取消计时", "停止计时", "取消倒计时", "关掉计时", "停掉计时")

        private val LIST_CUES = listOf(
            "我的提醒", "看看我的提醒", "看看提醒", "查看提醒", "有哪些提醒", "有什么提醒",
            "提醒列表", "提醒事项", "提醒清单", "提醒都有什么", "还有哪些提醒"
        )

        private val CANCEL_VERBS = listOf("取消", "删除", "撤销", "停掉", "不要", "别再", "清空")

        private val SNOOZE_CUES = listOf("再提醒我", "稍后再", "再响一次", "等会再", "晚点再提醒")

        private val UPDATE_VERBS = listOf("改到", "改成", "推迟", "提前", "换个时间", "调整时间")

        /** 往后推 */
        private val BACKWARD_CUES = listOf("推迟", "往后", "晚")

        /** 往前挪 */
        private val FORWARD_CUES = listOf("提前", "早")

        private val DAY_WORDS = listOf(
            "今天", "明天", "后天", "大后天", "今晚", "明晚", "明早",
            "下周", "这周", "礼拜", "星期"
        )

        /** 内容里要剥掉的指令词（长的在前） */
        private val CONTENT_NOISE = listOf(
            "提醒我一下", "提醒我", "提醒", "叫我", "喊我", "记得",
            "帮我", "麻烦", "请", "一下", "给我", "定个", "设个", "加个", "来个",
            "每天", "每日", "天天", "每周", "每星期", "一个", "别忘"
        )

        /** 短指令模板：相似度层拿它比对 */
        private val TEMPLATES = listOf(
            "提醒我" to IntentKind.SET_REMIND,
            "我的提醒" to IntentKind.LIST_REMIND,
            "看看提醒" to IntentKind.LIST_REMIND,
            "取消提醒" to IntentKind.CANCEL_REMIND,
            "清空提醒" to IntentKind.CLEAR_REMIND,
            "改到" to IntentKind.UPDATE_REMIND,
            "计时" to IntentKind.SET_TIMER
        )
    }
}
