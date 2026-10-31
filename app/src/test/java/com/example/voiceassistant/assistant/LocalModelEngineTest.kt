package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.voiceassistant.memory.InMemoryMemoryRepository

/**
 * 小模型接入对话引擎后的端到端行为：
 * 规则、语料、记忆全都答不上来时，由本地小模型兜底生成，
 * 并且每一轮对话都会成为它的训练样本（自学习闭环）。
 *
 * 注意测试句子必须刻意避开规则表 / 闲聊语料 / 预置记忆里的关键词
 * （比如「累」「散步」都会被闲聊接住，那反而是正确行为），
 * 所以这里统一用「月亮湾 / 海豚」这套私造语料。
 */
class LocalModelEngineTest {

    private val corpus = listOf(
        "月亮湾的夜晚特别安静，适合慢慢走走。",
        "海豚馆里最受欢迎的是那只月亮色的海豚。",
        "顺路买了一杯热豆浆，暖暖的很舒服。",
        "热豆浆配油条是那家店的招牌。",
        "招牌菜的分量很足，两个人吃刚刚好。",
        "月亮升起来的时候，海面会亮成一片。"
    )

    private fun newModel(): NgramModel = NgramModel().also { it.observeAll(corpus) }

    private fun engineWithModel(model: NgramModel = newModel()): AssistantEngine {
        AssistantConfig.localModel = true
        return AssistantEngine(memory = InMemoryMemoryRepository(), model = model)
    }

    @Test
    fun `规则语料记忆都答不上来时由小模型兜底`() {
        val engine = engineWithModel()
        val response = engine.respond("我想去月亮湾看海豚")
        assertEquals(AssistantEngine.INTENT_LOCAL_MODEL, response.intent)
        assertTrue("回复不应为空：${response.text}", response.text.isNotBlank())
    }

    @Test
    fun `兜底回复与输入相关`() {
        val engine = engineWithModel()
        val response = engine.respond("我想去月亮湾看海豚")
        val shared = response.text.windowed(2).toSet()
            .intersect("我想去月亮湾看海豚".windowed(2).toSet())
        assertTrue("兜底回复应该跟输入有共同字组：${response.text}", shared.isNotEmpty())
    }

    @Test
    fun `关闭小模型后退回教学提示`() {
        val engine = engineWithModel()
        AssistantConfig.localModel = false
        try {
            val response = engine.respond("我想去月亮湾看海豚")
            assertEquals(AssistantEngine.INTENT_FALLBACK, response.intent)
        } finally {
            AssistantConfig.localModel = true
        }
    }

    @Test
    fun `每一轮对话都成为训练样本`() {
        val engine = engineWithModel()
        val model = engine.localModel()!!
        val sentencesBefore = model.sentenceCount()

        // 这轮命中规则（时间），但输入和回复都会进训练集
        engine.respond("现在几点")
        assertTrue("对话样本应该被学习", model.sentenceCount() > sentencesBefore)
    }

    @Test
    fun `教过的答案也进模型训练集`() {
        val engine = engineWithModel()
        val model = engine.localModel()!!
        val before = model.sentenceCount()
        engine.reply("记住：加班的坏处 = 伤身体还掉头发，别熬太晚")
        assertTrue(model.sentenceCount() > before)
    }

    @Test
    fun `命中规则时依然按规则回答`() {
        val engine = engineWithModel()
        val response = engine.respond("现在几点")
        assertNotEquals(AssistantEngine.INTENT_LOCAL_MODEL, response.intent)
    }

    @Test
    fun `多轮学习后同一句不再走兜底`() {
        AssistantConfig.autoLearn = true
        val engine = engineWithModel()

        // 第一轮：小模型兜底生成
        val first = engine.respond("我想去月亮湾看海豚")
        assertEquals(AssistantEngine.INTENT_LOCAL_MODEL, first.intent)

        // 用户把这句教会（教学流程）
        engine.reply("我教你")
        engine.reply("记住：我想去月亮湾看海豚 = 月亮湾的海豚确实值得一看")
        // 第二轮：应该直接命中记忆，不再走模型
        val second = engine.respond("我想去月亮湾看海豚")
        assertEquals(AssistantEngine.INTENT_MEMORY, second.intent)
    }
}
