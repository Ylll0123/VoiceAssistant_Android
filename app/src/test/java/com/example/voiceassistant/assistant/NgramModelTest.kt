package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 本地小模型（字符级 n-gram）的行为验证。
 *
 * 重点不是生成文本的文学水平，而是：
 * 1. 相关性——输入里出现过的字组，生成结果里也得有；
 * 2. 没有共同字组时宁可承认不会（返回 null），也不瞎编；
 * 3. 参数能序列化 / 恢复，重启后接着学；
 * 4. 每次学习都反映到参数规模上（增量学习是真实的）。
 */
class NgramModelTest {

    private val corpus = listOf(
        "那就先停一下，五分钟什么都不干。",
        "累的时候别急着讲道理，先让自己坐下来。",
        "身体累睡一觉能缓，心里累得有人听你说完。",
        "累通常不是某一件事，是一堆小事叠在一起。",
        "听起来是撑了很久了，今天最耗你的是哪一件？",
        "去吃点东西吧，别扛着。",
        "饿着的时候判断力会变差。",
        "把手机放远一点，闭上眼慢慢数呼吸。"
    )

    private fun trainedModel(): NgramModel = NgramModel().also { it.observeAll(corpus) }

    @Test
    fun `训练后能生成和输入相关的回复`() {
        val model = trainedModel()
        val reply = model.generate("我今天上班好累啊", Random(42))
        assertNotNull("语料里有「累」相关的句子，应该能生成", reply)
        val text = reply!!
        assertTrue(
            "生成结果应该跟输入有共同字组：$text",
            text.windowed(2).any { "我今天上班好累啊".contains(it.substring(0, 2)) ||
                text.take(2) in "我今天上班好累啊".windowed(2) ||
                text.windowed(2).intersect("我今天上班好累啊".windowed(2).toSet()).isNotEmpty()
            }
        )
    }

    @Test
    fun `没有共同字组时承认不会`() {
        val model = trainedModel()
        assertNull(model.generate("量子纠缠的波函数坍缩"))
    }

    @Test
    fun `空模型和空输入不崩溃`() {
        val model = NgramModel()
        assertNull(model.generate("好累"))
        assertNull(trainedModel().generate(""))
    }

    @Test
    fun `增量学习反映在参数规模上`() {
        val model = NgramModel()
        val before = model.nodeCount()
        model.observe("把手机放远一点，慢慢数呼吸。")
        val afterFirst = model.nodeCount()
        assertTrue("学习一句话应该增加参数：$before -> $afterFirst", afterFirst > before)
        model.observe("学一句新话，模型参数应该继续变多。")
        assertTrue(model.nodeCount() > afterFirst)
        // 同一句重复学不增加参数
        val stable = model.nodeCount()
        model.observe("把手机放远一点，慢慢数呼吸。")
        assertEquals(stable, model.nodeCount())
    }

    @Test
    fun `序列化后可以恢复并继续生成`() {
        val model = trainedModel()
        val saved = model.serialize()
        assertTrue("序列化应该包含训练句子", saved.contains("累的时候别急着讲道理"))

        val restored = NgramModel()
        assertTrue(restored.loadInto(saved))
        assertEquals(model.sentenceCount(), restored.sentenceCount())
        assertNotNull(restored.generate("我好累啊今天", Random(7)))
    }

    @Test
    fun `空内容不能恢复`() {
        val model = NgramModel()
        assertTrue(!model.loadInto(""))
        assertTrue(!model.loadInto("\n\n"))
    }

    @Test
    fun `重复训练同一句不重复计数`() {
        val model = NgramModel()
        model.observe("今天天气不错")
        val nodes = model.nodeCount()
        model.observe("今天天气不错")
        assertEquals(nodes, model.nodeCount())
        assertEquals(1, model.sentenceCount())
    }

    @Test
    fun `太短或太乱的输入不进训练集`() {
        val model = NgramModel()
        model.observe("嗯")
        assertEquals(0, model.sentenceCount())
    }
}
