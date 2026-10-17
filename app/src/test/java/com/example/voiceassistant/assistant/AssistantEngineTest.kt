package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎的基础测试。
 *
 * 这些用例只依赖纯 Kotlin 逻辑（不碰 Android 框架），可以在本地 JVM 上秒跑，
 * 也是后续把单元测试覆盖率做到 60% 的起点。
 */
class AssistantEngineTest {

    private val engine = AssistantEngine()

    @Test
    fun `空输入时提示可以开始说话`() {
        assertEquals(AssistantEngine.LISTENING, engine.reply("   "))
    }

    @Test
    fun `阿拉伯数字算式可以直接计算`() {
        assertTrue(engine.reply("帮我算 12*8").contains("96"))
    }

    @Test
    fun `中文描述的算式也能算`() {
        assertTrue(engine.reply("25 乘以 4 等于多少").contains("100"))
    }

    @Test
    fun `询问时间会返回当前时刻`() {
        assertTrue(engine.reply("现在几点了").startsWith("现在是"))
    }

    @Test
    fun `询问日期会返回今天`() {
        assertTrue(engine.reply("今天几号").contains("今天是"))
    }

    @Test
    fun `问候和问时间同时出现时优先回答时间`() {
        val reply = engine.reply("你好，现在几点")
        assertTrue(reply.startsWith("现在是"))
    }

    @Test
    fun `问身份时自我介绍`() {
        assertTrue(engine.reply("你是谁").contains("VoiceAssistant"))
    }

    @Test
    fun `问能力时给出功能清单`() {
        assertTrue(engine.reply("你能做什么").contains("讲个笑话"))
    }

    @Test
    fun `问天气时说明尚未接入`() {
        assertTrue(engine.reply("明天天气怎么样").contains("还没接入"))
    }

    @Test
    fun `无法理解时给出示例引导`() {
        assertEquals(AssistantEngine.FALLBACK, engine.reply("帮我订一张去火星的机票"))
    }

    @Test
    fun `只有数字没有运算符时不当作算术`() {
        assertEquals(AssistantEngine.FALLBACK, engine.reply("12345"))
    }
}
