package com.example.voiceassistant.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * 内置意图规则集合。
 *
 * 每条规则只关心「我能不能答」，不关心别的规则存在，方便单独测试和后续增删。
 * 匹配一律基于 [AssistantRequest.text]（归一化后的文本），关键词判断走
 * [String.containsAny]，因此天然带识别容错。
 */
object AssistantRules {

    /**
     * 规则注册表：顺序即优先级。
     *
     * 越靠前越优先，几个容易互相抢话的位置要注意：
     * - 猜数字 / 连续计算 / 多轮追问必须在算数之前（它们的输入里也有数字）
     * - 世界时间必须在「现在几点」之前（「纽约现在几点」也含「几点」）
     * - 日期推算必须在日期之前（「下周三几号」也含「几号」）
     */
    fun all(): List<IntentRule> = listOf(
        GuessNumber,
        CalcContinue,
        FollowUp,
        Arithmetic,
        Timezone,
        Time,
        WeekdayCalc,
        Date,
        Age,
        UnitConvert,
        RandomPick,
        Quote,
        Joke,
        Identity,
        Help,
        Weather,
        Thanks,
        Farewell,
        Greeting
    )

    // ------------------------------------------------------------------ 多轮对话

    /**
     * 猜数字：一个真正意义上的多轮对话。
     * 目标值存在上下文槽位里，每次猜测读出来比较大小。
     */
    object GuessNumber : IntentRule {
        override val intent = "guess"

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            val context = request.context

            if (text.containsAny("猜数字", "猜个数", "猜数游戏", "玩个游戏", "玩游戏")) {
                val target = Random.nextInt(1, 101)
                return RuleReply(
                    "好，我心里想了一个 1 到 100 的整数，你来猜。每猜一次我会告诉你大了还是小了。",
                    intent,
                    mapOf("target" to target.toString(), "tries" to "0")
                )
            }

            if (!context.hasIntent(intent)) return null

            if (text.containsAny("不玩", "退出", "结束", "放弃", "不猜")) {
                val target = context.slot("target")?.toIntOrNull()
                return RuleReply(
                    "好，不玩了。刚才的答案是 ${target ?: "（已清空）"}。",
                    intent,
                    mapOf("target" to "", "tries" to "0")
                )
            }

            val guess = Regex("\\d{1,3}").find(text)?.value?.toIntOrNull() ?: return null
            val target = context.slot("target")?.toIntOrNull() ?: return null
            val tries = (context.slot("tries")?.toIntOrNull() ?: 0) + 1

            return when {
                guess == target -> RuleReply(
                    "猜对了！答案就是 $target，你一共猜了 $tries 次。",
                    intent,
                    mapOf("target" to "", "tries" to "0")
                )
                guess < target -> RuleReply(
                    "$guess 小了，再往大猜。这是第 $tries 次。",
                    intent,
                    mapOf("tries" to tries.toString())
                )
                else -> RuleReply(
                    "$guess 大了，往小猜。这是第 $tries 次。",
                    intent,
                    mapOf("tries" to tries.toString())
                )
            }
        }
    }

    /** 接着上次的结果继续算：「再加 5」「再乘以 2」 */
    object CalcContinue : IntentRule {
        override val intent = "calc_continue"

        private val pattern = Regex("(再|继续)?(加上|加|减去|减|乘以|乘|除以|除)(\\d+(\\.\\d+)?)")

        override fun reply(request: AssistantRequest): RuleReply? {
            val context = request.context
            if (!context.hasIntent("arithmetic") && !context.hasIntent(intent)) return null

            val match = pattern.find(request.text) ?: return null
            val operator = match.groupValues[2]
            val operand = match.groupValues[3].toDoubleOrNull() ?: return null
            val base = context.slot("value")?.toDoubleOrNull() ?: return null

            val result = when (operator) {
                "加上", "加" -> base + operand
                "减去", "减" -> base - operand
                "乘以", "乘" -> base * operand
                "除以", "除" -> if (operand == 0.0) return RuleReply("0 不能做除数。", intent)
                else base / operand
                else -> return null
            }
            val label = when (operator) {
                "加上", "加" -> "+"
                "减去", "减" -> "-"
                "乘以", "乘" -> "×"
                else -> "÷"
            }
            return RuleReply(
                "${formatNumber(base)} $label ${formatNumber(operand)} = ${formatNumber(result)}",
                "arithmetic",
                mapOf("value" to formatNumber(result))
            )
        }
    }

    /**
     * 多轮追问：把「那后天呢」「换一个」「再来一首」这类省略句补全。
     * 靠上下文里的上一轮意图判断用户到底在问什么。
     */
    object FollowUp : IntentRule {
        override val intent = "follow_up"

        private val weatherWords = arrayOf("天气", "气温", "下雨", "温度")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            val context = request.context

            val relative = relativeDay(text)
            if (relative != null && !text.containsAny(*weatherWords)) {
                if (context.hasIntent("date") && text.length <= 8) {
                    return RuleReply(dateSentence(LocalDate.now().plusDays(relative.first), relative.second), "date")
                }
                if (context.hasIntent("weather")) {
                    return RuleReply(weatherPlaceholder(relative.second), "weather")
                }
            }

            if (text.containsAny("再来一个", "再来一首", "换一个", "再讲一个", "还有吗")) {
                return when {
                    context.hasIntent("joke") -> RuleReply(Joke.pick(), "joke")
                    context.hasIntent("quote") -> RuleReply(Quote.pick(), "quote")
                    else -> null
                }
            }
            return null
        }
    }

    // ------------------------------------------------------------------ 计算与换算

    /** 算术：优先于闲聊，避免「1 加 1 等于几」被当成普通句子 */
    object Arithmetic : IntentRule {
        override val intent = "arithmetic"

        override fun reply(request: AssistantRequest): RuleReply? {
            val normalized = request.raw
                .replace("加上", "+")
                .replace("加", "+")
                .replace("减去", "-")
                .replace("减", "-")
                .replace("乘以", "*")
                .replace("乘", "*")
                .replace("除以", "/")
                .replace("除", "/")
                .replace("的平方", "^2")
                .replace("×", "*")
                .replace("÷", "/")
                .replace("等于多少", "")
                .replace("等于几", "")
                .replace("等于", "")
                .replace("是多少", "")
                .replace("帮我算", "")
                .replace("算一下", "")
                .replace("计算", "")

            val expression = normalized.filter { it.isDigit() || it in "+-*/%^()." }.trim()
            val hasDigit = expression.any { it.isDigit() }
            val hasOperator = expression.any { it in "+-*/%^" }
            if (!hasDigit || !hasOperator) return null

            val value = MathEvaluator.evaluate(expression) ?: return null
            if (abs(value) > 1e15) return null

            val formatted = formatNumber(value)
            return RuleReply("$expression = $formatted", intent, mapOf("value" to formatted))
        }
    }

    /** 单位换算：公里/英里、摄氏度/华氏度、斤/千克等 */
    object UnitConvert : IntentRule {
        override val intent = "unit_convert"

        private data class Conversion(
            val from: Array<String>,
            val to: Array<String>,
            val factor: Double,
            val fromLabel: String,
            val toLabel: String
        )

        private val conversions = listOf(
            Conversion(arrayOf("公里", "千米", "km"), arrayOf("英里", "mile"), 0.621371, "公里", "英里"),
            Conversion(arrayOf("英里", "mile"), arrayOf("公里", "千米", "km"), 1.609344, "英里", "公里"),
            Conversion(arrayOf("米"), arrayOf("英尺", "foot", "feet"), 3.28084, "米", "英尺"),
            Conversion(arrayOf("英尺", "foot", "feet"), arrayOf("米"), 0.3048, "英尺", "米"),
            Conversion(arrayOf("斤"), arrayOf("千克", "公斤", "kg"), 0.5, "斤", "千克"),
            Conversion(arrayOf("千克", "公斤", "kg"), arrayOf("斤"), 2.0, "千克", "斤"),
            Conversion(arrayOf("厘米", "cm"), arrayOf("英寸", "inch"), 0.393701, "厘米", "英寸"),
            Conversion(arrayOf("英寸", "inch"), arrayOf("厘米", "cm"), 2.54, "英寸", "厘米")
        )

        private val numberPattern = Regex("\\d+(\\.\\d+)?")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            val value = numberPattern.find(text)?.value?.toDoubleOrNull() ?: return null

            // 温度单独处理，不是简单乘法
            val hasCelsius = text.contains("摄氏") || (text.contains("度") && !text.contains("华氏"))
            val hasFahrenheit = text.contains("华氏")
            if (hasCelsius && hasFahrenheit && text.containsAny("多少", "换成", "等于", "是")) {
                return if (text.indexOf("华氏") < text.indexOf("度")) {
                    // 华氏 -> 摄氏
                    val result = (value - 32) * 5 / 9
                    RuleReply("${formatNumber(value)} 华氏度 = ${formatNumber(result)} 摄氏度", intent)
                } else {
                    val result = value * 9 / 5 + 32
                    RuleReply("${formatNumber(value)} 摄氏度 = ${formatNumber(result)} 华氏度", intent)
                }
            }

            for (conversion in conversions) {
                val fromHit = conversion.from.any { text.contains(it) }
                val toHit = conversion.to.any { text.contains(it) }
                if (fromHit && toHit) {
                    val result = value * conversion.factor
                    return RuleReply(
                        "${formatNumber(value)} ${conversion.fromLabel} = ${formatNumber(result)} ${conversion.toLabel}",
                        intent
                    )
                }
            }
            return null
        }
    }

    /** 年龄：说出出生年份，算到今年多大 */
    object Age : IntentRule {
        override val intent = "age"

        private val yearPattern = Regex("(19\\d{2}|20\\d{2})年")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            if (!text.containsAny("多大", "几岁", "年龄", "岁数")) return null
            val year = yearPattern.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null

            val today = LocalDate.now()
            if (year > today.year || year < 1900) return null
            val age = today.year - year
            return RuleReply("$year 年出生，到 ${today.year} 年是 $age 岁（按年份算，没考虑生日是否已过）。", intent)
        }
    }

    // ------------------------------------------------------------------ 时间与日期

    /** 世界时间：纽约、伦敦、东京等城市现在几点 */
    object Timezone : IntentRule {
        override val intent = "timezone"

        private val cities = linkedMapOf(
            "北京" to "Asia/Shanghai",
            "上海" to "Asia/Shanghai",
            "纽约" to "America/New_York",
            "洛杉矶" to "America/Los_Angeles",
            "旧金山" to "America/Los_Angeles",
            "伦敦" to "Europe/London",
            "巴黎" to "Europe/Paris",
            "柏林" to "Europe/Berlin",
            "莫斯科" to "Europe/Moscow",
            "东京" to "Asia/Tokyo",
            "首尔" to "Asia/Seoul",
            "新加坡" to "Asia/Singapore",
            "悉尼" to "Australia/Sydney",
            "迪拜" to "Asia/Dubai"
        )

        private val formatter = DateTimeFormatter.ofPattern("HH:mm")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            if (!text.containsAny("几点", "时间", "时候")) return null

            val city = cities.keys.firstOrNull { text.contains(it) } ?: return null
            val zone = ZoneId.of(cities.getValue(city))
            val time = LocalTime.now(zone).format(formatter)
            val local = LocalTime.now().format(formatter)
            return RuleReply("$city 现在是 $time（本地时间 $local）。", intent)
        }
    }

    /** 现在几点 */
    object Time : IntentRule {
        override val intent = "time"

        private val formatter = DateTimeFormatter.ofPattern("HH:mm")

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("几点", "时间", "时候了")) return null
            return RuleReply("现在是 ${LocalTime.now().format(formatter)}。", intent)
        }
    }

    /** 今天几号 / 星期几，支持昨天、明天、后天 */
    object Date : IntentRule {
        override val intent = "date"

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            val asksWeekday = text.containsAny("星期几", "周几")
            val asksDate = text.containsAny("几号", "日期", "几月几号", "多少号", "今天是")
            if (!asksWeekday && !asksDate) return null

            val relative = relativeDay(text)
            val target = LocalDate.now().plusDays(relative?.first ?: 0L)
            val label = relative?.second ?: "今天"

            return if (asksWeekday && !asksDate) {
                RuleReply("${label}是${weekdayCn(target)}。", intent)
            } else {
                RuleReply(dateSentence(target, label), intent)
            }
        }
    }

    /** 日期推算：下周三是几号、距离某个日子还有几天 */
    object WeekdayCalc : IntentRule {
        override val intent = "weekday_calc"

        private val weekdayPattern = Regex("(下|这|本|个)?(周|星期)([一二三四五六日天])")
        private val untilPattern = Regex("(\\d{1,2})月(\\d{1,2})[日号]")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            val today = LocalDate.now()

            val untilMatch = untilPattern.find(text)
            if (untilMatch != null && text.containsAny("还有", "距离", "几天", "倒数")) {
                val month = untilMatch.groupValues[1].toIntOrNull() ?: return null
                val day = untilMatch.groupValues[2].toIntOrNull() ?: return null
                if (month !in 1..12 || day !in 1..31) return null
                var target = LocalDate.of(today.year, month, day)
                if (target.isBefore(today)) target = target.plusYears(1)
                val days = ChronoUnit.DAYS.between(today, target)
                return RuleReply(
                    "距离 ${target.monthValue} 月 ${target.dayOfMonth} 日还有 $days 天。",
                    intent
                )
            }

            val match = weekdayPattern.find(text) ?: return null
            if (!text.containsAny("几号", "哪天", "几月几", "是多少")) return null

            val prefix = match.groupValues[1]
            val targetDay = weekdayValue(match.groupValues[3]) ?: return null
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())

            val target = when {
                prefix == "下" -> monday.plusWeeks(1).plusDays((targetDay - 1).toLong())
                prefix == "这" || prefix == "本" || prefix == "个" ->
                    monday.plusDays((targetDay - 1).toLong())
                else -> today.plusDays(((targetDay - today.dayOfWeek.value) + 7) % 7L)
            }
            val label = if (prefix == "下") "下${match.groupValues[2]}" + match.groupValues[3] else weekdayCn(target)
            return RuleReply(
                "$label 是 ${target.monthValue} 月 ${target.dayOfMonth} 日，${weekdayCn(target)}。",
                intent
            )
        }
    }

    // ------------------------------------------------------------------ 生活小工具

    /** 随机决定：抛硬币、掷骰子、随机数、二选一 */
    object RandomPick : IntentRule {
        override val intent = "random"

        private val rangePattern = Regex("(\\d{1,4})到(\\d{1,4})")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text

            if (text.containsAny("硬币", "正反面", "抛个币")) {
                return RuleReply(if (Random.nextBoolean()) "硬币是正面。" else "硬币是反面。", intent)
            }
            if (text.containsAny("骰子", "掷个色子")) {
                return RuleReply("骰子掷出了 ${Random.nextInt(1, 7)} 点。", intent)
            }
            val range = rangePattern.find(text)
            if (range != null && text.containsAny("随机", "随机数", "抽")) {
                val start = range.groupValues[1].toInt()
                val end = range.groupValues[2].toInt()
                if (start > end) return RuleReply("范围写反了，应该是小的数在前。", intent)
                return RuleReply("$start 到 $end 之间随机一个：${Random.nextInt(start, end + 1)}。", intent)
            }
            // 「选 A 还是 B」：normalize 会把「帮我」剥掉，所以这里用「选」+ 连接词判断
            val hasChoiceConnector = text.contains("还是") || text.contains("或者")
            if (hasChoiceConnector && text.containsAny("选", "哪个好", "二选一")) {
                val separator = if (text.contains("还是")) "还是" else "或者"
                val parts = request.raw.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val pick = parts[Random.nextInt(parts.size)]
                    return RuleReply("我选「${pick}」。", intent)
                }
            }
            return null
        }
    }

    /** 名言 / 诗句 */
    object Quote : IntentRule {
        override val intent = "quote"

        private val quotes = listOf(
            "「生活不止眼前的苟且，还有诗和远方的田野。」",
            "「不积跬步，无以至千里；不积小流，无以成江海。」——荀子",
            "「凡事值得做的事，就值得做好。」——切斯特菲尔德",
            "「海内存知己，天涯若比邻。」——王勃",
            "「一个人知道自己为什么而活，就可以忍受任何一种生活。」——尼采",
            "「山重水复疑无路，柳暗花明又一村。」——陆游"
        )

        fun pick(): String = quotes.random()

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("名言", "诗句", "背首诗", "来首诗", "鸡汤")) return null
            return RuleReply(pick(), intent)
        }
    }

    /** 讲个笑话 */
    object Joke : IntentRule {
        override val intent = "joke"

        private val jokes = listOf(
            "有一天，程序员去面试。面试官问：你最大的缺点是什么？程序员说：我没有缺点，只有 bug。",
            "为什么程序员分不清万圣节和圣诞节？因为 Oct 31 等于 Dec 25。",
            "小明问爸爸：为什么天空是蓝色的？爸爸说：因为海是蓝色的。小明又问：那海为什么是蓝色的？爸爸说：因为我也不知道。",
            "有个人掉进了河里，大喊救命。路人问：你会游泳吗？他说：我会一点点。路人说：那你先游一点点吧。",
            "同事说他家的猫会说话，我不信。他当场问猫：今天几号？猫说：喵。他说：你看，它不知道。"
        )

        fun pick(): String = jokes.random()

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("笑话", "逗我", "开心一下", "讲个好玩的")) return null
            return RuleReply(pick(), intent)
        }
    }

    // ------------------------------------------------------------------ 闲聊

    /** 你是谁 */
    object Identity : IntentRule {
        override val intent = "identity"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("你是谁", "你叫什么", "介绍自己")) return null
            return RuleReply(
                "我是 VoiceAssistant，一个跑在你手机上的本地语音助手。所有规则都在设备上执行，不联网也能用。",
                intent
            )
        }
    }

    /** 会什么 / 怎么用：内容直接由技能目录生成，保证和真实能力一致 */
    object Help : IntentRule {
        override val intent = "support"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("能做什么", "会什么", "帮助", "功能", "怎么用", "会些什么")) return null

            val summary = buildString {
                append("我现在会这些：\n")
                SkillCatalog.categories.forEach { category ->
                    append("· ")
                    append(category)
                    append("：")
                    append(SkillCatalog.byCategory(category).joinToString("、") { it.title })
                    append("\n")
                }
                append("点底部的「技能」页能看到每个功能的示例说法，点一下就能直接执行。")
            }
            return RuleReply(summary, intent)
        }
    }

    /** 天气：占位，等接入数据源后替换成真实查询 */
    object Weather : IntentRule {
        override val intent = "weather"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("天气", "下雨", "气温", "温度")) return null
            return RuleReply(weatherPlaceholder("今天"), intent)
        }
    }

    /** 谢谢 */
    object Thanks : IntentRule {
        override val intent = "thanks"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("谢谢", "多谢", "感谢", "辛苦")) return null
            return RuleReply("不客气，随时叫我。", intent)
        }
    }

    /** 再见 */
    object Farewell : IntentRule {
        override val intent = "farewell"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("再见", "拜拜", "退出")) return null
            return RuleReply("好的，需要我的时候再喊我。", intent)
        }
    }

    /** 打招呼：放最后，「你好，现在几点」会先被 Time 规则接住 */
    object Greeting : IntentRule {
        override val intent = "greeting"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("你好", "您好", "嗨", "在吗", "哈喽", "hello", "hi")) return null
            return RuleReply("${greetingByHour()}，我是你的语音助手。想聊点什么？", intent)
        }

        private fun greetingByHour(): String = when (LocalTime.now().hour) {
            in 5..10 -> "早上好"
            in 11..13 -> "中午好"
            in 14..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "你好"
        }
    }
}

// ---------------------------------------------------------------------- 工具函数

/** 数值格式化：整数不带小数点，小数最多保留 4 位 */
internal fun formatNumber(value: Double): String =
    if (abs(value - value.roundToLong()) < 1e-9) {
        value.roundToLong().toString()
    } else {
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
    }

/** 星期几的中文写法 */
internal fun weekdayCn(date: LocalDate): String =
    "星期" + "一二三四五六日"[date.dayOfWeek.value - 1]

/** 完整日期句子，例如「明天是 2026 年 10 月 31 日，星期六。」 */
internal fun dateSentence(date: LocalDate, label: String): String =
    "${label}是 ${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日，${weekdayCn(date)}。"

/** 从文本里识别相对日期：返回「偏移天数 to 说法」 */
internal fun relativeDay(text: String): Pair<Long, String>? = when {
    text.contains("大后天") -> 3L to "大后天"
    text.contains("后天") -> 2L to "后天"
    text.contains("明天") -> 1L to "明天"
    text.contains("昨天") -> -1L to "昨天"
    text.contains("前天") -> -2L to "前天"
    else -> null
}

/** 周一..周日 对应的数字 */
private fun weekdayValue(char: String): Int? = when (char) {
    "一" -> 1
    "二" -> 2
    "三" -> 3
    "四" -> 4
    "五" -> 5
    "六" -> 6
    "日", "天" -> 7
    else -> null
}

/** 天气占位回复，多轮追问时也能说明是哪一天 */
private fun weatherPlaceholder(dayLabel: String): String =
    "${dayLabel}的天气我还没接入在线数据源，现在查不到实时天气，这一步在后续计划里。"

/** 百分比取整的辅助，保留给以后可能用到的统计场景 */
@Suppress("unused")
private fun Int.percentOf(total: Int): Int =
    if (total == 0) 0 else (this * 100.0 / total).roundToInt()
