package com.example.voiceassistant.assistant

import com.example.voiceassistant.memory.InMemoryMemoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思维模式：本地答不上来时给的「有思路的回答」。
 *
 * 这里守三条底线：
 * 1. **认得准**：问原因不会被当成问办法；
 * 2. **不重复**：同一句连着问，回答必须换花样；
 * 3. **不抢戏**：没有提问线索的话（错别字、纯数字）不接管，留给纠错流程。
 */
class ReplyCrafterTest {

    // ------------------------------------------------------------------ 识别

    @Test
    fun `情绪 比较 归因 选择 求法 看法 讲解 事实都能认出来`() {
        assertEquals(ReplyCrafter.Mode.EMOTION, ReplyCrafter.detect("最近好累啊"))
        assertEquals(ReplyCrafter.Mode.COMPARE, ReplyCrafter.detect("这两个有什么区别"))
        assertEquals(ReplyCrafter.Mode.CAUSE, ReplyCrafter.detect("为什么会这样"))
        assertEquals(ReplyCrafter.Mode.CHOICE, ReplyCrafter.detect("我到底该不该换个环境"))
        assertEquals(ReplyCrafter.Mode.METHOD, ReplyCrafter.detect("完全没头绪怎么办"))
        assertEquals(ReplyCrafter.Mode.OPINION, ReplyCrafter.detect("你怎么看这件事"))
        assertEquals(ReplyCrafter.Mode.EXPLAIN, ReplyCrafter.detect("通俗地讲一下原理"))
        assertEquals(ReplyCrafter.Mode.FACT, ReplyCrafter.detect("这个东西是什么"))
    }

    @Test
    fun `没有提问线索时不接管：错别字和纯数字留给纠错流程`() {
        assertEquals(ReplyCrafter.Mode.UNKNOWN, ReplyCrafter.detect("讲个校话"))
        assertEquals(ReplyCrafter.Mode.UNKNOWN, ReplyCrafter.detect("12345"))
        assertEquals(ReplyCrafter.Mode.UNKNOWN, ReplyCrafter.detect("那后天呢"))
    }

    @Test
    fun `同时有多种线索时按最具体的那个来`() {
        // 「不知道该怎么选」既有求助词也有选择词，按选择处理
        assertEquals(ReplyCrafter.Mode.CHOICE, ReplyCrafter.detect("我纠结该怎么选"))
        // 情绪优先：先接住，再谈办法
        assertEquals(ReplyCrafter.Mode.EMOTION, ReplyCrafter.detect("我好烦，怎么办"))
    }

    // ------------------------------------------------------------------ 多样性

    @Test
    fun `每种模式都准备了足够多的说法`() {
        ReplyCrafter.Mode.values().forEach { mode ->
            val pool = ReplyCrafter.poolOf(mode)
            assertTrue("${mode.name} 的说法太少：${pool.size}", pool.size >= 4)
            assertTrue("${mode.name} 内部有重复", pool.toSet().size == pool.size)
        }
    }

    @Test
    fun `同一个问题连着问不会每次一字不差`() {
        val seen = mutableSetOf<String>()
        repeat(8) {
            val ctx = DialogContext()
            repeat(it) { ctx.remember("dummy", emptyMap()) }
            seen += ReplyCrafter.craft("我到底该不该换个环境", ctx)!!
        }
        assertTrue("八次只变出 ${seen.size} 种说法", seen.size >= 3)
    }

    @Test
    fun `回答里会把用户问的事原样带上`() {
        val answer = ReplyCrafter.craft("我到底该不该换个环境")!!
        assertTrue("应该复述用户的问题，实际：$answer", answer.contains("换个环境"))
    }

    @Test
    fun `不知道就是不知道：事实类回答不许编造`() {
        val answer = ReplyCrafter.craft("这个东西是什么")!!
        assertTrue("应该坦白不确定，实际：$answer", answer.contains("不确定") || answer.contains("没有"))
    }

    @Test
    fun `简洁风格下不加开场语和收尾钩子`() {
        val short = ReplyCrafter.craft("为什么会这样", withStyle = false)!!
        val long = ReplyCrafter.craft("为什么会这样", withStyle = true)!!
        assertTrue(short.isNotBlank())
        assertTrue("带样的应该更长：$long", long.length >= short.length)
    }

    @Test
    fun `兜底回复连续两次不一样`() {
        val ctx = DialogContext()
        val first = ReplyCrafter.fallbackReply(ctx)
        ctx.markReply(first)
        val second = ReplyCrafter.fallbackReply(ctx)
        assertNotEquals(first, second)
    }

    // ------------------------------------------------------------------ 训料

    @Test
    fun `训练语料覆盖全部模式且不带占位符残留`() {
        val pairs = ReplyCrafter.trainingPairs()
        assertTrue(pairs.size >= ReplyCrafter.Mode.values().size * 5)
        pairs.forEach { (q, a) ->
            assertFalse("问句不该留占位符：$q", q.contains("{t}"))
            assertFalse("答句不该留占位符：$a", a.contains("{t}"))
            assertTrue("答案不该是空的", a.length >= 10)
        }
        assertTrue(ThoughtBank.allSentences().size >= ReplyCrafter.poolOf(ReplyCrafter.Mode.METHOD).size)
    }

    // ------------------------------------------------------------------ 引擎集成

    @Test
    fun `本地答不上来的选择题走思维模式，而且不去联网`() {
        val engine = AssistantEngine(memory = InMemoryMemoryRepository())
        val input = "我到底该不该换个环境"

        val response = engine.respond(input)
        // 推理框架（DialogLogic）优先，兜不住才轮到思维模式（ReplyCrafter），两者都给思路
        assertTrue(
            "应该给出思考路径，实际意图=${response.intent}",
            response.intent == AssistantEngine.INTENT_THOUGHT ||
                response.intent == AssistantEngine.INTENT_LOGIC_FRAME
        )
        assertTrue("应该有具体步骤或判断角度，实际：${response.text}", response.text.length >= 30)

        // 用户没说「帮我查」，就不该去网上搜
        assertEquals(null, OnlineQuery.plan(input, response.intent))
    }

    @Test
    fun `情绪问题先接住再说，不装作没听见`() {
        val engine = AssistantEngine(memory = InMemoryMemoryRepository())
        val response = engine.respond("最近压力好大")
        assertNotEquals(AssistantEngine.INTENT_FALLBACK, response.intent)
        assertTrue("应该有承接语，实际：${response.text}", response.text.length >= 12)
    }

    @Test
    fun `具体提问不会被闲聊语料抢走`() {
        // 「为什么最近总是失眠」里有闲聊关键词（担心），以前会被闲聊接走，
        // 得到「把担心的事写下来」这种偏题回复；现在应该落到真正有内容的答案上
        val first = AssistantEngine(memory = InMemoryMemoryRepository())
        assertEquals(
            AssistantEngine.INTENT_KNOWLEDGE,
            first.respond("为什么最近总是失眠").intent
        )

        val second = AssistantEngine(memory = InMemoryMemoryRepository())
        assertEquals(
            AssistantEngine.INTENT_KNOWLEDGE,
            second.respond("论文写不出来怎么办").intent
        )
    }

    @Test
    fun `明确要求联网的仍然会联网`() {
        val plan = OnlineQuery.plan("帮我查一下量子纠缠", AssistantEngine.INTENT_FALLBACK)
        assertTrue("用户说「帮我查」就该去查", plan != null)
        assertEquals(OnlineQuery.Kind.SEARCH, plan!!.kind)
    }
}
