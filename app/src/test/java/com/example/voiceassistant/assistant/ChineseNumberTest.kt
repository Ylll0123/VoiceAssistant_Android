package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 中文数字转换：语音说出来的算术题要先翻成阿拉伯数字，
 * 本地能算的就不该去问云端（这是省 token 最直接的一处）。
 */
class ChineseNumberTest {

    @Test
    fun `基础数字`() {
        assertEquals(0L, ChineseNumber.parse("零"))
        assertEquals(1L, ChineseNumber.parse("一"))
        assertEquals(9L, ChineseNumber.parse("九"))
        assertEquals(2L, ChineseNumber.parse("两"))
    }

    @Test
    fun `十几和几十`() {
        assertEquals(10L, ChineseNumber.parse("十"))
        assertEquals(12L, ChineseNumber.parse("十二"))
        assertEquals(20L, ChineseNumber.parse("二十"))
        assertEquals(35L, ChineseNumber.parse("三十五"))
        assertEquals(99L, ChineseNumber.parse("九十九"))
    }

    @Test
    fun `百 千 万 亿`() {
        assertEquals(100L, ChineseNumber.parse("一百"))
        assertEquals(120L, ChineseNumber.parse("一百二十"))
        assertEquals(3500L, ChineseNumber.parse("三千五百"))
        assertEquals(35_000L, ChineseNumber.parse("三万五千"))
        assertEquals(100_000_000L, ChineseNumber.parse("一亿"))
    }

    @Test
    fun `不是数字的东西不认`() {
        assertNull(ChineseNumber.parse(""))
        assertNull(ChineseNumber.parse("苹果"))
        assertNull(ChineseNumber.parse("十a"))
    }

    @Test
    fun `挨着运算符的单个数字字也要换成数字`() {
        assertEquals("12 * 8", ChineseNumber.replaceIn("十二 * 八", single = true, near = setOf('*')))
        assertEquals("12 + 8 是多少", ChineseNumber.replaceIn("十二 + 八 是多少", single = true, near = setOf('+')))
        // 「帮我算一下」里的「一」不挨着运算符，不能变成 1
        assertEquals("帮我算一下 12 * 8", ChineseNumber.replaceIn("帮我算一下 十二 * 八", single = true, near = setOf('*')))
    }

    @Test
    fun `单个数字字留在原句里不动，免得破坏普通句子`() {
        // 「一起」「十分」这种不是数字串，不能乱改
        assertEquals("一起走", ChineseNumber.replaceIn("一起走"))
        assertEquals("十分好", ChineseNumber.replaceIn("十分好"))
    }

    @Test
    fun `算术规则现在算得出中文数字题`() {
        val engine = AssistantEngine()
        val reply = engine.reply("帮我算一下十二乘以八")
        assertEquals("arithmetic", engine.respond("帮我算一下十二乘以八").intent)
        assertEquals("12*8 = 96", reply)
    }
}
