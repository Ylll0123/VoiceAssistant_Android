package com.example.voiceassistant.nlu

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 中文时间说法 → [LocalDateTime]（纯函数，可单测）。
 *
 * 为什么要自己解析：本地 NLU 一旦命中「提醒我八点交作业」就必须**当场算出时间**，
 * 不能为了一个「八点」去请求一次大模型——那样「不调 LLM」就成了空话。
 *
 * 只认**说得清**的表达，认不出来返回 null，由调用方交给大模型：
 * ```
 * 八点 / 8点 / 8:30 / 八点半 / 晚上八点 / 下午三点一刻
 * 明天早上七点 / 后天中午 / 周三下午两点 / 11月8号9点
 * ```
 * 不认：「晚一点」「快中午的时候」——这种交给模型猜比本地瞎算强。
 */
object TimeWords {

    /** 解析绝对时间点；说不清就返回 null */
    fun parse(raw: String, now: LocalDateTime): LocalDateTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val clock = findClock(text) ?: return null
        val (hour, minute) = clock

        val explicitDate = findDate(text, now)
        val baseDate = explicitDate ?: now.toLocalDate()

        // 组合时可以跨天：hour 24 表示「晚上12点」推到次日 0 点
        val normalizedHour = if (hour == 24) 0 else hour
        var target = baseDate.atTime(normalizedHour, minute)
        if (hour == 24) target = target.plusDays(1)

        // 说的时间点已经过去了 → 顺延一天。
        // 哪怕是「今天八点」也这样：定到明天早上，总比立刻弹一个过期提醒强。
        if (target.isBefore(now)) {
            target = target.plusDays(1)
        }
        return target
    }

    /**
     * 解析「还有多久」这类相对时长，返回毫秒。
     *
     * ```
     * 10分钟后 / 十分钟后 / 半小时后 / 一个半小时后 / 3小时后 / 30秒后
     * ```
     */
    fun durationMillis(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // 「一个半小时」要排在「半小时」前面，否则会被吃掉半截
        Regex("(\\d+|[一二三四五六七八九十两]+)\\s*个?半\\s*小时").find(text)?.let {
            val whole = chineseNumber(it.groupValues[1]) ?: 1
            return (whole * 60L + 30L) * 60_000L
        }
        Regex("半\\s*个?\\s*小时").find(text)?.let { return 30L * 60_000L }

        Regex("(\\d+|[一二三四五六七八九十两]+)\\s*个?\\s*小时").find(text)?.let {
            val hours = chineseNumber(it.groupValues[1]) ?: return null
            return hours * 60L * 60_000L
        }
        Regex("(\\d+|[一二三四五六七八九十两]+)\\s*分钟").find(text)?.let {
            val minutes = chineseNumber(it.groupValues[1]) ?: return null
            return minutes * 60_000L
        }
        Regex("(\\d+|[一二三四五六七八九十两]+)\\s*秒").find(text)?.let {
            val seconds = chineseNumber(it.groupValues[1]) ?: return null
            return seconds * 1_000L
        }
        return null
    }

    /** 这句话里有没有「日期」的线索（判断要不要跨天时用） */
    fun hasDateWord(raw: String): Boolean =
        DATE_WORDS.any { raw.contains(it) } ||
            WEEKDAY.find(raw) != null ||
            MONTH_DAY.find(raw) != null

    // ------------------------------------------------------------------ 内部

    /** 找出「几点几分」，返回 (时, 分) */
    private fun findClock(text: String): Pair<Int, Int>? {
        // 「晚一点」「快一点」「多点」说的是「再等会儿」，不是一点钟。
        // 这类话本地猜不准，直接返回 null，让调用方交给大模型。
        if (FUZZY_POINT.containsMatchIn(text)) return null

        // 「8:30」这种冒号写法最没有歧义，先认
        COLON.find(text)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..24 || minute !in 0..59) return null
            return applyPeriod(hour, text) to minute
        }

        val match = CLOCK.find(text)
        if (match != null) {
            val hour = chineseNumber(match.groupValues[1]) ?: return null
            if (hour !in 0..24) return null

            val minute = when (val rawMinute = match.groupValues[2]) {
                "" -> 0
                "半" -> 30
                "一刻" -> 15
                "三刻" -> 45
                else -> rawMinute.toIntOrNull() ?: 0
            }
            if (minute !in 0..59) return null
            return applyPeriod(hour, text) to minute
        }

        // 只说了时段、没说几点（「后天中午」「明早」）：给一个该时段的默认整点。
        // 这些值已经是 24 小时制，不能再过一遍 [applyPeriod]。
        PERIOD_DEFAULT.firstOrNull { text.contains(it.first) }?.let { return it.second to 0 }
        return null
    }

    /**
     * 「晚上」+「八点」= 20 点。
     *
     * 只做必要的换算，**不猜**：没说时段就按字面，宁可顺延一天也不擅自加减 12 小时。
     */
    private fun applyPeriod(hour: Int, text: String): Int {
        val afternoon = listOf("下午", "傍晚", "晚上", "夜里", "晚间").any { text.contains(it) }
        val noon = text.contains("中午")
        val earlyMorning = text.contains("凌晨")
        return when {
            afternoon && hour in 1..11 -> hour + 12
            afternoon && hour == 12 -> 12
            noon && hour in 1..10 -> hour + 12
            earlyMorning && hour == 12 -> 0
            else -> hour
        }
    }

    /** 找出日期；没提到日期返回 null */
    private fun findDate(text: String, now: LocalDateTime): LocalDate? {
        when {
            text.contains("大后天") -> return now.toLocalDate().plusDays(3)
            text.contains("后天") -> return now.toLocalDate().plusDays(2)
            text.contains("明天") || text.contains("明日") ||
                text.contains("明早") || text.contains("明晚") -> return now.toLocalDate().plusDays(1)
            text.contains("今天") || text.contains("今日") || text.contains("今晚") ||
                text.contains("今早") || text.contains("今夜") -> return now.toLocalDate()
        }

        // 「11月8号」「11月8日」
        MONTH_DAY.find(text)?.let { match ->
            val month = match.groupValues[1].toIntOrNull() ?: return@let
            val day = match.groupValues[2].toIntOrNull() ?: return@let
            if (month !in 1..12 || day !in 1..31) return@let
            val date = LocalDate.of(now.year, month, day)
            // 说了一个已经过去的日期，按明年算（「12月1号」在 11 月说就是今年）
            return if (date.isBefore(now.toLocalDate())) date.plusYears(1) else date
        }

        // 「周三」「星期三」「礼拜三」
        WEEKDAY.find(text)?.let { match ->
            val target = weekdayValue(match.groupValues[1]) ?: return@let
            val today = now.toLocalDate()
            var delta = target - today.dayOfWeek.value
            // 正好是今天也往后推一周：人会说「今天」而不是「周二」，
            // 说「周二」通常指下一个周二；退一步说，定成已经过去的时刻也没用
            if (delta <= 0) delta += 7
            return today.plusDays(delta.toLong())
        }

        return null
    }

    private fun weekdayValue(raw: String): Int? = when (raw) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        "四", "4" -> 4
        "五", "5" -> 5
        "六", "6" -> 6
        "日", "天", "7", "七" -> 7
        else -> null
    }

    /**
     * 中文数字转整数，支持到 99。
     *
     * 「八」→8、「十」→10、「十一」→11、「二十三」→23、「两」→2。
     * 纯阿拉伯数字直接走 `toIntOrNull`。
     */
    fun chineseNumber(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toIntOrNull()?.let { return it }
        // 「十」单独出现是 10，不是「一十」
        if (text == "十") return 10
        if (text.length == 1) return DIGITS[text]
        if (text.length > 3) return null

        val tenIndex = text.indexOf('十')
        if (tenIndex < 0) return null
        val tens = if (tenIndex == 0) 1 else DIGITS[text.substring(0, tenIndex)] ?: return null
        val onesPart = text.substring(tenIndex + 1)
        val ones = if (onesPart.isEmpty()) 0 else DIGITS[onesPart] ?: return null
        return tens * 10 + ones
    }

    /** 把时间点格式化成「今天 20:00」「明天 08:00」这种好念的样子 */
    fun describe(target: LocalDateTime, now: LocalDateTime): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())
        val day = when (days) {
            0L -> "今天"
            1L -> "明天"
            2L -> "后天"
            else -> "${target.monthValue} 月 ${target.dayOfMonth} 日"
        }
        return "$day ${String.format("%02d:%02d", target.hour, target.minute)}"
    }

    fun atTime(date: LocalDate, hour: Int, minute: Int): LocalDateTime =
        date.atTime(LocalTime.of(hour, minute))

    // ------------------------------------------------------------------ 规则表

    /** 必须带「点」或冒号才算时刻，避免把「8 个苹果」当 8 点 */
    private val CLOCK = Regex("(\\d{1,2}|[一二三四五六七八九十两]{1,3})\\s*[点時时]\\s*(\\d{1,2}|半|一刻|三刻)?")

    private val COLON = Regex("(\\d{1,2}):(\\d{2})")

    private val WEEKDAY = Regex("(?:周|星期|礼拜)([一二三四五六日天1-7])")

    /** 「晚一点」「快一点」「再点」这类敷衍说法，不是时刻 */
    private val FUZZY_POINT = Regex("(晚|早|快|慢|多|少|好|再|差)\\s*一?\\s*点")

    private val MONTH_DAY = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")

    private val DATE_WORDS = listOf("今天", "今日", "明天", "明日", "后天", "大后天", "今晚", "今早", "今夜")

    /**
     * 只说了时段时的默认整点（24 小时制）。
     *
     * 长词在前：「明早」必须比「早」先命中，否则「明早」会被当成别的。
     */
    private val PERIOD_DEFAULT = listOf(
        "凌晨" to 5,
        "早上" to 8,
        "早晨" to 8,
        "明早" to 8,
        "上午" to 10,
        "中午" to 12,
        "下午" to 14,
        "傍晚" to 18,
        "晚上" to 20,
        "晚间" to 20,
        "今晚" to 20,
        "夜里" to 22
    )

    private val DIGITS: Map<String, Int> = mapOf(
        "零" to 0, "〇" to 0, "一" to 1, "二" to 2, "两" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9
    )
}
