package com.example.voiceassistant.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「只说了要发消息，没说发给谁」的识别。
 *
 * 误判的代价是用户明明把话说完了，却被莫名追问一句「你要发给谁」；
 * 漏判的代价只是少了这次引导（原来的流程照常）。所以这里**克制优先**：
 * 剥掉功能词之后只要还剩任何实义内容，就不插嘴。
 */
class SendIntentTest {

    @Test
    fun `光说要发微信消息，没有收件人`() {
        assertEquals("wechat", SendIntent.parse("给微信好友发消息")?.platform)
        assertEquals("wechat", SendIntent.parse("帮我发条微信")?.platform)
        assertEquals("wechat", SendIntent.parse("发微信消息")?.platform)
    }

    @Test
    fun `说要发 QQ 消息时平台认成 qq`() {
        assertEquals("qq", SendIntent.parse("给QQ好友发消息")?.platform)
        assertEquals("qq", SendIntent.parse("帮我发条QQ消息")?.platform)
        assertEquals("qq", SendIntent.parse("给企鹅发个消息")?.platform)
    }

    @Test
    fun `说了人名和内容就不该插嘴`() {
        assertNull(SendIntent.parse("给妈妈发微信说我晚点到"))
        assertNull(SendIntent.parse("给李思旭发条QQ消息说明天见"))
        assertNull(SendIntent.parse("发微信告诉妈妈我到了"))
    }

    @Test
    fun `只说人名没说内容也不插嘴（内容那一问交给原流程）`() {
        assertNull(SendIntent.parse("给妈妈发微信"))
    }

    @Test
    fun `跟发消息无关的话一律不管`() {
        assertNull(SendIntent.parse("打开微信"))
        assertNull(SendIntent.parse("今天天气怎么样"))
        assertNull(SendIntent.parse("帮我调低亮度"))
        assertNull(SendIntent.parse(""))
        assertNull(SendIntent.parse("   "))
    }

    @Test
    fun `只说传文件不算发消息`() {
        assertNull(SendIntent.parse("传个文件给妈妈"))
    }

    // ------------------------------------------------------------------ 取消

    @Test
    fun `常见的取消说法都认`() {
        assertTrue(SendIntent.cancel("算了"))
        assertTrue(SendIntent.cancel("取消"))
        assertTrue(SendIntent.cancel("算了。"))
        assertTrue(SendIntent.cancel("不用了"))
    }

    @Test
    fun `说别的话不算取消`() {
        assertTrue(!SendIntent.cancel("发给妈妈"))
        assertTrue(!SendIntent.cancel("好的"))
    }
}
