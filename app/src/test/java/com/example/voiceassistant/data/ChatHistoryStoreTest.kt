package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话记录的手写 JSON 编解码。
 *
 * 重点不是格式好看，而是**往返一致**：用户说的话里带引号、换行、
 * 甚至整段代码，重启后都要原样回来，不能把整份记录写坏。
 */
class ChatHistoryStoreTest {

    private fun roundTrip(messages: List<ChatMessage>): List<ChatMessage> =
        ChatHistoryStore.decode(ChatHistoryStore.encode(messages))

    @Test
    fun `空列表往返仍是空列表`() {
        assertEquals("[]", ChatHistoryStore.encode(emptyList()))
        assertTrue(ChatHistoryStore.decode("[]").isEmpty())
    }

    @Test
    fun `用户与助手消息的角色都能还原`() {
        val input = listOf(
            ChatMessage(text = "现在几点", isUser = true),
            ChatMessage(text = "现在是 15:26。", isUser = false)
        )
        val output = roundTrip(input)
        assertEquals(2, output.size)
        assertTrue(output[0].isUser)
        assertTrue(!output[1].isUser)
        assertEquals("现在几点", output[0].text)
        assertEquals("现在是 15:26。", output[1].text)
    }

    @Test
    fun `意图字段会保留 null 与具体值`() {
        val input = listOf(
            ChatMessage(text = "问", isUser = true),
            ChatMessage(text = "答", isUser = false, intent = "tool.reminder"),
            ChatMessage(text = "再来一句", isUser = false)
        )
        val output = roundTrip(input)
        assertNull(output[0].intent)
        assertEquals("tool.reminder", output[1].intent)
        assertNull(output[2].intent)
    }

    @Test
    fun `引号 反斜杠 换行不会破坏整份记录`() {
        val tricky = "他说：\"明天见\"，路径 C:\\tmp\\a.txt，换行\n第二行"
        val output = roundTrip(listOf(ChatMessage(text = tricky, isUser = true)))
        assertEquals(1, output.size)
        assertEquals(tricky, output[0].text)
    }

    @Test
    fun `时间戳按原值还原`() {
        val at = 1_793_891_971_443L
        val output = roundTrip(listOf(ChatMessage(text = "x", isUser = true, at = at)))
        assertEquals(at, output[0].at)
    }

    @Test
    fun `理解过程不落盘`() {
        val input = listOf(
            ChatMessage(text = "问题", isUser = true),
            ChatMessage.thinking("正在理解…"),
            ChatMessage(text = "答案", isUser = false)
        )
        val output = roundTrip(input)
        assertEquals(2, output.size)
        assertTrue(output.none { it.kind == ChatMessage.KIND_THINKING })
    }

    @Test
    fun `空白内容被丢掉`() {
        val output = roundTrip(
            listOf(
                ChatMessage(text = "  ", isUser = true),
                ChatMessage(text = "有内容", isUser = false)
            )
        )
        assertEquals(1, output.size)
        assertEquals("有内容", output[0].text)
    }

    @Test
    fun `超过上限只留最近的若干条`() {
        val many = (1..ChatHistoryStore.MAX_MESSAGES + 15).map {
            ChatMessage(text = "第 $it 句", isUser = it % 2 == 1)
        }
        val output = roundTrip(many)
        assertEquals(ChatHistoryStore.MAX_MESSAGES, output.size)
        // 留下的是尾巴：最后一句必须是刚才最后那句
        assertEquals("第 ${many.size} 句", output.last().text)
    }

    @Test
    fun `坏数据不会崩 只跳过坏条目`() {
        val raw = """[{"text":"好","user":1,"at":1},{"user":1},null,{"text":"","user":0}]"""
        val output = ChatHistoryStore.decode(raw)
        assertEquals(1, output.size)
        assertEquals("好", output[0].text)
    }

    @Test
    fun `非 JSON 与空串都退化成空表`() {
        assertTrue(ChatHistoryStore.decode(null).isEmpty())
        assertTrue(ChatHistoryStore.decode("").isEmpty())
        assertTrue(ChatHistoryStore.decode("今天天气不错").isEmpty())
        assertTrue(ChatHistoryStore.decode("{\"text\":\"不是数组\"}").isEmpty())
    }
}
