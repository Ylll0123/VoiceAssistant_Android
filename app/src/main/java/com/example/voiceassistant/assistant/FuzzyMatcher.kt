package com.example.voiceassistant.assistant

/**
 * 模糊匹配容错引擎（「听得清」的第一层）。
 *
 * 语音识别不可能 100% 准确（「打开日历」常被听成「打开日立」），
 * 所以规则命中不能只靠精确包含，这里做了三件事：
 *
 * 1. **归一化**：去掉中文标点、空格，统一小写，剥掉「请问 / 帮我 / 我想知道」
 *    这类没有信息量的客套前缀，让「请问现在几点了」和「几点了」落到同一条规则上。
 * 2. **同义词归一**：把口语说法映射到规则里的规范词（「讲个段子」→「讲个笑话」，
 *    「礼拜几」→「星期几」），音近、义近的说法都能落到同一个意图。
 * 3. **容错包含**：精确匹配失败时，用滑动窗口对关键词做编辑距离比较，
 *    允许 1~2 个字的识别误差（字数越少越严格，避免误命中）。
 *
 * 另外提供 [similarity] / [nearest] 两个打分工具，
 * 引擎在「一条规则都没命中」时用它们给出「你是不是想问…」的建议。
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

    /**
     * 同义词 / 常见口语说法 → 规则里的规范词。
     *
     * 左边是用户可能说出来的词（含语音识别的常见近音写法），
     * 右边是规则实际匹配的词。改这张表不会影响规则代码，
     * 所以扩词是低成本操作——加一条就多一种说法能被听懂。
     */
    private val SYNONYMS: List<Pair<String, String>> = listOf(
        // 笑话 / 名言
        "段子" to "笑话",
        "乐子" to "笑话",
        "逗我笑" to "笑话",
        "古诗" to "名言",
        "诗句" to "名言",
        "诗词" to "名言",
        "名言名句" to "名言",
        // 时间 / 日期
        "钟点" to "时间",
        "几点钟" to "几点",
        "礼拜" to "星期",
        "周天" to "周日",
        "今天日期" to "今天几号",
        "今儿个" to "今天",
        "明儿" to "明天",
        "后儿" to "后天",
        // 计算
        "记算" to "计算",
        "算数" to "算术",
        "除于" to "除以",
        // 小工具
        "色子" to "骰子",
        "筛子" to "骰子",
        "钢镚" to "硬币",
        "钢蹦" to "硬币",
        "抛个硬必" to "抛个硬币",
        // 闲聊
        "聊聊天" to "你好",
        "陪我聊" to "你好",
        "在不在" to "在吗",
        "谢谢你" to "谢谢",
        "拜拜了" to "拜拜"
    )

    /** 归一化：去标点空格、转小写、同义词替换、剥离客套前缀 */
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

        // 同义词替换放在剥前缀之后：「帮我讲个段子」先变成「讲个段子」再归一成「讲个笑话」
        SYNONYMS.forEach { (from, to) ->
            if (from != to) text = text.replace(from, to)
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
     *
     * 五个字以上也只容错 1 个字：长关键词本来就够独特，
     * 放开到 2 个字会让「有什么关系」被判成「有什么办法」。
     */
    fun allowedDistance(keyword: String, textLength: Int = Int.MAX_VALUE): Int = when {
        keyword.length <= 2 -> 0
        keyword.length == 3 -> if (textLength <= SHORT_SENTENCE_LENGTH) 1 else 0
        else -> 1
    }

    /** 短句阈值：不超过这个长度才允许 3 字关键词做容错 */
    private const val SHORT_SENTENCE_LENGTH = 6

    /**
     * 相似度（0~1）：1 表示完全一致，0 表示毫无关系。
     * 用「1 - 编辑距离 / 较长长度」算，长句不会被短句的差异过度惩罚。
     */
    fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return 1.0
        return (1.0 - levenshtein(a, b).toDouble() / maxLength).coerceIn(0.0, 1.0)
    }

    /**
     * 在一组候选说法里找最接近 [input] 的一条。
     *
     * 引擎用它做两件事：多候选挑选时给识别结果排序、
     * 一条规则都没命中时给出「你是不是想问…」的建议。
     *
     * @param threshold 低于这个相似度就不给建议，免得瞎猜
     */
    fun nearest(input: String, candidates: List<String>, threshold: Double = 0.6): String? {
        val normalizedInput = normalize(input)
        return candidates
            .map { it to similarity(normalizedInput, normalize(it)) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
            ?.first
    }

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
