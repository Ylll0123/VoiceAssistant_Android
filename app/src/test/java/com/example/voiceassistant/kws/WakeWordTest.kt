package com.example.voiceassistant.kws

import org.junit.After
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
}
