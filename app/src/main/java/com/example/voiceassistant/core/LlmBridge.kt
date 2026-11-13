package com.example.voiceassistant.core

import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.network.Http
import com.example.voiceassistant.network.LlmMessage

/**
 * 「一次纯文本补全」的极简接口。
 *
 * ## 为什么单独抽出来
 * `study/word_query`、`gen/short_sentence`、`tool/translate` 这些服务的本质是
 * 「拼一段提示词 → 拿一段文字 → 返回」，它们不该自己去管 URL、鉴权、JSON。
 *
 * 更要紧的是**避免循环依赖**：这些服务挂在总线上，总线被 `AiChat` 用；
 * 如果它们反过来直接依赖 `AiChat`，两边的构造顺序会拧成死结。
 * 中间隔一层 [LlmBridge]，构造时先有桥、再有总线、最后有 AiChat，顺序就干净了。
 *
 * 走的是**同一条** `llm/cloud_chat` 通道（同一个 baseUrl / apiKey / model），
 * 所以「统一架构」不只是说法：改一次配置，问答和工具走的都是一处。
 */
interface LlmBridge {

    /**
     * 补全一次。
     *
     * @param prompt 用户意图（已经被服务整理成一句明确的要求）
     * @param systemHint 附加在 system 里的角色说明，空串表示只用默认人设
     * @param maxTokens 上限，短回答给小值省时间
     * @return 失败（没配置 / 没网 / 接口报错）返回 null，**不抛异常**
     */
    fun complete(prompt: String, systemHint: String = "", maxTokens: Int = 400): String?
}

/**
 * 真实现：直接打 OpenAI 兼容的 `/chat/completions`，**不带 tools**。
 *
 * 不带工具是有意的——这些服务的职责是「把话写好」，不需要它们再出门调设备。
 */
class CloudLlmBridge(
    private val config: () -> CloudLlmHandler.CloudConfig
) : LlmBridge {

    override fun complete(prompt: String, systemHint: String, maxTokens: Int): String? {
        val cfg = config()
        if (!cfg.isReady()) return null
        val url = CloudLlmHandler.chatUrl(cfg.baseUrl)
        if (url.isEmpty()) return null

        val messages = buildList {
            val system = systemHint.ifBlank { cfg.systemPrompt }
            if (system.isNotBlank()) {
                add("""{"role":"system","content":"${CloudLlmHandler.escapeJson(system)}"}""")
            }
            add("""{"role":"user","content":"${CloudLlmHandler.escapeJson(prompt)}"}""")
        }
        val body = AiChat.buildBody(cfg.model, messages, maxTokens, tools = null)

        val (raw, code) = runCatching { Http.postJson(url, cfg.apiKey, body, cfg.timeoutMs) }
            .getOrElse { null to 0 }
        if (raw == null || code !in 200..299) return null

        return CloudLlmHandler.parseContent(raw)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** 给需要「一句话回复」的地方用的空实现：永远返回 null，调用方自己兜底 */
object NullLlmBridge : LlmBridge {
    override fun complete(prompt: String, systemHint: String, maxTokens: Int): String? = null
}

/** 历史消息的轻量别名，避免各服务重复 import */
typealias ChatTurn = LlmMessage
