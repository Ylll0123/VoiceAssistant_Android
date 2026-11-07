package com.example.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 「整句」模式测试。
 *
 * 语音输入是一整段话，本地规则却是按短指令写的。如果一条规则只要
 * 句子里**含有**某个关键词就抢着执行，后半句就被吞掉了——
 * 用户看到的现象就是「只认出一个词」。
 *
 * 这里钉死两件事：
 * 1. 在线（strict）时，复合句/长句必须放行给大模型；
 * 2. 离线（宽松）时，同样的句子仍要能本地办，别把可用性弄丢。
 */
class WholeSentenceTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)

    private fun strict(text: String): LocalNlu.Hit? = LocalNlu.route(text, now, strict = true)

    private fun loose(text: String): LocalNlu.Hit? = LocalNlu.route(text, now, strict = false)

    @Test
    fun `短指令在整句模式下照旧本地执行`() {
        assertEquals("device/flashlight", strict("打开手电筒")?.service)
        assertEquals("device/brightness", strict("把亮度调暗一点")?.service)
        assertEquals("audio/volume_ctrl", strict("音量调到 30%")?.service)
    }

    @Test
    fun `两句连在一起不许被前一句吞掉`() {
        val said = "把亮度调暗一点然后放首歌"
        // 在线：交给大模型拆开做（它会连播歌一起处理）
        assertNull(strict(said))
        // 离线：至少把能做的做掉，不能整句作废
        assertEquals("device/brightness", loose(said)?.service)
    }

    @Test
    fun `先后顺序的复合句也放行`() {
        assertNull(strict("先帮我把手电筒打开再查一下明天天气"))
        assertNull(strict("提醒我十分钟后给妈妈打电话，然后关掉手电筒"))
    }

    @Test
    fun `长句里只捞到一个关键词不足以拍板`() {
        val said = "我现在在外面天已经完全黑下来了能不能帮我把手电筒打开照一下路"
        assertNull(strict(said))
    }

    @Test
    fun `发消息的内容是用户原话 不该被当成复合句`() {
        val hit = strict("给妈妈发微信说然后我晚点到")
        assertEquals("msg/send_text", hit?.service)
        assertNotNull(hit)
    }

    @Test
    fun `带条件的句子交给大模型判断`() {
        assertNull(strict("如果太晚了就帮我把手电筒打开"))
        assertNull(strict("要是我忘了你就提醒我八点开会"))
    }

    @Test
    fun `并列的还有也算两件事`() {
        assertNull(strict("打开手电筒还有把音量调到一半"))
        // 离线时仍把认得出的那半件做掉
        assertEquals("device/flashlight", loose("打开手电筒还有把音量调到一半")?.service)
    }
}
