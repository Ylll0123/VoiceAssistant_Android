package com.example.voiceassistant.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 平台别名：本地 NLU、大模型、界面传来的写法都得认 */
class ImPlatformTest {

    @Test
    fun `标准 key`() {
        assertEquals(ImPlatform.WECHAT, ImPlatform.of("wechat"))
        assertEquals(ImPlatform.QQ, ImPlatform.of("qq"))
    }

    @Test
    fun `中文与常见别名`() {
        assertEquals(ImPlatform.WECHAT, ImPlatform.of("微信"))
        assertEquals(ImPlatform.WECHAT, ImPlatform.of("weixin"))
        assertEquals(ImPlatform.WECHAT, ImPlatform.of("wx"))
        assertEquals(ImPlatform.QQ, ImPlatform.of("QQ"))
        assertEquals(ImPlatform.QQ, ImPlatform.of("腾讯QQ"))
        assertEquals(ImPlatform.QQ, ImPlatform.of("企鹅"))
    }

    @Test
    fun `大小写包名与空白`() {
        assertEquals(ImPlatform.WECHAT, ImPlatform.of("  WECHAT  "))
        assertEquals(ImPlatform.WECHAT, ImPlatform.of("com.tencent.mm"))
        assertEquals(ImPlatform.QQ, ImPlatform.of("com.tencent.mobileqq"))
    }

    @Test
    fun `没给平台默认微信 不支持的返回 null`() {
        assertEquals(ImPlatform.WECHAT, ImPlatform.of(null))
        assertEquals(ImPlatform.WECHAT, ImPlatform.of(""))
        assertNull(ImPlatform.of("telegram"))
        assertNull(ImPlatform.of("短信"))
    }
}
