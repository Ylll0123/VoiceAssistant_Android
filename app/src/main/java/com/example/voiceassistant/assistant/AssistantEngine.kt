package com.example.voiceassistant.assistant

/**
 * 本地对话引擎（规则引擎）。
 *
 * 一次请求的处理流程：
 * 1. 归一化输入（[FuzzyMatcher.normalize]：去标点空格、剥离客套前缀）
 * 2. 按顺序询问每条规则，第一条命中的采用其回复
 * 3. 命中后把意图和槽位写回 [context]，供下一轮的多轮追问使用
 *
 * 规则列表的顺序即优先级，具体顺序见 [AssistantRules.all]。
 */
class AssistantEngine(
    private val dialogContext: DialogContext = DialogContext(),
    private val rules: List<IntentRule> = AssistantRules.all()
) {

    /** 当前会话的上下文（上一轮意图 + 槽位），设置页里会用它做调试展示 */
    fun context(): DialogContext = dialogContext

    /** 只关心回复文本时的简化入口 */
    fun reply(rawInput: String): String = respond(rawInput).text

    /** 完整的响应：回复文本 + 命中的意图 */
    fun respond(rawInput: String): AssistantResponse {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return AssistantResponse(LISTENING, INTENT_EMPTY)

        val normalized = FuzzyMatcher.normalize(raw)
        if (normalized.isEmpty()) return AssistantResponse(LISTENING, INTENT_EMPTY)

        val request = AssistantRequest(raw, normalized, dialogContext)
        for (rule in rules) {
            val reply = rule.reply(request) ?: continue
            dialogContext.remember(reply.intent, reply.slots)
            return AssistantResponse(reply.text, reply.intent)
        }
        return AssistantResponse(FALLBACK, INTENT_FALLBACK)
    }

    fun resetContext() = dialogContext.clear()

    companion object {
        const val LISTENING = "我在听，你说吧。"

        const val FALLBACK =
            "这句我还答不上来。可以试试：现在几点、今天几号、帮我算 12*8、讲个笑话，或者去「技能」页看看所有能做的事。"

        const val INTENT_EMPTY = "empty"
        const val INTENT_FALLBACK = "fallback"
    }
}

/** 一次完整响应 */
data class AssistantResponse(
    val text: String,
    val intent: String
)
