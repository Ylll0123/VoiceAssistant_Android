package com.example.voiceassistant.data

/**
 * 极简 JSON 解析 / 取值工具（约 150 行，自带递归下降解析器）。
 *
 * ## 为什么不用 org.json
 * 单元测试里的 `org.json` 是 Android 提供的桩，一调用就抛异常，
 * 联网解析的代码就完全没法测。自己写一个只有几百行的解析器之后，
 * 「接口返回的内容 → 一句话回答」这段逻辑可以在 JVM 单测里直接断言，
 * 不依赖真机也不依赖网络。
 *
 * 只支持标准 JSON：对象、数组、字符串、数字、true / false / null。
 */
object JsonLite {

    /**
     * 解析一段 JSON 文本。
     * @return Map<String, Any?> / List<Any?> / String / Double / Boolean / null
     * @throws IllegalArgumentException 格式非法
     */
    fun parse(text: String): Any? = Cursor(text).run {
        skipWs()
        val value = readValue()
        skipWs()
        value
    }

    /** 尝试解析，失败返回 null（网络返回的东西不可信，别让它把 App 搞崩） */
    fun parseOrNull(text: String?): Any? =
        if (text.isNullOrBlank()) null else runCatching { parse(text) }.getOrNull()

    /** 取对象里的字段 */
    fun get(root: Any?, key: String): Any? =
        (root as? Map<*, *>)?.get(key)

    /** 逐层取嵌套字段：getIn(root, "current", "temperature_2m") */
    fun getIn(root: Any?, vararg keys: String): Any? {
        var current: Any? = root
        for (key in keys) {
            current = get(current, key) ?: return null
        }
        return current
    }

    fun obj(value: Any?): Map<String, Any?> = (value as? Map<*, *>)?.let {
        it.entries.associate { (k, v) -> k.toString() to v }
    } ?: emptyMap()

    fun arr(value: Any?): List<Any?> = (value as? List<*>)?.toList() ?: emptyList()

    /** 取字符串：数字也按字符串返回（接口里数字和字符串经常混用） */
    fun str(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }

    /** 取数字：字符串数字也认 */
    fun num(value: Any?): Double? = when (value) {
        null -> null
        is Double -> value
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        is String -> value.trim().toDoubleOrNull()
        else -> value.toString().toDoubleOrNull()
    }

    fun int(value: Any?): Int? = num(value)?.toInt()

    // ------------------------------------------------------------------ 解析器

    private class Cursor(private val s: String) {

        var i: Int = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("输入为空 @$i")
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> { expectWord("true"); true }
                'f' -> { expectWord("false"); false }
                'n' -> { expectWord("null"); null }
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++ // 吃掉 '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("期望 ':' @$i")
                i++
                map[key] = readValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("对象没有闭合 @$i")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> throw IllegalArgumentException("期望 ',' 或 '}' @$i")
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // 吃掉 '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list += readValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("数组没有闭合 @$i")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw IllegalArgumentException("期望 ',' 或 ']' @$i")
                }
            }
        }

        private fun readString(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("期望字符串 @$i")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val ch = s[i++]
                when {
                    ch == '"' -> return sb.toString()
                    ch != '\\' -> sb.append(ch)
                    i >= s.length -> throw IllegalArgumentException("转义符后没有字符 @$i")
                    else -> appendEscaped(s[i++], sb)
                }
            }
            throw IllegalArgumentException("字符串没有闭合 @$i")
        }

        private fun appendEscaped(esc: Char, sb: StringBuilder) {
            when (esc) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (i + 4 > s.length) throw IllegalArgumentException("\\u 后不足四位 @$i")
                    val hex = s.substring(i, i + 4).toIntOrNull(16)
                    sb.append(hex?.toChar() ?: '?')
                    i += 4
                }
                else -> sb.append(esc)
            }
        }

        private fun readNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(start, i).toDoubleOrNull()
                ?: throw IllegalArgumentException("数字格式错误 @$start")
        }

        private fun expectWord(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("期望 $word @$i")
            i += word.length
        }
    }
}
