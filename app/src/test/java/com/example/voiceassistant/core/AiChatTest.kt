package com.example.voiceassistant.core

import com.example.voiceassistant.data.Conversation
import com.example.voiceassistant.data.JsonLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能层纯函数的测试（**不发真实请求**）。
 *
 * 这里测的都是「拼错了接口就 400、解析错了就答非所问」的地方：
 * 请求体结构、工具调用的两种写法、起名兜底。
 */
class AiChatTest {

    // ------------------------------------------------------------ 请求体

    @Test
    fun `请求体是合法 JSON 且带工具`() {
        val body = AiChat.buildBody(
            model = "glm-4-flash-250414",
            messages = listOf("""{"role":"user","content":"提醒我明早八点交作业"}"""),
            maxTokens = 512,
            tools = """[{"type":"function","function":{"name":"create_reminder"}}]"""
        )
        val root = JsonLite.parseOrNull(body)
        assertTrue("请求体必须是合法 JSON：$body", root != null)
        assertEquals("glm-4-flash-250414", JsonLite.str(JsonLite.get(root, "model")))
        assertEquals("auto", JsonLite.str(JsonLite.get(root, "tool_choice")))
        assertEquals(1, JsonLite.arr(JsonLite.get(root, "tools")).size)
        // 流式会让「整句拿去朗读」变复杂，语音助手固定关掉
        assertEquals("false", JsonLite.str(JsonLite.get(root, "stream")))
    }

    @Test
    fun `起标题时不带工具`() {
        val body = AiChat.buildBody("m", listOf("""{"role":"user","content":"起个标题"}"""), 32, null)
        val root = JsonLite.parseOrNull(body)
        assertTrue(root != null)
        assertFalse("起标题不该带工具，否则模型可能去调工具", body.contains("\"tools\""))
        assertTrue(body.contains("\"max_tokens\":32"))
    }

    @Test
    fun `工具调用消息能原样拼回去`() {
        // 第二轮请求必须把上一轮的 assistant(tool_calls) 和 tool 结果带上，
        // 少一个字段协议就报 400
        val messages = listOf(
            """{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                """"function":{"name":"list_reminders","arguments":"{}"}}]}""",
            """{"role":"tool","tool_call_id":"c1","content":"没有提醒。"}"""
        )
        val body = AiChat.buildBody("m", messages, 512, "[]")
        val root = JsonLite.parseOrNull(body)
        assertTrue(root != null)
        val parsed = JsonLite.arr(JsonLite.get(root, "messages"))
        assertEquals(2, parsed.size)
        assertEquals("tool", JsonLite.str(JsonLite.get(JsonLite.obj(parsed[1]), "role")))
        assertEquals("c1", JsonLite.str(JsonLite.get(JsonLite.obj(parsed[1]), "tool_call_id")))
    }

    // ------------------------------------------------------------ 工具调用解析

    @Test
    fun `解析标准的 tool_calls`() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"call_abc","type":"function","function":{"name":"create_reminder",
            "arguments":"{\"content\":\"交作业\",\"time\":\"2026-11-04 08:00\"}"}}]}}]}"""
        val calls = AiChat.parseToolCalls(raw)
        assertEquals(1, calls.size)
        assertEquals("call_abc", calls[0].id)
        assertEquals("create_reminder", calls[0].name)
        assertTrue(calls[0].arguments.contains("交作业"))
    }

    @Test
    fun `一次多个工具调用都要解析出来`() {
        val raw = """{"choices":[{"message":{"tool_calls":[
            {"id":"1","function":{"name":"torch_on","arguments":"{}"}},
            {"id":"2","function":{"name":"battery","arguments":"{}"}}]}}]}"""
        val calls = AiChat.parseToolCalls(raw)
        assertEquals(2, calls.size)
        assertEquals(listOf("torch_on", "battery"), calls.map { it.name })
    }

    @Test
    fun `兼容旧式 function_call`() {
        val raw = """{"choices":[{"message":{"function_call":{"name":"list_reminders","arguments":""}}}]}"""
        val calls = AiChat.parseToolCalls(raw)
        assertEquals(1, calls.size)
        assertEquals("list_reminders", calls[0].name)
        // 缺参数时补成空对象，否则工具层解析会炸
        assertEquals("{}", calls[0].arguments)
        assertTrue("没有 id 时要自己补一个", calls[0].id.isNotBlank())
    }

    @Test
    fun `普通回答里没有工具调用`() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"今天多云，十二度。"}}]}"""
        assertTrue(AiChat.parseToolCalls(raw).isEmpty())
        assertTrue(AiChat.parseToolCalls(null).isEmpty())
        assertTrue(AiChat.parseToolCalls("不是 JSON").isEmpty())
        assertTrue(AiChat.parseToolCalls("{\"choices\":[]}").isEmpty())
    }

    @Test
    fun `没有名字的工具调用直接丢掉`() {
        val raw = """{"choices":[{"message":{"tool_calls":[{"id":"1","function":{"arguments":"{}"}}]}}]}"""
        assertTrue(AiChat.parseToolCalls(raw).isEmpty())
    }

    // ------------------------------------------------------------ 起名兜底

    @Test
    fun `起名失败时直接用用户那句话`() {
        // 短句原样保留：用户问什么，标题就是什么
        assertEquals("提醒我明天八点交作业", AiChat.fallbackTitle("提醒我明天八点交作业"))

        val long = "帮我看看明天北京上海广州三个城市的天气怎么样要不要带伞出门比较好呢"
        val title = AiChat.fallbackTitle(long)
        assertEquals(25, title.length)
        assertTrue(title.endsWith("…"))
        assertEquals(long.take(24) + "…", title)
    }

    @Test
    fun `空的第一句用新对话兜底`() {
        assertEquals(Conversation.UNTITLED, AiChat.fallbackTitle(""))
        assertEquals(Conversation.UNTITLED, AiChat.fallbackTitle("   "))
    }

    @Test
    fun `首句里的换行会被压成空格`() {
        assertEquals("你好 在吗", AiChat.fallbackTitle("你好\n在吗"))
    }
}
