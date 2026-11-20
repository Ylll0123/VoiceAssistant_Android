package com.example.voiceassistant.core

import com.example.voiceassistant.data.Conversation
import com.example.voiceassistant.data.JsonLite
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.network.Http
import com.example.voiceassistant.network.LlmMessage
import com.example.voiceassistant.network.LlmResult
import com.example.voiceassistant.tool.AiTools

/**
 * 核心层：**一轮问答的全部智能都在这里**。
 *
 * v4.0 起，本机不再有规则引擎、常识库、天气数据：
 * 用户说什么都交给大模型，模型需要动手时通过 [AiTools] 调本机工具。
 *
 * ```
 * ask(history, input)
 *   ├─ 组 messages：system(人设 + 当前时间 + 风格) + 最近几轮 + 这一句
 *   ├─ POST /chat/completions（带 tools）
 *   ├─ 模型要调工具？→ 执行 → 把结果作为 role=tool 再发一轮（最多 4 轮）
 *   └─ 模型给文字 → 直接返回
 * ```
 *
 * ## 为什么循环要设上限
 * 模型有可能连着调十次工具（尤其是它以为失败了），一次对话就烧掉十次请求。
 * 4 轮足够覆盖「建提醒 → 回读确认」这种真实场景，再多就是它在绕圈。
 *
 * ## 为什么不用缓存
 * 带工具调用的回答依赖「当时手机的状态」（几点、有没有提醒），
 * 缓存下来的答案会在下一次原样复活，看着像灵异事件。
 */
class AiChat(
    private val tools: AiTools,
    /** 每次调用都重新取配置：用户改了模型要立刻生效 */
    private val config: () -> CloudLlmHandler.CloudConfig,
    private val clock: () -> java.time.LocalDateTime = { java.time.LocalDateTime.now() }
) {

    /** 一轮的结果 */
    data class Answer(
        val text: String,
        /** 非空表示这次失败，界面要显示 */
        val error: String? = null,
        /** 这轮调过哪些工具（状态栏 / 思考过程用） */
        val usedTools: List<String> = emptyList()
    )

    /** 单轮工具调用上限 */
    private val maxRounds = 4

    /**
     * 问一次。
     *
     * @param history 最近几轮对话（用户/助手成对，不含这一句）
     * @param input 用户这一句
     * @param styleHint 附加到 system 的说话风格 / 追问开关
     */
    fun ask(history: List<LlmMessage>, input: String, styleHint: String = ""): Answer {
        // 用户手动关掉云端 AI 之后，这条链路就是断的——本地指令走的是 Dispatcher，不经过这里
        if (!LlmSwitch.enabled) {
            return Answer("云端 AI 现在是关的，问答用不了。", "AI 已关闭")
        }
        val cfg = config()
        if (!cfg.isReady()) return Answer("还没有配置 AI 接口。", "还没配置 AI 接口")
        val url = CloudLlmHandler.chatUrl(cfg.baseUrl)
        if (url.isEmpty()) return Answer("接口地址是空的。", "接口地址是空的")

        val messages = mutableListOf<String>()
        messages += messageJson("system", systemPrompt(cfg, styleHint))
        history.forEach { messages += messageJson(it.role, it.content) }
        messages += messageJson("user", input)

        val used = mutableListOf<String>()

        repeat(maxRounds) { round ->
            val body = buildBody(cfg.model, messages, cfg.maxTokens, tools.schema)
            val (raw, code) = runCatching {
                Http.postJson(url, cfg.apiKey, body, cfg.timeoutMs)
            }.getOrElse { null to 0 }

            if (raw == null) {
                return Answer(
                    text = "连不上网络，先把网络打开再问我。",
                    error = if (code == 0) "没有网络" else "请求失败（$code）",
                    usedTools = used
                )
            }
            if (code !in 200..299) {
                return Answer(
                    text = "这一句没发出去：${CloudLlmHandler.parseError(raw, code)}",
                    error = CloudLlmHandler.parseError(raw, code),
                    usedTools = used
                )
            }

            val calls = parseToolCalls(raw)
            val content = CloudLlmHandler.parseContent(raw)

            if (calls.isEmpty()) {
                val text = content ?: ""
                if (text.isBlank()) {
                    return Answer("这次没生成回答，再说一遍试试。", "空回答", used)
                }
                return Answer(text, null, used)
            }

            // 模型要动手：先把它的「意图」原样记进对话，再补上每个工具的结果
            messages += assistantWithToolCalls(content, calls)
            calls.forEach { call ->
                used += call.name
                val result = tools.execute(call, input)
                messages += toolMessage(call.id, result)
            }

            // 最后一轮还只有工具调用、没有文字：让模型收个尾
            if (round == maxRounds - 1) {
                messages += messageJson("user", "请用一句话把刚才的结果告诉我。")
            }
        }

        return Answer("这件事没办成，你再说一次试试。", "工具调用没有收敛", used)
    }

    /**
     * 给一段对话起标题。
     *
     * 标题要让人一眼看出「这次我问了什么」，所以让它**尽量沿用用户的原话**，
     * 只去掉「请 / 帮我 / 麻烦」这类客套词——而不是另起一个概括性的短语。
     * 单独发一次很小的请求（max_tokens 很小），失败就用首句话截断。
     */
    fun title(firstUser: String, firstAssistant: String): String {
        val cfg = config()
        val fallback = fallbackTitle(firstUser)
        if (!cfg.isReady()) return fallback
        val url = CloudLlmHandler.chatUrl(cfg.baseUrl)
        if (url.isEmpty()) return fallback

        val prompt = "给下面这段对话起标题。要求：\n" +
            "1. 优先直接沿用用户问的那句话，只删掉「请」「帮我」「麻烦」「你能不能」这类客套词；\n" +
            "2. 不要另起一个概括性的短语，也不要加引号、书名号、序号或标点；\n" +
            "3. 不超过 18 个字，只输出标题本身。\n" +
            "用户：${firstUser.take(140)}\n助手：${firstAssistant.take(120)}"
        val body = buildBody(cfg.model, listOf(messageJson("user", prompt)), maxTokens = 64, tools = null)
        val (raw, code) = runCatching {
            Http.postJson(url, cfg.apiKey, body, cfg.timeoutMs)
        }.getOrElse { null to 0 }
        if (raw == null || code !in 200..299) return fallback

        val text = CloudLlmHandler.parseContent(raw)?.trim().orEmpty()
            .trim('"', '\'', '「', '」', '。', '，', '.', ' ', '\n')
            .replace(Regex("\\s+"), " ")
        // 模型偶尔会把「标题：」也吐出来
        val cleaned = text.removePrefix("标题：").removePrefix("标题:").trim()
        if (cleaned.isEmpty() || cleaned.length > 36) return fallback
        return cleaned
    }


    // ------------------------------------------------------------------ 组装

    /** system = 人设 + 当前时间 +（可选的）模式与风格指令 */
    private fun systemPrompt(cfg: CloudLlmHandler.CloudConfig, styleHint: String): String {
        val now = clock()
        val timeLine = "现在是 ${now.year} 年 ${now.monthValue} 月 ${now.dayOfMonth} 日 " +
            "${now.hour} 点 ${String.format("%02d", now.minute)} 分（${weekday(now)}）。"
        return listOf(
            cfg.systemPrompt,
            VOICE_INPUT_RULES,
            CAPABILITY_RULES,
            timeLine,
            AssistantMode.hint(),
            styleHint
        )
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun weekday(now: java.time.LocalDateTime): String =
        when (now.dayOfWeek.value) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            else -> "周日"
        }

    private fun messageJson(role: String, content: String): String =
        """{"role":"$role","content":"${CloudLlmHandler.escapeJson(content)}"}"""

    private fun toolMessage(callId: String, content: String): String =
        """{"role":"tool","tool_call_id":"${CloudLlmHandler.escapeJson(callId)}",""" +
            """"content":"${CloudLlmHandler.escapeJson(content)}"}"""

    /** 带 tool_calls 的 assistant 消息（协议要求原样回传，否则第二轮会 400） */
    private fun assistantWithToolCalls(content: String?, calls: List<AiTools.Call>): String {
        val body = calls.joinToString(",") { call ->
            """{"id":"${CloudLlmHandler.escapeJson(call.id)}","type":"function",""" +
                """"function":{"name":"${CloudLlmHandler.escapeJson(call.name)}",""" +
                """"arguments":"${CloudLlmHandler.escapeJson(call.arguments)}"}}"""
        }
        val text = if (content.isNullOrBlank()) "null" else "\"${CloudLlmHandler.escapeJson(content)}\""
        return """{"role":"assistant","content":$text,"tool_calls":[$body]}"""
    }


    // ------------------------------------------------------------------ 纯函数（可单测）

    /**
     * 这一层只放不需要网络、不需要 Android 的纯函数：
     * 拼请求体、从响应里取工具调用、起名兜底。
     * 放在伴生对象里，单测可以直接调，不用先造一个假的 [AiTools]。
     */
    companion object {

        /**
         * 固定拼进 system 的「语音输入须知」。
         *
         * 和用户可改的人设分开写：这条是**功能正确性的前提**，不能被自定义人设挤掉。
         * 语音识别一定会出错，错的最多的是同音字和断句；模型如果照着错字硬理解，
         * 就会答非所问，用户感受到的就是「识别能力太差」。
         */
        private const val VOICE_INPUT_RULES =
            "用户的话多半来自语音识别，可能带同音字、漏字或断句错误（例如「日立」其实是「日历」）。\n" +
                "先把整句还原成最合理的意思，再决定做什么；不要因为个别字词不合理就说无法理解。\n" +
                "一句话里如果有几件事，按顺序全部做完，最后用一句话总结结果。\n" +
                "指代不清（「发给他」而他并不存在）时问一句，不要自己猜一个对象。\n" +
                "用户重复同一个要求（「再发一次」「重新发」）时，必须**重新调用工具**再做一遍；" +
                "不能因为历史里刚才做过，就回一句「已经做过了」。\n" +
                "工具返回的结果要照原样说给用户，不要把「已经填好、等你点发送」改写成「已发送」，" +
                "也不要替工具保证它没报告过的事。"

        /**
         * 能力边界：**只讲现在真有的功能**。
         *
         * 为什么要单独一段、而且拼在人设后面：用户是可以自己改人设的，
         * 改过的人设可能还写着早就删掉的功能（唤醒、悬浮窗、回答方式……），
         * 模型照着说，用户就会问「在哪儿开」——然后发现根本没有。
         *
         * 这一段跟着版本走，人设怎么写都盖不掉它。
         */
        private const val CAPABILITY_RULES =
            "【这台手机现在只有这些能力，别的一律当作不存在】\n" +
                "- 手机：手电筒、屏幕亮度、媒体/铃声/闹钟音量、播放与切歌、打开任意已装应用、看电量\n" +
                "- 系统：截屏存相册、复制与读取剪贴板、免打扰开关、打开相机拍照\n" +
                "- 通话与出行：打电话（姓名或号码）、导航到某地\n" +
                "- 日程：建闹钟和提醒（直接建在系统时钟里）、记待办、查询、取消\n" +
                "- 消息：把一句话送进微信或 QQ 里某个人的聊天框；内容会同时复制到剪贴板\n" +
                "- 别把「静音」和「勿扰」混起来：静音是音量归零，勿扰是系统拦通知，说哪个做哪件\n" +
                "- 在线：翻译、天气、算数、查时间、查单词、解释概念、写一句话\n" +
                "- 会话：清空当前对话的上下文、把上一条指令再试一次、把上一条回答再念一遍\n" +
                "【下面这些已经删掉了，永远不要提，也不要建议用户去哪里开】\n" +
                "语音唤醒/唤醒词（叫不醒我）、悬浮窗/在别的应用上直接问我、回答方式（精简/详细）、\n" +
                "后台保活、讯飞离线唤醒、发送前免确认、自动发消息权限（无障碍）。\n" +
                "用户问起，就一句「没有这个功能」，不要说「已经去掉了」、不要解释原因，\n" +
                "也不要让用户去设置里找。\n" +
                "【发消息】\n" +
                "用户说「发消息给某人说/问 X」时，X 就是要发出去的原文：哪怕它是「你吃饭了吗」\n" +
                "这种问句，也照原样当 content 传给工具，不要回答它，也不要把「说」「问」写进 content。\n" +
                "做完只能说「已经填好，点一下发送」或「内容已复制到剪贴板」。\n" +
                "永远不要说「已发送」「已经发出去了」——点发送键的是用户，不是你。"

        /** 起名失败时的兜底：直接用用户问的那句话，最多 24 个字 */
        fun fallbackTitle(firstUser: String): String {
            val cleaned = firstUser.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isEmpty()) return Conversation.UNTITLED
            return if (cleaned.length <= 24) cleaned else cleaned.take(24) + "…"
        }

        /**
         * 拼请求体：messages 已经是 JSON 片段，直接内联。
         *
         * 不用 [CloudLlmHandler.buildBody] 是因为那条路只支持纯文本消息，
         * 而工具调用需要 assistant/tool 两种带附加字段的角色。
         */
        fun buildBody(model: String, messages: List<String>, maxTokens: Int, tools: String?): String {
            val tail = if (tools.isNullOrBlank()) {
                ""
            } else {
                ",\"tools\":" + tools + ",\"tool_choice\":\"auto\""
            }
            return """{"model":"${CloudLlmHandler.escapeJson(model)}","messages":[${messages.joinToString(",")}],""" +
                """"max_tokens":$maxTokens,"temperature":1.0,"stream":false$tail}"""
        }

        /**
         * 从响应里取 `tool_calls`。
         *
         * 兼容两种写法：`message.tool_calls`（标准）和部分网关的 `message.function_call`
         * （单个、只有 name/arguments，缺 id 时自己补一个）。
         */
        fun parseToolCalls(raw: String?): List<AiTools.Call> {
            if (raw.isNullOrBlank()) return emptyList()
            val root = JsonLite.parseOrNull(raw) ?: return emptyList()
            val first = JsonLite.arr(JsonLite.get(root, "choices")).firstOrNull() ?: return emptyList()
            val message = JsonLite.get(first, "message")

            val array = JsonLite.arr(JsonLite.get(message, "tool_calls"))
            if (array.isNotEmpty()) {
                return array.mapNotNull { element ->
                    val fields = JsonLite.obj(element)
                    val function = JsonLite.get(fields, "function")
                    val name = JsonLite.str(JsonLite.get(function, "name"))?.trim().orEmpty()
                    if (name.isEmpty()) return@mapNotNull null
                    AiTools.Call(
                        id = JsonLite.str(fields["id"])?.takeIf { it.isNotBlank() }
                            ?: "call_${System.nanoTime()}",
                        name = name,
                        arguments = JsonLite.str(JsonLite.get(function, "arguments"))?.trim()
                            .orEmpty().ifEmpty { "{}" }
                    )
                }
            }

            val legacy = JsonLite.get(message, "function_call")
            val legacyName = JsonLite.str(JsonLite.get(legacy, "name"))?.trim().orEmpty()
            if (legacyName.isNotEmpty()) {
                return listOf(
                    AiTools.Call(
                        id = "call_${System.nanoTime()}",
                        name = legacyName,
                        arguments = JsonLite.str(JsonLite.get(legacy, "arguments"))?.trim()
                            .orEmpty().ifEmpty { "{}" }
                    )
                )
            }
            return emptyList()
        }
    }
}
