package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模糊匹配：归一化 + 编辑距离容错 */
class FuzzyMatcherTest {

    @Test
    fun `归一化会去掉标点空格并剥离客套前缀`() {
        assertEquals("现在几点了", FuzzyMatcher.normalize("请问，现在几点了？"))
        assertEquals("12*8", FuzzyMatcher.normalize("帮我查 12 * 8"))
    }

    @Test
    fun `精确包含直接命中`() {
        assertTrue(FuzzyMatcher.containsFuzzy("现在几点了", "几点"))
    }

    @Test
    fun `三个字以上的关键词允许一个字识别错误`() {
        // 「讲个笑话」被识别成「讲个笑化」
        assertTrue(FuzzyMatcher.containsFuzzy("讲个笑化吧", "讲个笑话"))
        assertTrue(FuzzyMatcher.containsFuzzy("我们玩猜数子", "猜数字"))
    }

    @Test
    fun `两个字的关键词不做容错避免误命中`() {
        assertFalse(FuzzyMatcher.containsFuzzy("今天几天了", "几点"))
    }

    @Test
    fun `编辑距离计算正确`() {
        assertEquals(3, FuzzyMatcher.levenshtein("kitten", "sitting"))
        assertEquals(0, FuzzyMatcher.levenshtein("笑话", "笑话"))
        assertEquals(1, FuzzyMatcher.levenshtein("猜数子", "猜数字"))
    }
}
