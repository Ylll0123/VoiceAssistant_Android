package com.example.voiceassistant.memory

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.TeachCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地学习能力测试（「越聊越会」）。
 *
 * 覆盖三层：
 * 1. 教学指令解析 —— 用户说的几种说法都能拆成「问法 + 答法」
 * 2. 记忆检索 —— 换个语序、少两个字也能命中，不相关的不能乱命中
 * 3. 引擎闭环 —— 不会 → 我教你 → 记住 → 换种说法问得出来
 */
class MemoryLearningTest {

    // ---------------------------------------------------------------- 教学指令

    @Test
    fun `记住格式能拆出问法和答法`() {
        val command = TeachCommand.parse("记住：下班了 = 早点回家")
        assertNotNull(command)
        assertEquals("下班了", command!!.question)
        assertEquals("早点回家", command.answer)
    }

    @Test
    fun `我教你格式能拆出问法和答法`() {
        val command = TeachCommand.parse("我教你，说下班了 就回答 早点回家")
        assertNotNull(command)
        assertEquals("下班了", command!!.question)
        assertEquals("早点回家", command.answer)
    }

    @Test
    fun `当我说的时候能拆出问法和答法`() {
        val command = TeachCommand.parse("当我说我困了的时候，你就说早点睡")
        assertNotNull(command)
        assertEquals("我困了", command!!.question)
        assertEquals("早点睡", command.answer)
    }

    @Test
    fun `普通对话不会被当成教学指令`() {
        assertNull(TeachCommand.parse("现在几点了"))
        assertNull(TeachCommand.parse("帮我算 12*8"))
        assertNull(TeachCommand.parse("讲个笑话"))
    }

    @Test
    fun `只说教我时能识别为等待答案`() {
        assertTrue(TeachCommand.isBareRequest("我教你"))
        assertTrue(TeachCommand.isBareRequest("教你"))
        assertTrue(!TeachCommand.isBareRequest("我教你怎么做饭"))
    }

    // ---------------------------------------------------------------- 检索

    @Test
    fun `完全一样的问法相似度为满分`() {
        assertEquals(1.0, MemorySearch.score("下班了", "下班了"), 0.0001)
    }

    @Test
    fun `多一两个字也能命中`() {
        assertTrue(MemorySearch.score("我下班了", "下班了") >= MemorySearch.MIN_SCORE)
        assertTrue(MemorySearch.score("下班了现在", "下班了") >= MemorySearch.MIN_SCORE)
    }

    @Test
    fun `不相关的问法不会命中`() {
        assertTrue(MemorySearch.score("今天天气怎么样", "我饿了") < MemorySearch.MIN_SCORE)
        assertTrue(MemorySearch.score("帮我算 12*8", "下班了") < MemorySearch.MIN_SCORE)
        // 两个都短的句子最容易互相误命中，必须挡住
        assertTrue(MemorySearch.score("我饿了", "我累了") < MemorySearch.MIN_SCORE)
    }

    @Test
    fun `检索能从多条记忆里挑出最像的一条`() {
        val repo = InMemoryMemoryRepository()
        repo.remember("下班了", "早点回家", MemoryRepository.SOURCE_TAUGHT)
        repo.remember("我饿了", "去吃点东西", MemoryRepository.SOURCE_TAUGHT)

        val hit = MemorySearch.best("我下班了", repo.entries())
        assertNotNull(hit)
        assertEquals("下班了", hit!!.entry.question)
    }

    @Test
    fun `预置记忆不为空且没有重复问法`() {
        assertTrue(MemorySeed.pairs.size >= 20)
        val normalized = MemorySeed.pairs.map { FuzzyNormalizeHelper.normalize(it.first) }
        assertEquals(normalized.size, normalized.distinct().size)
    }

    // ---------------------------------------------------------------- 引擎闭环

    @Test
    fun `教过之后换个问法能答上来`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        // 教之前：闲聊语料能接住这句话（共情一下），但答不出教的内容
        val before = engine.reply("我今天被老板批评了")
        assertTrue("教之前不该答出答案，实际：$before", !before.contains("别往心里去"))

        // 用一句话教它
        val taught = engine.reply("记住：被老板批评了 = 别往心里去，问题解决掉就行")
        assertEquals(AssistantEngine.INTENT_TAUGHT, engine.respond("记住：被老板批评了 = 别往心里去").intent)
        assertTrue(taught.contains("记住了"))

        // 换个说法再问，应该从记忆里答出来
        val response = engine.respond("老板批评我了怎么办")
        assertEquals(AssistantEngine.INTENT_MEMORY, response.intent)
        assertTrue("实际回复：${response.text}", response.text.contains("别往心里去"))
    }

    @Test
    fun `不会的时候说教我 然后给答案 就能学会`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)

        val first = engine.respond("我要去洗澡了")
        assertEquals(AssistantEngine.INTENT_FALLBACK, first.intent)

        val asking = engine.respond("我教你")
        assertEquals(AssistantEngine.INTENT_TEACH_PROMPT, asking.intent)
        assertTrue("应该把上一句复述出来：${asking.text}", asking.text.contains("我要去洗澡了"))

        val learned = engine.respond("洗完早点睡")
        assertEquals(AssistantEngine.INTENT_TAUGHT, learned.intent)

        val again = engine.respond("我去洗澡了")
        assertEquals(AssistantEngine.INTENT_MEMORY, again.intent)
        assertTrue(again.text.contains("洗完早点睡"))
    }

    @Test
    fun `教的时候说算了就不入库`() {
        val repo = InMemoryMemoryRepository()
        val engine = AssistantEngine(memory = repo)
        val before = repo.count()

        engine.respond("我在写作业")
        engine.respond("我教你")
        val cancelled = engine.respond("算了")
        assertEquals(AssistantEngine.INTENT_TEACH_CANCEL, cancelled.intent)
        assertEquals(before, repo.count())
    }

    @Test
    fun `规则优先于记忆 内置能力不会被记忆抢走`() {
        val repo = InMemoryMemoryRepository()
        repo.remember("现在几点", "我不告诉你", MemoryRepository.SOURCE_TAUGHT)
        val engine = AssistantEngine(memory = repo)

        val response = engine.respond("现在几点")
        assertEquals("time", response.intent)
        assertTrue("内置规则不该被记忆覆盖，实际：${response.text}", !response.text.contains("不告诉你"))
    }

    @Test
    fun `重复教同一句只更新答案不新增记录`() {
        val repo = InMemoryMemoryRepository()
        engineTeach(repo, "上班了", "先列个清单")
        engineTeach(repo, "上班了", "先喝杯咖啡")

        val matches = repo.entries().filter { it.normalized == "上班了" }
        assertEquals(1, matches.size)
        assertEquals("先喝杯咖啡", matches.first().answer)
    }

    private fun engineTeach(repo: MemoryRepository, question: String, answer: String) {
        AssistantEngine(memory = repo).reply("记住：$question = $answer")
    }
}

/** 测试里复用生产代码的归一化，避免测试和实现走两套规则 */
private object FuzzyNormalizeHelper {
    fun normalize(text: String) = com.example.voiceassistant.assistant.FuzzyMatcher.normalize(text)
}
