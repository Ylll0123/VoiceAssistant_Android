package com.example.voiceassistant.assistant

/**
 * 一条对话规则（意图）。
 *
 * 约定：
 * - [reply] 返回非 null 表示这条规则命中，引擎直接采用这个回复；
 * - 返回 null 表示不归我管，交给下一条规则。
 *
 * 目前是关键词精确匹配。后续要做的「模糊匹配容错引擎」只需要在
 * 这里换一种命中判断方式（拼音 / 编辑距离），上层引擎和 UI 都不用改。
 */
fun interface IntentRule {

    /**
     * @param input 用户原始输入（保留标点和大小写，拼回复时用）
     * @param compact 去掉空格并转小写的输入（匹配用）
     */
    fun reply(input: String, compact: String): String?
}

/** 判断 [source] 中是否包含任意一个关键词 */
internal fun String.containsAny(vararg keywords: String): Boolean =
    keywords.any { this.contains(it) }
