package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 极简 JSON 解析器：解析对了，联网结果整理才可能对 */
class JsonLiteTest {

    @Test
    fun `解析对象与嵌套取值`() {
        val root = JsonLite.parse("""{"current":{"temp":12.5},"ok":true}""")
        assertEquals(12.5, JsonLite.num(JsonLite.getIn(root, "current", "temp"))!!, 0.001)
        assertEquals(true, JsonLite.get(root, "ok"))
    }

    @Test
    fun `解析数组并取第一个元素`() {
        val root = JsonLite.parse("""{"weather":[{"maxtempC":"22","desc":[{"value":"Sunny"}]}]}""")
        val first = JsonLite.arr(JsonLite.get(root, "weather")).firstOrNull()
        assertEquals(22.0, JsonLite.num(JsonLite.get(first, "maxtempC"))!!, 0.001)
        assertEquals(
            "Sunny",
            JsonLite.str(JsonLite.get(JsonLite.arr(JsonLite.get(first, "desc")).firstOrNull(), "value"))
        )
    }

    @Test
    fun `字符串转义要还原`() {
        val root = JsonLite.parse("""{"t":"a\"b\\c\ndé"}""")
        assertEquals("a\"b\\c\ndé", JsonLite.str(JsonLite.get(root, "t")))
    }

    @Test
    fun `null 与非法输入都不会崩`() {
        assertNull(JsonLite.parseOrNull(null))
        assertNull(JsonLite.parseOrNull(""))
        assertNull(JsonLite.parseOrNull("{oops"))
        assertNull(JsonLite.parseOrNull("不是 JSON"))
    }

    @Test
    fun `数字即使是字符串形式也能取成数字`() {
        assertEquals(8.0, JsonLite.num("8")!!, 0.001)
        assertEquals(-3.0, JsonLite.num(-3.0)!!, 0.001)
        assertEquals(20, JsonLite.int("20"))
    }
}
