package com.example.voiceassistant.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 号码与地图链接的纯函数测试。
 *
 * 这两件事在真机上出错的表现都极其含糊——「说了没反应」。
 * 一个号码少了一位数、地名里的 `&` 把链接截断，界面上都看不出来，
 * 所以宁可在这里把格式钉死。
 */
class PhoneFormatsTest {

    @Test
    fun `号码去掉空格横线和括号`() {
        assertEquals("13800000000", PhoneFormats.normalizeNumber("138 0000 0000"))
        assertEquals("13800000000", PhoneFormats.normalizeNumber("138-0000-0000"))
        assertEquals("13800000000", PhoneFormats.normalizeNumber("(138)0000-0000"))
        assertEquals("10086", PhoneFormats.normalizeNumber("10086"))
    }

    @Test
    fun `国际区号的加号必须留在最前面`() {
        assertEquals("+8613800000000", PhoneFormats.normalizeNumber("+86 138 0000 0000"))
        // 号码中间出现的加号没有意义，直接丢掉
        assertEquals("8613800000000", PhoneFormats.normalizeNumber("86+13800000000"))
    }

    @Test
    fun `不是号码的返回空串`() {
        assertEquals("", PhoneFormats.normalizeNumber("妈妈"))
        assertEquals("", PhoneFormats.normalizeNumber(""))
        assertEquals("", PhoneFormats.normalizeNumber(null))
        // 只有两位数字太短，不像号码
        assertEquals("", PhoneFormats.normalizeNumber("12"))
    }

    @Test
    fun `认得出来的才是号码`() {
        assertTrue(PhoneFormats.looksLikeNumber("13800000000"))
        assertTrue(PhoneFormats.looksLikeNumber("138 0000 0000"))
        assertTrue(PhoneFormats.looksLikeNumber("+86 138-0000-0000"))
        assertTrue(PhoneFormats.looksLikeNumber("110"))
        assertFalse(PhoneFormats.looksLikeNumber("妈妈"))
        assertFalse(PhoneFormats.looksLikeNumber("13800000000号"))
        assertFalse(PhoneFormats.looksLikeNumber(""))
    }

    @Test
    fun `地图链接要转义并带上出行方式`() {
        assertEquals(
            "geo:0,0?q=%E9%A6%96%E9%83%BD%E6%9C%BA%E5%9C%BA&mode=w",
            PhoneFormats.geoUri("首都机场", "walk")
        )
        // 没有方式就不带 mode 参数
        assertEquals("geo:0,0?q=%E5%85%AC%E5%8F%B8", PhoneFormats.geoUri("公司"))
    }

    @Test
    fun `地名里的与号和空格不能把链接拼坏`() {
        // & 和空格必须转义，否则 geo: 后面的参数会被截断
        assertEquals("geo:0,0?q=A%26B%20C", PhoneFormats.geoUri("A&B C"))
        assertEquals(
            "https://uri.amap.com/search?keyword=A%26B",
            PhoneFormats.mapWebFallback("A&B")
        )
    }
}
