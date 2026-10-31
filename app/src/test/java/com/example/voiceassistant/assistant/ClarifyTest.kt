package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「听得清」相关的两条链路：
 *
 * 1. 多候选识别：识别结果一歪，还能用后面的候选救回来
 * 2. 没听懂时反过来确认：先问「你是不是想问…」，用户答「是」再执行
 *
 * 确认链路用一个只认「笑话」的桩规则，避免依赖真实规则库的细节，
 * 这样测试只验证引擎的流程，不会因为改了某条文案就失败。
 */
class ClarifyTest {

    /** 只认「笑话」两个字的最小规则 */
    private val jokeRule = object : IntentRule {
        override val intent = "joke"
        override fun reply(request: AssistantRequest): RuleReply? =
            if (request.text.contains("笑话")) RuleReply("这是一个笑话。", intent) else null
    }

    @Test
    fun `首选结果听不懂时用后面的候选救回来`() {
        val engine = AssistantEngine()
        val response = engine.respond(listOf("帮我查看日立", "现在几点了"))

        assertEquals("time", response.intent)
        assertEquals("现在几点了", response.heard)
        assertTrue("应该报时间，实际：${response.text}", response.text.contains("现在"))
    }

    @Test
    fun `全部候选都不懂时给出确认建议并挂起`() {
        val engine = AssistantEngine(rules = listOf(jokeRule))
        val response = engine.respond("讲个校话")

        assertEquals(AssistantEngine.INTENT_CLARIFY, response.intent)
        assertTrue("应该反问，实际：${response.text}", response.text.contains("你是不是想问"))
        assertTrue("确认内容应指向最接近的示例", response.text.contains("讲个笑话"))
    }

    @Test
    fun `回答是之后执行挂起的说法`() {
        val engine = AssistantEngine(rules = listOf(jokeRule))
        engine.respond("讲个校话")

        val response = engine.respond("是")
        assertEquals("joke", response.intent)
        assertEquals("这是一个笑话。", response.text)
    }

    @Test
    fun `回答不是之后放弃并给出引导`() {
        val engine = AssistantEngine(rules = listOf(jokeRule))
        engine.respond("讲个校话")

        val response = engine.respond("不是")
        assertEquals(AssistantEngine.INTENT_CLARIFY_REJECTED, response.intent)
        assertNull("挂起的说法应该被清掉", engine.context().pendingUtterance)
    }

    @Test
    fun `确认轮里换话题不会被挂起内容带偏`() {
        val engine = AssistantEngine()
        engine.respond("讲个校话")

        // 用户没有回答是/不是，而是直接问了别的
        val response = engine.respond("现在几点了")
        assertEquals("time", response.intent)
    }

    @Test
    fun `否定判断优先于肯定（不是里含是）`() {
        val engine = AssistantEngine(rules = listOf(jokeRule))
        engine.respond("讲个校话")

        val response = engine.respond("不是")
        assertEquals(AssistantEngine.INTENT_CLARIFY_REJECTED, response.intent)
    }
}
