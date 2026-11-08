package com.example.voiceassistant.kws

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒词匹配。
 *
 * 「开关开着却叫不醒」曾经的主因是**默认就要求说全**：
 * 中文 ASR 对叠词尾巴很不稳，「小豆小豆」常常只回来「小豆」，
 * 严格匹配等于永远匹配不上。这里把默认宽松钉住。
 */
class WakeWordTest {

    @After
    fun restore() {
        WakeConfig.sensitivity = 60
    }

    @Test
    fun `默认灵敏度是宽松的 说前两个字就算`() {
        WakeConfig.sensitivity = 60
        assertFalse("默认不该要求说全", WakeConfig.strict)
        assertTrue(WakeWord.matches("小豆小豆", WakeWord.DEFAULT, WakeConfig.strict))
        assertTrue(WakeWord.matches("小豆小", WakeWord.DEFAULT, WakeConfig.strict))
        assertTrue(WakeWord.matches("小豆", WakeWord.DEFAULT, WakeConfig.strict))
    }

    @Test
    fun `灵敏度很低时才要求说全`() {
        WakeConfig.sensitivity = 10
        assertTrue(WakeConfig.strict)
        assertTrue(WakeWord.matches("小豆小豆", WakeWord.DEFAULT, WakeConfig.strict))
        assertFalse("严格模式下半截不算", WakeWord.matches("小豆", WakeWord.DEFAULT, WakeConfig.strict))
    }

    @Test
    fun `标点和空格不影响匹配`() {
        assertTrue(WakeWord.matches("小豆，小豆。", WakeWord.DEFAULT, strict = true))
        assertTrue(WakeWord.matches("小 豆 小 豆", WakeWord.DEFAULT, strict = true))
    }

    @Test
    fun `别的话不会误唤醒`() {
        assertFalse(WakeWord.matches("今天天气不错", WakeWord.DEFAULT))
        assertFalse(WakeWord.matches("帮我打开手电筒", WakeWord.DEFAULT))
        assertFalse(WakeWord.matches("", WakeWord.DEFAULT))
    }

    @Test
    fun `剥掉唤醒词剩下的才是指令`() {
        assertTrue(WakeWord.strip("小豆小豆，把音量调小") == "把音量调小")
        assertTrue(WakeWord.strip("小豆，现在几点") == "现在几点")
    }

    // ------------------------------------------------------------------ 灵敏度三档

    @Test
    fun `三档各自对应一个灵敏度数值`() {
        assertEquals(20, WakeWord.sensitivityOfLevel(0))
        assertEquals(55, WakeWord.sensitivityOfLevel(1))
        assertEquals(85, WakeWord.sensitivityOfLevel(2))
        // 越界落到中档，不要因为读到一个脏值就把用户扔进严格模式
        assertEquals(55, WakeWord.sensitivityOfLevel(-1))
        assertEquals(55, WakeWord.sensitivityOfLevel(9))
    }

    @Test
    fun `只有低灵敏要求说全唤醒词`() {
        assertTrue(WakeWord.sensitivityOfLevel(0) < WakeWord.LOW)
        assertFalse(WakeWord.sensitivityOfLevel(1) < WakeWord.LOW)
        assertFalse(WakeWord.sensitivityOfLevel(2) < WakeWord.LOW)
    }

    @Test
    fun `高灵敏只是循环更密 匹配规则和中档一样宽`() {
        // 高灵敏不能变成「听一个字就算」：那会把电视里的声音全收进来
        val mid = WakeWord.sensitivityOfLevel(1)
        val high = WakeWord.sensitivityOfLevel(2)
        assertFalse("高灵敏也得至少对上两个字", high < WakeWord.LOW)
        assertTrue("高灵敏的循环要比中档更密", WakeWord.quietRetryMs(high) < WakeWord.quietRetryMs(mid))
        assertTrue("高灵敏的静音判定要更短", WakeWord.silenceMs(high) < WakeWord.silenceMs(mid))
    }

    @Test
    fun `低灵敏最松的节奏 最不容易误唤醒`() {
        val low = WakeWord.sensitivityOfLevel(0)
        val mid = WakeWord.sensitivityOfLevel(1)
        assertTrue(WakeWord.quietRetryMs(low) > WakeWord.quietRetryMs(mid))
        assertTrue(WakeWord.silenceMs(low) > WakeWord.silenceMs(mid))
    }

    @Test
    fun `节奏都是正数且不超过一秒半`() {
        for (level in 0..2) {
            val s = WakeWord.sensitivityOfLevel(level)
            assertTrue(WakeWord.quietRetryMs(s) in 1..1500)
            assertTrue(WakeWord.silenceMs(s) in 1..1500)
        }
    }
}
