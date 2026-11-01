package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.assistant.ChineseNumber
import com.example.voiceassistant.assistant.MathEvaluator
import kotlin.math.abs

/**
 * 工具层：**计算**（四则运算、单位换算里的算式部分）。
 *
 * 这一层是真正本地执行的工具：不联网、不调模型、0 token。
 *
 * ## 为什么值得单独做
 * 语音说出来的数字是中文的（「帮我算一下十二乘以八」）。
 * 如果这类题算不出来，它会掉到兜底 → 被当成「本地不会」→ 送去问云端，
 * 白花一次 token。放在工具层先算掉，云端就只处理真正需要生成的问题。
 *
 * ## 判定方式
 * 和 [TimeTool] 一样以引擎意图为准。只有引擎没给结果时
 * （比如单测里直接调用），才退回关键词 + 数字检测。
 */
object CalcTool {

    const val NAME = "calc"

    /** 引擎命中这些意图 = 这句话确实是计算题 */
    private val INTENTS = setOf("arithmetic", "calc_continue", "unit_convert")

    private val OPERATOR_CUES = listOf(
        "加", "减", "乘", "除", "等于", "算一下", "算算", "计算"
    )

    private val OPERATORS = setOf('+', '-', '*', '/', '%', '^')

    private val ARABIC_DIGIT = Regex("[0-9]")
    private val CHINESE_DIGIT = Regex("[零〇一二两三四五六七八九十百千万亿]")

    /** 是不是计算题 */
    fun matches(raw: String, local: AssistantResponse? = null): Boolean {
        if (raw.isBlank()) return false
        if (local != null) return local.intent in INTENTS
        if (!OPERATOR_CUES.any { raw.contains(it) }) return false
        return ARABIC_DIGIT.containsMatchIn(raw) || CHINESE_DIGIT.containsMatchIn(raw)
    }

    /**
     * 把一句话里的算式算出来。
     *
     * @return 「12*8 = 96」这样的展示文本；不是算式返回 null，交给后面的路线
     */
    fun run(raw: String): String? {
        if (!matches(raw)) return null
        val withOperators = raw
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
        if (!expression.any { it.isDigit() }) return null
        if (!expression.any { it in "+-*/%^" }) return null

        val value = MathEvaluator.evaluate(expression) ?: return null
        if (abs(value) > 1e15) return null
        return "$expression = ${formatNumber(value)}"
    }

    /** 整数就不带小数点，念起来更像人话 */
    private fun formatNumber(value: Double): String {
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        val text = String.format("%.6f", value)
        return text.trimEnd('0').trimEnd('.')
    }
}
