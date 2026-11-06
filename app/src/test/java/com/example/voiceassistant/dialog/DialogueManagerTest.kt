package com.example.voiceassistant.dialog

import com.example.voiceassistant.fuzzy.FuzzyEngine
import com.example.voiceassistant.reminder.Reminder
import com.example.voiceassistant.reminder.ReminderPort
import com.example.voiceassistant.tool.ReminderTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 多轮上下文的测试：**槽位补全、指代消解、超时失效**。
 *
 * 这三件事是「像人一样对话」和「一条条下命令」的分界线，
 * 也是课程要求里最容易只写个样子、实际跑不通的部分。
 * 所以这里用假存储 + 假时钟把整个多轮过程跑一遍：
 * 两个字段（提醒内容、提醒时刻）分别来自两句话，中间还隔着一次追问。
 */
class DialogueManagerTest {

    /** 假存储：行为跟真库一致（状态过滤、按时间排序），但不碰 Android */
    private class FakePort(private val clock: () -> Long) : ReminderPort {

        val items = mutableListOf<Reminder>()
        private var seq = 0

        override fun create(
            content: String,
            at: Long,
            repeat: Reminder.Repeat,
            ctxId: String?
        ): Reminder {
            val created = Reminder(
                id = ++seq,
                content = content,
                triggerAt = at,
                createdAt = clock(),
                repeat = repeat,
                status = Reminder.Status.PENDING,
                ctxId = ctxId
            )
            items += created
            return created
        }

        override fun updateTime(id: Int, at: Long): Reminder? {
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            items[index] = items[index].copy(triggerAt = at, status = Reminder.Status.PENDING)
            return items[index]
        }

        override fun snooze(id: Int, minutes: Long): Reminder? {
            // 与真实现同一套语义：从原时间往后挪，原时间已过才从现在算
            val current = items.firstOrNull { it.id == id } ?: return null
            val base = maxOf(current.triggerAt, clock())
            return updateTime(id, base + minutes * 60_000L)
        }

        override fun cancel(id: Int): Boolean {
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return false
            items[index] = items[index].copy(status = Reminder.Status.CANCELED)
            return true
        }

        override fun cancelAll(): Int {
            val count = list().size
            for (i in items.indices) items[i] = items[i].copy(status = Reminder.Status.CANCELED)
            return count
        }

        override fun list(): List<Reminder> = items
            .filter { it.status == Reminder.Status.PENDING }
            .sortedBy { it.triggerAt }
    }

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private var now: Long = LocalDateTime.of(2026, 11, 5, 9, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private val engine = FuzzyEngine(clock = { now }, zone = zone)
    private lateinit var port: FakePort
    private lateinit var manager: DialogueManager

    @Before
    fun setUp() {
        now = LocalDateTime.of(2026, 11, 5, 9, 0).atZone(zone).toInstant().toEpochMilli()
        port = FakePort { now }
        manager = DialogueManager(port, clock = { now }, zone = zone)
    }

    /** 走一遍「识别 → 对话处理」，跟真实链路的顺序一致 */
    private fun say(text: String): DialogueManager.Outcome =
        manager.handle(text, engine.recognize(text, now))

    private fun askText(outcome: DialogueManager.Outcome): String {
        assertTrue("这一轮应当追问，实际：$outcome", outcome is DialogueManager.Outcome.Ask)
        return (outcome as DialogueManager.Outcome.Ask).text
    }

    private fun replyText(outcome: DialogueManager.Outcome): String {
        assertTrue("这一轮应当直接回答，实际：$outcome", outcome is DialogueManager.Outcome.Handled)
        return (outcome as DialogueManager.Outcome.Handled).text
    }

    // ------------------------------------------------------------------ 槽位补全

    @Test
    fun `缺时间会反问，下一轮只补一句时间就能落地`() {
        val first = askText(say("提醒我交作业"))
        assertTrue("要反问几点，实际：$first", first.contains("几点"))
        assertTrue("这时候还不该建提醒", port.items.isEmpty())

        val second = replyText(say("三点"))
        assertTrue("要确认时间和内容，实际：$second", second.contains("交作业"))

        val created = port.list().single()
        assertEquals("交作业", created.content)
        assertEquals("明天 03:00", ReminderTime.describe(created.triggerAt, now))
    }

    @Test
    fun `补时间的那句话里带日期也认`() {
        say("提醒我交作业")
        replyText(say("明天下午三点"))
        assertEquals(
            "明天 15:00",
            ReminderTime.describe(port.list().single().triggerAt, now)
        )
    }

    @Test
    fun `问两次还答不上来就放弃，不把用户困住`() {
        askText(say("提醒我交作业"))
        askText(say("嗯"))
        val giveUp = replyText(say("随便"))

        assertTrue("要说明放弃了，实际：$giveUp", giveUp.contains("先不设了"))
        assertTrue("放弃时不许偷偷建提醒", port.items.isEmpty())
    }

    // ------------------------------------------------------------------ 指代消解

    @Test
    fun `改成四点会改到刚才那条提醒上`() {
        replyText(say("提醒我明天八点交作业"))
        val id = port.list().single().id

        val reply = replyText(say("改成四点"))
        assertTrue("要说出改成了几点，实际：$reply", reply.contains("04:00"))

        val updated = port.list().single()
        assertEquals("改的必须还是那一条", id, updated.id)
        assertEquals("明天 04:00", ReminderTime.describe(updated.triggerAt, now))
        assertEquals("内容不能被改没", "交作业", updated.content)
    }

    @Test
    fun `推迟一小时是按原时间往后挪，不是从现在算`() {
        replyText(say("提醒我明天八点交作业"))
        val before = port.list().single().triggerAt

        replyText(say("推迟一小时"))

        assertEquals(
            "要按原时间 +1 小时",
            before + 60 * 60 * 1000L,
            port.list().single().triggerAt
        )
    }

    @Test
    fun `上下文过期之后不再瞎猜指代`() {
        replyText(say("提醒我明天八点交作业"))
        replyText(say("提醒我明天九点开会"))
        assertEquals(2, port.list().size)

        // 过了五分钟：会话超时（默认 120 秒），「改成四点」不再知道指哪条
        now += 300_000L
        val outcome = say("改成四点")

        assertTrue("应当反问改哪一条，实际：$outcome", outcome is DialogueManager.Outcome.Ask)
        assertTrue(askText(outcome).contains("哪一条"))
    }

    @Test
    fun `取消提醒会删掉刚才那条`() {
        replyText(say("提醒我明天八点交作业"))
        val reply = replyText(say("取消提醒"))

        assertTrue("要说清取消了什么，实际：$reply", reply.contains("交作业"))
        assertTrue("清单里不该还有", port.list().isEmpty())
    }

    // ------------------------------------------------------------------ 其它意图

    @Test
    fun `查看清单会说清有哪几条`() {
        assertTrue(replyText(say("我的提醒")).contains("没有提醒"))

        replyText(say("提醒我明天八点交作业"))
        val listing = replyText(say("我的提醒"))

        assertTrue("要列出内容，实际：$listing", listing.contains("交作业"))
        assertTrue("要带序号", listing.contains("1."))
    }

    @Test
    fun `每天的提醒会记住重复规则`() {
        replyText(say("每天八点提醒我吃药"))

        val created = port.list().single()
        assertEquals(Reminder.Repeat.DAILY, created.repeat)
        assertEquals("吃药", created.content)
        assertEquals("明天 08:00", ReminderTime.describe(created.triggerAt, now))
    }

    @Test
    fun `再提醒一次会把目标往后挪`() {
        replyText(say("提醒我明天八点交作业"))
        val before = port.list().single().triggerAt

        replyText(say("再提醒我一次"))

        assertEquals(
            before + DialogueManager.SNOOZE_MINUTES * 60_000L,
            port.list().single().triggerAt
        )
    }

    @Test
    fun `不是提醒域的话不归它管`() {
        val outcome = say("今天天气怎么样")
        assertEquals(DialogueManager.Outcome.NotMine, outcome)
        assertTrue(port.items.isEmpty())
    }
}
