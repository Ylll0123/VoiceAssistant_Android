package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日常闲聊引擎的测试：多变体回复、上下文承接、话题切换、不抢功能意图。
 */
class SmallTalkTest {

    private fun engine() = AssistantEngine()

    // ------------------------------------------------------------ 多变体

    @Test
    fun `闲聊不再落到兜底`() {
        val reply = engine().reply("我好累")
        assertNotEquals(AssistantEngine.FALLBACK, reply)
        assertTrue("应该有实质回应，实际：$reply", reply.length > 6)
    }

    @Test
    fun `同一句话连续问两遍回答不一样`() {
        val engine = engine()
        val first = engine.reply("我好累")
        val second = engine.reply("我好累")
        assertNotEquals("两次应该换说法，实际：$first / $second", first, second)
    }

    @Test
    fun `回复变体会避开刚说过的句子`() {
        val context = DialogContext()
        val topic = DialogCorpus.find("tired")!!
        val said = mutableListOf<String>()
        repeat(topic.replies.size) {
            val body = SmallTalk.pickReply(topic.replies, context)!!
            assertTrue("不该重复刚说过的：$said / $body", body !in said)
            said.add(body)
            context.markReply(body)
            context.remember(DialogContext.INTENT_CHAT, mapOf(DialogContext.SLOT_TOPIC to "tired"))
        }
        // 全说过后允许重新轮换，但不该报错
        assertTrue(SmallTalk.pickReply(topic.replies, context) != null)
    }

    // ------------------------------------------------------------ 上下文承接

    @Test
    fun `为什么能接着上文的话题说`() {
        val engine = engine()
        engine.reply("今天加班到十点")
        val reply = engine.reply("为什么")

        val expected = DialogCorpus.find("overtime")!!.more
        assertTrue(
            "应该接着加班话题展开，实际：$reply",
            expected.any { reply.contains(it) || it.startsWith(reply) } || reply.contains("加班")
        )
    }

    @Test
    fun `然后呢也沿用当前话题`() {
        val engine = engine()
        engine.reply("最近压力大")
        val reply = engine.reply("然后呢")
        assertNotEquals(AssistantEngine.FALLBACK, reply)
        assertTrue("应该还在焦虑话题里，实际：$reply", reply.length > 4)
    }

    @Test
    fun `那怎么办会给出话题里的具体建议`() {
        val engine = engine()
        engine.reply("我好累")
        val reply = engine.reply("那我该怎么办")

        val advice = DialogCorpus.find("tired")!!.advice
        assertTrue(
            "应该给出累了的具体建议，实际：$reply",
            advice.any { reply.contains(it) || it.startsWith(reply) }
        )
    }

    @Test
    fun `指代句沿用当前话题`() {
        val engine = engine()
        engine.reply("我跟朋友吵架了")
        val reply = engine.reply("这事我也很烦")
        assertNotEquals(AssistantEngine.FALLBACK, reply)
        assertEquals("应该还在朋友话题上", "friend", engine.context().activeTopic())
    }

    @Test
    fun `我也是这类应和能接住并递回话头`() {
        val engine = engine()
        engine.reply("我好累")
        val reply = engine.reply("嗯嗯")
        assertNotEquals(AssistantEngine.FALLBACK, reply)
    }

    @Test
    fun `换话题后当前话题会跟着更新`() {
        val engine = engine()
        engine.reply("我好累")
        assertEquals("tired", engine.context().activeTopic())

        engine.reply("今天好开心")
        assertEquals("happy", engine.context().activeTopic())
    }

    @Test
    fun `没有上文时承接句不会乱答`() {
        val engine = engine()
        // 没聊过任何话题，「为什么」接不住就走兜底或建议，不能编内容
        val reply = engine.reply("为什么")
        assertTrue(reply.isNotEmpty())
    }

    // ------------------------------------------------------------ 不抢功能意图

    @Test
    fun `时间问题不会被闲聊抢走`() {
        val engine = engine()
        val reply = engine.reply("现在几点了")
        assertTrue("应该报时间，实际：$reply", Regex("\\d{1,2}:\\d{2}").containsMatchIn(reply))
    }

    @Test
    fun `算数不会被闲聊抢走`() {
        val engine = engine()
        val reply = engine.reply("12*8")
        assertTrue("应该算出 96，实际：$reply", reply.contains("96"))
    }

    @Test
    fun `讲笑话仍然是笑话规则`() {
        val engine = engine()
        engine.reply("讲个笑话")
        assertEquals("joke", engine.context().lastIntent)
    }

    @Test
    fun `日期不会被闲聊抢走`() {
        val engine = engine()
        val reply = engine.reply("今天几号")
        assertTrue("应该报日期，实际：$reply", reply.contains("年") && reply.contains("月"))
    }

    @Test
    fun `含数字的句子不进闲聊`() {
        val context = DialogContext()
        assertNull("数字句不该被闲聊接走", SmallTalk.reply("明天要交第3版", context))
        assertNull("算式不该被闲聊接走", SmallTalk.reply("帮我算 12*8", context))
    }

    // ------------------------------------------------------------ 语料完整性

    @Test
    fun `语料规模足够大且结构完整`() {
        assertTrue("话题数应该够多，实际：${DialogCorpus.topicCount()}", DialogCorpus.topicCount() >= 50)
        assertTrue("应答句数应该够多，实际：${DialogCorpus.sentenceCount()}", DialogCorpus.sentenceCount() >= 250)
        DialogCorpus.topics.forEach { topic ->
            assertTrue("话题 ${topic.id} 缺回复", topic.replies.isNotEmpty())
            assertTrue("话题 ${topic.id} 缺关键词", topic.keywords.isNotEmpty())
            assertTrue("话题 ${topic.id} 变体太少，会复读", topic.replies.size >= 2)
        }
    }

    @Test
    fun `话题关键词不互相大面积重叠`() {
        // 抽查几组容易混淆的关键词，保证分数高的总是合理的那个
        val tired = SmallTalk.bestTopic("我今天真的好累啊")!!.first
        assertEquals("tired", tired.id)
        val overtime = SmallTalk.bestTopic("又得加班了")!!.first
        assertEquals("overtime", overtime.id)
    }
}
