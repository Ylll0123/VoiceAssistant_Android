package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 联网决策：什么该联网、查什么词 */
class OnlineQueryTest {

    @Test
    fun `问实时天气要联网，并带上识别出的城市`() {
        val plan = OnlineQuery.plan("北京天气怎么样", "weather")!!
        assertEquals(OnlineQuery.Kind.WEATHER, plan.kind)
        assertEquals("北京", plan.query)
        assertTrue(plan.city!!.city.contains("北京"))
    }

    @Test
    fun `上一句聊过城市时，「那里」这种省略也能联网`() {
        val city = CityClimate.find("上海")
        val plan = OnlineQuery.plan("那里多少度", "weather", fallbackCity = city)!!
        assertEquals(OnlineQuery.Kind.WEATHER, plan.kind)
        assertEquals("上海", plan.city!!.city)
    }

    @Test
    fun `没有城市信息就不联网`() {
        assertNull(OnlineQuery.plan("今天天气怎么样", "weather"))
    }

    @Test
    fun `用户明确说帮我查或搜一下才联网`() {
        val plan = OnlineQuery.plan("帮我查一下量子纠缠", AssistantEngine.INTENT_FALLBACK)!!
        assertEquals(OnlineQuery.Kind.SEARCH, plan.kind)
        assertEquals("量子纠缠", plan.query)

        val searched = OnlineQuery.plan("百度搜一下光合作用", AssistantEngine.INTENT_FALLBACK)!!
        assertEquals(OnlineQuery.Kind.SEARCH, searched.kind)
        assertEquals("光合作用", searched.query)
    }

    @Test
    fun `没说要查就不联网：交给本地思维模式回答`() {
        // 只是随口问一句，本地答不上来也不去搜——这是 v2.7 明确的行为
        assertNull(OnlineQuery.plan("介绍一下量子纠缠", AssistantEngine.INTENT_FALLBACK))
        assertNull(OnlineQuery.plan("量子纠缠是什么意思", AssistantEngine.INTENT_FALLBACK))
        assertNull(OnlineQuery.plan("我到底该不该换个环境", AssistantEngine.INTENT_FALLBACK))
        assertNull(OnlineQuery.plan("最近好累啊", AssistantEngine.INTENT_FALLBACK))
    }

    @Test
    fun `wantsWeb 只在显式要求时为真`() {
        assertTrue(OnlineQuery.wantsWeb("帮我查一下人工智能"))
        assertTrue(OnlineQuery.wantsWeb("百度百科查查这个词"))
        assertFalse(OnlineQuery.wantsWeb("为什么天空是蓝的"))
        assertFalse(OnlineQuery.wantsWeb("我该不该考研"))
        // 天气走专门的实时通道，不算「搜索」
        assertTrue(OnlineQuery.plan("北京天气怎么样", "weather") != null)
    }

    @Test
    fun `本地答好了就不联网：时间 计算 闲聊 知识`() {
        assertNull(OnlineQuery.plan("现在几点了", "time"))
        assertNull(OnlineQuery.plan("12 加 8 等于多少", "arithmetic"))
        assertNull(OnlineQuery.plan("我好累", DialogContext.INTENT_CHAT))
        assertNull(OnlineQuery.plan("为什么会下雨", AssistantEngine.INTENT_KNOWLEDGE))
    }

    @Test
    fun `查新闻和太短的句子不联网`() {
        assertNull(OnlineQuery.plan("", AssistantEngine.INTENT_FALLBACK))
        assertNull(OnlineQuery.plan("哦", AssistantEngine.INTENT_FALLBACK))
    }

    @Test
    fun `从自然问句里抽出查询主题`() {
        assertEquals("人工智能", OnlineQuery.extractTopic("帮我查一下人工智能是什么"))
        assertEquals("量子纠缠", OnlineQuery.extractTopic("量子纠缠是什么意思？"))
        assertEquals("光合作用", OnlineQuery.extractTopic("介绍一下光合作用"))
        assertEquals("相对论", OnlineQuery.extractTopic("请问相对论讲的是啥"))
    }
}
