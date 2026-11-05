package com.example.voiceassistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播报看门狗的时长估算。
 *
 * 目的不是算得准，而是保证「绝不可能提前掐断」+「不会无限等」。
 */
class TtsTimingTest {

    @Test
    fun `短句至少留出启动余量`() {
        assertEquals(4_000L, TtsTiming.watchdogMs(0))
        assertEquals(4_000L, TtsTiming.watchdogMs(-5))
        assertEquals(4_240L, TtsTiming.watchdogMs(1))
    }

    @Test
    fun `时长随字数线性增长`() {
        val ten = TtsTiming.watchdogMs(10)
        val twenty = TtsTiming.watchdogMs(20)
        assertEquals(4_000L + 10 * 240L, ten)
        assertEquals(4_000L + 20 * 240L, twenty)
    }

    @Test
    fun `常见回复长度给出足够宽松的上限`() {
        // 一句 60 字的回复按每秒 5 字念完约 12 秒，上限应明显大于它
        assertTrue(TtsTiming.watchdogMs(60) > 12_000L)
    }

    @Test
    fun `超长文本封顶两分钟`() {
        assertEquals(120_000L, TtsTiming.watchdogMs(1_000))
        assertEquals(120_000L, TtsTiming.watchdogMs(100_000))
    }

    @Test
    fun `时长单调不降`() {
        var previous = TtsTiming.watchdogMs(0)
        for (length in 1..300) {
            val current = TtsTiming.watchdogMs(length)
            assertTrue("长度 $length 的估时不该变短", current >= previous)
            previous = current
        }
    }
}
