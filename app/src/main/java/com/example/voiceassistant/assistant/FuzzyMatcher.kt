package com.example.voiceassistant.assistant

/**
 * 模糊匹配容错引擎。
 *
 * 语音识别不可能 100% 准确（「打开日历」常被听成「打开日立」），
 * 所以规则命中不能只靠精确包含，这里做了两件事：
 *
 * 1. **归一化**：去掉中文标点、空格，统一小写，并剥掉「请问 / 帮我 / 我想知道」
 *    这类没有信息量的客套前缀，让「请问现在几点了」和「几点了」落到同一条规则上。
 * 2. **容错包含**：精确匹配失败时，用滑动窗口对关键词做编辑距离比较，
 *    允许 1~2 个字的识别误差（字数越少越严格，避免误命中）。
 *
 * 全部是纯函数，方便单元测试。
 */
object FuzzyMatcher {

    /** 中文里常见、且不参与表达式计算的标点 */
    private val CHINESE_PUNCT = Regex("[，。！？、；：“”‘’（）《》〈〉…—～·]")

    private val SPACES = Regex("\\s+")

    /**
     * 客套前缀，按长度从长到短排列，避免「帮我」先被「帮」吃掉。
     * 只剥离开头，不动句子中间的内容。
     */
    private val PREFIXES = listOf(
        "请问一下", "请问", "帮我看看", "帮我查一下", "帮我查", "帮忙看看", "帮忙",
        "麻烦你", "麻烦", "我想知道", "我想问一下", "我想问", "想问一下", "想问",
        "可不可以", "能不能", "你可以帮我", "你能帮我", "帮我", "嗯", "那个"
    )

    /** 归一化：去标点空格、转小写、剥离客套前缀 */
    fun normalize(raw: String): String {
        var text = raw.trim()
        text = CHINESE_PUNCT.replace(text, "")
        text = SPACES.replace(text, "")
        text = text.lowercase()

        // 剥前缀最多两轮：「请问帮我查一下」这类叠加说法
        repeat(2) {
            val hit = PREFIXES.firstOrNull { text.startsWith(it) && text.length > it.length }
            if (hit != null) text = text.removePrefix(hit) else return@repeat
        }
        return text
    }

    /**
     * 文本里是否「大约包含」关键词。
     *
     * @param text 已归一化的输入
     * @param keyword 规则里的关键词（应为小写、无空格）
     */
    fun containsFuzzy(text: String, keyword: String): Boolean {
        if (text.isEmpty() || keyword.isEmpty()) return false
        if (text.contains(keyword)) return true

        val distance = allowedDistance(keyword, text.length)
        if (distance == 0) return false

        // 滑动窗口：长度在 keyword.length ± distance 之间，逐窗口比编辑距离
        val minLength = (keyword.length - distance).coerceAtLeast(1)
        val maxLength = keyword.length + distance
        for (length in minLength..maxLength) {
            if (length > text.length) continue
            for (start in 0..(text.length - length)) {
                val window = text.substring(start, start + length)
                if (levenshtein(window, keyword) <= distance) return true
            }
        }
        return false
    }

    fun containsAnyFuzzy(text: String, vararg keywords: String): Boolean =
        keywords.any { containsFuzzy(text, it) }

    /**
     * 允许的编辑距离：关键词越短越严格，句子越长越严格。
     *
     * 两个字以内完全不容错（「几点」和「几天」只差一个字，容错会误命中）；
     * 三个字的关键词只在短句里容错——短句基本就是一条指令（「我们玩猜数子」），
     * 长句里往往是自然语言（「明天天气怎么样」），
     * 否则「怎么样」会被判成「怎么用」、「多少英」会被判成「多少号」。
     */
    fun allowedDistance(keyword: String, textLength: Int = Int.MAX_VALUE): Int = when {
        keyword.length <= 2 -> 0
        keyword.length == 3 -> if (textLength <= SHORT_SENTENCE_LENGTH) 1 else 0
        keyword.length == 4 -> 1
        else -> 2
    }

    /** 短句阈值：不超过这个长度才允许 3 字关键词做容错 */
    private const val SHORT_SENTENCE_LENGTH = 6

    /** 经典 Levenshtein 距离，滚动数组实现 */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,        // 插入
                    previous[j] + 1,           // 删除
                    previous[j - 1] + cost     // 替换
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
