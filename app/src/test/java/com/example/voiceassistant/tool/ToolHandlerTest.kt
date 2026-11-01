package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.service.AudioController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 工具层的测试：**只测判定与执行，不碰网络**。
 *
 * 重点是一条底线 —— 工具是本机能力，任何一句话被工具认领后，
 * 都不该再产生请求（既不上网也不问云端）。
 */
class ToolHandlerTest {

    /** 本地引擎这一轮的结果；工具据此判定，所以要能随手造一个 */
    private fun local(text: String = "兜底", intent: String = "UNKNOWN") =
        AssistantResponse(text, intent, null)

    private class FakeAudio : AudioController {
        private var mutedState = false
        val adjustments = mutableListOf<Boolean>()
        override fun setMuted(muted: Boolean) {
            mutedState = muted
        }

        override fun isMuted(): Boolean = mutedState
        override fun adjustVolume(up: Boolean) {
            adjustments += up
        }
    }

    private val audio = FakeAudio()
    private lateinit var handler: ToolHandler

    @Before
    fun setUp() {
        // 提醒存在内存里，测试之间要清干净，否则会互相影响
        ReminderTool.sink.clear()
        handler = ToolHandler(audio)
    }

    @Test
    fun `提醒类指令会被工具认领并执行`() {
        assertTrue(handler.claims("提醒我明天交报告", local()))
        val reply = handler.handle("提醒我明天交报告", local())
        assertNotNull(reply)
        assertEquals(ToolIntent.REMINDER, reply!!.intent)
        assertTrue(reply.text.contains("明天交报告"))
    }

    @Test
    fun `可以列出和删除提醒`() {
        handler.handle("提醒我喝水", local())
        val list = handler.handle("我的提醒", local())
        assertTrue("应列出刚记的那条，实际：$list", list!!.text.contains("喝水"))

        val removed = handler.handle("删除提醒 1", local())
        assertTrue(removed!!.text.contains("删掉了"))

        val empty = handler.handle("我的提醒", local())
        assertTrue(empty!!.text.contains("没有提醒"))
    }

    @Test
    fun `音量指令直接改系统音量`() {
        assertTrue(handler.claims("静音", local()))
        handler.handle("静音", local())
        assertTrue(audio.isMuted())

        handler.handle("取消静音", local())
        assertFalse(audio.isMuted())

        handler.handle("大点声", local())
        handler.handle("小点声", local())
        assertEquals(listOf(true, false), audio.adjustments)
    }

    @Test
    fun `取消静音要真的恢复声音，不能被静音分支吃掉`() {
        // 回归：「取消静音」里含「静音」。若先判静音分支，
        // 用户越说「取消静音」越没声音——这里把顺序钉死。
        handler.handle("静音", local())
        assertTrue("前置：先进入静音", audio.isMuted())

        val reply = handler.handle("取消静音", local())
        assertFalse("取消静音后必须恢复出声", audio.isMuted())
        assertEquals(ToolIntent.VOLUME, reply!!.intent)
    }

    @Test
    fun `让助手闭嘴时不念出来`() {
        val reply = handler.handle("别说了", local())
        assertNotNull(reply)
        assertEquals(ToolIntent.TTS_STOP, reply!!.intent)
    }

    @Test
    fun `时间和计算走委托，采用本地引擎那条答案`() {
        val timeLocal = local("现在 3 点 20 分。", "time")
        assertTrue(handler.claims("现在几点", timeLocal))
        val time = handler.handle("现在几点", timeLocal)
        assertEquals(ToolIntent.TIME, time!!.intent)
        assertEquals("现在 3 点 20 分。", time.text)

        val calcLocal = local("12*8 = 96", "arithmetic")
        assertTrue(handler.claims("帮我算一下十二乘以八", calcLocal))
        val calc = handler.handle("帮我算一下十二乘以八", calcLocal)
        assertEquals(ToolIntent.CALC, calc!!.intent)
        assertEquals("12*8 = 96", calc.text)
    }

    @Test
    fun `含时间词的成因题不会被时钟抢走`() {
        // 回归：「黑洞是什么时候形成的」含「什么时候」，但问的是成因。
        // 判定必须以引擎意图为准，否则这类题会被送去读钟点。
        val factLocal = local("黑洞由大质量恒星坍缩形成。", "fact")
        assertFalse("成因题不该被时间工具认领", handler.claims("黑洞是什么时候形成的", factLocal))
        assertNull(handler.handle("黑洞是什么时候形成的", factLocal))
    }

    @Test
    fun `教学指令不会被工具抢走`() {
        assertFalse("「记住：提醒我喝水」是教学，不是建提醒", handler.claims("记住：提醒我喝水", local()))
        assertNull(handler.handle("记住：提醒我喝水", local()))
    }

    @Test
    fun `无关的话不归工具管`() {
        assertFalse(handler.claims("今天天气怎么样", local()))
        assertFalse(handler.claims("讲个笑话", local()))
    }

    @Test
    fun `空白输入不认领`() {
        assertFalse(handler.claims("", local()))
        assertFalse(handler.claims("   ", local()))
        assertNull(handler.handle("", local()))
    }
}