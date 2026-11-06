package com.example.voiceassistant.fuzzy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归一化与纠错的测试：**ASR 原文怎么变干净**。
 *
 * 重点有两类：
 * ① 该纠的必须纠对（「山点」→「三点」、「提星」→「提醒」）；
 * ② 不该纠的绝不能动（「还是点外卖」不能变成「还四点外卖」）。
 * 第二类比第一类更重要——纠错纠错了，比不纠更糟。
 */
class TextNormalizerTest {

    @Test
    fun `全角数字与标点会被拉平`() {
        assertEquals("明天8点提醒我", TextNormalizer.normalize("明天，８点提醒我。"))
    }

    @Test
    fun `语气词和空格不会参与匹配`() {
        assertEquals("明天八点提醒我交作业", TextNormalizer.normalize("嗯……明天啊 八点 吧，提醒我交作业"))
    }

    @Test
    fun `同义说法归一成一个词`() {
        assertEquals("提醒我明天八点开会", TextNormalizer.normalize("帮我记一下明天八点开会"))
        assertEquals("提醒我喝水", TextNormalizer.normalize("到点叫我喝水"))
    }

    @Test
    fun `时刻前的同音字会被纠正`() {
        val fixed = TextNormalizer.normalizeAndFix("明天山点提醒我交作业")
        assertEquals("明天三点提醒我交作业", fixed.text)
        assertTrue("要留下纠正记录，界面才能解释", fixed.fixes.any { it.contains("山") })
    }

    @Test
    fun `关键词错字会被纠正`() {
        assertEquals("我的提醒", TextNormalizer.normalizeAndFix("我的提星").text)
        assertEquals("提醒我八点", TextNormalizer.normalizeAndFix("题醒我八点").text)
    }

    @Test
    fun `没有指令线索的句子不动同音字`() {
        // 「还是点外卖」里也有「是点」，但它不是指令，不能改成「四点」
        val fixed = TextNormalizer.normalizeAndFix("还是点外卖")
        assertFalse("不能把正常句子改坏，实际：${fixed.text}", fixed.text.contains("四点"))
    }

    @Test
    fun `紧跟还是的错字也不纠`() {
        val fixed = TextNormalizer.normalizeAndFix("提醒我还是点外卖")
        assertFalse("「还是」后面的字不能动，实际：${fixed.text}", fixed.text.contains("四点"))
    }

    @Test
    fun `全角半角混排的中文标点都能去掉`() {
        assertEquals("提醒我交报告", TextNormalizer.normalize("提醒我，交报告！"))
    }
}
