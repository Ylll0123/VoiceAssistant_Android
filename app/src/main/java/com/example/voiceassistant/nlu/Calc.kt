package com.example.voiceassistant.nlu

/**
 * 本地四则运算求值（纯函数，不碰网络、不碰 Android）。
 *
 * ## 为什么自己写解析而不用 `eval`
 * Kotlin/JVM 没有安全的 eval，`ScriptEngine` 在 Android 上根本不存在（而且能执行任意代码）。
 * 这里只认四则运算、括号、百分号，别的字符一律拒绝——**宁可算不出来交回大模型，也不能算错**。
 *
 * 支持的输入（先经 [normalize] 把中英文写法统一）：
 * ```
 * 12*8          (3+5)*2        100/7          -6+10
 * 12乘8         3加5再乘以2      50%           2的3次方
 * ```
 */
object Calc {

    /**
     * 把日常说法换成运算符。
     *
     * 顺序有讲究：「除以」必须在「除」之前替换，否则「12除以4」会变成「12除/4」。
     */
    fun normalize(raw: String): String = raw
        .replace("除以", "/")
        .replace("乘以", "*")
        .replace("乘上", "*")
        .replace("乘", "*")
        .replace("加上", "+")
        .replace("加", "+")
        .replace("减去", "-")
        .replace("减", "-")
        .replace("的", "^")            // 「2的3次方」→ 2^3
        .replace("次方", "")
        .replace("×", "*")
        .replace("✕", "*")
        .replace("÷", "/")
        .replace("（", "(")
        .replace("）", ")")
        .replace("＝", "=")
        .replace("　", "")

    /** 去掉问句的外壳，只留算式：「帮我算12乘8等于几？」→「12*8」 */
    fun stripWrapper(raw: String): String = RAW_PREFIXES.fold(normalize(raw)) { acc, prefix ->
        acc.replace(prefix, "")
    }.replace(Regex("[=＝].*$"), "")
        .replace(Regex("[?？。！!，,]+$"), "")
        .trim()

    /**
     * 判断这句话是不是「单纯的计算」。
     *
     * 三个条件缺一不可：剥掉外壳后只剩算式字符、至少一个运算符、能算出结果。
     * 这样「12」「今天的作业」「1个苹果多少钱」都不会被误认领。
     */
    fun looksLikeMath(raw: String): Boolean {
        val body = stripWrapper(raw)
        if (body.isEmpty()) return false
        if (!body.all { it.isDigit() || it in "+-*/^().% " }) return false
        if (body.none { it in "+-*/^%" }) return false
        return evaluate(body) != null
    }

    /**
     * 求值。算不出来（语法错、除零、字符不合法）返回 null。
     *
     * 用递归下降，优先级靠调用层级体现：
     * ```
     * expr   := term (('+'|'-') term)*
     * term   := power (('*'|'/'|'%') power)*
     * power  := unary ('^' power)?        // 右结合
     * unary  := '-' unary | primary
     * primary:= number | '(' expr ')'
     * ```
     */
    fun evaluate(expression: String): Double? {
        val text = expression.replace(" ", "")
        if (text.isEmpty()) return null
        val parser = Parser(text)
        val value = runCatching { parser.parseExpression() }.getOrNull() ?: return null
        // 还有剩余字符说明有没吃掉的符号，属于语法错
        if (parser.position != text.length) return null
        if (value.isNaN() || value.isInfinite()) return null
        return value
    }

    /** 把结果写成好看的样子：整数不带小数点，小数最多留 4 位 */
    fun format(value: Double): String {
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
            return value.toLong().toString()
        }
        val rounded = String.format("%.4f", value).trimEnd('0').trimEnd('.')
        return if (rounded == "-0") "0" else rounded
    }

    private class Parser(private val text: String) {
        var position = 0

        fun parseExpression(): Double {
            var value = parseTerm()
            while (position < text.length) {
                when (text[position]) {
                    '+' -> { position++; value += parseTerm() }
                    '-' -> { position++; value -= parseTerm() }
                    else -> return value
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parsePower()
            while (position < text.length) {
                when (text[position]) {
                    '*' -> { position++; value *= parsePower() }
                    '/' -> {
                        position++
                        val divisor = parsePower()
                        if (divisor == 0.0) throw ArithmeticException("除数为零")
                        value /= divisor
                    }
                    '%' -> {
                        position++
                        val divisor = parsePower()
                        if (divisor == 0.0) throw ArithmeticException("除数为零")
                        value %= divisor
                    }
                    else -> return value
                }
            }
            return value
        }

        private fun parsePower(): Double {
            val base = parseUnary()
            if (position < text.length && text[position] == '^') {
                position++
                return Math.pow(base, parsePower())
            }
            return base
        }

        private fun parseUnary(): Double {
            if (position < text.length && text[position] == '-') {
                position++
                return -parseUnary()
            }
            if (position < text.length && text[position] == '+') {
                position++
                return parseUnary()
            }
            return parsePrimary()
        }

        private fun parsePrimary(): Double {
            if (position < text.length && text[position] == '(') {
                position++
                val value = parseExpression()
                if (position >= text.length || text[position] != ')') {
                    throw IllegalArgumentException("括号没闭合")
                }
                position++
                return value
            }
            val start = position
            while (position < text.length && (text[position].isDigit() || text[position] == '.')) {
                position++
            }
            if (start == position) throw IllegalArgumentException("这里应该是数字")
            return text.substring(start, position).toDouble()
        }
    }

    /** 计算题常见的外壳词，剥掉后才是纯算式 */
    private val RAW_PREFIXES = listOf(
        "帮我算一下", "帮我算算", "帮我算", "帮我计算", "计算一下", "计算",
        "算一下", "算算", "算一算", "请问", "等于多少", "等于几", "是多少", "多少"
    )
}
