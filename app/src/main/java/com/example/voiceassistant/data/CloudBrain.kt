package com.example.voiceassistant.data

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 云端大模型接口（OpenAI 兼容协议 /chat/completions）。
 *
 * ## 它解决什么
 * 本地引擎再怎么训练，知识量和推理能力也有限——用户会问「帮我把这段话说得委婉点」
 * 「量子纠缠到底是什么」这类没有标准答案、需要真正生成能力的问题。
 * 接入大模型是为了让助手在这种时候**真的像 AI**，而不是给一套固定的思维模板。
 *
 * ## 三条硬约束
 * 1. **可选：** 不填 Key 就完全不会联网，App 仍然是纯离线助手；
 * 2. **零新增依赖：** 用原生 [HttpURLConnection] + 自带的 [JsonLite] 解析，
 *    不引 Retrofit / OkHttp / 各家 SDK，APK 体积和依赖复杂度都不变；
 * 3. **失败必须可降级：** 超时、没网、Key 错误、接口改版……

 *    全部返回 [CloudResult.Fail]，调用方拿本地答案兜底，绝不把界面卡死。
 *
 * ## 为什么用「兼容协议」而不是各家原生 SDK
 * DeepSeek、通义千问（兼容模式）、智谱、Moonshot、硅基流动……
 * 都提供了 OpenAI 风格的 `/chat/completions` 接口，请求体完全一样。
 * 换服务商只需要改 Base URL、Key、模型名三项，界面上一个下拉框就能切换。
 *
 * ## 线程约定
 * [call] 是**阻塞**的，只能在子线程调用；
 * [buildBody] / [parseContent] / [parseError] / [chatUrl] / [shouldAsk] 都是纯函数，单测可直接断言。
 */
object CloudBrain {

    /** 三种工作方式 */
    enum class CloudMode(val code: Int) {
        /** 不用云端：完全离线，保持原来的行为 */
        OFF(0),

        /** 本地优先：本地答得好就用本地，只有「明显没答好」才问一下云端 */
        LOCAL_FIRST(1),

        /** AI 优先：只要不是本机功能（算数 / 教学 / 槽位追问），都交给大模型 */
        CLOUD_FIRST(2);

        companion object {
            fun of(code: Int): CloudMode = values().firstOrNull { it.code == code } ?: OFF
        }
    }

    /** 一份完整的云端配置 */
    data class CloudConfig(
        val enabled: Boolean = false,
        val mode: CloudMode = CloudMode.OFF,
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        val maxTokens: Int = DEFAULT_MAX_TOKENS
    ) {
        /** 配置是否可用：开关打开且三项必填都有值 */
        fun isReady(): Boolean =
            enabled && mode != CloudMode.OFF &&
                baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

        /** 展示用的脱敏文本：Key 只留前六位和后四位 */
        fun maskedKey(): String = maskKey(apiKey)
    }

    /** 一条对话消息 */
    data class ChatTurn(val role: String, val content: String) {
        companion object {
            fun system(content: String) = ChatTurn("system", content)
            fun user(content: String) = ChatTurn("user", content)
            fun assistant(content: String) = ChatTurn("assistant", content)
        }
    }

    /** 一次调用的结果：成功拿到回答 / 失败并带上人话原因 */
    sealed class CloudResult {
        data class Ok(val text: String, val model: String) : CloudResult()

        /**
         * @param retryable 失败来源：`true` 表示服务端有响应（网络是通的，是参数/内容问题）；
         *   `false` 表示网络层就断了。当前不做自动重试，仅用于把原因说清楚。
         */
        data class Fail(val reason: String, val retryable: Boolean = false) : CloudResult()
    }

    /** 服务商预设：换服务商 = 换 Base URL + 模型名，其它都一样 */
    data class Provider(
        val label: String,
        val baseUrl: String,
        val model: String,
        val hint: String
    )

    val PROVIDERS: List<Provider> = listOf(
        Provider("自定义（自己填）", "", "", "填任意 OpenAI 兼容接口"),
        Provider("火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seed-2-1-pro-260915", "console.volcengine.com/ark 申请；模型名可填模型 ID 或接入点 ep-xxx"),
        Provider("DeepSeek", "https://api.deepseek.com", "deepseek-flash",
            "platform.deepseek.com 申请 Key；旧名 deepseek-chat 已于 2026-07 停用"),
        Provider("通义千问（兼容模式）", "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-plus", "阿里云百炼控制台申请"),
        Provider("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
            "open.bigmodel.cn 申请 Key；免费档模型名以控制台为准"),
        Provider("Kimi（Moonshot）", "https://api.moonshot.cn/v1", "moonshot-v1-8k",
            "platform.moonshot.cn 申请"),
        Provider("硅基流动", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct",
            "siliconflow.cn，有免费额度；模型名从模型广场复制"),
        Provider("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", "platform.openai.com（需自备网络）")
    )

    /**
     * 默认人设——**极简版**。
     *
     * system 的内容每一轮都会随请求发出去，是纯成本：写得越详细，
     * 每轮多花的 token 越多，而语音助手根本用不上长指令。
     * 这里只留最短的两条约束：简短 + 口语。
     *
     * 唯一额外保留的是「别用 Markdown」：回答要交给 TTS 念出来，
     * `**加粗**`、`- 列表` 会被原样念出声，属于功能性必需，不是修饰。
     */
    const val DEFAULT_SYSTEM_PROMPT =
        "你是语音助手，简短回答用户问题，口语化，不主动延伸、不追问。不要使用 Markdown 或列表符号。"

    /**
     * 2.8.1 及更早版本的默认人设原文。
     *
     * 人设一旦保存过就写进了本地配置，光改 [DEFAULT_SYSTEM_PROMPT] 对老用户不生效——
     * 这里留一份旧原文，用来在读取配置时识别并自动迁移到极简版。
     */
    const val LEGACY_SYSTEM_PROMPT =
        "你是手机里的中文语音助手，用简洁、自然、有逻辑的口语回答。" +
            "每次回答控制在四句话以内，像真人聊天而不是写文章。" +
            "不要使用 Markdown、列表符号、emoji 或任何特殊格式。" +
            "和上文保持连贯：用户提到的事物或省略指代要按前文理解。" +
            "不确定的事情要说明不确定，不要编造事实。"

    /**
     * 旧人设自动迁移成极简版。
     *
     * 只有「正好等于旧默认原文」才替换——用户自己写的人设原样保留，不覆盖。
     */
    fun migratePrompt(saved: String): String =
        if (saved.trim() == LEGACY_SYSTEM_PROMPT) DEFAULT_SYSTEM_PROMPT else saved

    private const val UA = "VoiceAssistant/2.8 (Android; OpenAI-compatible)"
    private const val DEFAULT_TIMEOUT_MS = 20000

    /**
     * 输出上限 128。
     *
     * 语音助手的回答是要念出来的，128 token（约 80 字以内）足够说完两三句，
     * 而输出 token 通常比输入贵。设上限既能控制成本，也顺带避免长篇大论。
     */
    private const val DEFAULT_MAX_TOKENS = 128
    /**
     * 默认温度为 1——这是 OpenAI `/chat/completions` 自身的默认值，
     * 也是兼容性最好的选择：方舟上的思考型模型（kimi-k3 / doubao-seed）只允许这个值，
     * 用 0.7 会被 400 拒绝。语音助手场景里 1.0 与 0.7 的回答质量差别很小。
     */
    private const val DEFAULT_TEMPERATURE = 1.0

    // ------------------------------------------------------------------ 请求

    /**
     * 拼出 chat 接口地址。用户填的地址可能已经带 `/v1`，也可能带完整路径，
     * 所以这里统一处理：只补缺失的 `/chat/completions`，不重复拼接。
     */
    fun chatUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions", ignoreCase = true)) {
            base
        } else {
            "$base/chat/completions"
        }
    }

    /** 拼 JSON 请求体（手写字符串，避免依赖序列化库） */
    fun buildBody(
        model: String,
        messages: List<ChatTurn>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double = DEFAULT_TEMPERATURE
    ): String {
        val turns = messages.joinToString(",") {
            "{\"role\":\"${escapeJson(it.role)}\",\"content\":\"${escapeJson(it.content)}\"}"
        }
        return "{" +
            "\"model\":\"${escapeJson(model)}\"," +
            "\"messages\":[$turns]," +
            "\"temperature\":$temperature," +
            "\"max_tokens\":$maxTokens," +
            "\"stream\":false" +
            "}"
    }

    /** JSON 字符串转义：引号、反斜杠和控制字符必须处理，否则长文本会把请求体搞坏 */
    fun escapeJson(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for (ch in raw) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '\b' -> out.append("\\b")
                ch == '\u000C' -> out.append("\\f")
                ch < ' ' -> out.append("\\u").append(String.format(Locale.US, "%04x", ch.code))
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ 响应

    /**
     * 从接口响应里取回答文本。
     *
     * 兼容两种常见结构：`choices[0].message.content`（标准 OpenAI / 绝大多数兼容实现）
     * 和 `choices[0].text`（部分老接口）。
     */
    fun parseContent(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val first = JsonLite.arr(JsonLite.get(root, "choices")).firstOrNull() ?: return null
        val message = JsonLite.get(first, "message")
        val content = JsonLite.str(JsonLite.get(message, "content"))
            ?: JsonLite.str(JsonLite.get(first, "text"))
        val trimmed = content?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        // 模型偶尔会把 Markdown 符号带上（尽管提示了不要），念出来很难听，统一去掉
        return cleanReply(trimmed)
    }

    /** 把错误信息转成给用户看的人话 */
    fun parseError(raw: String?, httpCode: Int): String {
        val message = raw?.let {
            JsonLite.parseOrNull(it)?.let { root ->
                JsonLite.str(JsonLite.get(JsonLite.get(root, "error"), "message"))
                    ?: JsonLite.str(JsonLite.get(root, "message"))
            }
        }?.trim()
        val prefix = when (httpCode) {
            401, 403 -> "Key 不对或没权限"
            404 -> "接口地址找不到"
            429 -> "请求太频繁了"
            in 500..599 -> "服务端出问题了"
            else -> "请求失败"
        }
        return if (message.isNullOrEmpty()) prefix else "$prefix：$message"
    }

    /** 去掉模型回答里不适合朗读的格式符号 */
    fun cleanReply(raw: String): String {
        return raw
            .replace(Regex("\\*\\*"), "")
            .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^[\\-\\*]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("`"), "")
            .replace(Regex("[ \t]+"), " ")
            .trim()
    }

    // ------------------------------------------------------------------ 调用

    /**
     * 发起一次对话请求（**阻塞**，只能在子线程调用）。
     *
     * @param config 云端配置
     * @param history 上下文历史（不含当前这一句）
     * @param input 用户这一句
     * @param extraSystem 附加到 system 里的内容（记忆 / 当前时间等）
     */
    fun call(
        config: CloudConfig,
        history: List<ChatTurn>,
        input: String,
        extraSystem: String = ""
    ): CloudResult {
        if (!config.isReady()) return CloudResult.Fail("还没有配置 AI 接口")
        val url = chatUrl(config.baseUrl)
        if (url.isEmpty()) return CloudResult.Fail("接口地址是空的")
        // 同一个问题刚问过：直接用上次的答案，不再计费
        CloudCache.get(input)?.let { return CloudResult.Ok(it, "${config.model}（缓存）") }

        val system = if (extraSystem.isBlank()) {
            config.systemPrompt
        } else {
            "${config.systemPrompt}\n$extraSystem"
        }
        val messages = buildList {
            add(ChatTurn.system(system))
            addAll(
                history.takeLast(MAX_HISTORY_TURNS * 2)
                    .map { it.copy(content = clip(it.content)) }
            )
            add(ChatTurn.user(input))
        }

        // 只发一次，不重试。
        // 自动重试看起来是「更稳」，实际是双倍账单：网络抖动、识别失败、
        // 服务端偶发错误都会让同一句话被计费两次，而语音助手的下一句本来就会重新问。
        // 失败一律退回本地答案，离线可用性不受影响。
        return attempt(config, url, messages, config.maxTokens, DEFAULT_TEMPERATURE).also {
            if (it is CloudResult.Ok) CloudCache.put(input, it.text)
        }
    }

    /** 发一次请求并解析结果（不含重试逻辑） */
    private fun attempt(
        config: CloudConfig,
        url: String,
        messages: List<ChatTurn>,
        maxTokens: Int,
        temperature: Double
    ): CloudResult {
        val body = buildBody(config.model, messages, maxTokens, temperature)
        return runCatching {
            val response = httpPost(url, config.apiKey, body, config.timeoutMs)
            val content = parseContent(response.first)
            if (content != null) {
                val usedModel = JsonLite.str(JsonLite.get(JsonLite.parseOrNull(response.first), "model"))
                    ?: config.model
                CloudResult.Ok(content, usedModel)
            } else {
                CloudResult.Fail(parseError(response.first, response.second), retryable = true)
            }
        }.getOrElse { e ->
            CloudResult.Fail(describeException(e, config.timeoutMs))
        }
    }

    /**
     * 错误信息是否在谈论 temperature 限制。
     *
     * 现在请求固定用合法的默认值 1.0，不会再触发这类报错；
     * 保留这个函数是为了「用户手动配置了奇怪的温度」时能把原因说清楚。
     */
    fun mentionsTemperatureLimit(reason: String): Boolean =
        reason.contains("temperature", ignoreCase = true)

    /** 只问一句「在吗」，用于设置页的连通性测试 */
    fun ping(config: CloudConfig): CloudResult =
        call(config, emptyList(), "回复两个字：收到")

    /** 阻塞式 POST；返回「响应体 to HTTP 状态码」 */
    fun httpPost(url: String, apiKey: String, body: String, timeoutMs: Int): Pair<String?, Int> {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                String(buffer.toByteArray(), Charsets.UTF_8)
            }
            text to code
        } finally {
            conn.disconnect()
        }
    }

    private fun describeException(e: Throwable, timeoutMs: Int): String = when {
        e is java.net.SocketTimeoutException -> "请求超过 ${timeoutMs / 1000} 秒没回来（可能网络不通）"
        e is java.net.UnknownHostException -> "域名解析不了，检查一下网络或接口地址"
        e is java.net.ConnectException -> "连不上服务器，检查接口地址或网络"
        e is javax.net.ssl.SSLException -> "HTTPS 握手失败，检查接口地址"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "请求失败"
    }

    // ------------------------------------------------------------------ 决策

    /**
     * 这些意图是**本机功能**，永远不问云端：
     *
     * - 教学过程（教 / 被教 / 取消）一旦被大模型接走，用户就再也教不会本地助手了；
     * - 反问补全（clarify / slot）是本地多轮的中间状态，让大模型插嘴会打断流程。
     */
    private val LOCAL_ONLY_INTENTS = setOf(
        AssistantEngineIntentProvider.TEACH_PROMPT,
        AssistantEngineIntentProvider.TAUGHT,
        AssistantEngineIntentProvider.TEACH_CANCEL,
        AssistantEngineIntentProvider.CLARIFY_REJECTED,
        AssistantEngineIntentProvider.SLOT,
        AssistantEngineIntentProvider.EMPTY,
        AssistantEngineIntentProvider.MEMORY_TAUGHT_INTRO
    )

    /** 本地「明显没答好」的意图：说明规则、知识库、模型都没接住 */
    private val WEAK_INTENTS = setOf(
        AssistantEngineIntentProvider.FALLBACK,
        AssistantEngineIntentProvider.LOCAL_MODEL,
        AssistantEngineIntentProvider.THOUGHT,
        AssistantEngineIntentProvider.LOGIC_FRAME,
        AssistantEngineIntentProvider.CLARIFY
    )

    /**
     * 这一轮要不要问云端。
     *
     * @param intent 本地命中的意图
     * @param localAnswer 本地给出的回答
     * @param config 云端配置
     */
    fun shouldAsk(intent: String, localAnswer: String, config: CloudConfig): Boolean {
        if (!config.isReady()) return false
        if (intent in LOCAL_ONLY_INTENTS) return false
        return when (config.mode) {
            CloudMode.OFF -> false
            CloudMode.CLOUD_FIRST -> true
            CloudMode.LOCAL_FIRST -> intent in WEAK_INTENTS || looksThin(localAnswer)
        }
    }

    /**
     * 本地答案「太单薄」的判定：短于 12 个字且没有实质内容。
     * 「抱一下。想说就说…」这类共情其实够用，但「不知道」「嗯」显然不够，
     * 这里把门槛压得很低，宁可少问云端，也要保证响应速度和节省额度。
     */
    fun looksThin(localAnswer: String): Boolean {
        val text = localAnswer.trim()
        if (text.isEmpty()) return true
        if (containsAny(text, THIN_CUES)) return true
        return text.length <= 6
    }

    private val THIN_CUES = listOf(
        "没太听懂", "听不懂", "答不上来", "不知道", "不确定你在说什么", "换个说法"
    )

    private fun containsAny(text: String, cues: List<String>): Boolean = cues.any { text.contains(it) }

    /** Key 脱敏：只留前六位和后四位 */
    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 10) return trimmed.first() + "****"
        return trimmed.take(6) + "****" + trimmed.takeLast(4)
    }

    /**
     * 最多带几轮历史上去（超出最早的会被丢掉）。
     *
     * 历史是每一轮都要重复发送的，轮数翻倍 = 成本翻倍。
     * 本地的「逻辑分析」会回看 6 条（QueryUnderstanding.LOCAL_MEMORY_TURNS），
     * 但真正发给云端的只有最近 2 轮——指代消解和背景补全已经在本地做完，
     * 云端拿到的是一句完整、自足的问题，不需要靠长历史去猜上下文。
     */
    const val MAX_HISTORY_TURNS = 2

    /** 单条历史最多保留多少字：历史每轮重发，长回答是纯浪费 */
    const val MAX_TURN_CHARS = 40

    /** 历史过长时截断（末尾加省略号，避免模型以为话说到一半） */
    fun clip(text: String, max: Int = MAX_TURN_CHARS): String {
        val value = text.trim()
        if (value.length <= max) return value
        return value.substring(0, max) + "…"
    }

    /**
     * 发给云端的问题：优先用本地归纳出的简要问题。
     *
     * 原始长句里的背景铺垫（「明天我要去上海出差」）和附带问句（「要不要带伞」）
     * 对答案没有额外贡献，却要按 token 计费；本地已经把它压成一句核心问句。
     * 归纳失败（简要问题为空或反而更长）就退回原话，不冒语义丢失的风险。
     */
    fun pickInput(raw: String, brief: String): String {
        val value = brief.trim()
        val source = raw.trim()
        if (value.isEmpty()) return source
        // 留 4 个字的余量：指代消解后往往略长一点（「它怎么样」→「上海怎么样」），
        // 但那句才是云端能看懂的，不能因为长了两个字就退回带代词的原话。
        return if (value.length > source.length + 4) source else value
    }
}

/**
 * 云端答案的**会话内缓存**。
 *
 * ## 为什么需要
 * 语音助手的对话里，同一句话被问第二次是很常见的：
 * 用户没听清再问一遍、换个说法问同一件事、连续对话时反复确认。
 * 每一次都要重新计费，而答案其实是一样的（这是语音助手，不是需要"每次都不一样"的创作场景）。
 *
 * ## 设计取舍
 * - 只在**内存**里存，进程退出就没了：不做持久化的原因是时效——
 *   「今天天气怎么样」昨天的答案不能留到今天，缓存宁可短命也别答错；
 * - 容量 24 条，满了丢最早的：一次会话里真正会重复的也就那么几句；
 * - key 用归一化后的问句（去标点空格），「上海天气怎么样？」和「上海天气怎么样」算同一句。
 */
object CloudCache {

    private const val MAX_ENTRIES = 24

    private val entries = java.util.LinkedHashMap<String, String>(MAX_ENTRIES + 1, 0.75f, true)

    /** 归一化：去标点、空格、大小写，让同一句话的两种写法命中同一条 */
    fun key(input: String): String = input
        .trim()
        .lowercase()
        .replace(Regex("[\\s，,。.！!？?、；;：:\"'“”‘’（）()【】\\[\\]]"), "")

    /** 命中就返回上次的答案，没命中返回 null */
    @Synchronized
    fun get(input: String): String? = entries[key(input)]

    @Synchronized
    fun put(input: String, answer: String) {
        if (answer.isBlank()) return
        entries[key(input)] = answer
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** 设置页「清空」/ 换模型时用：换服务商后旧答案不该继续复用 */
    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}

/**
 * 意图常量的本地副本。
 *
 * CloudBrain 属于 data 层，不该反向依赖 assistant 层的 [com.example.voiceassistant.assistant.AssistantEngine]
 * （那是引擎负责的组合出口），所以这里只抄常量字符串，避免层间循环依赖。
 */
object AssistantEngineIntentProvider {
    const val EMPTY = "empty"
    const val FALLBACK = "fallback"
    const val CLARIFY = "clarify"
    const val CLARIFY_REJECTED = "clarify_rejected"
    const val SLOT = "slot"
    const val TEACH_PROMPT = "teach_prompt"
    const val TAUGHT = "taught"
    const val TEACH_CANCEL = "teach_cancel"
    const val LOCAL_MODEL = "local_model"
    const val LOGIC_FRAME = "logic_frame"
    const val THOUGHT = "thought"
    const val MEMORY_TAUGHT_INTRO = "memory"
}
