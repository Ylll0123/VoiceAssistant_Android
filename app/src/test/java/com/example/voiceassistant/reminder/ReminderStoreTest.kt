package com.example.voiceassistant.reminder

import com.example.voiceassistant.tool.ReminderTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒持久化的测试：**只测编解码，不碰 SharedPreferences**。
 *
 * 存盘这件事最怕的是「写进去读不出来」——用户设的提醒在一句话里
 * 带了个引号，整份清单就废了。所以这里把转义、坏数据、空表都钉死。
 */
class ReminderStoreTest {

    private fun reminder(id: Int, text: String, at: Long = 0L) =
        ReminderTool.Reminder(id, text, at, 1_700_000_000_000L)

    @Test
    fun `普通清单能原样读回来`() {
        val items = listOf(
            reminder(1, "交报告", 1_800_000_000_000L),
            reminder(2, "喝水")
        )
        val decoded = ReminderStore.decode(ReminderStore.encode(items))
        assertEquals(items, decoded)
    }

    @Test
    fun `引号反斜杠换行不会写坏整份清单`() {
        val tricky = "他说\"明天\"\\后天\n交报告"
        val decoded = ReminderStore.decode(ReminderStore.encode(listOf(reminder(7, tricky))))
        assertEquals(tricky, decoded.single().text)
    }

    @Test
    fun `空表编成空数组再读回来还是空`() {
        assertEquals("[]", ReminderStore.encode(emptyList()))
        assertTrue(ReminderStore.decode("[]").isEmpty())
    }

    @Test
    fun `坏数据退化成空表而不是崩溃`() {
        assertTrue(ReminderStore.decode("").isEmpty())
        assertTrue(ReminderStore.decode("不是 JSON").isEmpty())
        assertTrue(ReminderStore.decode("{\"a\":1}").isEmpty())
    }

    @Test
    fun `缺字段或字段为空的条目会被跳过`() {
        val raw = """[{"id":1,"text":"正常","at":0,"created":0},""" +
            """{"id":2,"text":"","at":0,"created":0},""" +
            """{"text":"没有 id"}]"""
        val decoded = ReminderStore.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("正常", decoded.single().text)
    }

    @Test
    fun `没有触发时刻的条目不算排了闹钟`() {
        val item = ReminderStore.decode(ReminderStore.encode(listOf(reminder(1, "记一下")))).single()
        assertEquals(0L, item.triggerAt)
        assertTrue("没时间不该被当成已排闹钟", !item.scheduled)
    }
}
