package com.example.voiceassistant.reminder

import java.time.Instant
import java.time.ZoneId

/**
 * 重复规则的下一次触发时刻（纯函数，方便单测）。
 *
 * ## 两个必须踩对的点
 * 1. **按日历加，不按毫秒加**。一天不是恒定的 86 400 000 毫秒：
 *    夏令时切换那天是 23 或 25 小时。用 `LocalDateTime.plusDays(1)`
 *    让「每天 8 点」永远是当地 8 点，而不是偶尔变成 7 点或 9 点。
 * 2. **循环推到未来**。手机可能睡了一整天（关机 / 没电 / 被系统冻结），
 *    醒来时「每天」的下一次可能还在过去。只加一次是不够的，
 *    要一直推到 now 之后，否则闹钟一触发就立刻再触发一次。
 */
object ReminderRepeat {

    /** 单次提醒没有下一次 */
    fun next(triggerAt: Long, repeat: Reminder.Repeat, zone: ZoneId = ZoneId.systemDefault()): Long =
        when (repeat) {
            Reminder.Repeat.NONE -> 0L
            Reminder.Repeat.DAILY -> plusDays(triggerAt, 1, zone)
            Reminder.Repeat.WEEKLY -> plusDays(triggerAt, 7, zone)
        }

    /**
     * 推到 [now] 之后的下一次。
     *
     * @return 单次提醒返回 [triggerAt]（不变）；重复提醒返回 > now 的时刻
     */
    fun nextAfter(
        triggerAt: Long,
        repeat: Reminder.Repeat,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        if (repeat == Reminder.Repeat.NONE) return triggerAt
        var next = next(triggerAt, repeat, zone)
        // 最多推 400 次（一年出头）——防止脏数据让这里变成死循环
        var guard = 0
        while (next in 1..now && guard++ < MAX_ADVANCE) {
            next = next(next, repeat, zone)
        }
        return next
    }

    private fun plusDays(at: Long, days: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()
            .plusDays(days)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private const val MAX_ADVANCE = 400
}
