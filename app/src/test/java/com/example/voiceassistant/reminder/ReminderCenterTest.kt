package com.example.voiceassistant.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒中心的测试：**状态怎么流转、闹钟该不该重排**。
 *
 * 存储和闹钟都换成假的，所以这里可以在几毫秒内把
 * 「建 → 响 → 重排 → 再响 → 取消」整条生命周期跑完，
 * 不用等真的到点，也不会碰 AlarmManager。
 */
class ReminderCenterTest {

    private class FakeRepo : ReminderRepository {
        val rows = mutableListOf<Reminder>()
        private var seq = 0

        override fun all(): List<Reminder> = rows.toList()

        override fun insert(reminder: Reminder): Reminder {
            val saved = reminder.copy(id = ++seq)
            rows += saved
            return saved
        }

        override fun update(reminder: Reminder): Boolean {
            val index = rows.indexOfFirst { it.id == reminder.id }
            if (index < 0) return false
            rows[index] = reminder
            return true
        }

        override fun delete(id: Int): Boolean = rows.removeAll { it.id == id }

        override fun deleteAll() {
            rows.clear()
        }

        override fun pruneTriggered(before: Long): Int {
            val targets = rows.filter {
                it.status == Reminder.Status.TRIGGERED && it.triggerAt < before
            }
            rows.removeAll(targets)
            return targets.size
        }
    }

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private var now: Long = LocalDateTime.of(2026, 11, 5, 9, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private lateinit var repo: FakeRepo
    private lateinit var center: ReminderCenter
    private lateinit var scheduled: MutableList<Int>
    private lateinit var canceled: MutableList<Int>

    @Before
    fun setUp() {
        now = LocalDateTime.of(2026, 11, 5, 9, 0).atZone(zone).toInstant().toEpochMilli()
        repo = FakeRepo()
        scheduled = mutableListOf()
        canceled = mutableListOf()
        center = ReminderCenter(
            repo = repo,
            schedule = { scheduled += it.id },
            cancelAlarm = { canceled += it },
            clock = { now },
            zone = zone
        )
    }

    @Test
    fun `新建的提醒会立刻排上闹钟`() {
        val created = center.create("交报告", now + 60_000L)

        assertEquals(listOf(created.id), scheduled)
        assertEquals(1, center.list().size)
    }

    @Test
    fun `没说时间的提醒不排闹钟`() {
        val created = center.create("记一下", 0L)

        assertTrue("没有时间就不该占闹钟", scheduled.isEmpty())
        assertEquals("但清单里要有", 1, center.list().size)
        assertEquals(0L, created.triggerAt)
    }

    @Test
    fun `取消会同时撤闹钟并从清单里消失`() {
        val created = center.create("交报告", now + 60_000L)

        assertTrue(center.cancel(created.id))

        assertTrue(canceled.contains(created.id))
        assertTrue(center.list().isEmpty())
    }

    @Test
    fun `一次性的提醒响过之后变成已触发`() {
        val created = center.create("交报告", now + 60_000L)

        val next = center.fire(created.id)

        assertNull("一次性没有下一次", next)
        assertTrue("清单里不该再出现", center.list().isEmpty())
        assertEquals(
            Reminder.Status.TRIGGERED,
            repo.all().single { it.id == created.id }.status
        )
    }

    @Test
    fun `每天的提醒响过之后自动排下一天`() {
        val created = center.create("吃药", now + 60_000L, Reminder.Repeat.DAILY)

        val next = center.fire(created.id)

        assertEquals(
            "下一次应当是原时间 +1 天",
            created.triggerAt + 24 * 60 * 60 * 1000L,
            next?.triggerAt ?: 0L
        )
        assertTrue("必须重排闹钟，否则明天不会响", scheduled.count { it == created.id } >= 2)
        assertEquals("仍然在清单里", 1, center.list().size)
        assertEquals(Reminder.Status.PENDING, center.list().single().status)
    }

    @Test
    fun `关机一整天之后每天的提醒仍指向未来`() {
        // 模拟「手机睡了两天才醒」：触发时刻在过去，重排必须推到 now 之后
        now += 3L * 24 * 60 * 60 * 1000
        val created = center.create("吃药", now - 2L * 24 * 60 * 60 * 1000, Reminder.Repeat.DAILY)

        val next = center.fire(created.id)

        assertTrue("下一次必须在未来，实际：${next?.triggerAt}", (next?.triggerAt ?: 0L) > now)
    }

    @Test
    fun `稍后提醒从原时间往后挪`() {
        val created = center.create("交报告", now + 60_000L)

        val snoozed = center.snooze(created.id, 10)

        assertEquals(now + 60_000L + 10 * 60_000L, snoozed?.triggerAt ?: 0L)
    }

    @Test
    fun `已经响过的提醒再提醒就从现在算`() {
        // 通知上点「10 分钟后再提醒」时，原时间已经过去了
        val created = center.create("交报告", now - 60_000L)

        val snoozed = center.snooze(created.id, 10)

        assertEquals(now + 10 * 60_000L, snoozed?.triggerAt ?: 0L)
    }

    @Test
    fun `清空返回清掉几条`() {
        center.create("一", now + 60_000L)
        center.create("二", now + 120_000L)

        assertEquals(2, center.cancelAll())
        assertTrue(center.list().isEmpty())
    }

    @Test
    fun `清单按时间排序，没时间的排最后`() {
        center.create("没时间", 0L)
        center.create("晚一点", now + 120_000L)
        center.create("先响", now + 60_000L)

        val order = center.list().map { it.content }
        assertEquals(listOf("先响", "晚一点", "没时间"), order)
    }

    @Test
    fun `建了就能查到，取消之后就没了`() {
        // v4.0 起只有一条写入通道（AiTools 走 ReminderPort），不会有两份存储
        val added = center.create("喝水", now + 60_000L)

        assertEquals(1, center.list().size)
        assertEquals("喝水", added.content)
        assertTrue(center.cancel(added.id))
        assertTrue(center.list().isEmpty())
    }
}
