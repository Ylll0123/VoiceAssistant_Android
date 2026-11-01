package com.example.voiceassistant.core

import com.example.voiceassistant.assistant.QueryUnderstanding
import com.example.voiceassistant.data.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话历史管理的测试。
 *
 * 关注两件事：历史不会无限增长（否则 token 越聊越贵）、
 * 以及"什么时候才需要把历史发给云端"。
 */
class HistoryManagerTest {

    @Test
    fun `轮数上限是固定的，历史不会无限增长`() {
        assertTrue(
            "历史每轮都要重发，轮数必须受限",
            HistoryManager.DEFAULT_MAX_TURNS <= QueryUnderstanding.LOCAL_MEMORY_TURNS
        )
    }

    @Test
    fun `只有追问或存在指代时才带上下文`() {
        val followUp = QueryUnderstanding.analyze("它怎么样", listOf("上海天气怎么样"))
        assertTrue("被消解过说明有指代，需要历史", history().needsContext(followUp))

        val standalone = QueryUnderstanding.analyze("量子纠缠到底是什么", emptyList())
        assertFalse("自足的问句不需要历史", history().needsContext(standalone))
    }

    @Test
    fun `给云端的上下文会被限轮限长`() {
        val session = ChatSession()
        val manager = HistoryManager(session, maxTurns = 2, clipChars = 10)
        repeat(4) {
            session.addUser("问题$it")
            session.addAssistant("回答$it 这是一条很长的回答需要被截断")
        }
        val turns = manager.cloudTurns(useHistory = true)
        assertEquals(4, turns.size)
        assertTrue(turns.first().content.length <= 11)
        assertEquals("不需要历史时返回空表，一个 token 都不多发", 0, manager.cloudTurns(false).size)
    }

    @Test
    fun `空消息不落盘`() {
        val session = ChatSession()
        val manager = HistoryManager(session)
        manager.recordUser("   ")
        assertTrue(session.messages.isEmpty())
    }

    private fun history(): HistoryManager = HistoryManager(ChatSession())
}
