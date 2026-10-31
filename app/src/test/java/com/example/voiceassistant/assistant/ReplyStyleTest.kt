package com.example.voiceassistant.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 说话方式测试：设置页里的「回答风格 / 主动追问」要真的改变输出。
 *
 * 这里直接用 [SmallTalk.assemble] 而不走引擎，是为了把「内容挑选」和
 * 「怎么组装」两件事分开测：变体轮换另有一组测试覆盖。
 */
class ReplyStyleTest {

    @After
    fun tearDown() {
        AssistantConfig.reset()
    }

    @Test
    fun `简洁模式只给一句不加反问`() {
        AssistantConfig.replyStyle = AssistantConfig.ReplyStyle.CONCISE
        AssistantConfig.proactiveFollowUp = true

        val topic = DialogCorpus.find("tired")!!
        val body = SmallTalk.pickReply(topic.replies, DialogContext())!!
        val text = SmallTalk.assemble(topic, body, DialogContext(), switched = true)

        assertEquals("简洁模式不该额外加内容，实际：$text", body, text)
    }

    @Test
    fun `标准模式会在换话题时递一个反问`() {
        AssistantConfig.replyStyle = AssistantConfig.ReplyStyle.STANDARD
        AssistantConfig.proactiveFollowUp = true

        val topic = DialogCorpus.find("tired")!!
        val body = SmallTalk.pickReply(topic.replies, DialogContext())!!
        val text = SmallTalk.assemble(topic, body, DialogContext(), switched = true)

        if (topic.followUps.isNotEmpty() && !body.contains("？")) {
            assertTrue(
                "标准模式应该在话题回复后面追加反问，实际：$text",
                text.length > body.length
            )
        }
    }

    @Test
    fun `详细模式会比标准模式多一段展开`() {
        val topic = DialogCorpus.find("tired")!!
        val context = DialogContext()
        val body = SmallTalk.pickReply(topic.replies, context)!!

        AssistantConfig.replyStyle = AssistantConfig.ReplyStyle.STANDARD
        val standard = SmallTalk.assemble(topic, body, context, switched = false)

        AssistantConfig.replyStyle = AssistantConfig.ReplyStyle.DETAILED
        val detailed = SmallTalk.assemble(topic, body, context, switched = false)

        assertTrue(
            "详细模式应该更长，实际：标准=$standard / 详细=$detailed",
            detailed.length > standard.length
        )
    }

    @Test
    fun `关掉主动追问就不再追加反问`() {
        AssistantConfig.replyStyle = AssistantConfig.ReplyStyle.STANDARD
        AssistantConfig.proactiveFollowUp = false

        val topic = DialogCorpus.find("tired")!!
        val body = SmallTalk.pickReply(topic.replies, DialogContext())!!
        val text = SmallTalk.assemble(topic, body, DialogContext(), switched = true)

        assertEquals("关掉追问后不该再追加，实际：$text", body, text)
    }
}
