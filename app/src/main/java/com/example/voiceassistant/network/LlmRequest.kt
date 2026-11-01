package com.example.voiceassistant.network

/**
 * 网络层：**只描述线上协议长什么样**。
 *
 * 这一层不拼装内容、不做任何业务判断——那些属于 handler 层。
 * 于是「换一家大模型」= 只改 [com.example.voiceassistant.handler.llm.CloudLlmHandler]，
 * 「换一个搜索源」= 只改 [SearchApi]，UI 和调度核心一行都不用动。
 *
 * 协议选用 OpenAI 兼容的 `/chat/completions`：
 * 豆包（火山方舟）、DeepSeek、通义、Kimi、智谱以及绝大多数自建网关都吃这一套。
 */

/** 一条对话消息 */
data class LlmMessage(
    val role: String,
    val content: String
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        fun system(content: String) = LlmMessage(ROLE_SYSTEM, content)
        fun user(content: String) = LlmMessage(ROLE_USER, content)
        fun assistant(content: String) = LlmMessage(ROLE_ASSISTANT, content)
    }
}

/** 一次对话请求 */
data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    /** 回答上限。语音助手念太长没人听，默认压到很短 */
    val maxTokens: Int,
    /**
     * 采样温度。
     *
     * 固定 1.0（OpenAI 协议默认值）：部分服务商（火山方舟的部分模型）
     * 只接受这个值，传 0.7 会直接返回 400 `temperature only 1 is allowed`。
     * 想让回答更稳，靠 system 提示词而不是改温度。
     */
    val temperature: Double,
    /** 关闭流式：语音助手要整句拿去朗读，流式只会让逻辑变复杂 */
    val stream: Boolean = false
)

/** 一次调用的结果：拿到回答 / 失败并带上人话原因 */
sealed class LlmResult {
    data class Ok(val text: String, val model: String) : LlmResult()
    data class Fail(val reason: String, val retryable: Boolean = false) : LlmResult()
}
