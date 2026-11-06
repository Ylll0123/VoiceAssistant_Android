package com.example.voiceassistant.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 重复规则的测试：**下一次到底是什么时候**。
 *
 * 只按毫秒加 24 小时会出错：夏令时那天是 23 或 25 小时，
 * 「每天 8 点」会慢慢漂成 7 点或 9 点。所以这里钉死了「按日历加」。
 */
class ReminderRepeatTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int, day: Int = 6): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `一次性的没有下一次`() {
        assertEquals(0L, ReminderRepeat.next(at(8, 0), Reminder.Repeat.NONE, zone))
    }

    @Test
    fun `每天就是加一天，时刻不变`() {
        val next = ReminderRepeat.next(at(8, 0), Reminder.Repeat.DAILY, zone)
        assertEquals(at(8, 0, day = 7), next)
    }

    @Test
    fun `每周就是加七天`() {
        val next = ReminderRepeat.next(at(8, 0), Reminder.Repeat.WEEKLY, zone)
        assertEquals(at(8, 0, day = 13), next)
    }

    @Test
    fun `积累了好几天的重复提醒会一次推到未来`() {
        // 触发时刻是三天前，「每天」的下一次必须跳过这三天
        val now = at(9, 0, day = 9)
        val next = ReminderRepeat.nextAfter(at(8, 0, day = 6), Reminder.Repeat.DAILY, now, zone)

        assertTrue("必须在 now 之后，实际：$next vs $now", next > now)
        assertEquals("时刻仍应当是 8:00", 8, instantHour(next))
    }

    @Test
    fun `触发之后至少往前推一天，不会原地重排`() {
        // 触发时刻比 now 还晚一点点（闹钟提前唤醒 / 时钟微调）时，
        // 也必须推到下一天：否则刚响完就又被排上，会连环响。
        val trigger = at(8, 0, day = 7)
        val now = at(9, 0, day = 6)

        assertEquals(at(8, 0, day = 8), ReminderRepeat.nextAfter(trigger, Reminder.Repeat.DAILY, now, zone))
    }

    private fun instantHour(at: Long): Int =
        java.time.Instant.ofEpochMilli(at).atZone(zone).hour
}
