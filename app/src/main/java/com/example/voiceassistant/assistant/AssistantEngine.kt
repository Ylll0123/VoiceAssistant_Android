package com.example.voiceassistant.assistant

import java.util.Locale

/**
 * 本地对话引擎（规则引擎）。
 *
 * 工作方式：输入一句话 → 按顺序询问每条规则 → 第一条命中的规则给出回复。
 * 列表顺序即优先级：越靠前越优先，所以算术在最前、打招呼在最后。
 *
 * 后续扩展点：
 * - 多轮上下文：在 [reply] 外层维护一个会话状态对象，把「上一轮意图 + 已填槽位」
 *   传给规则，规则就能处理「那后天呢」这类省略句；
 * - 模糊匹配：把 [IntentRule] 里的 contains 判断换成拼音 / 编辑距离匹配，
 *   本类和 UI 都不用改。
 */
class AssistantEngine {

    /** 规则注册表：新增能力只需在这里加一条 */
    private val rules: List<IntentRule> = listOf(
        AssistantRules.Arithmetic,
        AssistantRules.Time,
        AssistantRules.Date,
        AssistantRules.Joke,
        AssistantRules.Identity,
        AssistantRules.Help,
        AssistantRules.Weather,
        AssistantRules.Thanks,
        AssistantRules.Farewell,
        AssistantRules.Greeting,
    )

    /** 生成一条回复 */
    fun reply(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isEmpty()) return LISTENING

        val compact = input.lowercase(Locale.getDefault()).replace(" ", "")
        for (rule in rules) {
            rule.reply(input, compact)?.let { return it }
        }
        return FALLBACK
    }

    companion object {
        const val LISTENING = "我在听，你说吧。"

        const val FALLBACK =
            "这句话我还答不上来。你可以试试问我：现在几点、今天几号、帮我算 12*8，或者让我讲个笑话。"
    }
}
