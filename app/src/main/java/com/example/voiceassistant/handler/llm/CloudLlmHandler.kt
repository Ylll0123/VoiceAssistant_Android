package com.example.voiceassistant.handler.llm

import com.example.voiceassistant.BuildConfig
import com.example.voiceassistant.data.JsonLite
import com.example.voiceassistant.network.Http
import com.example.voiceassistant.network.LlmMessage

/**
 * 处理器层：**云端大模型**。
 *
 * v4.0 起这个类只剩三件事：
 * 1. 存「用哪家、哪个模型、什么 Key、什么人设」——[CloudConfig]；
 * 2. 做协议层的文本处理——地址拼接、JSON 转义、回答清洗、错误翻译；
 * 3. 给设置页提供一次连通性测试——[ping]。
 *
 * 真正的对话循环（带工具调用的多轮）在 [com.example.voiceassistant.core.AiChat]，
 * 因为它要拿到 `tools` 和调用栈；这里不再碰「这一轮该不该问云端」这类判断。
 *
 * ## 为什么去掉了缓存
 * 带工具调用的回答依赖「当时手机的状态」（几点、有没有提醒、电量多少），
 * 缓存下来的答案会在下一次原样复活，看着像灵异事件。
 *
 * ## 协议
 * OpenAI 兼容的 `/chat/completions`。智谱、方舟、DeepSeek、通义、Kimi
 * 以及绝大多数自建网关都吃这一套，所以换服务商 = 换 Base URL + 模型名。
 */
object CloudLlmHandler {

    /**
     * 历史遗留的工作方式枚举。
     *
     * v4.0 之后**没有本地备用答案**了（离线就用不了），
     * 但配置里存着这个字段，删掉会让老用户升级后读到默认值；
     * 保留结构、只把它当作「开没开」的开关看待：[CloudMode.OFF] = 未启用。
     */
    enum class CloudMode(val code: Int) {
        OFF(0),
        LOCAL_FIRST(1),
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
            enabled && mode != CloudMode.OFF && baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

        /** 展示用的脱敏文本：Key 只留前六位和后四位 */
        fun maskedKey(): String = maskKey(apiKey)
    }

    /** 一个服务商预设：换服务商 = 换 Base URL + 模型名，其它都一样 */
    data class Provider(
        val label: String,
        val baseUrl: String,
        val model: String,
        val hint: String
    )

    /**
     * 服务商预设列表。
     *
     * **第一个就是默认接入的**（见 [DEFAULT_PROVIDER]）：智谱 GLM-4-Flash-250414，
     * 官方免费、128K 上下文、OpenAI 兼容，支持 function calling，
     * 装完只要填一个 API Key 就能用。
     */
    val PROVIDERS: List<Provider> = listOf(
        Provider(
            "智谱 GLM",
            "https://open.bigmodel.cn/api/paas/v4",
            "glm-4-flash-250414",
            "默认接入的免费模型，地址以 /v4 结尾；到 open.bigmodel.cn 注册后取 API Key 填进来即可"
        ),
        Provider(
            "火山方舟（豆包）",
            "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seed-1-6-250615",
            "方舟控制台开通模型后，用「API Key」填入；地址填上面这段即可，不用加 /chat/completions"
        ),
        Provider(
            "DeepSeek",
            "https://api.deepseek.com",
            "deepseek-flash",
            "deepseek-chat 已停用，请用 deepseek-flash；地址不带 /v1"
        ),
        Provider(
            "通义千问",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "qwen-plus",
            "阿里云百炼的 OpenAI 兼容模式地址"
        ),
        Provider(
            "月之暗面 Kimi",
            "https://api.moonshot.cn/v1",
            "kimi-k2-0905-preview",
            "Moonshot 开放平台，地址以 /v1 结尾"
        )
    )

    /** 默认接入的服务商：新装用户不用配置就知道「点这里能用」 */
    val DEFAULT_PROVIDER: Provider get() = PROVIDERS.first()

    /**
     * 随包内置的 Key（编译期从 `local.properties` 注入到 [BuildConfig.GLM_API_KEY]）。
     *
     * 这样下载 APK 的人不用注册、不用填 Key 就能直接问大模型；
     * 想换成自己的，在「设置 → 关于 → AI 模型」里改一次即可。
     *
     * 注意这是**共享额度**：Key 在 APK 里，懂行的人能提取出来。
     * 所以智谱后台要设额度上限，发现异常就换一把钥匙重新打包。
     */
    val DEFAULT_API_KEY: String get() = BuildConfig.GLM_API_KEY

    // ------------------------------------------------------------------ 常量

    /**
     * 默认人设。
     *
     * v4.0 的分工变了：本机不再有规则引擎、常识库、天气数据，
     * **所有能力都靠大模型 + 它手里的工具**。所以人设的重点从
     * 「背哪些常识」变成了「什么时候该动手、动手失败怎么说」——
     * 模型很容易在明明有工具的时候回答「我做不到」，这段必须写死。
     */
    const val DEFAULT_SYSTEM_PROMPT = """你是 HardlySeek，装在手机里的中文语音助手。你的回答会被朗读出来，所以：
用短句，一般三五句说完；不要 Markdown、列表符号、emoji、括号注释；
不要「首先其次综上所述」这类书面结构；数字直接说，别写成算式。

说话方式：
先给结论，再补一句理由，必要时把话头抛回去；
像熟人聊天，不像客服公告，允许「嗯」「这事儿其实」这种口语起头，但别油腻；
用户情绪重时先接住情绪再谈事，他在抱怨的时候不要立刻讲道理、不要评判；
不确定就说不确定，宁可少说，也不要编事实、编数据、编日期。

你手里有工具，能真的操作这台手机：
create_reminder 建提醒、list_reminders 查提醒、cancel_reminder 取消提醒、
set_timer 计时、device 控制手电筒/音量/亮度/打开应用/报电量、stop_speaking 停止朗读。

工具怎么用（很重要）：
内容和时间都齐了，**立刻调用工具**，不要复述、不要确认、不要追问「要不要重复」
「是哪个作业」这种已经有了答案的问题——重复默认不重复，事项就是用户说的那件事。
只有真的缺关键信息（比如只说了「提醒我一下」）才问一句，问完等用户回答。
「今晚八点」「明早七点半」「半小时后」都要换算成 yyyy-MM-dd HH:mm 再传。
调用完用一句话说结果（几点响、设了多久），不要念参数。
工具返回失败就照实说失败原因，不要编一个成功。

除此之外的任何问题——算数换算、天气出行、查资料、讲道理、闲聊——
你自己回答，不要推给别人，也不要说「请用别的软件查」。
需要精确的实时天气或最新资料时，直接说你看到的信息截至什么时候。

边界：医疗诊断、法律结论、投资买卖只给常识和方向，明确说「具体要问医生、律师或专业人士」。
不编新闻、不编人名和数字。真不知道就说不知道，再给一个用户可以自己查的方向。"""

    /**
     * 3.0 之前的默认人设原文。
     *
     * 人设一旦保存过就写进了本地配置，光改 [DEFAULT_SYSTEM_PROMPT] 对老用户不生效——
     * 这里留一份旧原文，读取配置时识别到就自动迁移。
     */
    const val LEGACY_SYSTEM_PROMPT =
        "你是手机里的中文语音助手，用简洁、自然、有逻辑的口语回答。" +
            "每次回答控制在四句话以内，像真人聊天而不是写文章。" +
            "不要使用 Markdown、列表符号、emoji 或任何特殊格式。" +
            "和上文保持连贯：用户提到的事物或省略指代要按前文理解。" +
            "不确定的事情要说明不确定，不要编造事实。"

    /** v3.6 的极简人设：语料删掉之后它太单薄了，必须迁移 */
    const val LEGACY_MINIMAL_PROMPT =
        "你是语音助手，简短回答用户问题，口语化，不主动延伸、不追问。不要使用 Markdown 或列表符号。"

    /**
     * v3.8 的人设：把本地语料压成的那段「知识骨架」。
     *
     * 它宣称「这台手机能直接做的事：报时间日期、算数换算……」——
     * 那是本地规则引擎的说法。v4.0 把这些全交给模型和工具了，
     * 留着这段会让模型以为该用旧办法，所以整段换掉。
     */
    val LEGACY_KNOWLEDGE_PROMPT: String
        get() = "你是 HardlySeek，装在手机里的中文语音助手。你的回答会被朗读出来，所以："

    /** 只有「正好等于旧默认原文」才替换；用户自己写的人设原样保留 */
    fun migratePrompt(saved: String): String {
        val trimmed = saved.trim()
        val legacy = trimmed == LEGACY_SYSTEM_PROMPT || trimmed == LEGACY_MINIMAL_PROMPT ||
            trimmed.startsWith(LEGACY_KNOWLEDGE_PROMPT)
        return if (legacy) DEFAULT_SYSTEM_PROMPT else saved
    }

    const val DEFAULT_MAX_TOKENS = 512
    const val DEFAULT_TEMPERATURE = 1.0
    const val DEFAULT_TIMEOUT_MS = 20_000

    // ------------------------------------------------------------------ 请求

    /**
     * 拼出 chat 接口地址。
     *
     * 用户填的地址可能是 `https://ark.../api/v3`，也可能已经写全
     * `/chat/completions`，还可能有尾斜杠或空格——统一处理成「有且仅有一个」。
     */
    fun chatUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val suffix = "/chat/completions"
        return if (base.endsWith(suffix)) base else base + suffix
    }

    /** 拼 JSON 请求体（手写字符串，避免依赖序列化库） */
    fun buildBody(
        model: String,
        messages: List<LlmMessage>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        temperature: Double = DEFAULT_TEMPERATURE
    ): String {
        val turns = messages.joinToString(",") {
            """{"role":"${escapeJson(it.role)}","content":"${escapeJson(it.content)}"}"""
        }
        return """{"model":"${escapeJson(model)}","messages":[$turns],""" +
            """"max_tokens":$maxTokens,"temperature":$temperature,"stream":false}"""
    }

    /** JSON 字符串转义：引号、反斜杠和控制字符必须处理，否则长文本会把请求体搞坏 */
    fun escapeJson(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for (char in raw) {
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char.code < 0x20 -> out.append(String.format("\\u%04x", char.code))
                else -> out.append(char)
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

    // ------------------------------------------------------------------ 工具

    /** Key 脱敏：只留前六位和后四位 */
    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 10) return trimmed.first() + "****"
        return trimmed.take(6) + "****" + trimmed.takeLast(4)
    }

    /**
     * 只问一句「在吗」，用于设置页的连通性测试。
     *
     * **阻塞**，只能在子线程调用。结果按 LlmResult 的两种形态返回会引入旧依赖，
     * 这里直接给一句人话：成功是模型名，失败是原因。
     */
    fun ping(config: CloudConfig): String {
        if (!config.isReady()) return "还没有配置 AI 接口"
        val url = chatUrl(config.baseUrl)
        if (url.isEmpty()) return "接口地址是空的"
        val body = buildBody(config.model, listOf(LlmMessage.user("回复两个字：收到")))
        return runCatching {
            val (raw, code) = Http.postJson(url, config.apiKey, body, config.timeoutMs)
            if (code in 200..299) {
                parseContent(raw)?.take(20) ?: "连上了，但模型没回话"
            } else {
                parseError(raw, code)
            }
        }.getOrElse { e ->
            Http.describe(e, config.timeoutMs)
        }
    }
}
