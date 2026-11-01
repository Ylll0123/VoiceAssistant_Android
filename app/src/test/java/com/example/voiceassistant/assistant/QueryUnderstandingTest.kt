package com.example.voiceassistant.assistant

import com.example.voiceassistant.data.CloudBrain
import com.example.voiceassistant.data.ChatMessage
import com.example.voiceassistant.data.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地语义理解测试。
 *
 * 这组用例对应的是用户最直接的两个抱怨：
 * 「问题识别不准确」和「回答前后没有逻辑」。
 * 所以这里断言的不是漂亮的分类结果，而是三件实事——
 * 短句别认错、长句能拆对、拆完能归纳成一句可发给云端的简要问题。
 */
class QueryUnderstandingTest {

    // ------------------------------------------------------------ ① 短句精准匹配

    @Test
    fun `常用短句走精准表不靠猜`() {
        assertEquals(QueryUnderstanding.Intent.TIME, QueryUnderstanding.analyze("几点了").intent)
        assertEquals("现在几点", QueryUnderstanding.analyze("几点了").brief)

        assertEquals(QueryUnderstanding.Intent.WEATHER, QueryUnderstanding.analyze("天气怎么样").intent)
        assertEquals("今天天气怎么样", QueryUnderstanding.analyze("天气怎么样").brief)

        assertEquals(QueryUnderstanding.Intent.GREET, QueryUnderstanding.analyze("你好").intent)
    }

    @Test
    fun `带客套前缀的短句也能落进精准表`() {
        // normalize 会先剥掉「请问」再查表
        val one = QueryUnderstanding.analyze("请问几点了")
        assertEquals(QueryUnderstanding.Intent.TIME, one.intent)

        val two = QueryUnderstanding.analyze("你好啊")
        assertEquals(QueryUnderstanding.Intent.GREET, two.intent)
    }

    @Test
    fun `短句不会被切碎`() {
        val result = QueryUnderstanding.analyze("今天天气如何")
        assertEquals(1, result.clauses.size)
    }

    // ------------------------------------------------------------ ② 长句切分

    @Test
    fun `长句按标点切成多段`() {
        val clauses = QueryUnderstanding.split("明天我要去上海出差，那边天气怎么样，要不要带伞")
        assertTrue("应该切出多段，实际：$clauses", clauses.size >= 2)
        assertTrue(clauses.any { it.contains("天气") })
    }

    @Test
    fun `没有标点时靠连词断句`() {
        val clauses = QueryUnderstanding.split("我想了解一下量子计算然后顺便说一下它有什么用")
        assertTrue("应该靠「然后」断开，实际：$clauses", clauses.size >= 2)
    }

    @Test
    fun `语气碎片不单独成段`() {
        val clauses = QueryUnderstanding.split("那个，你好")
        assertTrue("「那个」不该单独成段，实际：$clauses", clauses.size <= 2)
    }

    // ------------------------------------------------------------ ③ 主问题挑选

    @Test
    fun `背景比问题长时也能挑出真正的问句`() {
        // 「明天我要去上海出差」更长，但真正的问题是天气
        val result = QueryUnderstanding.analyze(
            "明天我要去上海出差，那边天气怎么样，要不要带伞"
        )
        assertEquals(QueryUnderstanding.Intent.WEATHER, result.intent)
        assertTrue("归纳结果要落在天气上，实际：${result.brief}", result.brief.contains("天气"))
    }

    @Test
    fun `一句话里同时有算数和闲聊时取算数`() {
        val result = QueryUnderstanding.analyze("你好啊，帮我算一下十二乘以八")
        assertEquals(QueryUnderstanding.Intent.MATH, result.intent)
    }

    // ------------------------------------------------------------ ④ 指代消解

    @Test
    fun `代词换成记住的实体`() {
        val memory = listOf("上海天气怎么样")
        val entity = QueryUnderstanding.rememberedEntity(memory)
        assertEquals("上海", entity)

        val resolved = QueryUnderstanding.resolve("它怎么样", entity)
        assertTrue(resolved.contains("上海"))
    }

    @Test
    fun `没有代词时不乱改句子`() {
        assertEquals("今天天气怎么样", QueryUnderstanding.resolve("今天天气怎么样", "上海"))
    }

    @Test
    fun `analyzer 会把指代后的说法写进归纳结果`() {
        val result = QueryUnderstanding.analyze(
            "那边天气怎么样",
            memory = listOf("上海天气怎么样")
        )
        assertTrue("「那边」应换成上海，实际：${result.brief}", result.brief.contains("上海"))
        assertTrue(
            "思考过程里应说明指代换了什么，实际：${result.steps}",
            result.steps.any { it.contains("指代") }
        )
    }

    // ------------------------------------------------------------ ⑤ 关键词与步骤

    @Test
    fun `关键词能抽出来`() {
        val keys = QueryUnderstanding.keywordsOf("上海明天会不会下雨")
        assertTrue("应包含天气类关键词，实际：$keys", keys.any { it.contains("下雨") })
        assertTrue("应认出城市，实际：$keys", keys.any { it == "上海" })
    }

    @Test
    fun `思考过程不超过五行且非空`() {
        val steps = QueryUnderstanding.analyze(
            "明天我要去上海出差，那边天气怎么样，要不要带伞"
        ).steps
        assertTrue(steps.isNotEmpty())
        assertTrue("步骤太多会看不过来，实际：$steps", steps.size <= 5)
    }

    @Test
    fun `长句会给出重述后的简要问题`() {
        // 有背景铺垫的长句一定会被压缩，压缩结果要出现在思考过程里
        val result = QueryUnderstanding.analyze("明天我要去上海出差，那边天气怎么样，要不要带伞")
        assertTrue(
            "应说明归纳成什么，实际：${result.steps}",
            result.steps.any { it.startsWith("重述") }
        )
    }

    @Test
    fun `压不动的句子就原样发不硬造`() {
        val raw = "我不太明白量子纠缠到底是什么意思"
        val result = QueryUnderstanding.analyze(raw)
        assertEquals(raw, result.brief)
        assertTrue(result.steps.isNotEmpty())
    }

    // ------------------------------------------------------------ ⑥ 归纳结果够短

    @Test
    fun `归纳结果比原句短或相等`() {
        val raw = "明天我要去上海出差，那边天气怎么样，要不要带伞"
        val brief = QueryUnderstanding.analyze(raw).brief
        assertTrue("归纳后不该比原句还长：$brief", brief.length <= raw.length)
        assertTrue("归纳后不该是空话：$brief", brief.isNotBlank())
    }

    // ------------------------------------------------------------ ⑦ 云端只拿精简内容

    @Test
    fun `发给云端的是简要问题`() {
        val raw = "明天我要去上海出差，那边天气怎么样，要不要带伞"
        val brief = QueryUnderstanding.analyze(raw).brief
        assertEquals(brief, CloudBrain.pickInput(raw, brief))
    }

    @Test
    fun `归纳失败时退回原话`() {
        assertEquals("原话", CloudBrain.pickInput("原话", ""))
        assertEquals("原话", CloudBrain.pickInput("原话", "这句归纳得比原话还长很多"))
    }

    @Test
    fun `指代消解后即使略长也要用消解后的说法`() {
        // 「它怎么样」云端看不懂，必须发「上海怎么样」
        val result = QueryUnderstanding.analyze("它怎么样", memory = listOf("上海天气怎么样"))
        assertEquals("上海怎么样", result.brief)
        assertEquals("上海怎么样", CloudBrain.pickInput("它怎么样", result.brief))
    }

    @Test
    fun `历史过长会被截断`() {
        val long = "一二三四五六七八九十".repeat(10)
        val clipped = CloudBrain.clip(long)
        assertTrue(clipped.length <= CloudBrain.MAX_TURN_CHARS + 1)
        assertTrue(clipped.endsWith("…"))
        assertEquals("短的", CloudBrain.clip("短的"))
    }

    // ------------------------------------------------------------ ⑧ 思考气泡

    @Test
    fun `思考过程会随答案出现而消失`() {
        val session = ChatSession()
        session.startThinking("正在理解…")
        session.appendThinking("· 类型：天气")
        assertTrue(session.messages.any { it.kind == ChatMessage.KIND_THINKING })

        session.clearThinking()
        assertTrue("答案出来后不该还留着思考过程", session.messages.none { it.kind == ChatMessage.KIND_THINKING })
    }

    @Test
    fun `思考过程不影响历史与待替换气泡的位置`() {
        val session = ChatSession()
        session.send("你好")
        val before = session.lastAssistantIndex()

        session.startThinking("正在理解…")
        // 待替换的那条仍是刚才那条助手消息，不能被思考条目顶掉
        assertEquals(before, session.lastAssistantIndex())

        val turns = session.recentTurns(2)
        assertTrue("历史里不该混入思考过程：$turns", turns.none { it.content.contains("正在理解") })
    }

    @Test
    fun `最近用户发言可用于指代消解`() {
        val session = ChatSession()
        session.send("上海天气怎么样")
        session.send("你好")
        val lines = session.recentUserLines(QueryUnderstanding.LOCAL_MEMORY_TURNS)
        assertEquals(2, lines.size)
        assertEquals(6, QueryUnderstanding.LOCAL_MEMORY_TURNS)
    }

    @Test
    fun `展示时长随步骤增加而变长`() {
        assertTrue(QueryUnderstanding.holdMs(1) < QueryUnderstanding.holdMs(4))
        assertTrue(QueryUnderstanding.holdMs(9) <= QueryUnderstanding.holdMs(5) + 1)
    }

    // ------------------------------------------------------------ ⑨ 端到端：别把情绪句认错

    @Test
    fun `否定式情绪句不会被认成正面的闲聊请求`() {
        val result = QueryUnderstanding.analyze("我不高兴")
        assertEquals(QueryUnderstanding.Intent.CHAT, result.intent)
        assertTrue(result.brief.contains("不高兴"))
        assertFalse("不该把「不高兴」归纳成高兴", result.brief.contains("很高兴"))
    }
}
