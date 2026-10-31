package com.example.voiceassistant.assistant

import com.example.voiceassistant.memory.InMemoryMemoryRepository
import com.example.voiceassistant.memory.MemoryRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自学习测试：教一句之后，相似的新说法它自己就会了。
 *
 * 这是「越聊越会」的核心，也是课程里「自适应 / 自学习」那部分的可验证证据：
 * 用户只教了一句，系统自己把新说法扩进了记忆库，之后直接命中，不再走相似匹配。
 */
class SelfLearningTest {

    @After
    fun tearDown() {
        AssistantConfig.reset()
    }

    @Test
    fun `教过一句后相似的新说法会自动学会`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        engine.reply("记住：下班了 = 早点回家")

        // 换个说法问：和教过的那句不完全一样，但足够像
        val reply = engine.reply("我下班了啊")
        assertEquals("应该沿用教过的答案，实际：$reply", "早点回家", reply)

        val learned = repo.entries().filter { it.isLearned }
        assertTrue(
            "应该自动学会新说法，实际记忆：${repo.entries().map { it.question }}",
            learned.any { it.question == "我下班了啊" }
        )
        assertTrue(
            "自学条目的答案要和教过的一致",
            learned.all { it.answer == "早点回家" }
        )
    }

    @Test
    fun `学会之后再说一遍就直接命中`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        engine.reply("记住：下班了 = 早点回家")
        engine.reply("我下班了啊")
        val before = repo.count()

        // 第三次：记忆里已经有这条了，不该再重复写一条
        engine.reply("我下班了啊")
        assertEquals("不该重复写入同一条说法", before, repo.count())
        assertEquals("早点回家", engine.reply("我下班了啊"))
    }

    @Test
    fun `关掉自学习就不会自动记住`() {
        AssistantConfig.autoLearn = false

        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        engine.reply("记住：下班了 = 早点回家")
        engine.reply("我下班了啊")

        assertFalse(
            "关掉自学习后不该自动写入，实际：${repo.entries()}",
            repo.entries().any { it.isLearned }
        )
    }

    @Test
    fun `不像的说法不会被硬学`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        engine.reply("记住：下班了 = 早点回家")
        // 完全不相干的一句：不能被当成「下班了」的变体记进去
        engine.reply("今天晚饭吃什么好呢")

        assertFalse(
            "不相干的话不该被学进去，实际：${repo.entries().map { it.question }}",
            repo.entries().any { it.isLearned && it.question.contains("晚饭") }
        )
    }

    @Test
    fun `自学的说法来源是 learned 而不是 taught`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        engine.reply("记住：下班了 = 早点回家")
        engine.reply("我下班了啊")

        val entry = repo.entries().first { it.question == "我下班了啊" }
        assertEquals(MemoryRepository.SOURCE_LEARNED, entry.source)
        assertFalse(entry.isTaught)
        assertTrue(entry.isLearned)
    }

    @Test
    fun `答不上来的话会通知出去供记忆页收录`() {
        val collected = mutableListOf<String>()
        val engine = AssistantEngine(memory = InMemoryMemoryRepository())
        engine.onUnanswered = { collected += it }

        engine.reply("量子纠缠和人生选择有什么关系")

        assertTrue(
            "没答上来的话应该被收走，实际收到：$collected",
            collected.any { it.contains("量子纠缠") }
        )
    }
}
