package com.example.voiceassistant.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复读 / 朗读工具。
 *
 * 两条铁律：
 * 1. 让照着念的，**一个字都不能改**地还回去；
 * 2. 正常提问（「说个笑话」「说说天气」）绝不能抢。
 */
class EchoToolTest {

    @Test
    fun `说出后面的字原样返回`() {
        val reply = EchoTool.handle("说出我爱中国")
        assertEquals("我爱中国", reply?.text)
        assertEquals(ToolIntent.ECHO, reply?.intent)
    }

    @Test
    fun `各种明确动词都能认领`() {
        listOf(
            "帮我朗读好好学习天天向上" to "好好学习天天向上",
            "跟我念欢迎光临" to "欢迎光临",
            "复读：今天星期五" to "今天星期五",
            "重复一遍我爱中国" to "我爱中国",
            "背诵锄禾日当午" to "锄禾日当午",
            "念出这段文字" to "这段文字"
        ).forEach { (input, expected) ->
            assertEquals("「$input」应念出「$expected」", expected, EchoTool.extract(input))
        }
    }

    @Test
    fun `标点分隔与结尾句号被剥掉`() {
        assertEquals("我爱中国", EchoTool.extract("说出：我爱中国。"))
        assertEquals("我爱中国", EchoTool.extract("说出，我爱中国！"))
    }

    @Test
    fun `没有内容不认领`() {
        assertNull(EchoTool.extract("说出"))
        assertNull(EchoTool.extract("朗读"))
        assertFalse(EchoTool.matches("说出 "))
    }

    @Test
    fun `正常提问绝不抢`() {
        listOf(
            "说个笑话",
            "说说天气怎么样",
            "给我讲讲黑洞",
            "重复利用是什么意思",
            "上海天气怎么样"
        ).forEach { input ->
            assertFalse("「$input」不该被复读抢走", EchoTool.matches(input))
        }
    }

    @Test
    fun `ToolHandler 整条链路能跑通`() {
        val handler = ToolHandler()
        val reply = handler.handle(
            "说出我爱中国",
            com.example.voiceassistant.assistant.AssistantResponse("本地占位", "local")
        )
        assertEquals("我爱中国", reply?.text)
        assertEquals(ToolIntent.ECHO, reply?.intent)
    }
}
