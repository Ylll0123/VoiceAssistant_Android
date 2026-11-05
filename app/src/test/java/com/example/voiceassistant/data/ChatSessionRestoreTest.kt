package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话恢复：重启后把上次的对话接回来，且不重复加欢迎语。
 */
class ChatSessionRestoreTest {

    @Test
    fun `恢复后消息按原顺序回来`() {
        val session = ChatSession()
        session.restore(
            listOf(
                ChatMessage(text = "刚才问的", isUser = true),
                ChatMessage(text = "刚才答的", isUser = false)
            )
        )
        assertEquals(2, session.messages.size)
        assertEquals("刚才问的", session.messages[0].text)
        assertTrue(session.messages[0].isUser)
        assertEquals("刚才答的", session.messages[1].text)
    }

    @Test
    fun `恢复过的会话不再补欢迎语`() {
        val session = ChatSession()
        session.restore(listOf(ChatMessage(text = "旧对话", isUser = true)))
        session.ensureWelcome("你好，我是 DeepSuck")
        assertEquals(1, session.messages.size)
        assertEquals("旧对话", session.messages[0].text)
    }

    @Test
    fun `空存档照旧补欢迎语`() {
        val session = ChatSession()
        session.restore(emptyList())
        session.ensureWelcome("你好，我是 DeepSuck")
        assertEquals(1, session.messages.size)
        assertEquals("你好，我是 DeepSuck", session.messages[0].text)
    }

    @Test
    fun `恢复过的上下文仍能用于追问`() {
        val session = ChatSession()
        session.restore(
            listOf(
                ChatMessage(text = "上海天气怎么样", isUser = true),
                ChatMessage(text = "上海今天晴。", isUser = false)
            )
        )
        assertEquals(listOf("上海天气怎么样"), session.recentUserLines(3))
        assertEquals(2, session.recentTurns(2).size)
    }

    @Test
    fun `列表每次变化都会通知落盘`() {
        val session = ChatSession()
        val saved = mutableListOf<List<ChatMessage>>()
        session.onChanged = { saved.add(it) }

        session.addUser("一")
        session.addAssistant("二")

        assertEquals(2, saved.size)
        assertEquals(1, saved[0].size)
        assertEquals(2, saved[1].size)
    }
}
