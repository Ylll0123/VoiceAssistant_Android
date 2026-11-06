package com.example.voiceassistant.fuzzy

/**
 * 编辑距离（Levenshtein），模糊容错的第一道手段。
 *
 * 为什么先上它而不是词向量：
 * 语音识别的错误大多是**单字错、少一个字、多一个字**（「提醒」听成「提星」），
 * 这类错误用编辑距离一抓一个准，而且是纯整数运算——
 * 不占内存、不用加载模型、毫秒级出结果。词向量留给「语序颠倒、同义改写」，
 * 那才是它擅长的；为了几个字的错别字去跑 embedding 属于杀鸡用牛刀。
 */
object Levenshtein {

    /** 标准 DP：两行滚动数组，O(min(n,m)) 空间 */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        // 始终让短的那串做列，滚动数组更小
        val (rows, cols) = if (a.length >= b.length) a to b else b to a
        var previous = IntArray(cols.length + 1) { it }
        var current = IntArray(cols.length + 1)

        for (i in 1..rows.length) {
            current[0] = i
            val rc = rows[i - 1]
            for (j in 1..cols.length) {
                val cost = if (rc == cols[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,          // 插入
                    previous[j] + 1,             // 删除
                    previous[j - 1] + cost       // 替换
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[cols.length]
    }

    /** 相似度：1 - 距离/最长长度，落在 0.0 ~ 1.0 */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val max = maxOf(a.length, b.length)
        if (max == 0) return 1.0
        return 1.0 - distance(a, b).toDouble() / max
    }
}
