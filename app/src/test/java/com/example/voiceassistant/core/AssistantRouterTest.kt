package com.example.voiceassistant.core

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.assistant.OnlineQuery
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.handler.web.WebHandler
import com.example.voiceassistant.network.LlmMessage
import com.example.voiceassistant.network.LlmResult
import com.example.voiceassistant.tool.ToolReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * 调度核心的测试：**关心顺序，不关心实现**。
 *
 * 三层都用假实现替掉，于是可以直接断言：
 * - 本地能答时，联网和云端一次都没被调用（省 token 的关键）；
 * - 联网查到时，云端一次都没被调用（免费优先）；
 * - 联网没查到且配了云端时，才轮到云端；
 * - 云端失败时回落到本地答案，不会空屏。
 */
class AssistantRouterTest {

    /** 调用轨迹，用来断言"到底按什么顺序问了谁" */
    private val log = mutableListOf<String>()

    private val web = object : WebSource {
        var result: WebHandler.Result? = null
        override fun execute(plan: OnlineQuery.Plan): WebHandler.Result? {
            log += "web:${plan.kind}"
            return result
        }
    }

    private val cloud = object : CloudSource {
        var result: LlmResult = LlmResult.Fail("没配")
        override fun call(
            config: CloudLlmHandler.CloudConfig,
            history: List<LlmMessage>,
            input: String,
            extraSystem: String
        ): LlmResult {
            log += "cloud"
            return result
        }
    }

    private val local = object : LocalSource {
        var response = AssistantResponse("本地兜底", "fallback")
        override fun answer(candidates: List<String>): AssistantResponse {
            log += "local"
            return response
        }
    }

    private val noTools = object : ToolSource {
        override fun claims(raw: String, local: AssistantResponse): Boolean = false
        override fun handle(raw: String, local: AssistantResponse): ToolReply? = null
    }

    private fun router(): AssistantRouter = AssistantRouter(
        history = HistoryManager(ChatSession()),
        toolHandler = noTools,
        qaHandler = local,
        webHandler = web,
        llmHandler = cloud
    )

    @org.junit.Test
    fun `本地答得好时，联网和云端一次都不调用`() {
        local.response = AssistantResponse("现在 3 点 20 分。", "time")
        val reply = router().process(listOf("现在几点"), AssistantRouter.Config())
        assertEquals("现在 3 点 20 分。", reply.text)
        assertEquals(AssistantRouter.Source.LOCAL, reply.source)
        assertTrue("本地路线不该触网，实际：$log", log.none { it.startsWith("web") || it == "cloud" })
    }

    @org.junit.Test
    fun `天气问题走联网，拿到结果就不再问云端`() {
        local.response = AssistantResponse("北京常年 12℃~20℃。", "weather")
        web.result = WebHandler.Result("北京现在 18℃，多云。")
        cloud.result = LlmResult.Ok("云端答案", "m")

        val reply = router().process(listOf("北京天气怎么样"), AssistantRouter.Config())
        assertEquals("北京现在 18℃，多云。", reply.text)
        assertEquals(AssistantRouter.Source.WEB, reply.source)
        assertTrue("搜到了就不该再花 token，实际：$log", "cloud" !in log)
    }

    @org.junit.Test
    fun `天气路线即使配了云端也不降级，避免为数据问题付费`() {
        local.response = AssistantResponse("北京常年 12℃~20℃，这是多年平均，不是今天的实况。", "weather")
        web.result = null
        cloud.result = LlmResult.Ok("云端答案", "doubao")

        val config = AssistantRouter.Config(
            cloud = readyConfig().copy(mode = CloudLlmHandler.CloudMode.CLOUD_FIRST)
        )
        val reply = router().process(listOf("北京天气怎么样"), config)
        assertTrue("天气查不到应回落到本地常年数据，实际：$reply", reply.text.contains("常年"))
        assertEquals(AssistantRouter.Source.LOCAL, reply.source)
        assertTrue("天气不该降级到云端，实际：$log", "cloud" !in log)
    }

    @org.junit.Test
    fun `百科问题联网没查到且配了云端时，才降级问云端`() {
        local.response = AssistantResponse("我本地的看法…", "fallback")
        web.result = null
        cloud.result = LlmResult.Ok("云端说：黑洞是大质量恒星坍缩形成的。", "doubao")

        val reply = router().process(
            listOf("什么是黑洞"),
            AssistantRouter.Config(cloud = readyConfig())
        )
        assertEquals("云端说：黑洞是大质量恒星坍缩形成的。", reply.text)
        assertEquals(AssistantRouter.Source.CLOUD, reply.source)
        assertEquals(listOf("local", "web:SEARCH", "cloud"), log)
    }

    @org.junit.Test
    fun `云端失败时回落到本地答案，不会空屏`() {
        local.response = AssistantResponse("我本地的看法…", "thought")
        web.result = null
        cloud.result = LlmResult.Fail("请求超过 20 秒没回来")

        val reply = router().process(
            listOf("什么是黑洞"),
            AssistantRouter.Config(cloud = readyConfig())
        )
        assertTrue("应回落到本地答案，实际：$reply", reply.text.startsWith("我本地的看法"))
        assertEquals("念的时候不带括号说明", "我本地的看法…", reply.speakText)
        assertEquals("请求超过 20 秒没回来", reply.error)
        assertEquals(AssistantRouter.Source.LOCAL, reply.source)
    }

    @org.junit.Test
    fun `没配云端时任何问题都不会去问 AI`() {
        local.response = AssistantResponse("兜底", "fallback")
        val reply = router().process(listOf("什么是黑洞"), AssistantRouter.Config())
        assertEquals(AssistantRouter.Source.LOCAL, reply.source)
        assertEquals(null, reply.error)
        assertTrue("不该调用云端，实际：$log", "cloud" !in log)
    }

    /** 一份「已填好 Key」的云端配置，用来触发云端路线 */
    private fun readyConfig(): CloudLlmHandler.CloudConfig = CloudLlmHandler.CloudConfig(
        enabled = true,
        mode = CloudLlmHandler.CloudMode.LOCAL_FIRST,
        baseUrl = "https://example.com/v1",
        apiKey = "sk-test",
        model = "test-model"
    )
}
