package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.ChineseNumber
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 工具层：把「提醒我明天八点半交报告」里的**时间**抠出来。
 *
 * 纯函数、只认 [now] 和 [zone]，所以能在单测里钉死各种说法，
 * 不用等真机、也不受当前时刻影响。
 *
 * ## 支持的说法
 * | 类型 | 例子 |
 * |---|---|
 * | 相对时长 | 「10 分钟后」「过半小时」「两个小时之后」「1 天后」 |
 * | 今天/明天/后天 | 「明天 8 点」「今晚 8 点半」「后天中午 12 点」 |
 * | 具体日期 | 「11 月 10 号 8 点」 |
 * | 只有时刻 | 「8 点」「下午 3 点」「晚上 7 点一刻」 |
 *
 * ## 三条容易踩的规则
 * 1. **「下午/晚上」要加 12 小时**：「晚上 8 点」是 20 点，不是 8 点；
 *    但「晚上 12 点」要落回 0 点，不能算成 12 点（中午）。
 * 2. **说的时间已经过了就顺延一天**：早上九点说「8 点提醒我」，
 *    指的是明天 8 点。否则闹钟会立刻响，等于没提醒。
 * 3. **标签跟着实际时刻走**：如果顺延了，标签要写「明天」而不是「今天」。
 */
object ReminderTime {

    /** 解析结果：绝对时刻 + 给人看的说明（「明天 08:00」） */
    data class Resolved(val at: Long, val label: String)

    /** 数字：阿拉伯数字或一到三位中文数字（「十」「十五」「二十三」） */
    private const val NUM = "([0-9]{1,2}|[零〇一二两三四五六七八九十]{1,3})"

    private val HALF_HOUR = Regex("(半)\\s*(个)?\\s*(小时|钟头)")
    private val RELATIVE = Regex(
        "(?:过|再过|等)?\\s*${NUM}\\s*(秒钟|秒|分钟|分|个小时|小时|钟头|天)\\s*(?:之后|以后|后)"
    )

    /** 不带「后」的时长：「计时 5 分钟」「定个 2 小时的闹钟」 */
    private val BARE = Regex("${NUM}\\s*(秒钟|秒|分钟|分|个小时|小时|钟头)")

    private val DAY_WORDS = listOf(
        "大后天" to 3,
        "后天" to 2,
        "明天" to 1,
        "明日" to 1,
        "明早" to 1,
        "今晚" to 0,
        "今天" to 0,
        "今日" to 0
    )

    private val PERIOD_AFTERNOON = listOf("下午", "傍晚", "晚上", "夜里", "晚间", "夜间", "今晚", "明晚")
    private val PERIOD_NOON = listOf("中午", "正午")
    private val PERIOD_MORNING = listOf("早上", "早晨", "上午", "凌晨", "清晨")

    /**
     * 「8 点」「八点半」「7 点一刻」「9 时」。
     *
     * 注意分钟的括号要写成 `(?:${NUM})?`：NUM 自己已经带了一层捕获括号，
     * 再套一层普通括号会**多出一个组**，后面的分组序号全部后移一位，
     * 于是「半」被读到第 4 组、这里读第 3 组拿到 null，八点半就变成了八点整。
     */
    private val CLOCK = Regex("${NUM}\\s*(?:点|时)\\s*(?:${NUM})?\\s*(半|一刻|三刻)?")

    /** 「8:30」「20：15」 */
    private val CLOCK_COLON = Regex("([0-9]{1,2})[:：]([0-9]{2})")

    private val MONTH_DAY = Regex(
        "([0-9]{1,2}|[一二三四五六七八九十]{1,3})\\s*月\\s*([0-9]{1,2}|[一二三四五六七八九十]{1,3})\\s*[号日]"
    )

    private val LABEL = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 解析时间。
     *
     * @param now 当前时刻（毫秒），单测里传固定值
     * @return 认不出来返回 null（那就存成"没有时间"的普通清单项）
     */
    fun resolve(raw: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Resolved? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        relative(text, now)?.let { return it }

        val base = Instant.ofEpochMilli(now).atZone(zone)

        // ① 哪天：明说「11 月 10 号」优先，其次「明天 / 后天」
        val baseLocal = base.toLocalDateTime()
        val explicit = explicitDate(text, baseLocal)
        // 说了日期却拼不出合法日子（13 月 40 号），不要默默换成别的日子
        if (explicit == null && MONTH_DAY.containsMatchIn(text)) return null
        val offsetDay = explicit ?: dayOffset(text, baseLocal)

        // ② 几点
        val clock = clock(text) ?: run {
            // 只说了「明天」没说几点：默认早上 9 点，总比什么都不做强
            if (offsetDay != null && offsetDay.second > 0) {
                val at = offsetDay.first.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                return Resolved(at, dayLabel(offsetDay.second) + " 09:00")
            }
            return null
        }

        var target = (offsetDay?.first ?: base.toLocalDate()).atTime(clock.hour, clock.minute)
        var millis = target.atZone(zone).toInstant().toEpochMilli()

        // ③ 已经过了就顺延一天（「今天 8 点」在九点说 → 明天 8 点）
        if (millis <= now) {
            target = target.plusDays(1)
            millis = target.atZone(zone).toInstant().toEpochMilli()
        }

        val offset = ChronoUnit.DAYS.between(base.toLocalDate(), target.toLocalDate()).toInt()
        return Resolved(millis, dayLabel(offset) + " " + target.format(LABEL))
    }

    /**
     * 只解析「时长」部分（「10 分钟」「半小时」「2 小时」）。
     *
     * 计时器用得上：它只关心「多久之后」，不需要日期那套规则。
     */
    fun duration(raw: String, now: Long): Resolved? = relative(raw.trim(), now)

    /** 这句话里有没有「时长」（不看时刻），计时器用它决定要不要认领 */
    fun hasDuration(raw: String): Boolean = relative(raw.trim(), 0L) != null

    /**
     * 把时间表达从句子里剥掉，只留"要提醒什么"。
     *
     * 「明天 8 点交报告」→「交报告」。不剥的话回答会变成
     * 「好，明天 08:00 提醒你：明天 8 点交报告」，把时间念两遍。
     */
    fun stripTime(raw: String): String {
        var text = raw
        for ((word, _) in DAY_WORDS) text = text.replace(word, "")
        for (word in PERIOD_AFTERNOON + PERIOD_NOON + PERIOD_MORNING) {
            text = text.replace(word, "")
        }
        text = MONTH_DAY.replace(text, "")
        text = RELATIVE.replace(text, "")
        text = HALF_HOUR.replace(text, "")
        text = BARE.replace(text, "")
        text = CLOCK_COLON.replace(text, "")
        text = CLOCK.replace(text, "")
        return text.trim('，', ',', '。', '.', '、', '：', ':', '的', ' ')
    }

    /**
     * 把一个绝对时刻说成人话（「明天 08:00」）。
     *
     * 列清单时用：用户看到「1. 明天 08:00 交报告」，
     * 比「1. 交报告」多一眼就能确认闹钟排对了。
     */
    fun describe(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val target = Instant.ofEpochMilli(at).atZone(zone)
        if (at < now) return "已过期 ${target.format(LABEL)}"
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val offset = ChronoUnit.DAYS.between(today, target.toLocalDate()).toInt()
        return dayLabel(offset) + " " + target.format(LABEL)
    }

    // ------------------------------------------------------------------ 相对时长
    private fun relative(text: String, now: Long): Resolved? {
        if (HALF_HOUR.containsMatchIn(text)) {
            return Resolved(now + 30 * 60_000L, "30 分钟后")
        }
        // 先找带「后」的明确说法，再退到裸时长（「5 分钟」）
        val match = RELATIVE.find(text) ?: BARE.find(text) ?: return null
        val amount = number(match.groupValues[1]) ?: return null
        if (amount <= 0) return null
        return when (val unit = match.groupValues[2]) {
            "秒", "秒钟" -> Resolved(now + amount * 1_000L, "$amount 秒后")
            "分钟", "分" -> Resolved(now + amount * 60_000L, humanDuration(amount))
            "小时", "个小时", "钟头" -> Resolved(now + amount * 60 * 60_000L, humanDuration(amount * 60))
            "天" -> Resolved(now + amount * 24 * 60 * 60_000L, "$amount 天后")
            else -> null
        }
    }

    private fun humanDuration(minutes: Long): String = when {
        minutes < 60 -> "$minutes 分钟后"
        minutes % 60 == 0L -> "${minutes / 60} 小时后"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟后"
    }

    // ------------------------------------------------------------------ 哪天

    private fun dayOffset(text: String, base: LocalDateTime): Pair<LocalDate, Int>? {
        for ((word, offset) in DAY_WORDS) {
            if (text.contains(word)) return base.toLocalDate().plusDays(offset.toLong()) to offset
        }
        return null
    }

    private fun explicitDate(text: String, base: LocalDateTime): Pair<LocalDate, Int>? {
        val match = MONTH_DAY.find(text) ?: return null
        val month = number(match.groupValues[1])?.toInt() ?: return null
        val day = number(match.groupValues[2])?.toInt() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        var date = runCatching { LocalDate.of(base.year, month, day) }.getOrNull() ?: return null
        // 说的日子今年已经过了 → 认为是明年
        if (date.isBefore(base.toLocalDate())) {
            date = runCatching { LocalDate.of(base.year + 1, month, day) }.getOrNull() ?: return null
        }
        return date to ChronoUnit.DAYS.between(base.toLocalDate(), date).toInt()
    }

    private fun dayLabel(offset: Int): String = when (offset) {
        0 -> "今天"
        1 -> "明天"
        2 -> "后天"
        3 -> "大后天"
        else -> "$offset 天后"
    }

    // ------------------------------------------------------------------ 几点

    private data class Clock(val hour: Int, val minute: Int)

    private fun clock(text: String): Clock? {
        CLOCK_COLON.find(text)?.let { m ->
            val h = m.groupValues[1].toIntOrNull()
            val min = m.groupValues[2].toIntOrNull()
            if (h != null && min != null && min in 0..59) {
                val hour = applyPeriod(h, text)
                if (hour in 0..23) return Clock(hour, min)
            }
        }

        val match = CLOCK.find(text) ?: return null
        val rawHour = number(match.groupValues[1])?.toInt() ?: return null

        val spelledMinute = match.groupValues[2].takeIf { it.isNotEmpty() }
            ?.let { number(it)?.toInt() }
        val minute = spelledMinute ?: when (match.groupValues[3]) {
            "半" -> 30
            "一刻" -> 15
            "三刻" -> 45
            else -> 0
        }
        if (minute !in 0..59) return null

        val hour = applyPeriod(rawHour, text)
        if (hour !in 0..23) return null
        return Clock(hour, minute)
    }

    /** 「下午/晚上」+12；「晚上 12 点」落回 0 点；「中午 1 点」也按下午算 */
    private fun applyPeriod(hour: Int, text: String): Int {
        return when {
            PERIOD_AFTERNOON.any { text.contains(it) } -> when {
                hour == 12 -> 0          // 晚上 12 点 = 午夜
                hour < 12 -> hour + 12
                else -> hour
            }
            PERIOD_NOON.any { text.contains(it) } -> if (hour in 1..5) hour + 12 else hour
            PERIOD_MORNING.any { text.contains(it) } -> if (hour == 12) 0 else hour
            else -> hour
        }
    }

    /** 数字串 → 整数：先试阿拉伯，再试中文 */
    private fun number(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        return ChineseNumber.parse(text)
    }
}
