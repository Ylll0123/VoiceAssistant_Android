package com.example.voiceassistant.assistant

/**
 * 一个极简的四则运算解析器（递归下降）。
 * 支持 + - * / % ^、括号、小数与负号，例如 "3*(4+5)/2"、"2^10"。
 */
object MathEvaluator {

    fun evaluate(expression: String): Double? {
        val normalized = expression
            .replace(" ", "")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("（", "(")
            .replace("）", ")")
        if (normalized.isEmpty()) return null
        return try {
            val parser = Parser(normalized)
            val value = parser.parseExpression()
            if (parser.isFinished && value.isFinite()) value else null
        } catch (e: Exception) {
            null
        }
    }

    private class Parser(private val source: String) {
        private var pos = 0

        val isFinished: Boolean get() = pos >= source.length

        fun parseExpression(): Double {
            var value = parseTerm()
            while (!isFinished) {
                when (source[pos]) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
            return value
        }

        private fun parseTerm(): Double {
            var value = parseUnary()
            while (!isFinished) {
                when (source[pos]) {
                    '*' -> { pos++; value *= parseUnary() }
                    '/' -> { pos++; value /= parseUnary() }
                    '%' -> { pos++; value %= parseUnary() }
                    else -> return value
                }
            }
            return value
        }

        private fun parseUnary(): Double {
            if (!isFinished) {
                when (source[pos]) {
                    '-' -> { pos++; return -parseUnary() }
                    '+' -> { pos++; return parseUnary() }
                }
            }
            return parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            if (!isFinished && source[pos] == '^') {
                pos++
                return Math.pow(base, parseUnary())
            }
            return base
        }

        private fun parsePrimary(): Double {
            if (!isFinished && source[pos] == '(') {
                pos++
                val value = parseExpression()
                if (!isFinished && source[pos] == ')') pos++
                return value
            }
            val start = pos
            while (!isFinished && (source[pos].isDigit() || source[pos] == '.')) pos++
            val number = source.substring(start, pos).toDoubleOrNull()
            if (number == null) throw IllegalArgumentException("非法数字: $source")
            return number
        }
    }
}
