package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语气极性测试：**否定词不能被关键词命中漏掉**。
 *
 * 这一组用例的存在意义很直接——用户说「我不高兴」，
 * 以前会命中「高兴」关键词，回答变成「那挺好，值得高兴一下」，
 * 这是最典型的答非所问。
 */
class PolarityTest {

    // ------------------------------------------------------------ 否定判定

    @Test
    fun `不高兴里的高兴被否定`() {
        val text = "我不高兴"
        val index = text.indexOf("高兴")
        assertTrue(Polarity.isNegatedAt(text, index, 2))
    }

    @Test
    fun `很高兴里的高兴没有被否定`() {
        val text = "我今天很高兴"
        val index = text.indexOf("高兴")
        assertFalse(Polarity.isNegatedAt(text, index, 2))
    }

    @Test
    fun `不太开心也算否定`() {
        val text = "我最近不太开心"
        val index = text.indexOf("开心")
        assertTrue(Polarity.isNegatedAt(text, index, 2))
    }

    @Test
    fun `高兴不起来是后缀否定`() {
        val text = "整个人高兴不起来"
        val index = text.indexOf("高兴")
        assertTrue(Polarity.isNegatedAt(text, index, 2))
    }

    @Test
    fun `否定词离得太远不算否定`() {
        // 「不」和「开心」中间隔了好几个字，不是对它的否定
        val text = "不知道为什么别人总说我开心"
        val index = text.indexOf("开心")
        assertFalse(Polarity.isNegatedAt(text, index, 2))
    }

    @Test
    fun `positiveHit 跳过被否定的词`() {
        assertNull(Polarity.positiveHit("我不高兴", Polarity.POSITIVE_MOODS))
        assertEquals("高兴", Polarity.positiveHit("我今天很高兴", Polarity.POSITIVE_MOODS))
    }

    @Test
    fun `negativeHit 只认被否定的词`() {
        assertEquals("高兴", Polarity.negativeHit("我不高兴", Polarity.POSITIVE_MOODS))
        assertNull(Polarity.negativeHit("我今天很高兴", Polarity.POSITIVE_MOODS))
    }

    // ------------------------------------------------------------ 情绪倾向

    @Test
    fun `否定式表达判成负面`() {
        assertEquals(-1, Polarity.moodOf("我不高兴"))
        assertEquals(-1, Polarity.moodOf("不太开心"))
        assertEquals(-1, Polarity.moodOf("开心不起来"))
        assertEquals(-1, Polarity.moodOf("我好烦"))
    }

    @Test
    fun `肯定式表达判成正面`() {
        assertEquals(1, Polarity.moodOf("我今天很开心"))
        assertEquals(1, Polarity.moodOf("这件事让我很满足"))
    }

    @Test
    fun `没有情绪词时说不清`() {
        assertEquals(0, Polarity.moodOf("帮我算一下十二乘以八"))
    }

    // ------------------------------------------------------------ 端到端

    @Test
    fun `说不高兴不会落到开心话题`() {
        val id = SmallTalk.bestTopic("我不高兴")?.first?.id
        assertNotEquals("不该落到开心话题，实际：$id", "happy", id)
    }

    @Test
    fun `说不高兴不会回成高兴`() {
        listOf("我不高兴", "我不太高兴", "我今天一点都不开心", "高兴不起来").forEach { input ->
            val reply = AssistantEngine().reply(input)
            assertFalse("「$input」不该回成高兴一类，实际：$reply", reply.contains("值得高兴"))
            assertFalse("「$input」不该说「什么好事」，实际：$reply", reply.contains("什么好事"))
            assertTrue("「$input」应该有实质回应，实际：$reply", reply.length > 6)
        }
    }

    @Test
    fun `说高兴仍然是开心话题`() {
        assertEquals("happy", SmallTalk.bestTopic("我今天很高兴")?.first?.id)
    }

    @Test
    fun `情绪低落会用专门的共情说法`() {
        val reply = ReplyCrafter.craft("我不高兴", DialogContext())!!
        assertTrue(
            "低落时应从 DOWN 池取说法，实际：$reply",
            ThoughtBank.DOWN.any { reply.contains(it.take(12)) }
        )
    }
}
