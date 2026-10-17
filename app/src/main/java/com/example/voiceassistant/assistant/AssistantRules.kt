package com.example.voiceassistant.assistant

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 内置意图规则集合。
 *
 * 每条规则只关心「我能不能答」，不关心别的规则存在，方便单独测试和后续增删。
 */
object AssistantRules {

    /** 算术：优先于其他规则，避免「1 加 1 等于几」被当成闲聊 */
    object Arithmetic : IntentRule {
        override fun reply(input: String, compact: String): String? {
            val normalized = input
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

            return "$expression = ${formatNumber(value)}"
        }

        private fun formatNumber(value: Double): String =
            if (abs(value - value.roundToLong()) < 1e-9) {
                value.roundToLong().toString()
            } else {
                String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
            }
    }

    /** 现在几点 */
    object Time : IntentRule {
        private val formatter = DateTimeFormatter.ofPattern("HH:mm")

        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("几点", "时间", "时候了")) return null
            return "现在是 ${LocalTime.now().format(formatter)}。"
        }
    }

    /** 今天几号 / 星期几 */
    object Date : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("几号", "日期", "星期几", "周几")) return null
            val today = LocalDate.now()
            val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
            return "今天是 ${today.year} 年 ${today.monthValue} 月 ${today.dayOfMonth} 日，$weekday。"
        }
    }

    /** 讲个笑话 */
    object Joke : IntentRule {
        private val jokes = listOf(
            "有一天，程序员去面试。面试官问：你最大的缺点是什么？程序员说：我没有缺点，只有 bug。",
            "为什么程序员分不清万圣节和圣诞节？因为 Oct 31 等于 Dec 25。",
            "小明问爸爸：为什么天空是蓝色的？爸爸说：因为海是蓝色的。小明又问：那海为什么是蓝色的？爸爸说：因为我也不知道。",
            "有个人掉进了河里，大喊救命。路人问：你会游泳吗？他说：我会一点点。路人说：那你先游一点点吧。"
        )

        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("笑话", "逗我", "开心一下")) return null
            return jokes.random()
        }
    }

    /** 你是谁 */
    object Identity : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("你是谁", "你叫什么", "介绍自己")) return null
            return "我是 VoiceAssistant，一个跑在你手机上的语音助手。你可以用说的，也可以打字。"
        }
    }

    /** 会什么 / 怎么用 */
    object Help : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("能做什么", "会什么", "帮助", "功能", "怎么用")) return null
            return buildString {
                append("我现在能做这些事：\n")
                append("· 报时间、日期\n")
                append("· 算数，比如「帮我算 25 乘以 4」\n")
                append("· 讲个笑话\n")
                append("· 语音输入和语音播报\n")
                append("在线天气、大模型闲聊还没接，属于下一步。")
            }
        }
    }

    /** 天气：占位，等接入数据源后替换成真实查询 */
    object Weather : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("天气", "下雨", "气温", "温度")) return null
            return "我还没接入在线天气服务，现在查不到实时天气。这一步可以作为下一个功能来做。"
        }
    }

    /** 谢谢 */
    object Thanks : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("谢谢", "多谢", "感谢", "辛苦")) return null
            return "不客气，随时叫我。"
        }
    }

    /** 再见 */
    object Farewell : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("再见", "拜拜", "退出")) return null
            return "好的，需要我的时候再喊我。"
        }
    }

    /** 打招呼：放最后，「你好，现在几点」会先被 Time 规则接住 */
    object Greeting : IntentRule {
        override fun reply(input: String, compact: String): String? {
            if (!compact.containsAny("你好", "您好", "嗨", "在吗", "哈喽", "hello", "hi")) return null
            return "${greetingByHour()}，我是你的语音助手。想聊点什么？"
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
