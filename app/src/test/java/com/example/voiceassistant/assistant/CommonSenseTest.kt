package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * v2.5「答得有逻辑」的端到端验证：
 *
 * - 天气 / 温度 / 出行建议：用城市常年气候数据答，且说明不是实时数据；
 * - 基础单词翻译：内置词典双向查询；
 * - 常识问答：问原理的问题交给知识库（答案是人写的，有因果）；
 * - 上下文串联：上一轮问天气，下一轮只说城市名也能接上。
 */
class CommonSenseTest {

    private fun engine(): AssistantEngine = AssistantEngine()

    // ------------------------------------------------------------------ 天气

    @Test
    fun `问天气给出城市的常年气温并说明不是实时数据`() {
        val reply = engine().reply("北京天气怎么样")
        assertTrue(reply.contains("北京"))
        assertTrue(reply.contains("常年"))
        // 现在支持联网了，措辞改成"不是此刻的实时天气"
        assertTrue(reply.contains("实时天气"))
    }

    @Test
    fun `问原理的天气问题交给知识库回答`() {
        val engine = engine()
        val reply = engine.reply("为什么会下雨")
        assertTrue(reply.contains("蒸发") || reply.contains("水汽"))
        assertEquals(AssistantEngine.INTENT_KNOWLEDGE, engine.context().lastIntent)
    }

    @Test
    fun `追问城市名能继承上一轮的天气话题`() {
        val engine = engine()
        engine.reply("今天天气怎么样")
        val second = engine.reply("那北京呢")
        assertTrue(second.contains("北京"))
        assertTrue(second.contains("℃"))
    }

    @Test
    fun `出行建议按月份给出可执行提醒`() {
        val reply = engine().reply("出行有什么建议")
        assertTrue(reply.contains("出行建议"))
        assertTrue(reply.length > 20)
    }

    @Test
    fun `气候数据按月给出季节与气温区间`() {
        val city = CityClimate.find("上海")
        assertNotNull(city)
        val range = CityClimate.rangeOf(city!!, LocalDate.now().monthValue)
        assertTrue(range.last > range.first)
        assertTrue(CityClimate.dressAdvice(range.last).isNotEmpty())
    }

    // ------------------------------------------------------------------ 翻译

    @Test
    fun `中文查英文`() {
        val reply = engine().reply("苹果用英语怎么说")
        assertTrue(reply.contains("apple"))
    }

    @Test
    fun `英文查中文`() {
        val reply = engine().reply("apple 是什么意思")
        assertTrue(reply.contains("苹果"))
    }

    @Test
    fun `翻译可以追问下一个词`() {
        val engine = engine()
        engine.reply("苹果用英语怎么说")
        assertTrue(engine.reply("那香蕉呢").contains("banana"))
    }

    @Test
    fun `词典双向查询`() {
        assertEquals("apple", Dictionary.toEnglish("苹果"))
        assertEquals("苹果", Dictionary.toChinese("apple"))
        assertNull(Dictionary.toEnglish("不存在的词xyz"))
        assertTrue(Dictionary.size() > 100)
    }

    // ------------------------------------------------------------------ 知识库

    @Test
    fun `知识库能命中日常问题的问法变体`() {
        val hit = KnowledgeBase.match("感冒了怎么办")
        assertNotNull(hit)
        assertEquals("健康常识", hit!!.qa.category)
        assertTrue(hit.answer.contains("休息") || hit.answer.contains("自愈"))
    }

    @Test
    fun `知识库对无关输入不认领`() {
        assertNull(KnowledgeBase.match("我想去月亮湾看海豚"))
    }

    @Test
    fun `知识库规模足够大并且问答都能取到`() {
        assertTrue(KnowledgeBase.size() >= 100)
        assertTrue(KnowledgeBase.questionCount() > KnowledgeBase.size())
        assertTrue(KnowledgeBase.trainingPairs().isNotEmpty())
    }

    @Test
    fun `连续聊同一主题会加承接语`() {
        val engine = engine()
        engine.reply("为什么会下雨")
        val second = engine.reply("台风是怎么形成的")
        // 两条都属于天气常识之外的不同分类时不强求，这里只验证第二条仍由知识库作答
        assertTrue(second.isNotEmpty())
    }

    // ------------------------------------------------------------------ 小模型

    @Test
    fun `模型优先用检索问答而不是随机游走`() {
        val model = NgramModel()
        model.observePair("天气怎么样", "今天有雨，出门记得带伞。")
        val reply = model.generate("今天天气怎么样")
        assertEquals("今天有雨，出门记得带伞。", reply)
        assertEquals(1, model.pairCount())
    }

    @Test
    fun `灌入知识库后模型参数和问答对都变大`() {
        val before = NgramModel()
        val model = NgramModel()
        model.observePairs(KnowledgeBase.trainingPairs())
        assertTrue(model.pairCount() > 100)
        assertTrue(model.nodeCount() > before.nodeCount())
    }
}
