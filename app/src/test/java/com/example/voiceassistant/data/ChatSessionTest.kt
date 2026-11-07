package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内存模型的测试。
 *
 * 这里管的是「改一句话之后上下文还对不对」——
 * v4.0 的编辑会作废旧回答并重新提问，逻辑错了就会把改过的句子当历史再发一遍。
 */
class ChatSessionTest {

    private fun session(vararg texts: String): ChatSession {
        val s = ChatSession()
        s.load(
            texts.mapIndexed { index, text ->
                ChatMessage(text = text, isUser = index % 2 == 0, at = index.toLong())
            }
        )
        return s
    }

    @Test
    fun `最后一条用户消息的位置`() {
        val s = session("问一", "答一", "问二", "答二")
        assertEquals(2, s.lastUserIndex())
        assertTrue(s.hasReplyAfterUser())
    }

    @Test
    fun `还没回答时最后一条用户消息后没有回答`() {
        val s = session("问一", "答一", "问二")
        assertEquals(2, s.lastUserIndex())
        assertFalse(s.hasReplyAfterUser())
    }

    @Test
    fun `改问题后旧回答被作废`() {
        val s = session("问一", "答一", "问二", "答二")
        val index = s.lastUserIndex()
        assertTrue(s.updateUserText(index, "问三"))
        assertTrue(s.dropAnswerAfter(index))

        assertEquals(3, s.messages.size)
        assertEquals("问三", s.messageAt(index)?.text)
        // 原来那条「答二」已经没了
        assertFalse(s.messages.any { it.text == "答二" })
        assertFalse("改完就应该没有回答了", s.hasReplyAfterUser())
    }

    @Test
    fun `作废回答只删紧邻的一条`() {
        val s = session("问一", "答一", "问二", "答二")
        assertTrue(s.dropAnswerAfter(2))
        assertEquals(listOf("问一", "答一", "问二"), s.messages.map { it.text } )
        // 已经删过一次，再删没有可删的
        assertFalse(s.dropAnswerAfter(2))
    }

    @Test
    fun `下一条是用户消息时不乱删`() {
        val s = session("问一", "答一", "问二")
        // 问二后面还没有回答，不能把用户说的下一句当成回答删掉
        assertFalse(s.dropAnswerAfter(2))
        assertEquals(3, s.messages.size)
    }

    @Test
    fun `改问题时的历史只取它之前的完整轮次`() {
        val s = session("问一", "答一", "问二", "答二", "问三")
        // 正在改的是「问三」（index 4），历史应该只有 问一/答一、问二/答二 之外…
        val turns = s.recentTurnsBefore(4, 4)
        assertEquals(4, turns.size)
        assertEquals("问一", turns[0].content)
        assertEquals("答一", turns[1].content)
        assertEquals("问二", turns[2].content)
        assertEquals("答二", turns[3].content)
    }

    @Test
    fun `改中间那句时后面的内容不算历史`() {
        val s = session("问一", "答一", "问二", "答二")
        // 改「问一」：它之前什么都没有，历史必须是空的
        assertTrue(s.recentTurnsBefore(0, 4).isEmpty())
        // 改「问二」：历史只有第一轮，且不能出现「答二」这种未来内容
        val turns = s.recentTurnsBefore(2, 4)
        assertEquals(2, turns.size)
        assertEquals("问一", turns[0].content)
        assertEquals("答一", turns[1].content)
    }

    @Test
    fun `历史轮数上限仍然生效`() {
        val s = session("问一", "答一", "问二", "答二", "问三", "答三", "问四")
        val turns = s.recentTurnsBefore(6, 2)
        assertEquals(4, turns.size)
        assertEquals("问二", turns[0].content)
        assertEquals("答三", turns[3].content)
    }

    @Test
    fun `删一轮会连带删掉它的回答`() {
        val s = session("问一", "答一", "问二", "答二")
        assertTrue(s.deleteTurn(2))
        assertEquals(listOf("问一", "答一"), s.messages.map { it.text })
    }
}
