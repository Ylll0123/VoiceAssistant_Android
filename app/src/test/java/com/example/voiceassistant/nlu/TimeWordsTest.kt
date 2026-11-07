package com.example.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 中文时间解析测试。
 *
 * 基准时间钉在 **2026-11-03 10:00（周二）**，所有期望值都相对它算，
 * 免得单测在半夜跑一遍、白天跑一遍结论不同。
 */
class TimeWordsTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)

    private fun parse(text: String): LocalDateTime? = TimeWords.parse(text, now)

    // ------------------------------------------------------------ 绝对时间点

    @Test
    fun `只有几点没说日期，过了点就顺延到明天`() {
        // 上午十点说「八点」，指的是明早八点
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), parse("八点"))
        // 还没到的点就是今天
        assertEquals(LocalDateTime.of(2026, 11, 3, 15, 0), parse("下午三点"))
    }

    @Test
    fun `时段词会把小时换到 24 小时制`() {
        assertEquals(LocalDateTime.of(2026, 11, 3, 20, 0), parse("晚上八点"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 14, 0), parse("下午两点"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 15, 15), parse("下午三点一刻"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 19, 30), parse("晚上七点半"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 12, 0), parse("中午十二点"))
    }

    @Test
    fun `冒号写法最优先`() {
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 30), parse("8:30"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 18, 5), parse("18:05"))
    }

    @Test
    fun `明天后天大后天都对`() {
        assertEquals(LocalDateTime.of(2026, 11, 4, 7, 0), parse("明天早上七点"))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), parse("后天九点"))
        assertEquals(LocalDateTime.of(2026, 11, 6, 9, 0), parse("大后天九点"))
    }

    @Test
    fun `只说时段也给一个合理整点`() {
        assertEquals(LocalDateTime.of(2026, 11, 5, 12, 0), parse("后天中午"))
        assertEquals(LocalDateTime.of(2026, 11, 4, 8, 0), parse("明早"))
        assertEquals(LocalDateTime.of(2026, 11, 3, 20, 0), parse("今晚"))
    }

    @Test
    fun `星期几按最近的未来算`() {
        // 2026-11-03 是周二，所以「周三」是明天
        assertEquals(LocalDateTime.of(2026, 11, 4, 14, 0), parse("周三下午两点"))
        // 「周二」是下周二，不是今天
        assertEquals(LocalDateTime.of(2026, 11, 10, 9, 0), parse("星期二早上九点"))
    }

    @Test
    fun `几月几号能认，过了就算明年`() {
        assertEquals(LocalDateTime.of(2026, 11, 8, 9, 0), parse("11月8号9点"))
        // 11 月说「1 月 5 号」，只能是明年
        assertEquals(LocalDateTime.of(2027, 1, 5, 9, 0), parse("1月5日9点"))
    }

    @Test
    fun `说不清就返回空，交给大模型`() {
        assertNull(parse(""))
        assertNull(parse("晚一点"))
        assertNull(parse("快下班的时候"))
        assertNull(parse("提醒我买菜"))
    }

    // ------------------------------------------------------------ 相对时长

    @Test
    fun `分钟后小时后秒后`() {
        assertEquals(10 * 60_000L, TimeWords.durationMillis("10分钟后提醒我"))
        assertEquals(10 * 60_000L, TimeWords.durationMillis("十分钟后"))
        assertEquals(3 * 60L * 60_000L, TimeWords.durationMillis("3小时后"))
        assertEquals(30_000L, TimeWords.durationMillis("30秒后"))
    }

    @Test
    fun `半小时和一个半小时`() {
        assertEquals(30L * 60_000L, TimeWords.durationMillis("半小时后"))
        // 「一个半」必须排在「半小时」前面判，否则会被吃掉半截
        assertEquals(90L * 60_000L, TimeWords.durationMillis("一个半小时后"))
        assertEquals(90L * 60_000L, TimeWords.durationMillis("1个半小时后"))
    }

    @Test
    fun `没有时长就返回空`() {
        assertNull(TimeWords.durationMillis("提醒我买菜"))
        assertNull(TimeWords.durationMillis("随便"))
    }

    // ------------------------------------------------------------ 辅助

    @Test
    fun `中文数字转换`() {
        assertEquals(8, TimeWords.chineseNumber("八"))
        assertEquals(10, TimeWords.chineseNumber("十"))
        assertEquals(11, TimeWords.chineseNumber("十一"))
        assertEquals(23, TimeWords.chineseNumber("二十三"))
        assertEquals(2, TimeWords.chineseNumber("两"))
        assertEquals(12, TimeWords.chineseNumber("12"))
        assertNull(TimeWords.chineseNumber("很多"))
    }

    @Test
    fun `日期词判断`() {
        assertTrue(TimeWords.hasDateWord("明天七点"))
        assertTrue(TimeWords.hasDateWord("周三"))
        assertTrue(TimeWords.hasDateWord("11月8号"))
        assertTrue(!TimeWords.hasDateWord("八点"))
    }

    @Test
    fun `念得出来的描述`() {
        assertEquals("今天 20:00", TimeWords.describe(LocalDateTime.of(2026, 11, 3, 20, 0), now))
        assertEquals("明天 08:00", TimeWords.describe(LocalDateTime.of(2026, 11, 4, 8, 0), now))
        assertEquals("后天 08:00", TimeWords.describe(LocalDateTime.of(2026, 11, 5, 8, 0), now))
        assertEquals("11 月 20 日 08:00", TimeWords.describe(LocalDateTime.of(2026, 11, 20, 8, 0), now))
    }
}
