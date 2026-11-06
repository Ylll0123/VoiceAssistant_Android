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
     * - 寒暄放最后：不带信息的客套话不该抢走功能性问句
     *
     * 只收**能算准**的能力（时间 / 日期 / 计算 / 换算 / 天气 / 娱乐 / 寒暄）。
     * 需要知识或推理的问题一律不在这里排队，交给云端大模型。
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
        Outing,
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
     * 多轮追问：把「那后天呢」「那东京呢」「那 20 公里呢」「换一个」这类省略句补全。
     *
     * 实现方式是**改写 + 重讲**：根据上一轮的意图和槽位，把省略句补成完整说法，
     * 再交给 [RuleReply.rerun] 重新走一遍规则。
     * 这样追问逻辑不用复制粘贴各意图的实现，新增能力时只要在这里补一条改写即可。
     */
    object FollowUp : IntentRule {
        override val intent = "follow_up"

        private val weatherWords = arrayOf("天气", "气温", "下雨", "温度")
        private val numberPattern = Regex("\\d+(\\.\\d+)?")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            val context = request.context

            // 「那后天呢」：沿用上一轮的日期 / 天气
            val relative = relativeDay(text)
            if (relative != null && !text.containsAny(*weatherWords)) {
                if (context.hasIntent("date") && text.length <= 8) {
                    return RuleReply(dateSentence(LocalDate.now().plusDays(relative.first), relative.second), "date")
                }
                if (context.hasIntent("weather")) {
                    val city = CityClimate.find(context.slot("city").orEmpty())
                    return RuleReply(
                        "${relative.second}的实时天气这次没查到，常年气候参考：${CityClimate.describe(city)}",
                        "weather"
                    )
                }
            }

            // 「那北京呢」：沿用上一轮的天气/温度，只换城市
            // 必须是省略句——「北京天气怎么样」这种完整问句由 Weather 自己处理，
            // 否则这里会把它再改写成自己，形成死循环（深度用尽后返回空回复）
            if (context.hasIntent("weather") &&
                CityClimate.find(text) != null &&
                text.length <= 6 &&
                !text.contains("怎么样") &&
                !WEATHER_WORDS.any { text.contains(it) }
            ) {
                val city = CityClimate.find(text)!!
                return RuleReply("", "weather", rerun = "${city.city}天气怎么样")
            }

            // 「那东京呢」：沿用上一次的世界时间，只换城市
            // 只认「省略句」——以「呢」结尾的短句，否则像「东京现在几点」这种
            // 完整问句会被当成追问改写，规则永远轮不到自己处理
            if (context.hasIntent("timezone") &&
                text.endsWith("呢") &&
                text.length <= 6 &&
                !text.containsAny("几点", "时间")
            ) {
                val city = Timezone.cityIn(text)
                if (city != null) {
                    return RuleReply("", "timezone", rerun = "$city 现在几点")
                }
            }

            // 「那 20 公里呢」：沿用上一次的换算方向，只换数值
            if (context.hasIntent("unit_convert") &&
                text.endsWith("呢") &&
                !text.containsAny("多少", "换算", "等于")
            ) {
                val from = context.slot("unit_from")
                val to = context.slot("unit_to")
                val number = numberPattern.find(text)?.value
                if (from != null && to != null && number != null) {
                    return RuleReply("", "unit_convert", rerun = "$number $from 是多少 $to")
                }
            }

            // 「再来一个 / 换一个」：换一条笑话或名言
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

        /** 运算符：判断句子里的单字数字是不是"挨着运算符"用的 */
        private val OPERATORS = setOf('+', '-', '*', '/', '%', '^')

        override fun reply(request: AssistantRequest): RuleReply? {
            // 语音说出来的数字是中文的（「十二乘以八」），先翻成阿拉伯数字，
            // 否则这类题会掉到兜底、被当成"本地不会"送去问云端，白花 token。
            // 先把运算符换成符号，再按"挨着运算符"这个条件翻数字——
            // 这样「帮我算一下」里的「一」不会被当成 1。
            val withOperators = request.raw
                .replace("加上", "+")
                .replace("加", "+")
                .replace("减去", "-")
                .replace("减", "-")
                .replace("乘以", "*")
                .replace("乘", "*")
                .replace("除以", "/")
                .replace("除", "/")

            val normalized = ChineseNumber.replaceIn(withOperators, single = true, near = OPERATORS)
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
                // 记住换算方向，下一轮「那 20 度呢」可以直接沿用
                val slots = mapOf("unit_from" to "摄氏度", "unit_to" to "华氏度")
                return if (text.indexOf("华氏") < text.indexOf("度")) {
                    // 华氏 -> 摄氏
                    val result = (value - 32) * 5 / 9
                    RuleReply(
                        "${formatNumber(value)} 华氏度 = ${formatNumber(result)} 摄氏度",
                        intent,
                        mapOf("unit_from" to "华氏度", "unit_to" to "摄氏度")
                    )
                } else {
                    val result = value * 9 / 5 + 32
                    RuleReply("${formatNumber(value)} 摄氏度 = ${formatNumber(result)} 华氏度", intent, slots)
                }
            }

            for (conversion in conversions) {
                val fromHit = conversion.from.any { text.contains(it) }
                val toHit = conversion.to.any { text.contains(it) }
                if (fromHit && toHit) {
                    val result = value * conversion.factor
                    return RuleReply(
                        "${formatNumber(value)} ${conversion.fromLabel} = ${formatNumber(result)} ${conversion.toLabel}",
                        intent,
                        mapOf(
                            "unit_from" to conversion.fromLabel,
                            "unit_to" to conversion.toLabel
                        )
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

        /** 文本里出现的城市名，没有则返回 null（多轮追问「那东京呢」也用它） */
        fun cityIn(text: String): String? = cities.keys.firstOrNull { text.contains(it) }

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            if (!text.containsAny("几点", "时间", "时候")) return null

            val city = cityIn(text) ?: return null
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
            // 日期关键词一律精确匹配：模糊容错会把「今天好开心」当成「今天是…」
            val asksWeekday = text.contains("星期几") || text.contains("周几")
            val asksDate = listOf("几号", "日期", "几月几号", "多少号", "今天是").any { text.contains(it) }
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
            return RuleReply(PhraseBook.pick(intent, quotes, request.context), intent)
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
            return RuleReply(PhraseBook.pick(intent, jokes, request.context), intent)
        }
    }

    // ------------------------------------------------------------------ 闲聊

    /** 你是谁 */
    object Identity : IntentRule {
        override val intent = "identity"

        private val variants = listOf(
            "我是 HardlySeek，你手机里的语音助手。日常的事我自己算，比如时间、算数、提醒；" +
                "要动脑子的问题会去问大模型。",
            "叫我 HardlySeek 就行。报时、算数、设提醒、查天气这些我在本机就办了，" +
                "别的就交给云端的大模型来想。",
            "我是 HardlySeek。能算准的自己做，算不准的去问大模型，不瞎编。"
        )

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("你是谁", "你叫什么", "介绍自己")) return null
            return RuleReply(PhraseBook.pick(intent, variants, request.context), intent)
        }
    }

    /** 会什么 / 怎么用：内容直接由技能目录生成，保证和真实能力一致 */
    object Help : IntentRule {
        override val intent = "support"

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            // 精确匹配：模糊容错会把「为什么」当成「会什么」、「那怎么办」当成「怎么用」
            val asked = listOf("能做什么", "会什么", "帮助", "功能", "怎么用", "会些什么")
                .any { text.contains(it) }
            if (!asked) return null

            val summary = buildString {
                append("我现在会这些：\n")
                SkillCatalog.categories.forEach { category ->
                    append("· ")
                    append(category)
                    append("：")
                    append(SkillCatalog.byCategory(category).joinToString("、") { it.title })
                    append("\n")
                }
                append("点底部的「功能」页能看到每个功能的示例说法，点一下就能直接执行。")
            }
            return RuleReply(summary, intent)
        }
    }

    /**
     * 天气 / 温度：本机这一条是**兜底**，用内置的城市常年气候数据回答。
     *
     * 实时天气由 [com.example.voiceassistant.core.RoutePlanner] 送去联网查；
     * 断网、查不到、或者这句只是随口一提温度时，落到这里给**这个城市这个月的
     * 常年气温区间 + 穿衣建议**，比一句「查不到」有用，并且明确标注是常年平均。
     * 「为什么会下雨」这类问原理的直接交给大模型，不在这儿答。
     */
    object Weather : IntentRule {
        override val intent = "weather"

        /** 问原理的说法，让位给知识库 */
        private val EXPLAIN_CUES = arrayOf("为什么", "为啥", "怎么形成", "如何形成", "原理", "是怎么回事", "怎么来的")

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            // 用精确包含：模糊容错会把「怎么办」判成「怎么穿」，规则会抢走不属于它的话
            if (!WEATHER_WORDS.any { text.contains(it) }) return null
            if (EXPLAIN_CUES.any { text.contains(it) }) return null

            val city = CityClimate.find(request.raw) ?: CityClimate.find(text)
            val day = relativeDay(text)?.second
            val body = CityClimate.describe(city)
            val answer = if (day != null) {
                "${day}的实时天气这次没查到，先用常年气候给你参考：$body"
            } else {
                body
            }
            return RuleReply(
                answer,
                intent,
                mapOf("city" to (city?.city ?: ""), "day" to (day ?: ""))
            )
        }
    }

    /** 出行建议：结合当前月份和城市，给能照做的提醒 */
    object Outing : IntentRule {
        override val intent = "outing"

        private val OUTING_WORDS = listOf(
            "出行", "出门", "带伞", "适合出门", "出游", "出去玩", "旅游", "通勤", "上班路上",
            "穿什么", "怎么穿", "适合穿", "出行建议", "出行注意"
        )

        override fun reply(request: AssistantRequest): RuleReply? {
            val text = request.text
            if (!OUTING_WORDS.any { text.contains(it) }) return null

            val city = CityClimate.find(request.raw) ?: CityClimate.find(text)
                ?: CityClimate.find(request.context.slot("city").orEmpty())
            val month = LocalDate.now().monthValue
            val tip = CityClimate.outingTip(month, city)
            val range = city?.let { CityClimate.rangeOf(it, month) }
            val dress = range?.let { CityClimate.dressAdvice(it.last) }
            val answer = buildString {
                if (city != null) append("${city.city}这个月常年 ${range!!.first}℃~${range.last}℃，")
                append("出行建议：")
                append(tip)
                if (dress != null) append("。穿衣上：$dress")
                append("。这是常年气候参考，实时天气这次没查到——要出门前我再帮你联网确认一次更保险。")
            }
            return RuleReply(answer, intent, mapOf("city" to (city?.city ?: "")))
        }
    }

    /** 谢谢 */
    object Thanks : IntentRule {
        override val intent = "thanks"

        private val variants = listOf(
            "不客气，随时叫我。",
            "小事，别客气。",
            "客气了，有需要再喊我。"
        )

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("谢谢", "多谢", "感谢", "辛苦")) return null
            return RuleReply(PhraseBook.pick(intent, variants, request.context), intent)
        }
    }

    /** 再见 */
    object Farewell : IntentRule {
        override val intent = "farewell"

        private val variants = listOf(
            "好的，需要我的时候再喊我。",
            "好，回头见。",
            "去吧，我在这儿等着。"
        )

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("再见", "拜拜", "退出")) return null
            return RuleReply(PhraseBook.pick(intent, variants, request.context), intent)
        }
    }

    /** 打招呼：放最后，「你好，现在几点」会先被 Time 规则接住 */
    object Greeting : IntentRule {
        override val intent = "greeting"

        override fun reply(request: AssistantRequest): RuleReply? {
            if (!request.text.containsAny("你好", "您好", "嗨", "在吗", "哈喽", "hello", "hi")) return null
            // 已经打过招呼：换个说法，别机械重复同一句开场白
            val metBefore = request.context.hasIntent(intent) || request.context.turns > 1
            val text = if (metBefore) {
                PhraseBook.pick(intent, repeatVariants, request.context)
            } else {
                // 第一次打招呼也不再固定成「我是你的语音助手」：
                // 轮换几套开场，并且顺手告诉用户我能干什么，比自我介绍有用
                PhraseBook.pick(intent, firstVariants, request.context)
                    .replace("{greeting}", greetingByHour())
            }
            return RuleReply(text, intent)
        }

        private val firstVariants = listOf(
            "{greeting}！报时、算数、设提醒、查天气我都在行，别的也可以直接问我。",
            "嗨，我在。想先来点实用的，还是随便聊聊？",
            "{greeting}。有事直接说，能算准的我当场给你，要动脑子的我去问大模型。",
            "你好呀。不知道从哪开始的话，可以问我「北京天气怎么样」「帮我算 12 乘以 8」。",
            "{greeting}，我是 HardlySeek。每天几点、几号、多少度、要提醒你什么，这些我张口就来。",
            "嗨，来啦。今天有什么想聊的，或者需要我帮你拿个主意？"
        )

        private val repeatVariants = listOf(
            "嗨，又见面了。说吧。",
            "在呢。今天怎么样？",
            "嗯，我在。想聊点什么？",
            "我在。有什么要聊的尽管说。"
        )

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

private val WEATHER_WORDS = listOf(
            "天气", "下雨", "下雪", "气温", "温度", "多少度", "几度", "冷吗", "热吗", "天气怎么样"
        )

/** 天气占位回复，多轮追问时也能说明是哪一天（联网也失败时才用） */
private fun weatherPlaceholder(dayLabel: String): String =
    "${dayLabel}的实时天气这次没联网查到，先按常年气候给你参考，过一会儿可以再问一次。"

/** 百分比取整的辅助，保留给以后可能用到的统计场景 */
@Suppress("unused")
private fun Int.percentOf(total: Int): Int =
    if (total == 0) 0 else (this * 100.0 / total).roundToInt()
