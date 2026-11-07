package com.example.voiceassistant.service

/**
 * 从识别引擎给的一堆结果里挑**最完整的一句**。
 *
 * ## 为什么需要这一步
 * 系统识别返回的是「按置信度排序的候选」，不是「按完整度排序」。
 * 实际用起来经常出现：候选第一条被截断成半句（「帮我发微信」），
 * 后面某一条反而是整句（「帮我发微信给妈妈说晚点到」）。
 * 以前一律取 `candidates.first()`，用户看到的就是「只识别到几个字」。
 *
 * 这里不猜语义，只做一件保守的事：**如果某条明显更长、而且是以首选为开头的延续，
 * 就认为首选被截断了，改用它**。不是无脑选最长——那会把两条不同的句子拼错。
 *
 * 全静态纯函数，可直接单测（见 `AsrPickTest`）。
 */
object AsrPick {

    /** 长出来的部分少于这个字数，就不值得改判：多半只是标点或语气词的差别 */
    private const val EXTRA_CHARS = 3

    /**
     * @param candidates 引擎返回的候选，按置信度从高到低
     * @param partial 最后一条中间结果（引擎没给最终结果时兜底）
     * @return 选中的整句；都没有就返回空串
     */
    fun choose(candidates: List<String?>?, partial: String? = null): String {
        val list = candidates.orEmpty()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val head = list.firstOrNull()
        if (head == null) {
            // 引擎偶尔只给中间结果就报超时，这时候半句也比「没听清」有用
            return partial?.trim().orEmpty()
        }

        // 首选被截断：后面有一条以它为前缀、并且明显更长
        list.asSequence()
            .filter { it != head && it.startsWith(head) && it.length >= head.length + EXTRA_CHARS }
            .maxByOrNull { it.length }
            ?.let { return it }

        // 最终结果反而比中间结果短：同样说明被截断，用中间结果里更长的那句
        val p = partial?.trim().orEmpty()
        if (p.startsWith(head) && p.length >= head.length + EXTRA_CHARS) return p

        return head
    }
}
