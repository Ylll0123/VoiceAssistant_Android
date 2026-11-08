package com.example.voiceassistant.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「说 QQ 却发到微信」的防线。
 *
 * 模型看到的 schema 里 platform 是可选的，它图省事就整个不填；
 * 服务那边拿到空值一律按微信处理——用户明明说了 QQ，跳出来的是微信。
 * 这里测的就是**用用户原话把参数改回来**这一步。
 */
class PlatformHintTest {

    @Test
    fun `从原话里认出 QQ`() {
        assertEquals("qq", PlatformHint.of("给妈妈发条QQ消息"))
        assertEquals("qq", PlatformHint.of("用企鹅发给李思旭说我晚点到"))
    }

    @Test
    fun `从原话里认出微信`() {
        assertEquals("wechat", PlatformHint.of("给妈妈发微信"))
        assertEquals("wechat", PlatformHint.of("发个 V信 给老王"))
    }

    @Test
    fun `没提平台就往回 null，不替用户决定`() {
        assertNull(PlatformHint.of("给妈妈发条消息"))
        assertNull(PlatformHint.of("今天几号"))
    }

    @Test
    fun `两个都提到时以最后出现的为准`() {
        // 「把微信里的东西转到 QQ」——真正要动手的是 QQ
        assertEquals("qq", PlatformHint.of("把微信的消息转到QQ"))
        assertEquals("wechat", PlatformHint.of("QQ 上说的转到微信"))
    }

    @Test
    fun `模型漏填 platform 时按原话补上`() {
        val out = PlatformHint.fill(
            "msg_send_text",
            mapOf("to" to "妈妈", "content" to "晚点到"),
            "给妈妈发条QQ消息说晚点到"
        )
        assertEquals("qq", out["platform"])
        // 其它参数不能被碰
        assertEquals("妈妈", out["to"])
        assertEquals("晚点到", out["content"])
    }

    @Test
    fun `模型填错时也按原话改回来`() {
        val out = PlatformHint.fill(
            "msg_send_text",
            mapOf("platform" to "wechat", "to" to "妈妈", "content" to "晚点到"),
            "给妈妈发条QQ消息说晚点到"
        )
        assertEquals("qq", out["platform"])
    }

    @Test
    fun `原话没提平台就尊重模型的选择`() {
        val args = mapOf("platform" to "qq", "to" to "妈妈", "content" to "晚点到")
        val out = PlatformHint.fill("msg_send_text", args, "给妈妈发条消息说晚点到")
        assertEquals("qq", out["platform"])
    }

    @Test
    fun `模型填对了就不动`() {
        val args = mapOf("platform" to "qq", "to" to "妈妈", "content" to "x")
        val out = PlatformHint.fill("msg_send_text", args, "给妈妈发条QQ消息")
        assertEquals("qq", out["platform"])
    }

    @Test
    fun `诊断工具同样校对平台`() {
        val out = PlatformHint.fill("msg_query_can_send", emptyMap(), "为什么 QQ 发不出去")
        assertEquals("qq", out["platform"])
    }

    @Test
    fun `别的工具即便有同名字段也不碰`() {
        val args = mapOf("platform" to "wechat")
        val out = PlatformHint.fill("device/open_app", args, "用QQ打开微信")
        assertEquals("wechat", out["platform"])
    }
}
