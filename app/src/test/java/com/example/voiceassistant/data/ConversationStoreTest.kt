package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话记录存储的编解码测试。
 *
 * 这几条是纯函数（不碰 SharedPreferences），但它们是**用户数据的唯一入口**：
 * 编码错一个引号，整本对话记录就读不回来了。所以边界情况要压实。
 */
class ConversationStoreTest {

    // ------------------------------------------------------------ 索引

    @Test
    fun `索引能原样存取`() {
        val list = listOf(
            Conversation(id = "a1", title = "北京天气", createdAt = 100L, updatedAt = 200L, named = true),
            Conversation(id = "b2", title = "写周报", createdAt = 300L, updatedAt = 400L)
        )
        val decoded = ConversationStore.decodeIndex(ConversationStore.encodeIndex(list))
        assertEquals(2, decoded.size)
        assertEquals("a1", decoded[0].id)
        assertEquals("北京天气", decoded[0].title)
        assertEquals(200L, decoded[0].updatedAt)
        assertTrue(decoded[0].named)
        assertEquals("写周报", decoded[1].title)
        assertFalse(decoded[1].named)
    }

    @Test
    fun `空索引编成空数组`() {
        assertEquals("[]", ConversationStore.encodeIndex(emptyList()))
        assertTrue(ConversationStore.decodeIndex("[]").isEmpty())
    }

    @Test
    fun `标题里的引号和换行不会破坏索引`() {
        val list = listOf(Conversation(id = "x", title = "他说\"算了\"\n然后走了"))
        val decoded = ConversationStore.decodeIndex(ConversationStore.encodeIndex(list))
        assertEquals(1, decoded.size)
        assertEquals("他说\"算了\"\n然后走了", decoded[0].title)
    }

    @Test
    fun `坏数据不会让列表整个消失`() {
        // 手动改坏一条：没有 id 的那条跳过，好的那条仍然要能读出来
        val raw = """[{"title":"没有 id"},{"id":"ok","title":"正常"}]"""
        val decoded = ConversationStore.decodeIndex(raw)
        assertEquals(1, decoded.size)
        assertEquals("ok", decoded[0].id)
        // 完全不是 JSON 时返回空表，而不是抛异常（否则一升级就闪退）
        assertTrue(ConversationStore.decodeIndex("不是 JSON").isEmpty())
        assertTrue(ConversationStore.decodeIndex(null).isEmpty())
        assertTrue(ConversationStore.decodeIndex("").isEmpty())
    }

    @Test
    fun `缺标题的会话用新对话兜底`() {
        val decoded = ConversationStore.decodeIndex("""[{"id":"a"}]""")
        assertEquals(Conversation.UNTITLED, decoded[0].title)
    }

    // ------------------------------------------------------------ 消息

    @Test
    fun `消息能原样存取并保留顺序`() {
        val messages = listOf(
            ChatMessage(text = "提醒我明天八点交作业", isUser = true, at = 1L),
            ChatMessage(text = "好，明天八点提醒你交作业。", isUser = false, at = 2L)
        )
        val decoded = ConversationStore.decodeMessages(ConversationStore.encodeMessages(messages))
        assertEquals(2, decoded.size)
        assertTrue(decoded[0].isUser)
        assertEquals("提醒我明天八点交作业", decoded[0].text)
        assertFalse(decoded[1].isUser)
        assertEquals(2L, decoded[1].at)
    }

    @Test
    fun `消息里的引号换行不会被写坏`() {
        val text = "第一行\n第二行 \"引号\" 和 \\反斜杠"
        val decoded = ConversationStore.decodeMessages(
            ConversationStore.encodeMessages(listOf(ChatMessage(text = text, isUser = true)))
        )
        assertEquals(text, decoded[0].text)
    }

    @Test
    fun `空白消息不入库`() {
        val messages = listOf(
            ChatMessage(text = "  ", isUser = true),
            ChatMessage(text = "算一下 12 乘 8", isUser = true)
        )
        val decoded = ConversationStore.decodeMessages(ConversationStore.encodeMessages(messages))
        assertEquals(1, decoded.size)
        assertEquals("算一下 12 乘 8", decoded[0].text)
    }

    @Test
    fun `只保留最近的两百条`() {
        val many = (1..(ConversationStore.MAX_MESSAGES + 25)).map {
            ChatMessage(text = "第 $it 句", isUser = it % 2 == 1)
        }
        val kept = ConversationStore.decodeMessages(ConversationStore.encodeMessages(many))
        assertEquals(ConversationStore.MAX_MESSAGES, kept.size)
        // 丢的是最早的，最后一句必须在
        assertEquals("第 ${many.size} 句", kept.last().text)
    }

    @Test
    fun `坏消息跳过好消息保留`() {
        val raw = """[{"user":1,"at":1},{"text":"正常的","user":0,"at":2}]"""
        val decoded = ConversationStore.decodeMessages(raw)
        assertEquals(1, decoded.size)
        assertEquals("正常的", decoded[0].text)
        assertTrue(ConversationStore.decodeMessages("{}").isEmpty())
    }
}
