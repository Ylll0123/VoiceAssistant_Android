package com.example.voiceassistant.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 语音识别的「整句择优」测试。
 *
 * 这里的取舍很单纯：**只在明显是同一句话被截断时才改判**，
 * 其余情况一律尊重引擎给的首选。无脑选最长会把两条不同的候选拼错，
 * 那比截断更难发现。
 */
class AsrPickTest {

    @Test
    fun `首选通常就是对的`() {
        assertEquals("打开手电筒", AsrPick.choose(listOf("打开手电筒", "打开手电")))
    }

    @Test
    fun `首选被截断时取更长的延续`() {
        // 引擎把首选压成了半句，后面那条才是完整句
        val picked = AsrPick.choose(
            listOf("发微信给妈妈", "发微信给妈妈说我晚点到", "发微信给妈妈说我晚点")
        )
        assertEquals("发微信给妈妈说我晚点到", picked)
    }

    @Test
    fun `不相干的更长句子不算延续`() {
        // 第二条不是首选的延伸，不能因为长就换过去
        assertEquals("今天天气怎么样", AsrPick.choose(listOf("今天天气怎么样", "提醒我明天早上七点起床")))
    }

    @Test
    fun `只多出一个字不值得改判`() {
        assertEquals("打开微信", AsrPick.choose(listOf("打开微信", "打开微信吧")))
    }

    @Test
    fun `没有最终结果时用中间结果兜底`() {
        assertEquals("帮我调到五", AsrPick.choose(emptyList(), "帮我调到五"))
        assertEquals("帮我调到五", AsrPick.choose(null, "帮我调到五"))
    }

    @Test
    fun `最终结果比中间结果短说明被截断`() {
        // 引擎的 onResults 反而更短：这时中间结果更可信
        assertEquals(
            "给妈妈发微信说我晚点到",
            AsrPick.choose(listOf("给妈妈发微信"), "给妈妈发微信说我晚点到")
        )
    }

    @Test
    fun `全空就返回空串`() {
        assertEquals("", AsrPick.choose(null, null))
        assertEquals("", AsrPick.choose(listOf("  ", ""), ""))
    }
}
