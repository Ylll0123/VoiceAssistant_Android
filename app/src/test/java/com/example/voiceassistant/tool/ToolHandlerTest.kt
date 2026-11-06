package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.service.AudioController
import com.example.voiceassistant.service.DeviceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 工具层的测试：**只测判定与执行，不碰系统 API**。
 *
 * 重点是一条底线 —— 工具是本机能力，任何一句话被工具认领后，
 * 都不该再产生请求（既不上网也不问云端）。
 *
 * 时间相关的判定都注入固定时刻，否则「明天 8 点」的断言会随运行时间变化。
 */
class ToolHandlerTest {

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

    private class FakeDevice : DeviceController {
        var torch = false
        var batteryText: String? = "现在电量 78%，正在充电。"
        var installed = setOf("微信")
        val opened = mutableListOf<String>()

        override fun battery(): String? = batteryText

        override fun setTorch(on: Boolean): Boolean {
            torch = on
            return true
        }

        override fun openApp(name: String): String? {
            if (name !in installed) return null
            opened += name
            return name
        }
    }

    /** 本地引擎这一轮的结果；工具据此判定，所以要能随手造一个 */
    private fun local(text: String = "兜底", intent: String = "UNKNOWN") =
        AssistantResponse(text, intent, null)

    /** 固定成 2026-11-05 09:00 */
    private val now: Long = LocalDateTime.of(2026, 11, 5, 9, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val audio = FakeAudio()
    private val device = FakeDevice()
    private lateinit var handler: ToolHandler

    @Before
    fun setUp() {
        // 提醒存在内存里，测试之间要清干净，否则会互相影响
        ReminderTool.sink.clear()
        handler = ToolHandler(audio, device) { now }
    }

    // ------------------------------------------------------------------ 提醒

    @Test
    fun `提醒类指令会被工具认领并执行`() {
        assertTrue(handler.claims("提醒我明天交报告", local()))
        val reply = handler.handle("提醒我明天交报告", local())
        assertNotNull(reply)
        assertEquals(ToolIntent.REMINDER, reply!!.intent)
        assertTrue("应记下要提醒的内容，实际：${reply.text}", reply.text.contains("交报告"))
    }

    @Test
    fun `说了时刻的提醒会解析出具体时间`() {
        val reply = handler.handle("提醒我明天八点交报告", local())
        assertTrue("应回出具体时刻，实际：${reply!!.text}", reply.text.contains("明天 08:00"))
        // 时间从正文里剥掉，不再重复念「明天八点」
        assertEquals("交报告", ReminderTool.sink.all().single().content)
        assertTrue("应排上闹钟", ReminderTool.sink.all().single().scheduled)
    }

    @Test
    fun `没说时间的提醒不会假装能响`() {
        val reply = handler.handle("提醒我交报告", local())
        assertTrue("要说明没法定时", reply!!.text.contains("没说时间"))
        assertFalse(ReminderTool.sink.all().single().scheduled)
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
    fun `删除不存在的序号会说清楚`() {
        handler.handle("提醒我喝水", local())
        val reply = handler.handle("删除提醒 5", local())
        assertTrue("应提示序号越界，实际：${reply!!.text}", reply.text.contains("不存在"))
        assertEquals("越界不该误删", 1, ReminderTool.sink.all().size)
    }

    // ------------------------------------------------------------------ 计时

    @Test
    fun `计时五分钟会排上倒计时`() {
        assertTrue(handler.claims("计时五分钟", local()))
        val reply = handler.handle("计时五分钟", local())
        assertEquals(ToolIntent.TIMER, reply!!.intent)
        assertTrue("应说出时长，实际：${reply.text}", reply.text.contains("5 分钟后"))
        assertEquals(now + 5 * 60_000L, ReminderTool.sink.all().single().triggerAt)
    }

    @Test
    fun `计时排在提醒前面，不会把指令本身念一遍`() {
        // 「定个 10 分钟闹钟」两边关键词都沾，必须由计时认领
        val reply = handler.handle("定个十分钟的闹钟", local())
        assertEquals(ToolIntent.TIMER, reply!!.intent)
        assertEquals("好，10 分钟后叫你。", reply.text)
    }

    @Test
    fun `取消计时能停掉倒计时`() {
        handler.handle("计时五分钟", local())
        val reply = handler.handle("取消计时", local())
        assertTrue(reply!!.text.contains("停了"))
        assertTrue("清单里不该还有计时", ReminderTool.sink.all().isEmpty())
    }

    @Test
    fun `没给时长时会反问`() {
        val reply = handler.handle("帮我计时", local())
        assertTrue("应反问多久，实际：${reply!!.text}", reply.text.contains("多久"))
    }

    // ------------------------------------------------------------------ 设备

    @Test
    fun `电量查询`() {
        val reply = handler.handle("手机还有多少电", local())
        assertEquals(ToolIntent.BATTERY, reply!!.intent)
        assertTrue(reply.text.contains("78%"))
    }

    @Test
    fun `手电筒可以开也可以关`() {
        val on = handler.handle("打开手电筒", local())
        assertEquals(ToolIntent.TORCH, on!!.intent)
        assertTrue("应把手电筒打开", device.torch)

        val off = handler.handle("关掉手电筒", local())
        assertEquals(ToolIntent.TORCH, off!!.intent)
        assertFalse("应把手电筒关掉", device.torch)
    }

    @Test
    fun `打开应用会走设备层`() {
        val reply = handler.handle("打开微信", local())
        assertEquals(ToolIntent.DEVICE, reply!!.intent)
        assertTrue(reply.text.contains("微信"))
        assertEquals(listOf("微信"), device.opened)
    }

    @Test
    fun `打开没装的应用会如实说找不到`() {
        val reply = handler.handle("打开一个不存在的应用", local())
        assertTrue("应说找不到，实际：${reply!!.text}", reply.text.contains("没找到"))
    }

    // ------------------------------------------------------------------ 老能力回归

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
        val timeLocal = local("现在 9 点整。", "time")
        assertTrue(handler.claims("现在几点", timeLocal))
        val time = handler.handle("现在几点", timeLocal)
        assertEquals(ToolIntent.TIME, time!!.intent)
        assertEquals("现在 9 点整。", time.text)

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
