package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 串行调度：**本地 → 联网 → 云端**。
 *
 * 这些断言直接对应三类线上问题：
 * - 本地和云端抢回答（一条消息只允许有一条最终路线）；
 * - 该联网的没联网（天气 / 事实）；
 * - token 花得快（能搜到的就不问云端，联网路线不带云端兜底）。
 */
class AnswerRouterTest {

    private fun understand(text: String, memory: List<String> = emptyList()) =
        QueryUnderstanding.analyze(text, memory)

    private fun decide(
        raw: String,
        intent: String,
        online: Boolean = true,
        cloud: Boolean = true,
        cloudFirst: Boolean = false,
        city: CityClimate.CityClimate? = null,
        localText: String = "本地答案"
    ) = AnswerRouter.decide(
        raw = raw,
        intent = intent,
        localText = localText,
        understanding = understand(raw),
        onlineEnabled = online,
        cloudReady = cloud,
        cloudFirst = cloudFirst,
        fallbackCity = city
    )

    @Test
    fun `本地答得好的一次请求都不发`() {
        val cases = listOf(
            "现在几点了" to "time",
            "12 加 8 等于多少" to "arithmetic",
            "苹果用英语怎么说" to "translate",
            "我好累" to DialogContext.INTENT_CHAT,
            "为什么会下雨" to AssistantEngine.INTENT_KNOWLEDGE
        )
        for ((raw, intent) in cases) {
            val d = decide(raw, intent)
            assertEquals("$raw 应走本地", AnswerRouter.Route.LOCAL, d.route)
            assertFalse("$raw 不该联网", d.needsNetwork)
        }
    }

    @Test
    fun `天气必须联网，没说城市就按定位查`() {
        val withCity = decide("北京天气怎么样", "weather")
        assertEquals(AnswerRouter.Route.WEB, withCity.route)
        assertEquals(OnlineQuery.Kind.WEATHER, withCity.plan!!.kind)
        assertEquals("北京", withCity.plan!!.city!!.city)
        // 天气是数据问题，不需要再问大模型兜底（省 token）
        assertFalse(withCity.cloudFallback)

        val noCity = decide("今天天气怎么样", "weather")
        assertEquals(AnswerRouter.Route.WEB, noCity.route)
        assertNull(noCity.plan!!.city)
    }

    @Test
    fun `上一句聊过城市时，「那里」也能查`() {
        val d = AnswerRouter.decide(
            raw = "那里多少度",
            intent = "weather",
            localText = "常年…",
            understanding = understand("那里多少度"),
            onlineEnabled = true,
            cloudReady = true,
            cloudFirst = false,
            fallbackCity = CityClimate.find("上海")
        )
        assertEquals(AnswerRouter.Route.WEB, d.route)
        assertEquals("上海", d.plan!!.city!!.city)
    }

    @Test
    fun `问明天天气要取第二天的数据`() {
        val d = decide("明天上海天气怎么样", "weather")
        assertEquals(1, d.plan!!.dayOffset)
        assertEquals("明天", d.plan!!.dayLabel)
    }

    @Test
    fun `出门带伞这类也要实时天气`() {
        val d = decide("今天出门要带伞吗", "outing")
        assertEquals(AnswerRouter.Route.WEB, d.route)
        assertEquals(OnlineQuery.Kind.WEATHER, d.plan!!.kind)
    }

    @Test
    fun `用户明确说帮我查：先联网搜，搜不到才问云端`() {
        val d = decide("帮我查一下量子纠缠", AssistantEngine.INTENT_FALLBACK)
        assertEquals(AnswerRouter.Route.WEB, d.route)
        assertEquals(OnlineQuery.Kind.SEARCH, d.plan!!.kind)
        assertTrue(d.cloudFallback)
    }

    @Test
    fun `本地答不上来的事实问题：先免费搜，再付费问云端`() {
        val raw = "量子纠缠到底是什么"
        val d = decide(raw, AssistantEngine.INTENT_FALLBACK)
        assertEquals(AnswerRouter.Route.WEB, d.route)
        assertEquals(OnlineQuery.Kind.SEARCH, d.plan!!.kind)
        assertTrue("搜不到时应该还能降级到云端", d.cloudFallback)
        assertFalse("搜索词不该是整句话：${d.plan!!.query}", d.plan!!.query.contains("到底"))
    }

    @Test
    fun `主观和情绪问题不搜索：直接问云端`() {
        val opinion = decide("我到底该不该换个环境", AssistantEngine.INTENT_THOUGHT)
        assertEquals(AnswerRouter.Route.CLOUD, opinion.route)
        assertNull(opinion.plan)
    }

    @Test
    fun `没配云端也不联网时：本地兜底`() {
        val raw = "量子纠缠到底是什么"
        val d = decide(raw, AssistantEngine.INTENT_FALLBACK, online = false, cloud = false)
        assertEquals(AnswerRouter.Route.LOCAL, d.route)
        assertEquals("本地答案", d.fallbackText)
    }

    @Test
    fun `联网关了但配了云端：直接问云端`() {
        val d = decide("量子纠缠到底是什么", AssistantEngine.INTENT_FALLBACK, online = false, cloud = true)
        assertEquals(AnswerRouter.Route.CLOUD, d.route)
    }

    @Test
    fun `教学流程永远不联网不问云端`() {
        val raw = "记住：下班了 = 早点回家"
        val d = decide(raw, AssistantEngine.INTENT_TAUGHT, cloudFirst = true)
        assertEquals(AnswerRouter.Route.LOCAL, d.route)
    }

    @Test
    fun `AI 优先时本地答好的也过一遍大模型，但天气仍然先联网`() {
        assertEquals(
            AnswerRouter.Route.CLOUD,
            decide("现在几点了", "time", cloudFirst = true).route
        )
        assertEquals(
            AnswerRouter.Route.WEB,
            decide("北京天气怎么样", "weather", cloudFirst = true).route
        )
    }

    @Test
    fun `只有存在指代或追问时才带历史，省 token`() {
        val followUp = understand("它怎么样", listOf("上海天气怎么样"))
        assertTrue(AnswerRouter.needsHistory(followUp))

        val standalone = understand("量子纠缠到底是什么")
        assertFalse(AnswerRouter.needsHistory(standalone))
    }

    @Test
    fun `兜底答案始终是本地那一条，联网失败也不会空屏`() {
        val d = decide("北京天气怎么样", "weather", localText = "北京常年气候…")
        assertEquals("北京常年气候…", d.fallbackText)
        assertEquals("weather", d.fallbackIntent)
    }
}
