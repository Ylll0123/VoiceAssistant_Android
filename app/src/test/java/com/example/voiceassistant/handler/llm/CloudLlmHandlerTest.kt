package com.example.voiceassistant.handler.llm

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.data.JsonLite
import com.example.voiceassistant.assistant.QueryUnderstanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.voiceassistant.network.LlmMessage
import com.example.voiceassistant.network.LlmResult
import com.example.voiceassistant.core.TextCleaner

/**
 * 云端大模型接口的测试（**不发真实网络请求**）。
 *
 * 测的都是纯函数部分：
 * - 地址拼接（用户可能填 `/v1`，也可能填完整路径）；
 * - 请求体与 JSON 转义（中文和引号都不能把请求体搞坏）；
 * - 各家的响应解析与错误信息；
 * - 该不该问云端的决策（教学 / 槽位这些本机流程不能被抢）。
 */
class CloudLlmHandlerTest {

    private fun config(
        mode: CloudLlmHandler.CloudMode = CloudLlmHandler.CloudMode.LOCAL_FIRST,
        url: String = "https://api.deepseek.com/v1",
        key: String = "sk-test-123456",
        model: String = "deepseek-flash"
    ) = CloudLlmHandler.CloudConfig(enabled = true, mode = mode, baseUrl = url, apiKey = key, model = model)

    // ------------------------------------------------------------ 地址与请求体

    @Test
    fun `方舟地址写带不带 chat completions 都一样`() {
        val full = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
        assertEquals(full, CloudLlmHandler.chatUrl("https://ark.cn-beijing.volces.com/api/v3"))
        assertEquals(full, CloudLlmHandler.chatUrl("https://ark.cn-beijing.volces.com/api/v3/"))
        assertEquals(full, CloudLlmHandler.chatUrl(full))
        assertEquals(full, CloudLlmHandler.chatUrl("  $full  "))
    }

    @Test
    fun `地址只补一次 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            CloudLlmHandler.chatUrl("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            CloudLlmHandler.chatUrl("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "https://x.com/v1/chat/completions",
            CloudLlmHandler.chatUrl("https://x.com/v1/chat/completions")
        )
        assertEquals("", CloudLlmHandler.chatUrl("  "))
    }

    @Test
    fun `请求体带人设和历史`() {
        val body = CloudLlmHandler.buildBody(
            "deepseek-flash",
            listOf(
                LlmMessage.system("你是助手"),
                LlmMessage.user("你好"),
                LlmMessage.assistant("嗨"),
                LlmMessage.user("我不高兴")
            ),
            maxTokens = 200
        )
        assertTrue(body.contains("\"model\":\"deepseek-flash\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"assistant\""))
        assertTrue(body.contains("我不高兴"))
        assertTrue(body.contains("\"max_tokens\":200"))
        // 拼出来的必须是合法 JSON，否则服务端直接 400
        assertTrue(JsonLite.parseOrNull(body) != null)
    }

    @Test
    fun `转义不会破坏 JSON`() {
        val body = CloudLlmHandler.buildBody(
            "m",
            listOf(LlmMessage.user("他说：\"别吵了\"\n然后走了")),
        )
        val parsed = JsonLite.parseOrNull(body)
        assertTrue("带引号和换行的请求体必须是合法 JSON：$body", parsed != null)
        val content = JsonLite.str(
            JsonLite.get(
                JsonLite.obj(JsonLite.arr(JsonLite.get(parsed, "messages")).firstOrNull()),
                "content"
            )
        )
        assertEquals("他说：\"别吵了\"\n然后走了", content)
    }

    // ------------------------------------------------------------ 解析

    @Test
    fun `解析标准 OpenAI 响应`() {
        val raw = "{\"id\":\"1\",\"model\":\"deepseek-flash\"," +
            "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"我在，你说。\"}}]}"
        assertEquals("我在，你说。", CloudLlmHandler.parseContent(raw))
    }

    @Test
    fun `兼容 choices text 结构`() {
        val raw = "{\"choices\":[{\"text\":\"老接口格式\"}]}"
        assertEquals("老接口格式", CloudLlmHandler.parseContent(raw))
    }

    @Test
    fun `去掉模型回答里的 Markdown 符号`() {
        val raw = "{\"choices\":[{\"message\":{\"content\":\"**先说结论**：都可以\\n- 第一点\\n- 第二点\"}}]}"
        val content = CloudLlmHandler.parseContent(raw)!!
        assertFalse(content.contains("**"))
        assertFalse(content.contains("- 第一点"))
    }

    @Test
    fun `空内容和非法响应都当失败`() {
        assertFalse(CloudLlmHandler.parseContent(null) != null)
        assertFalse(CloudLlmHandler.parseContent("") != null)
        assertFalse(CloudLlmHandler.parseContent("not json") != null)
        assertFalse(CloudLlmHandler.parseContent("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}") != null)
    }

    @Test
    fun `错误信息转成人话`() {
        val raw = "{\"error\":{\"message\":\"Incorrect API key provided\"}}"
        val text = CloudLlmHandler.parseError(raw, 401)
        assertTrue(text.contains("Key 不对"))
        assertTrue(text.contains("Incorrect API key provided"))
        assertEquals("服务端出问题了", CloudLlmHandler.parseError(null, 500))
    }

    // ------------------------------------------------------------ 决策

    @Test
    fun `没配 Key 就不问云端`() {
        val empty = CloudLlmHandler.CloudConfig()
        assertFalse(CloudLlmHandler.shouldAsk(AssistantEngine.INTENT_FALLBACK, "我不知道", empty))
        assertFalse(empty.isReady())
    }

    @Test
    fun `关掉之后即使配好也不问`() {
        val off = config(mode = CloudLlmHandler.CloudMode.OFF)
        assertFalse(CloudLlmHandler.shouldAsk(AssistantEngine.INTENT_FALLBACK, "我不知道", off))
    }

    @Test
    fun `本地优先只在答不好时问`() {
        val cfg = config(mode = CloudLlmHandler.CloudMode.LOCAL_FIRST)
        assertTrue(CloudLlmHandler.shouldAsk(AssistantEngine.INTENT_FALLBACK, "这句我没太听懂", cfg))
        // 本地答得挺好就不浪费额度
        assertFalse(CloudLlmHandler.shouldAsk("weather", "北京现在常年 12℃~20℃，出门带件外套。", cfg))
        // 本机多轮的中间状态永远不问
        assertFalse(CloudLlmHandler.shouldAsk("slot", "你要翻译哪个词？", cfg))
    }

    @Test
    fun `AI 优先时除了本机功能都问`() {
        val cfg = config(mode = CloudLlmHandler.CloudMode.CLOUD_FIRST)
        assertTrue(CloudLlmHandler.shouldAsk("chat", "抱一下。想说就说。", cfg))
        assertTrue(CloudLlmHandler.shouldAsk(AssistantEngine.INTENT_FALLBACK, "这句我答不上来", cfg))
        // 反问补全是本地多轮中间状态，不交出去
        assertFalse(CloudLlmHandler.shouldAsk("slot", "你要翻译哪个词？", cfg))
    }

    @Test
    fun `单薄答案会被判成答不好`() {
        assertTrue(CloudLlmHandler.looksThin("嗯"))
        assertTrue(CloudLlmHandler.looksThin("这句我没太听懂"))
        assertFalse(CloudLlmHandler.looksThin("北京现在常年 12℃~20℃，建议带件薄外套。"))
    }

    // ------------------------------------------------------------ 其它

    @Test
    fun `Key 脱敏保留头尾`() {
        assertEquals("sk-tes****3456", CloudLlmHandler.maskKey("sk-test-123456"))
        assertEquals("", CloudLlmHandler.maskKey(""))
    }

    @Test
    fun `默认人设带知识骨架但仍是 TTS 友好`() {
        val prompt = CloudLlmHandler.DEFAULT_SYSTEM_PROMPT
        assertTrue("仍须禁止 Markdown（会被 TTS 原样念出）：$prompt", prompt.contains("Markdown"))
        assertTrue("要说明回答会被朗读：$prompt", prompt.contains("朗读") || prompt.contains("短句"))
        // 本地语料删掉后，语料里沉淀的要点改由人设携带，这几类必须在
        listOf("天气", "健康", "学习", "情绪", "安全", "居家").forEach { key ->
            assertTrue("人设里应保留「$key」类要点：$prompt", prompt.contains(key))
        }
        assertTrue("人设每轮都要发送，不能无限长，当前 ${prompt.length} 字：$prompt", prompt.length <= 2000)
    }

    @Test
    fun `两个旧版默认人设都会迁移成知识骨架版`() {
        // 3.0 之前的长人设
        assertEquals(
            CloudLlmHandler.DEFAULT_SYSTEM_PROMPT,
            CloudLlmHandler.migratePrompt(CloudLlmHandler.LEGACY_SYSTEM_PROMPT)
        )
        // v3.6 的极简人设：语料删掉后它太单薄，也要换掉
        assertEquals(
            CloudLlmHandler.DEFAULT_SYSTEM_PROMPT,
            CloudLlmHandler.migratePrompt(CloudLlmHandler.LEGACY_MINIMAL_PROMPT)
        )
    }

    @Test
    fun `用户自己写的人设不会被迁移覆盖`() {
        val custom = "你是我的私人助理，每次都要先报时间"
        assertEquals(custom, CloudLlmHandler.migratePrompt(custom))
        assertEquals("", CloudLlmHandler.migratePrompt(""))
    }

    // ------------------------------------------------------------ 上下文

    @Test
    fun `历史按时间顺序只取完整轮次`() {
        val session = ChatSession()
        session.addUser("北京天气怎么样")
        session.addAssistant("北京常年 12℃~20℃。")
        session.addUser("那上海呢")
        session.addAssistant("上海常年 15℃~23℃。")

        val turns = session.recentTurns(8)
        assertEquals(4, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("北京天气怎么样", turns[0].content)
        assertEquals("assistant", turns[1].role)
        assertEquals("那上海呢", turns[2].content)
        assertEquals("上海常年 15℃~23℃。", turns[3].content)
    }

    @Test
    fun `轮数上限会裁掉更早的内容`() {
        val session = ChatSession()
        repeat(5) {
            session.addUser("问题$it")
            session.addAssistant("回答$it")
        }
        val turns = session.recentTurns(2)
        assertEquals(4, turns.size)
        assertTrue(turns.last().content == "回答4")
    }

    @Test
    fun `末尾落单的用户句不算一轮`() {
        val session = ChatSession()
        session.addUser("只有我说了话")
        assertTrue(session.recentTurns(4).isEmpty())
    }

    @Test
    fun `服务商预设自带可用地址`() {
        assertTrue(CloudLlmHandler.PROVIDERS.size >= 5)
        val deepseek = CloudLlmHandler.PROVIDERS.first { it.label == "DeepSeek" }
        assertTrue(deepseek.baseUrl.startsWith("https://"))
        assertTrue(deepseek.model.isNotBlank())
    }

    @Test
    fun `默认接入的是智谱免费的 Flash 模型`() {
        val provider = CloudLlmHandler.DEFAULT_PROVIDER
        assertEquals("默认服务商必须是列表第一项", CloudLlmHandler.PROVIDERS.first(), provider)
        assertEquals("glm-4-flash-250414", provider.model)
        assertEquals("https://open.bigmodel.cn/api/paas/v4", provider.baseUrl)
        // 默认地址 + 默认模型拼出来的就是可用的 chat 接口
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            CloudLlmHandler.chatUrl(provider.baseUrl)
        )
    }

    @Test
    fun `预设模型名不能用已停用的旧名`() {
        // deepseek-chat / deepseek-reasoner 已在 2026-07-24 停用，请求会被拒
        val dead = setOf("deepseek-chat", "deepseek-reasoner")
        CloudLlmHandler.PROVIDERS.forEach { p ->
            org.junit.Assert.assertFalse(
                "「${p.label}」还在用已停用的模型名 ${p.model}",
                p.model in dead
            )
        }
    }

    @Test
    fun `预设地址拼出来不带重复的 v1`() {
        CloudLlmHandler.PROVIDERS.filter { it.baseUrl.isNotBlank() }.forEach { p ->
            val url = CloudLlmHandler.chatUrl(p.baseUrl)
            assertTrue("「${p.label}」地址拼错了：$url", url.endsWith("/chat/completions"))
            org.junit.Assert.assertEquals(
                "「${p.label}」里出现两段 /chat/completions",
                url.indexOf("/chat/completions"), url.lastIndexOf("/chat/completions")
            )
        }
    }

    // ------------------------------------------------------- 默认值与重试策略

    @Test
    fun `默认温度为 1，兼容方舟思考型模型`() {
        // OpenAI /chat/completions 自身的默认值就是 1；用 0.7 会被方舟的
        // kimi-k3 / doubao-seed 这类模型以「temperature only 1 is allowed」拒绝
        val body = CloudLlmHandler.buildBody("kimi-k3-1-pro-261024", listOf(LlmMessage.user("你好")))
        assertTrue("默认请求体必须是 temperature 1.0：$body", body.contains("\"temperature\":1.0"))
        assertFalse(body.contains("\"temperature\":0.7"))
    }

    @Test
    fun `能认出 temperature 相关的报错`() {
        assertTrue(CloudLlmHandler.mentionsTemperatureLimit("请求失败：`temperature` only 1 is allowed for this model"))
        assertTrue(CloudLlmHandler.mentionsTemperatureLimit("请求失败：Temperature must be 1"))
        assertFalse(CloudLlmHandler.mentionsTemperatureLimit("Key 不对或没权限：invalid api key"))
    }

    @Test
    fun `默认输出上限够说完一段话`() {
        val body = CloudLlmHandler.buildBody("m", listOf(LlmMessage.user("你好")))
        // 大模型现在是主力回答者，128 token 只够两句话，留到 512 才说得完整
        assertEquals(512, CloudLlmHandler.DEFAULT_MAX_TOKENS)
        assertTrue("默认上限应为 512：$body", body.contains("\"max_tokens\":512"))
    }

    @Test
    fun `输出上限可被显式覆盖`() {
        val body = CloudLlmHandler.buildBody(
            "kimi-k3-1-pro-261024",
            listOf(LlmMessage.user("你好")),
            maxTokens = 1024
        )
        assertTrue(body.contains("\"max_tokens\":1024"))
        assertTrue(body.contains("\"temperature\":1.0"))
    }

    @Test
    fun `简要问题比原句短才发给云端`() {
        val raw = "明天我要去上海出差，那边天气怎么样，要不要带伞"
        val brief = QueryUnderstanding.analyze(raw).brief
        assertTrue(brief.length < raw.length)
        assertEquals(brief, CloudLlmHandler.pickInput(raw, brief))
    }

    @Test
    fun `历史里的长回答会被截断`() {
        val turns = listOf(
            LlmMessage.user("你好"),
            LlmMessage.assistant("一".repeat(200))
        )
        val clipped = turns.map { it.copy(content = TextCleaner.clip(it.content)) }
        assertEquals("你好", clipped[0].content)
        assertTrue(clipped[1].content.length <= TextCleaner.DEFAULT_CLIP_CHARS + 1)
    }

    @Test
    fun `失败标记出能不能重试`() {
        val net = LlmResult.Fail("连不上服务器", retryable = false)
        val server = LlmResult.Fail("接口地址找不到", retryable = true)
        assertFalse((net as LlmResult.Fail).retryable)
        assertTrue((server as LlmResult.Fail).retryable)
    }
}
