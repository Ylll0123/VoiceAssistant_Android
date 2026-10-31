package com.example.voiceassistant.assistant

/**
 * 功能性回复的变体轮换器。
 *
 * 「谢谢」「再见」「你是谁」这类回复以前只有一句固定话术，现在每个意图
 * 准备几种说法，由 [PhraseBook.pick] 按轮数轮换，并避开最近刚说过的。
 * 轮换基于 [DialogContext.turns]，是确定性的：好测试、可复现，又足够常换。
 */
object PhraseBook {

    /**
     * 从 [options] 里挑一条：
     * 1. 过滤掉最近刚说过的（同句或包含关系都算）；
     * 2. 剩下的按「会话轮数 + 意图种子」轮换取用。
     */
    fun pick(intent: String, options: List<String>, context: DialogContext): String {
        if (options.isEmpty()) return ""
        if (options.size == 1) return options.first()

        val fresh = options.filter { !context.wasSaidRecently(it) }
        val pool = fresh.ifEmpty { options }
        val seed = intent.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return pool[(context.turns + seed) % pool.size]
    }
}
