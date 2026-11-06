package com.example.voiceassistant.handler.llm

import com.example.voiceassistant.BuildConfig
import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.DialogContext
import com.example.voiceassistant.core.CloudSource
import com.example.voiceassistant.core.TextCleaner
import com.example.voiceassistant.data.JsonLite
import com.example.voiceassistant.network.Http
import com.example.voiceassistant.network.LlmMessage
import com.example.voiceassistant.network.LlmResult

/**
 * 处理器层：**云端大模型**。
 *
 * 唯一会花钱的一层，所以它的职责被压得很窄：
 * 只负责「把一段已经组好的对话发出去，把回答拿回来」，
 * **不判断该不该问**（那是 [com.example.voiceassistant.core.RoutePlanner] 的事），
 * **不组装记忆和时间**（那是 [com.example.voiceassistant.core.AssistantRouter] 的事）。
 *
 * ## 协议
 * OpenAI 兼容的 `/chat/completions`。豆包（火山方舟）、DeepSeek、通义、Kimi、智谱
 * 以及绝大多数自建网关都吃这一套，所以换服务商 = 换 Base URL + 模型名，其它不动。
 *
 * ## 省 token 的几个固定参数
 * | 参数 | 值 | 原因 |
 * |---|---|---|
 * | `max_tokens` | 128 | 回答是要念出来的，够说两三句；输出 token 通常更贵 |
 * | `temperature` | 1.0 | 协议默认值，也是兼容性最好的；方舟思考型模型**只允许**这个值 |
 * | `stream` | false | 语音助手要整句拿去朗读，流式只会让逻辑变复杂 |
 * | 重试 | 关 | 自动重试看着更稳，实际是双倍账单；语音助手下一句本来就会重新问 |
 *
 * ## 失败处理
 * 无网络 / 超时 / Key 错 / 地址错，一律返回带中文原因的 [LlmResult.Fail]，
 * 由上层回落到本地答案——**配了云端也不能让离线可用性变差**。
 */
object CloudLlmHandler : CloudSource {

    /** 三种工作方式 */
    enum class CloudMode(val code: Int) {
        /** 不用云端：完全离线 */
        OFF(0),

        /** 本地优先：只有本地明显没答好才问云端 */
        LOCAL_FIRST(1),

        /** AI 优先：只要不是本机功能都交给大模型 */
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
     * 官方免费、128K 上下文、OpenAI 兼容，装完只要填一个 API Key 就能用，
     * 不需要用户先想清楚去哪家注册、充多少钱。
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
     * 想换成自己的，在「关于 → AI 模型」里改一次即可，改过之后以本地保存的为准。
     *
     * 注意这是**共享额度**：Key 在 APK 里，懂行的人能提取出来。
     * 所以智谱后台要设额度上限，发现异常就换一把钥匙重新打包。
     */
    val DEFAULT_API_KEY: String get() = BuildConfig.GLM_API_KEY

    // ------------------------------------------------------------------ 常量

    /**
     * 默认人设 = 原来的本地语料浓缩成的「知识骨架」。
     *
     * v3.8 之前，常识问答（KbLife 等五个分组）、闲聊语料（DialogCorpus）、
     * 思维框架（ThoughtBank / ReplyCrafter）都是写在本机的一大坨数据。
     * 现在答案交给大模型，本地那套全部删掉了，但**语料里沉淀的经验没有丢**：
     * 每类问题该讲哪些要点、什么语气、边界在哪，都收进了这段人设里。
     *
     * 这也是唯一还随每轮请求发送的「本地知识」——它必须短：
     * 一千多字 ≈ 两千 token，走免费模型也扛得住。
     */
    const val DEFAULT_SYSTEM_PROMPT = """你是 HardlySeek，装在手机里的中文语音助手。你的回答会被朗读出来，所以：
用短句，一般三五句说完；不要 Markdown、列表符号、emoji、括号注释；
不要「首先其次综上所述」这类书面结构；数字直接说，别写成算式。

说话方式：
先给结论，再补一句理由，必要时把话头抛回去；
像熟人聊天，不像客服公告，允许「嗯」「这事儿其实」这种口语起头，但别油腻；
用户情绪重时先接住情绪再谈事，他在抱怨的时候不要立刻讲道理、不要评判；
不确定就说不确定，宁可少说，也不要编事实、编数据、编日期。

这台手机能直接做的事：报时间日期、算数换算、查实时天气（没网就给常年气候）、
设提醒和计时、开关手电筒、报电量、打开常用应用、随机决定、讲笑话念名言。
用户让你做这些就直接照做；做不到的（发消息、点外卖、放音乐）说明原因，不要假装做了。

常被问到的几类，答的时候抓这些要点：
【天气穿衣】雨是水汽蒸发、凝结、降落；雪是全程低于零度；台风是海面水汽上升成旋，
风眼区反而风平浪静。穿衣看体感和风力，不只看气温；温差大的季节提醒早晚加衣。
【健康】感冒多是病毒，抗生素无效，靠休息和喝水；发烧先物理降温，持续高烧就医；
久坐腰颈痛先改姿势、定时起身；眼干是盯屏幕太久，每二十分钟看远处二十秒；
失眠先固定起床时间、白天多见光、睡前一小时不刷手机，别一上来就谈药。
【饮食】三餐规律比补品重要；少糖少油、少吃夜宵；别等渴了才喝水；点外卖多带一份菜。
【学习做事】拖延多半是任务太大不知从哪下手，拆成十五分钟能做完的一步；
记不住是复习间隔不对，隔天、隔周各过一遍比连看三小时管用；
考前不刷新内容，过错题和框架；写不出东西先写烂的第一版，再改。
【情绪人际】焦虑是身体在预警，不是软弱，先做一件能马上完成的小事把控制感拿回来；
难过时先允许自己难过，别急着劝「想开点」；社交累了是正常的，独处充电不算毛病；
和人起矛盾先说事实和感受，别用「你总是」开头。
【数码安全】手机进水立刻断电，别开机别充电，先晾干；折腾系统前先备份；
陌生链接和「客服退款」一律先挂断，再从官方 App 进去；密码到处一样是最大的风险。
【居家】油锅起火盖锅盖断火源，绝不浇水；微波炉不放金属和带壳蛋；
冷藏四度、冷冻零下十八度；油烟机滤网一个月清一次。
【拿主意】把「该不该」换成「最坏结果我能不能承担」；给自己留一个能退回的中间选项，
比一次赌到底稳。

边界：医疗诊断、法律结论、投资买卖只给常识和方向，明确说「具体要问医生、律师或专业人士」。
不编新闻、不编人名和数字。真不知道就说不知道，再给一个用户可以自己查的方向。"""

    /**
     * 3.0 之前的默认人设原文。
     *
     * 人设一旦保存过就写进了本地配置，光改 [DEFAULT_SYSTEM_PROMPT] 对老用户不生效——
     * 这里留一份旧原文，读取配置时识别到就自动迁移到极简版。
     */
    const val LEGACY_SYSTEM_PROMPT =
        "你是手机里的中文语音助手，用简洁、自然、有逻辑的口语回答。" +
            "每次回答控制在四句话以内，像真人聊天而不是写文章。" +
            "不要使用 Markdown、列表符号、emoji 或任何特殊格式。" +
            "和上文保持连贯：用户提到的事物或省略指代要按前文理解。" +
            "不确定的事情要说明不确定，不要编造事实。"

    /**
     * 上一版（v3.6）的默认人设。语料删掉之后它太单薄了——
     * 不迁移的话，升级上来的老用户会拿到一个「什么都不会」的助手。
     */
    const val LEGACY_MINIMAL_PROMPT =
        "你是语音助手，简短回答用户问题，口语化，不主动延伸、不追问。不要使用 Markdown 或列表符号。"

    /** 只有「正好等于旧默认原文」才替换；用户自己写的人设原样保留 */
    fun migratePrompt(saved: String): String {
        val trimmed = saved.trim()
        return if (trimmed == LEGACY_SYSTEM_PROMPT || trimmed == LEGACY_MINIMAL_PROMPT) {
            DEFAULT_SYSTEM_PROMPT
        } else {
            saved
        }
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

    // ------------------------------------------------------------------ 调用

    /**
     * 发起一次对话请求（**阻塞**，只能在子线程调用）。
     *
     * @param config 云端配置
     * @param history 已经裁剪好的上下文历史（不含当前这一句）；不需要上下文时传空表
     * @param input 用户这一句
     * @param extraSystem 附加到 system 里的内容（当前时间 / 用户教过的说法等）
     */
    override fun call(
        config: CloudConfig,
        history: List<LlmMessage>,
        input: String,
        extraSystem: String
    ): LlmResult {
        if (!config.isReady()) return LlmResult.Fail("还没有配置 AI 接口")
        val url = chatUrl(config.baseUrl)
        if (url.isEmpty()) return LlmResult.Fail("接口地址是空的")
        // 同一个问题刚问过：直接用上次的答案，不再计费
        CloudCache.get(input)?.let { return LlmResult.Ok(it, "${config.model}（缓存）") }

        val system = if (extraSystem.isBlank()) {
            config.systemPrompt
        } else {
            "${config.systemPrompt}\n$extraSystem"
        }
        val messages = buildList {
            add(LlmMessage.system(system))
            addAll(history)
            add(LlmMessage.user(input))
        }

        // 只发一次，不重试。
        // 自动重试看起来是「更稳」，实际是双倍账单：网络抖动、识别失败、
        // 服务端偶发错误都会让同一句话被计费两次，而语音助手的下一句本来就会重新问。
        // 失败一律退回本地答案，离线可用性不受影响。
        return attempt(config, url, messages, config.maxTokens, DEFAULT_TEMPERATURE).also {
            if (it is LlmResult.Ok) CloudCache.put(input, it.text)
        }
    }

    /** 发一次请求并解析结果（不含重试逻辑） */
    private fun attempt(
        config: CloudConfig,
        url: String,
        messages: List<LlmMessage>,
        maxTokens: Int,
        temperature: Double
    ): LlmResult {
        val body = buildBody(config.model, messages, maxTokens, temperature)
        return runCatching {
            val response = Http.postJson(url, config.apiKey, body, config.timeoutMs)
            val content = parseContent(response.first)
            if (content != null) {
                val usedModel = JsonLite.str(JsonLite.get(JsonLite.parseOrNull(response.first), "model"))
                    ?: config.model
                LlmResult.Ok(content, usedModel)
            } else {
                LlmResult.Fail(parseError(response.first, response.second), retryable = true)
            }
        }.getOrElse { e ->
            LlmResult.Fail(Http.describe(e, config.timeoutMs))
        }
    }

    /**
     * 错误信息是否在谈论 temperature 限制。
     *
     * 请求固定用合法的默认值 1.0，不会触发这类报错；
     * 保留这个函数是为了「用户手动配置了奇怪的温度」时能把原因说清楚。
     */
    fun mentionsTemperatureLimit(reason: String): Boolean =
        reason.contains("temperature", ignoreCase = true)

    /** 只问一句「在吗」，用于设置页的连通性测试 */
    fun ping(config: CloudConfig): LlmResult =
        call(config, emptyList(), "回复两个字：收到", "")

    // ------------------------------------------------------------------ 决策

    /**
     * 这些意图是**本机功能**，永远不问云端：
     * 反问补全（clarify / slot）是本地多轮的中间状态，让大模型插嘴会打断流程。
     */
    private val LOCAL_ONLY_INTENTS = setOf(
        AssistantEngine.INTENT_EMPTY,
        AssistantEngine.INTENT_CLARIFY,
        AssistantEngine.INTENT_CLARIFY_REJECTED,
        DialogContext.INTENT_SLOT
    )

    /** 本地「明显没答好」的意图：说明规则和思考框架都没接住 */
    private val WEAK_INTENTS = setOf(
        AssistantEngine.INTENT_FALLBACK,
        AssistantEngine.INTENT_CLARIFY
    )

    /**
     * 这一轮要不要问云端。
     *
     * 只有「配好了」且「本地确实没答好」才问——
     * 时间、计算、闲聊这些本地能答好的，一个 token 都不花。
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
     * 本地答案「太单薄」的判定。
     *
     * 门槛压得很低（短于 6 个字、或出现「不知道 / 没听懂」这类话），
     * 宁可少问云端，也要保证响应速度和省额度。
     */
    fun looksThin(localAnswer: String): Boolean {
        val text = localAnswer.trim()
        if (text.isEmpty()) return true
        if (THIN_CUES.any { text.contains(it) }) return true
        return text.length <= 6
    }

    private val THIN_CUES = listOf(
        "没太听懂", "听不懂", "答不上来", "不知道", "不确定你在说什么", "换个说法"
    )

    // ------------------------------------------------------------------ 工具

    /** Key 脱敏：只留前六位和后四位 */
    fun maskKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length <= 10) return trimmed.first() + "****"
        return trimmed.take(6) + "****" + trimmed.takeLast(4)
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
 * 语音助手的对话里，同一句话被问第二次很常见：没听清再问一遍、
 * 换个说法问同一件事、连续对话时反复确认。每次都重新计费，而答案其实是一样的
 * （这是语音助手，不是需要「每次都不一样」的创作场景）。
 *
 * ## 设计取舍
 * - 只在**内存**里存，进程退出就没了：时效问题——「今天天气怎么样」
 *   昨天的答案不能留到今天，缓存宁可短命也别答错；
 * - 容量 24 条，满了丢最早的：一次会话里真正会重复的也就那么几句；
 * - key 用归一化后的问句（去标点空格），「上海天气怎么样？」和「上海天气怎么样」算同一句。
 */
object CloudCache {

    private const val MAX_ENTRIES = 24

    private val entries = java.util.LinkedHashMap<String, String>(MAX_ENTRIES + 1, 0.75f, true)

    /** 命中就返回上次的答案，没命中返回 null */
    @Synchronized
    fun get(input: String): String? = entries[TextCleaner.cacheKey(input)]

    @Synchronized
    fun put(input: String, answer: String) {
        if (answer.isBlank()) return
        entries[TextCleaner.cacheKey(input)] = answer
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** 换服务商 / 模型时清空：旧答案不该继续复用 */
    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
