package com.example.voiceassistant.data

import com.example.voiceassistant.assistant.AssistantEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端大模型接口的测试（**不发真实网络请求**）。
 *
 * 测的都是纯函数部分：
 * - 地址拼接（用户可能填 `/v1`，也可能填完整路径）；
 * - 请求体与 JSON 转义（中文和引号都不能把请求体搞坏）；
 * - 各家的响应解析与错误信息；
 * - 该不该问云端的决策（教学 / 槽位这些本机流程不能被抢）。
 */
class CloudBrainTest {

    private fun config(
        mode: CloudBrain.CloudMode = CloudBrain.CloudMode.LOCAL_FIRST,
        url: String = "https://api.deepseek.com/v1",
        key: String = "sk-test-123456",
        model: String = "deepseek-flash"
    ) = CloudBrain.CloudConfig(enabled = true, mode = mode, baseUrl = url, apiKey = key, model = model)

    // ------------------------------------------------------------ 地址与请求体

    @Test
    fun `地址只补一次 chat completions`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            CloudBrain.chatUrl("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            CloudBrain.chatUrl("https://api.deepseek.com/v1/")
        )
        assertEquals(
            "https://x.com/v1/chat/completions",
            CloudBrain.chatUrl("https://x.com/v1/chat/completions")
        )
        assertEquals("", CloudBrain.chatUrl("  "))
    }

    @Test
    fun `请求体带人设和历史`() {
        val body = CloudBrain.buildBody(
            "deepseek-flash",
            listOf(
                CloudBrain.ChatTurn.system("你是助手"),
                CloudBrain.ChatTurn.user("你好"),
                CloudBrain.ChatTurn.assistant("嗨"),
                CloudBrain.ChatTurn.user("我不高兴")
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
        val body = CloudBrain.buildBody(
            "m",
            listOf(CloudBrain.ChatTurn.user("他说：\"别吵了\"\n然后走了")),
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
        assertEquals("我在，你说。", CloudBrain.parseContent(raw))
    }

    @Test
    fun `兼容 choices text 结构`() {
        val raw = "{\"choices\":[{\"text\":\"老接口格式\"}]}"
        assertEquals("老接口格式", CloudBrain.parseContent(raw))
    }

    @Test
    fun `去掉模型回答里的 Markdown 符号`() {
        val raw = "{\"choices\":[{\"message\":{\"content\":\"**先说结论**：都可以\\n- 第一点\\n- 第二点\"}}]}"
        val content = CloudBrain.parseContent(raw)!!
        assertFalse(content.contains("**"))
        assertFalse(content.contains("- 第一点"))
    }

    @Test
    fun `空内容和非法响应都当失败`() {
        assertFalse(CloudBrain.parseContent(null) != null)
        assertFalse(CloudBrain.parseContent("") != null)
        assertFalse(CloudBrain.parseContent("not json") != null)
        assertFalse(CloudBrain.parseContent("{\"choices\":[{\"message\":{\"content\":\"  \"}}]}") != null)
    }

    @Test
    fun `错误信息转成人话`() {
        val raw = "{\"error\":{\"message\":\"Incorrect API key provided\"}}"
        val text = CloudBrain.parseError(raw, 401)
        assertTrue(text.contains("Key 不对"))
        assertTrue(text.contains("Incorrect API key provided"))
        assertEquals("服务端出问题了", CloudBrain.parseError(null, 500))
    }

    // ------------------------------------------------------------ 决策

    @Test
    fun `没配 Key 就不问云端`() {
        val empty = CloudBrain.CloudConfig()
        assertFalse(CloudBrain.shouldAsk(AssistantEngine.INTENT_FALLBACK, "我不知道", empty))
        assertFalse(empty.isReady())
    }

    @Test
    fun `关掉之后即使配好也不问`() {
        val off = config(mode = CloudBrain.CloudMode.OFF)
        assertFalse(CloudBrain.shouldAsk(AssistantEngine.INTENT_FALLBACK, "我不知道", off))
    }

    @Test
    fun `本地优先只在答不好时问`() {
        val cfg = config(mode = CloudBrain.CloudMode.LOCAL_FIRST)
        assertTrue(CloudBrain.shouldAsk(AssistantEngine.INTENT_FALLBACK, "这句我没太听懂", cfg))
        assertTrue(CloudBrain.shouldAsk(AssistantEngine.INTENT_THOUGHT, "可以这么想：先分清楚", cfg))
        // 本地答得挺好就不浪费额度
        assertFalse(CloudBrain.shouldAsk("weather", "北京现在常年 12℃~20℃，出门带件外套。", cfg))
        // 本机功能永远不问
        assertFalse(CloudBrain.shouldAsk(AssistantEngine.INTENT_TAUGHT, "记住了。", cfg))
        assertFalse(CloudBrain.shouldAsk("slot", "你要翻译哪个词？", cfg))
    }

    @Test
    fun `AI 优先时除了本机功能都问`() {
        val cfg = config(mode = CloudBrain.CloudMode.CLOUD_FIRST)
        assertTrue(CloudBrain.shouldAsk("chat", "抱一下。想说就说。", cfg))
        assertTrue(CloudBrain.shouldAsk(AssistantEngine.INTENT_KNOWLEDGE, "光合作用是…", cfg))
        // 教学流程不交出去，否则用户再也教不会本地助手
        assertFalse(CloudBrain.shouldAsk(AssistantEngine.INTENT_TEACH_PROMPT, "那这句该怎么回答？", cfg))
        assertFalse(CloudBrain.shouldAsk("slot", "你要翻译哪个词？", cfg))
    }

    @Test
    fun `单薄答案会被判成答不好`() {
        assertTrue(CloudBrain.looksThin("嗯"))
        assertTrue(CloudBrain.looksThin("这句我没太听懂"))
        assertFalse(CloudBrain.looksThin("北京现在常年 12℃~20℃，建议带件薄外套。"))
    }

    // ------------------------------------------------------------ 其它

    @Test
    fun `Key 脱敏保留头尾`() {
        assertEquals("sk-tes****3456", CloudBrain.maskKey("sk-test-123456"))
        assertEquals("", CloudBrain.maskKey(""))
    }

    @Test
    fun `默认人设要求口语和四句以内`() {
        val prompt = CloudBrain.DEFAULT_SYSTEM_PROMPT
        assertTrue(prompt.contains("四句话以内"))
        assertTrue(prompt.contains("Markdown"))
        assertTrue(prompt.contains("连贯"))
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
        assertTrue(CloudBrain.PROVIDERS.size >= 5)
        val deepseek = CloudBrain.PROVIDERS.first { it.label == "DeepSeek" }
        assertTrue(deepseek.baseUrl.startsWith("https://"))
        assertTrue(deepseek.model.isNotBlank())
    }

    @Test
    fun `预设模型名不能用已停用的旧名`() {
        // deepseek-chat / deepseek-reasoner 已在 2026-07-24 停用，请求会被拒
        val dead = setOf("deepseek-chat", "deepseek-reasoner")
        CloudBrain.PROVIDERS.forEach { p ->
            org.junit.Assert.assertFalse(
                "「${p.label}」还在用已停用的模型名 ${p.model}",
                p.model in dead
            )
        }
    }

    @Test
    fun `预设地址拼出来不带重复的 v1`() {
        CloudBrain.PROVIDERS.filter { it.baseUrl.isNotBlank() }.forEach { p ->
            val url = CloudBrain.chatUrl(p.baseUrl)
            assertTrue("「${p.label}」地址拼错了：$url", url.endsWith("/chat/completions"))
            org.junit.Assert.assertEquals(
                "「${p.label}」里出现两段 /chat/completions",
                url.indexOf("/chat/completions"), url.lastIndexOf("/chat/completions")
            )
        }
    }
}
