package com.example.voiceassistant.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 时间解析的测试：**只认固定时刻，不看当前时间**。
 *
 * 基准时刻定在 2026-11-05 09:00（周四），
 * 这样「明天八点」「今天八点」这类断言的期望值是确定的。
 */
class ReminderTimeTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-05 09:00 */
    private val now: Long = LocalDateTime.of(2026, 11, 5, 9, 0)
        .atZone(zone).toInstant().toEpochMilli()

    private fun at(month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, month, day, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private fun resolve(raw: String) = ReminderTime.resolve(raw, now, zone)

    // ------------------------------------------------------------------ 相对时长

    @Test
    fun `十分钟后`() {
        assertEquals(now + 10 * 60_000L, resolve("10 分钟后")!!.at)
    }

    @Test
    fun `过半小时`() {
        assertEquals(now + 30 * 60_000L, resolve("过半小时")!!.at)
        assertEquals("30 分钟后", resolve("过半小时")!!.label)
    }

    @Test
    fun `两个小时后`() {
        assertEquals(now + 2 * 60 * 60_000L, resolve("两个小时后")!!.at)
        assertEquals("2 小时后", resolve("两个小时后")!!.label)
    }

    @Test
    fun `一小时半的零头会单独说出来`() {
        assertEquals("1 小时 30 分钟后", resolve("90 分钟后")!!.label)
    }

    @Test
    fun `裸时长也认`() {
        // 「计时 5 分钟」这种没有「后」字的说法
        assertEquals(now + 5 * 60_000L, ReminderTime.duration("计时五分钟", now)!!.at)
    }

    // ------------------------------------------------------------------ 哪天 + 几点

    @Test
    fun `明天八点`() {
        assertEquals(at(11, 6, 8, 0), resolve("明天八点交报告")!!.at)
        assertEquals("明天 08:00", resolve("明天八点交报告")!!.label)
    }

    @Test
    fun `今天八点但已经过了，要顺延到明天`() {
        // 现在是 09:00，说「今天 8 点」只能是明天，否则闹钟立刻响
        assertEquals(at(11, 6, 8, 0), resolve("今天8点")!!.at)
        assertEquals("明天 08:00", resolve("今天8点")!!.label)
    }

    @Test
    fun `今晚八点半`() {
        assertEquals(at(11, 5, 20, 30), resolve("今晚八点半")!!.at)
        assertEquals("今天 20:30", resolve("今晚八点半")!!.label)
    }

    @Test
    fun `下午三点要加十二小时`() {
        assertEquals(at(11, 5, 15, 0), resolve("下午3点")!!.at)
    }

    @Test
    fun `晚上十二点要落回零点`() {
        // 20 点 + 12 是错的；「晚上 12 点」是午夜
        assertEquals(at(11, 6, 0, 0), resolve("晚上12点")!!.at)
        assertEquals("明天 00:00", resolve("晚上12点")!!.label)
    }

    @Test
    fun `中午十二点不加十二小时`() {
        assertEquals(at(11, 5, 12, 0), resolve("中午12点")!!.at)
    }

    @Test
    fun `后天中午`() {
        assertEquals(at(11, 7, 12, 0), resolve("后天中午12点")!!.at)
        assertEquals("后天 12:00", resolve("后天中午12点")!!.label)
    }

    @Test
    fun `具体日期加时刻`() {
        assertEquals(at(11, 10, 8, 0), resolve("11月10号8点")!!.at)
    }

    @Test
    fun `明说日期时不带时刻默认早上九点`() {
        // 11-05 的「后天」是 11-07
        assertEquals(at(11, 7, 9, 0), resolve("后天")!!.at)
    }

    @Test
    fun `只给了时刻就取最近的一次`() {
        assertEquals(at(11, 5, 15, 0), resolve("15点")!!.at)
        // 8 点已经过了 → 明天
        assertEquals(at(11, 6, 8, 0), resolve("8点")!!.at)
    }

    // ------------------------------------------------------------------ 认不出来

    @Test
    fun `没有时间信息返回空`() {
        assertNull(resolve("提醒我交报告"))
        assertNull(resolve("讲个笑话"))
        assertNull(resolve(""))
    }

    @Test
    fun `非法日期不硬凑`() {
        assertNull(resolve("13月40号8点"))
    }

    // ------------------------------------------------------------------ 剥时间

    @Test
    fun `把时间从正文里剥掉`() {
        assertEquals("交报告", ReminderTime.stripTime("明天八点交报告"))
        assertEquals("喝水", ReminderTime.stripTime("10 分钟后喝水"))
        assertEquals("开会", ReminderTime.stripTime("下午3点开会"))
    }

    @Test
    fun `剥完为空时调用方要能兜住`() {
        assertTrue(ReminderTime.stripTime("明天八点").isEmpty())
    }

    // ------------------------------------------------------------------ describe

    @Test
    fun `describe 说人话`() {
        assertEquals("明天 08:00", ReminderTime.describe(at(11, 6, 8, 0), now, zone))
        assertEquals("今天 20:30", ReminderTime.describe(at(11, 5, 20, 30), now, zone))
        assertTrue(ReminderTime.describe(now - 60_000L, now, zone).startsWith("已过期"))
    }
}
