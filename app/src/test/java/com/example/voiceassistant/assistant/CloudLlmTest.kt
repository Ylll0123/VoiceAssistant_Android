package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端兜底（纯逻辑部分）测试。
 *
 * 这里只测**不联网**的那部分：请求体构造、响应解析、文本整理。
 * 真正的网络请求在 CloudLlm.ask 里，有网才跑，测试中不触发；
 * 而且无论它成功失败，本地规则 + 语料 + 记忆库都能照常工作。
 */
class CloudLlmTest {

    @Test
    fun `请求体带模型名和用户问题`() {
        val body = CloudLlm.buildRequest("deepseek-chat", "今天心情不太好")
        assertTrue(body.contains("\"model\":\"deepseek-chat\""))
        assertTrue(body.contains("今天心情不太好"))
        assertTrue("应该带 system 提示词约束输出长度", body.contains("60 个字"))
    }

    @Test
    fun `请求体会带上最近的对话历史`() {
        val history = listOf("我好累" to "先歇一会儿", "为什么" to "累是攒出来的")
        val body = CloudLlm.buildRequest("m", "那怎么办", history)
        assertTrue(body.contains("我好累"))
        assertTrue(body.contains("那怎么办"))
    }

    @Test
    fun `标准响应能解析出内容`() {
        val json = """
            {"choices":[{"message":{"role":"assistant","content":"先睡一觉吧"}}]}
        """.trimIndent()
        assertEquals("先睡一觉吧", CloudLlm.parseReply(json))
    }

    @Test
    fun `旧式 text 字段也能解析`() {
        val json = """{"choices":[{"text":"别想太多"}]}"""
        assertEquals("别想太多", CloudLlm.parseReply(json))
    }

    @Test
    fun `空响应和坏响应都返回 null`() {
        assertNull(CloudLlm.parseReply(""))
        assertNull(CloudLlm.parseReply("gateway timeout"))
        assertNull(CloudLlm.parseReply("{\"choices\":[]}"))
        assertNull(CloudLlm.parseReply("{\"error\":\"bad key\"}"))
    }

    @Test
    fun `缺少参数时不发起请求`() {
        assertNull(CloudLlm.ask("", "key", "model", "你好"))
        assertNull(CloudLlm.ask("https://x", "", "model", "你好"))
        assertNull(CloudLlm.ask("https://x", "key", "", "你好"))
    }

    @Test
    fun `整理后的文本适合朗读`() {
        val raw = "**先睡一觉吧**\n\n别  \n```code```\n想太多😀"
        val tidy = CloudLlm.tidy(raw)
        assertTrue("不该有换行，实际：$tidy", !tidy.contains("\n"))
        assertTrue("不该有 markdown 标记，实际：$tidy", !tidy.contains("*") && !tidy.contains("`"))
        assertTrue("不该有 emoji，实际：$tidy", !tidy.contains("😀"))
    }

    @Test
    fun `太长的回答会被截断`() {
        val long = "啊".repeat(500)
        val tidy = CloudLlm.tidy(long)
        assertTrue("应该截断，实际长度 ${tidy.length}", tidy.length <= 170)
    }

    @Test
    fun `历史只取最近几轮`() {
        val history = (1..10).map { "第${it}轮问句" to "第${it}轮答句" }
        val body = CloudLlm.buildRequest("m", "现在", history)
        assertTrue("不该把十轮历史全带上", !body.contains("第1轮问句"))
        assertTrue("应该带上最近三轮", body.contains("第10轮问句") && body.contains("第8轮问句"))
    }

    @Test
    fun `特殊字符会被正确转义`() {
        val body = CloudLlm.buildRequest("m", "他说\"你好\"\n然后走了")
        assertTrue("引号要转义，实际：$body", body.contains("\\\"你好\\\""))
        assertTrue("换行要转义，实际：$body", body.contains("\\n") && !body.contains("然后走了\n"))
    }

    @Test
    fun `请求体是完整的一层对象`() {
        val body = CloudLlm.buildRequest("m", "你好")
        assertTrue(body.startsWith("{"))
        assertTrue(body.endsWith("}"))
        assertTrue("应该包含 messages 数组，实际：$body", body.contains("\"messages\":["))
    }
}
