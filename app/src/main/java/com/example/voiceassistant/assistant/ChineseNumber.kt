package com.example.voiceassistant.assistant

/**
 * 中文数字 → 阿拉伯数字（「十二乘以八」→「12*8」）。
 *
 * ## 为什么需要
 * 语音识别出来的算术题几乎都是中文数字（说「12 乘 8」的人会直接打键盘），
 * 而算术规则只认阿拉伯数字，于是「帮我算一下十二乘以八」会一路掉到兜底，
 * 最后被当成"本地答不上来"送去问云端——**明明本地就会算，却花了 token**。
 *
 * 这里只做一件事：把句子里的中文数字串换掉，剩下的交给 [MathEvaluator]。
 *
 * ## 范围
 * 支持到「万」和「亿」（「三万五千」「一亿二千万」），覆盖语音助手能遇到的量级；
 * 「零点五」这类小数不处理——语音里基本不会出现，硬做反而容易误伤。
 */
object ChineseNumber {

    private val DIGITS = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2,
        '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    private val UNITS = mapOf('十' to 10L, '百' to 100L, '千' to 1000L)
    private val SECTIONS = mapOf('万' to 10_000L, '亿' to 100_000_000L)

    /** 中文数字里可能出现的字符（用来在句子里圈出待转换的片段） */
    private val CHINESE_NUMERIC = DIGITS.keys + UNITS.keys + SECTIONS.keys

    private val PATTERN = Regex("[${Regex.escape(CHINESE_NUMERIC.joinToString(""))}]+")

    /**
     * 解析一个纯中文数字串；含非法字符返回 null。
     *
     * 算法是标准的「分节累加」：万 / 亿 前面是一节，节内是 千百十 的累加。
     */
    fun parse(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        var total = 0L
        var section = 0L
        var current = 0L
        var sawDigit = false
        var sawAny = false

        for (ch in text) {
            val digit = DIGITS[ch]
            if (digit != null) {
                current = digit.toLong()
                sawDigit = true
                sawAny = true
                continue
            }
            val unit = UNITS[ch]
            if (unit != null) {
                // 「十五」= 15，「十」= 10：单位前没数字就按 1 算
                val value = if (sawDigit) current else 1L
                section += value * unit
                current = 0
                sawDigit = false
                sawAny = true
                continue
            }
            val big = SECTIONS[ch]
            if (big != null) {
                section += if (sawDigit) current else 0L
                total += section.coerceAtLeast(1L) * big
                section = 0
                current = 0
                sawDigit = false
                sawAny = true
                continue
            }
            return null
        }
        if (!sawAny) return null
        return total + section + current
    }

    /**
     * 把句子里的中文数字换成阿拉伯数字，其它部分原样保留。
     *
     * [single] 决定单个数字字（「八」）要不要替换：
     * - 默认 false：单个「一」「十」在普通句子里（「一起」「十分」）不是数字，换了会破坏原句；
     * - 算术场景传 true 并给出 [near]（运算符字符）：
     *   只有**挨着运算符**的单字才换，所以「十二 * 八」里的「八」变成 8，
     *   而「帮我算一下」里的「一」安然无恙。
     */
    fun replaceIn(text: String, single: Boolean = false, near: Set<Char> = emptySet()): String {
        if (text.isEmpty()) return text
        return PATTERN.replace(text) { match ->
            val raw = match.value
            val ok = raw.length >= 2 ||
                (single && (near.isEmpty() || touchesOperator(text, match.range, near)))
            val value = if (ok) parse(raw) else null
            if (value != null) value.toString() else raw
        }
    }

    /** 这个片段是不是紧贴着运算符（跳过中间的空格） */
    private fun touchesOperator(text: String, range: IntRange, near: Set<Char>): Boolean {
        val before = neighbour(text, range.first - 1, -1)
        val after = neighbour(text, range.last + 1, +1)
        return (before != null && before in near) || (after != null && after in near)
    }

    private fun neighbour(text: String, from: Int, step: Int): Char? {
        var i = from
        while (i in text.indices) {
            val ch = text[i]
            if (!ch.isWhitespace()) return ch
            i += step
        }
        return null
    }
}
