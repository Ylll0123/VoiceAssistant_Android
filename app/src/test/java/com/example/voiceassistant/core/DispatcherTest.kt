package com.example.voiceassistant.core

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.ServiceBus
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.tool.AiTools
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * 路由层测试：**一句话该走哪条路**。
 *
 * 这一层最容易出错的地方不是「本地判错」，而是**顺序**：
 * 确认回答抢走新指令、本地规则截胡问答，都属于这一类。
 */
class DispatcherTest {

    /** 2026-11-03 10:00 周二 */
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)

    private lateinit var bus: ServiceBus
    private lateinit var ai: AiChat
    private lateinit var pending: FakePending
    private lateinit var dispatcher: Dispatcher

    /** 假供给方：记下自己被调过几次 */
    private class Recorder(private val serviceName: String) : Supply {
        override val name = serviceName
        override val summary = "测试用"
        var calls = 0
        override fun invoke(args: BusArgs): BusReply {
            calls++
            return BusReply.ok("办好了")
        }
    }

    private class FakePending(var pending: Boolean = false) : PendingSend {
        var discarded = false
        override fun hasPendingSend(): Boolean = pending
        override fun discardPending(): Boolean {
            if (!pending) return false
            pending = false
            discarded = true
            return true
        }
    }

    private lateinit var torch: Recorder
    private lateinit var confirmSend: Recorder

    @Before
    fun setUp() {
        LlmSwitch.set(true)
        torch = Recorder("device/flashlight")
        confirmSend = Recorder("msg/confirm_send")
        bus = ServiceBus().registerAll(torch, confirmSend)
        // 配置留空 → AiChat 会在发请求前就返回「还没配置」，测试不碰网络
        ai = AiChat(AiTools(bus), config = { CloudLlmHandler.CloudConfig() })
        pending = FakePending()
        dispatcher = Dispatcher(bus, ai, pending, now = { now })
    }

    @After
    fun tearDown() {
        LlmSwitch.set(true)
    }

    private fun dispatch(text: String, online: Boolean = true): Dispatcher.Result =
        dispatcher.dispatch(text, emptyList(), "", online)

    // ------------------------------------------------------------ 确认判断

    @Test
    fun `确认类回答只认短而明确的话`() {
        assertEquals(Dispatcher.ConfirmKind.YES, dispatcher.confirmKind("确认"))
        assertEquals(Dispatcher.ConfirmKind.YES, dispatcher.confirmKind("发吧"))
        assertEquals(Dispatcher.ConfirmKind.YES, dispatcher.confirmKind("好的。"))
        assertEquals(Dispatcher.ConfirmKind.NO, dispatcher.confirmKind("算了"))
        assertEquals(Dispatcher.ConfirmKind.NO, dispatcher.confirmKind("别发"))
        // 带着后续指令的「好」不能当成确认，否则用户的新需求会被吞掉
        assertEquals(
            Dispatcher.ConfirmKind.OTHER,
            dispatcher.confirmKind("好，那你顺便帮我定个闹钟")
        )
        assertEquals(Dispatcher.ConfirmKind.OTHER, dispatcher.confirmKind("今天天气怎么样"))
    }

    // ------------------------------------------------------------ 确认优先

    @Test
    fun `有待发消息时说确认就真发`() {
        pending.pending = true
        val result = dispatch("确认")

        assertTrue(result is Dispatcher.Result.Local)
        assertEquals("msg/confirm_send", (result as Dispatcher.Result.Local).service)
        assertEquals(1, confirmSend.calls)
    }

    @Test
    fun `说算了就丢掉待发内容`() {
        pending.pending = true
        val result = dispatch("算了")

        assertTrue(pending.discarded)
        assertEquals("msg/discard", (result as Dispatcher.Result.Local).service)
        // 不能顺手把消息发出去
        assertEquals(0, confirmSend.calls)
    }

    @Test
    fun `待确认时说了别的事，待发内容留着不动`() {
        pending.pending = true
        val result = dispatch("打开手电筒")

        // 当新指令处理，手电筒照常开
        assertEquals("device/flashlight", (result as Dispatcher.Result.Local).service)
        assertEquals(1, torch.calls)
        assertEquals(false, pending.discarded)
        assertTrue(pending.pending)
    }

    // ------------------------------------------------------------ 本地优先

    @Test
    fun `本地认得的指令不碰网络也不碰大模型`() {
        // 就算没网、就算关了云端 AI，本机操作照样能用
        LlmSwitch.set(false)
        val result = dispatch("打开手电筒", online = false)

        assertTrue(result is Dispatcher.Result.Local)
        assertEquals("办好了", (result as Dispatcher.Result.Local).reply.speech)
        assertEquals(1, torch.calls)
    }

    // ------------------------------------------------------------ 兜底分支

    @Test
    fun `空话直接回绝`() {
        val result = dispatch("   ")
        assertTrue(result is Dispatcher.Result.Blocked)
        assertEquals("说点什么吧。", (result as Dispatcher.Result.Blocked).message)
    }

    @Test
    fun `云端 AI 关了要说清哪些还能用`() {
        LlmSwitch.set(false, "用户关闭")
        val result = dispatch("解释一下傅里叶变换")

        assertTrue(result is Dispatcher.Result.Blocked)
        val message = (result as Dispatcher.Result.Blocked).message
        assertTrue(message.contains("关"))
        // 得告诉用户本机能力还在，不然会以为整个助手废了
        assertTrue(message.contains("本机"))
    }

    @Test
    fun `没网时不发请求，直接说清原因`() {
        val result = dispatch("解释一下傅里叶变换", online = false)

        assertTrue(result is Dispatcher.Result.Blocked)
        assertTrue((result as Dispatcher.Result.Blocked).message.contains("没联网"))
    }

    @Test
    fun `本地认不出、又能联网时交给大模型`() {
        val result = dispatch("解释一下傅里叶变换")

        // 配置是空的，所以大模型这条路会以「还没配置」结束——重点是它确实走到了这一步
        assertTrue(result is Dispatcher.Result.Ask)
        assertEquals("还没配置 AI 接口", (result as Dispatcher.Result.Ask).answer.error)
    }
}
