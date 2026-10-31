package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话逻辑：上下文承接、缺参数反问、推理框架。
 *
 * 测的是「上一句 → 这一句」的关系，所以每条用例都是**先聊一句再造上下文**。
 */
class DialogLogicTest {

    // ------------------------------------------------------------------ 上下文承接

    private fun contextAfterWeather(city: String = "北京"): DialogContext {
        val context = DialogContext()
        context.remember("weather", mapOf(DialogContext.SLOT_ENTITY to city))
        return context
    }

    @Test
    fun `只说「还有吗」要自动带上上一句的城市`() {
        val expanded = DialogLogic.expand("还有吗", contextAfterWeather())
        assertEquals("北京这两天天气还有什么要注意的", expanded)
    }

    @Test
    fun `只说「为什么」要接上一句的主题`() {
        val context = DialogContext()
        context.remember("knowledge", mapOf(DialogContext.SLOT_ENTITY to "下雨"))
        assertEquals("下雨为什么会这样", DialogLogic.expand("为什么", context))
    }

    @Test
    fun `只说「怎么办」要接上一句的主题`() {
        val context = DialogContext()
        context.remember("knowledge", mapOf(DialogContext.SLOT_ENTITY to "感冒"))
        assertEquals("感冒该怎么办", DialogLogic.expand("怎么办", context))
    }

    @Test
    fun `地点代词「那里」替换成上次聊的城市`() {
        val context = contextAfterWeather("上海")
        val expanded = DialogLogic.expand("那里天气怎么样", context)
        assertEquals("上海天气怎么样", expanded)
    }

    @Test
    fun `句子本身完整时不改写`() {
        assertNull(DialogLogic.expand("北京天气怎么样", contextAfterWeather()))
        assertNull(DialogLogic.expand("", contextAfterWeather()))
    }

    // ------------------------------------------------------------------ 缺参数反问

    @Test
    fun `只说「翻译」会反问缺的词`() {
        assertEquals("translate", DialogLogic.missingSlot("翻译"))
        assertEquals("translate", DialogLogic.missingSlot("用英语怎么说"))
        assertTrue(DialogLogic.slotQuestion("translate").contains("你要翻译哪个词"))
    }

    @Test
    fun `说清楚了就不反问`() {
        assertNull(DialogLogic.missingSlot("苹果用英语怎么说"))
        assertNull(DialogLogic.missingSlot("apple"))
        assertNull(DialogLogic.missingSlot("今天天气怎么样"))
    }

    @Test
    fun `补上词之后拼成完整问句`() {
        assertEquals("苹果用英语怎么说", DialogLogic.fillSlot("translate", "苹果"))
        assertEquals("apple", DialogLogic.fillSlot("translate", "apple"))
        assertNull(DialogLogic.fillSlot("translate", ""))
    }

    // ------------------------------------------------------------------ 推理框架

    @Test
    fun `做选择的问题给决策三步`() {
        val answer = DialogLogic.frameAnswer("我该不该考研")!!
        assertTrue("实际：$answer", answer.contains("「我该不该考研」"))
        assertTrue("实际：$answer", answer.contains("先定目的"))
        assertTrue("实际：$answer", answer.contains("能接受就去做"))
    }

    @Test
    fun `求助类问题给拆解四步`() {
        val answer = DialogLogic.frameAnswer("论文写不出来怎么办")!!
        assertTrue("实际：$answer", answer.contains("四步拆"))
        assertTrue("实际：$answer", answer.contains("最小动作"))
    }

    @Test
    fun `问原因时不编造：给归因角度并说明不敢瞎猜`() {
        val answer = DialogLogic.frameAnswer("为什么最近总是失眠啊")!!
        assertTrue("实际：$answer", answer.contains("不想瞎编"))
        assertTrue("实际：$answer", answer.contains("直接触发条件"))
    }

    @Test
    fun `要评价的问题给「事实—影响—取舍」`() {
        val answer = DialogLogic.frameAnswer("你觉得这样安排对吗")!!
        assertTrue("实际：$answer", answer.contains("事实是什么"))
    }

    @Test
    fun `不该走框架的话返回 null，交给后面的兜底`() {
        assertNull(DialogLogic.frameAnswer("你好"))
        assertNull(DialogLogic.frameAnswer("讲个笑话"))
        assertNull(DialogLogic.frameAnswer(""))
    }

    // ------------------------------------------------------------------ 端到端

    @Test
    fun `问完天气再问「还有吗」能接着答，不是答不上来`() {
        val engine = AssistantEngine()
        engine.reply("北京天气怎么样")
        val followUp = engine.reply("还有吗")
        assertTrue("实际：$followUp", followUp.contains("北京"))
        assertTrue("实际：$followUp", followUp.length > 20)
    }

    @Test
    fun `只说「翻译」先反问，补上词后给出译文`() {
        val engine = AssistantEngine()
        val asked = engine.reply("翻译")
        assertTrue("实际：$asked", asked.contains("哪个词"))

        val answered = engine.reply("苹果")
        assertTrue("实际：$answered", answered.contains("apple"))
    }

    @Test
    fun `本地答不上来的选择题会给出思考框架而不是乱答`() {
        val engine = AssistantEngine()
        val answer = engine.reply("我到底要不要换个环境")
        assertTrue("实际：$answer", answer.contains("目的清楚"))
    }
}
