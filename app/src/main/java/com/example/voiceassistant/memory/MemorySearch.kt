package com.example.voiceassistant.memory

import com.example.voiceassistant.assistant.FuzzyMatcher

/**
 * 记忆检索（纯函数，可单测）。
 *
 * 判断「用户这句话是不是在问我记住过的某个问题」，用三档证据：
 *
 * 1. **完全相同** → 1.0
 * 2. **包含关系**（「我下班了」包含「下班了」）→ 按长度比例给 0.78~0.95，
 *    短问题被长句包含同样可信；但「1 个字被 20 个字包含」这种要衰减，否则会乱答。
 * 3. **字面相似度** → 编辑距离相似度 0.65 + 字符二元组重叠 0.35。
 *    二元组负责「现在几点了」vs「几点了现在」这种语序变化，编辑距离负责错别字。
 *
 * 最短的问题（1~3 个字）整体打个折，避免「我饿了」和「我累了」互相命中。
 */
object MemorySearch {

    /** 低于这个分数就当作没记住过，宁可说不会，也不要答错 */
    const val MIN_SCORE = 0.72

    fun best(query: String, entries: List<MemoryEntry>): MemoryHit? {
        val normalizedQuery = FuzzyMatcher.normalize(query)
        if (normalizedQuery.isEmpty()) return null

        var bestEntry: MemoryEntry? = null
        var bestScore = 0.0
        for (entry in entries) {
            val score = score(normalizedQuery, entry.normalized)
            if (score > bestScore) {
                bestScore = score
                bestEntry = entry
            }
        }
        val entry = bestEntry ?: return null
        return if (bestScore >= MIN_SCORE) MemoryHit(entry, bestScore) else null
    }

    /** 两句话的相似度，0~1 */
    fun score(query: String, question: String): Double {
        if (query == question) return 1.0
        val minLength = minOf(query.length, question.length)
        val maxLength = maxOf(query.length, question.length)
        if (minLength == 0) return 0.0

        if (query.contains(question) || question.contains(query)) {
            val ratio = minLength.toDouble() / maxLength
            if (ratio >= 0.75) return 0.95
            if (minLength >= 2 && ratio >= 0.5) return 0.78 + 0.17 * ratio
        }

        val charSimilarity = FuzzyMatcher.similarity(query, question)
        val bigramSimilarity = bigramJaccard(query, question)
        var score = 0.65 * charSimilarity + 0.35 * bigramSimilarity

        // 短问题容错收紧：「我饿了」不能被「我累了」答掉
        if (minLength <= 3) score *= 0.9

        // 关键词覆盖率：用户换个说法、加几个字（「老板批评我了怎么办」
        // 对教过的「被老板批评了」）时，编辑距离会掉得很低，但核心词其实都在。
        // 这里看「记住的那句话有几个字出现在用户这句里」，只对 4 字以上的记忆生效，
        // 否则「我饿了」「我累了」这种三字句会互相误命中。
        val cover = coverage(question, query)
        if (question.length >= 4 && cover >= 0.8) {
            val boosted = 0.75 + 0.2 * ((cover - 0.8) / 0.2).coerceIn(0.0, 1.0)
            if (boosted > score) score = boosted
        } else if (question.length >= 3 && cover >= 1.0) {
            if (0.88 > score) score = 0.88
        }

        return score.coerceIn(0.0, 0.99)
    }

    /** 记忆里的问法有多少字出现在用户这句话里（0~1） */
    fun coverage(question: String, query: String): Double {
        if (question.isEmpty()) return 0.0
        val queryChars = query.toSet()
        val hit = question.count { it in queryChars }
        return hit.toDouble() / question.length
    }

    /** 字符二元组 Jaccard 相似度，对语序变化不敏感 */
    fun bigramJaccard(a: String, b: String): Double {
        val setA = bigrams(a)
        val setB = bigrams(b)
        if (setA.isEmpty() || setB.isEmpty()) return if (a == b) 1.0 else 0.0
        val intersection = setA.intersect(setB).size
        val union = setA.union(setB).size
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return if (text.isEmpty()) emptySet() else setOf(text)
        return (0 until text.length - 1).mapTo(mutableSetOf()) { text.substring(it, it + 2) }
    }
}
