package com.example.voiceassistant.fuzzy

import com.example.voiceassistant.reminder.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 模糊容错引擎的测试：**从 ASR 原文到标准意图**。
 *
 * 这里钉的是整套系统最容易出错的地方——「听错了还能不能用」。
 * 所有时间断言都注入固定时刻（2026-11-05 09:00 上海），
 * 否则「明天八点」这类用例会随运行时间漂移。
 */
class FuzzyEngineTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Long = LocalDateTime.of(2026, 11, 5, 9, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private val engine = FuzzyEngine(clock = { now }, zone = zone)

    private fun recognize(text: String): IntentResult = engine.recognize(text, now)

    // ------------------------------------------------------------------ 纠错 + 槽位

    @Test
    fun `同音错字纠正之后能抽出完整槽位`() {
        // 框架里的那条验收用例：明天山点 → 明天 03:00，内容 = 交作业
        val result = recognize("明天山点提醒我交作业")

        assertEquals(IntentKind.SET_REMIND, result.kind)
        assertEquals("交作业", result.slots.content)
        assertEquals("明天 03:00", result.slots.timeLabel)
        assertTrue("纠错后的文本要留下", result.corrected.contains("三点"))
        assertTrue("置信度要够高才能直接执行", result.confidence >= FuzzyEngine.HIGH)
    }

    @Test
    fun `没说时间时时间槽位是空的，交给对话层追问`() {
        val result = recognize("提醒我交作业")

        assertEquals(IntentKind.SET_REMIND, result.kind)
        assertEquals(0L, result.slots.timeAt)
        assertEquals("交作业", result.slots.content)
    }

    @Test
    fun `只说了提醒什么的时候内容为空`() {
        val result = recognize("提醒我一下")
        assertEquals(IntentKind.SET_REMIND, result.kind)
        assertTrue(result.slots.content.isBlank())
    }

    @Test
    fun `每天的说法会识别成重复提醒`() {
        val result = recognize("每天八点提醒我吃药")

        assertEquals(IntentKind.SET_REMIND, result.kind)
        assertEquals(Reminder.Repeat.DAILY, result.slots.repeat)
        assertEquals("吃药", result.slots.content)
    }

    // ------------------------------------------------------------------ 各意图

    @Test
    fun `查看清单`() {
        assertEquals(IntentKind.LIST_REMIND, recognize("我的提醒").kind)
        assertEquals(IntentKind.LIST_REMIND, recognize("看看有哪些提醒").kind)
    }

    @Test
    fun `取消与清空`() {
        val cancel = recognize("取消提醒")
        assertEquals(IntentKind.CANCEL_REMIND, cancel.kind)

        assertEquals(IntentKind.CLEAR_REMIND, recognize("清空提醒").kind)
        assertEquals(IntentKind.CLEAR_REMIND, recognize("取消全部提醒").kind)
    }

    @Test
    fun `取消类说法里的序号能抽出来`() {
        assertEquals(2, recognize("取消提醒 2").slots.index)
    }

    @Test
    fun `改时间支持绝对时刻`() {
        val result = recognize("改成四点")

        assertEquals(IntentKind.UPDATE_REMIND, result.kind)
        assertEquals("明天 04:00", result.slots.timeLabel)
    }

    @Test
    fun `改时间支持相对位移`() {
        val result = recognize("推迟一小时")

        assertEquals(IntentKind.UPDATE_REMIND, result.kind)
        assertEquals(60 * 60 * 1000L, result.slots.shiftMs)
    }

    @Test
    fun `计时和提醒不会互相抢`() {
        // 有时长 → 计时
        assertEquals(IntentKind.SET_TIMER, recognize("定个十分钟的闹钟").kind)
        assertEquals(IntentKind.SET_TIMER, recognize("计时五分钟").kind)
        // 有时刻 → 提醒
        assertEquals(IntentKind.SET_REMIND, recognize("定个明天八点的闹钟").kind)
    }

    // ------------------------------------------------------------------ 容错与拒绝

    @Test
    fun `错字会被词表纠正`() {
        val result = recognize("我的提星")
        assertEquals(IntentKind.LIST_REMIND, result.kind)
        assertTrue(result.fixes.any { it.contains("提星") })
    }

    @Test
    fun `词表救不回来的短指令靠编辑距离兜底`() {
        // 「提箱」不在词表里，跟「提醒」差一个字 → 相似度 0.75
        val result = recognize("取消提箱")

        assertEquals(IntentKind.CANCEL_REMIND, result.kind)
        assertTrue("属于「能执行但不太确定」的档位", result.confidence in 0.6..0.9)
    }

    @Test
    fun `非指令的句子一律拒绝`() {
        // 沾了「提醒」两个字，但这是在讨论提醒本身，不是在设提醒
        assertEquals(IntentKind.UNKNOWN, recognize("提醒有什么意义").kind)
        assertEquals(IntentKind.UNKNOWN, recognize("今天天气怎么样").kind)
        assertEquals(IntentKind.UNKNOWN, recognize("讲个笑话").kind)
        assertEquals(IntentKind.UNKNOWN, recognize("12*8").kind)
    }

    @Test
    fun `教学指令不算建提醒`() {
        assertEquals(IntentKind.UNKNOWN, recognize("记住：提醒我喝水").kind)
    }

    @Test
    fun `内容里的不要不会被当成取消`() {
        val result = recognize("提醒我不要迟到")
        assertEquals(IntentKind.SET_REMIND, result.kind)
        assertTrue(result.slots.content.contains("迟到"))
    }

    @Test
    fun `低置信度的结果不算可执行`() {
        val unknown = recognize("讲个笑话")
        assertFalse(unknown.actionable)
        assertEquals(0.0, unknown.confidence, 0.0001)
    }
}
