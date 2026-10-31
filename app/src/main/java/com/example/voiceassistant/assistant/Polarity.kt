package com.example.voiceassistant.assistant

/**
 * 语气极性：**判断一个词在句子里到底是肯定还是否定**。
 *
 * ## 为什么需要它
 * 关键词命中是本地引擎最主要的识别手段，但它只看「有没有这个词」，
 * 不看「这个词是被肯定还是否定」。于是：
 *
 * | 用户说的 | 命中的关键词 | 以前的理解 |
 * |---|---|---|
 * | 我不高兴 | 高兴 | 用户在表达开心 → 「那挺好，值得高兴一下。」 |
 * | 我不开心 | 开心 | 同上 |
 * | 这办法不太好 | 好 | 在夸我 |
 * | 我不想睡 | 睡 | 要睡觉 |
 *
 * 最后那一列就是「答非所问」的来源之一。加了这一层之后，
 * 「不 / 没 / 不太 / 一点也不」这类否定词**紧跟在关键词前面**时，
 * 关键词按「被否定」处理：不再触发它对应的正面回答。
 *
 * ## 判定规则（保守但够用）
 * 1. 否定词必须出现在关键词**前面**，且中间最多隔一个字（通常是「很 / 太」这类程度副词），
 *    否则「不开心的日子他想起来还是会笑」这种长句会被误判；
 * 2. 关键词后面紧跟「不起来 / 不了 / 不得」也算否定（「高兴不起来」）；
 * 3. 双重否定当作肯定处理（「不是不开心」→ 没那么正面，但不当单纯的否定处理，
 *    这里保守地返回「未否定」，交给后面的模块自己判断）。
 *
 * 纯逻辑，不依赖 Android，可在单元测试里直接断言。
 */
object Polarity {

    /** 常见的否定词（放在关键词之前） */
    private val NEGATORS = listOf(
        "一点也不", "一点都不", "不是很", "不太算", "谈不上", "算不上", "说不上",
        "不太", "并不", "没有", "没能", "没法", "无法", "毫无", "别提",
        "不", "没", "别"
    )

    /** 关键词之后表示否定的尾巴：「高兴不起来」「笑不出来」 */
    private val NEGATIVE_TAILS = listOf("不起来", "不出来", "不了", "不得", "不来")

    /**
     * 关键词在 [index] 处命中时，它是不是被否定了。
     *
     * @param text 已经归一化过的句子
     * @param index 关键词在句中的起始下标
     * @param keywordLength 关键词长度（用于取它后面的尾巴）
     */
    fun isNegatedAt(text: String, index: Int, keywordLength: Int): Boolean {
        if (index < 0 || index > text.length) return false
        if (prefixNegated(text, index)) return true
        val tailStart = index + keywordLength
        if (tailStart in 0..text.length) {
            val tail = text.substring(tailStart)
            if (NEGATIVE_TAILS.any { tail.startsWith(it) }) return true
        }
        return false
    }

    /** 关键词前面是不是紧跟否定词（中间最多隔一个字） */
    private fun prefixNegated(text: String, index: Int): Boolean {
        val prefix = text.substring(0, index)
        if (prefix.isEmpty()) return false
        // 长否定词优先：「一点也不高兴」里的「一点也不」，不能被单个「不」抢掉后误判
        for (negator in NEGATORS) {
            val pos = prefix.lastIndexOf(negator)
            if (pos < 0) continue
            val gap = prefix.length - (pos + negator.length)
            if (gap <= 1) return true
        }
        return false
    }

    /**
     * 在一堆词里找第一个「**没被否定**地命中」的词。
     *
     * 「我今天不太高兴」+ [listOf("高兴")] → null（高兴被否定了）
     * 「我今天很高兴」+ [listOf("高兴")] → "高兴"
     *
     * @return 命中的那个词；全部被否定或都没命中返回 null
     */
    fun positiveHit(text: String, words: Collection<String>): String? {
        for (word in words) {
            if (word.isEmpty()) continue
            val index = text.indexOf(word)
            if (index >= 0 && !isNegatedAt(text, index, word.length)) return word
        }
        return null
    }

    /**
     * 在一堆词里找第一个「**被否定**地命中」的词（含空命中时的回落）。
     *
     * 「我不高兴」+ [listOf("高兴", "开心")] → "高兴"
     *
     * @return 被否定的那个词；没有返回 null
     */
    fun negativeHit(text: String, words: Collection<String>): String? {
        for (word in words) {
            if (word.isEmpty()) continue
            val index = text.indexOf(word)
            if (index >= 0 && isNegatedAt(text, index, word.length)) return word
        }
        return null
    }

    /** 句子里有没有「否定形式的正面词」：判断完结果为真是**负面情绪**的重要线索 */
    fun hasNegatedPositive(text: String, positives: Collection<String>): Boolean =
        negativeHit(text, positives) != null

    // ------------------------------------------------------------------ 常用词表

    /** 正面情绪词：被否定时要按「心情不好」处理 */
    val POSITIVE_MOODS = listOf(
        "开心", "高兴", "快乐", "兴奋", "激动", "爽", "舒坦", "满足", "愉快"
    )

    /** 负面情绪词：命中就是负面，不需要再看否定 */
    val NEGATIVE_MOODS = listOf(
        "难过", "难受", "低落", "委屈", "想哭", "崩溃", "失落", "心塞",
        "焦虑", "烦躁", "好烦", "很烦", "生气", "火大", "累了", "好累",
        "失眠", "睡不着", "孤独", "孤单", "迷茫", "emo", "抑郁", "胸闷",
        "不开心", "不高兴", "不太高兴", "不太开心", "高兴不起来", "开心不起来",
        "不爽", "提不起劲", "没劲"
    )

    /**
     * 判断这句话的情绪倾向。
     *
     * @return 1 正面 / -1 负面 / 0 说不清（没出现情绪词）
     */
    fun moodOf(text: String): Int {
        if (NEGATIVE_MOODS.any { text.contains(it) }) return -1
        if (positiveHit(text, POSITIVE_MOODS) != null) return 1
        if (negativeHit(text, POSITIVE_MOODS) != null) return -1
        return 0
    }
}
