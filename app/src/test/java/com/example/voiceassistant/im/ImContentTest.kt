package com.example.voiceassistant.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待发送内容的清洗规则。
 *
 * 这一条最容易出错、也最容易被忽略：内容多一个「说」、少一个问号，
 * 用户看到的就是「发出去的话不像我说的」。
 */
class ImContentTest {

    @Test
    fun `模型带壳的话要剥干净`() {
        assertEquals("我晚点到", ImContent.clean("好的，内容是：我晚点到"))
        assertEquals("我晚点到", ImContent.clean("你可以这样说：我晚点到"))
        assertEquals("我晚点到", ImContent.clean("说一声，我晚点到"))
    }

    @Test
    fun `引号一层层剥掉`() {
        assertEquals("我晚点到", ImContent.clean("\"我晚点到\""))
        assertEquals("我晚点到", ImContent.clean("「我晚点到」"))
        assertEquals("我晚点到", ImContent.clean("“我晚点到”"))
        assertEquals("我晚点到", ImContent.clean("\"「我晚点到」\""))
    }

    @Test
    fun `问句原样保留 不要当成要回答的问题`() {
        assertEquals("你吃饭了吗", ImContent.clean("你吃饭了吗"))
        assertEquals("你吃饭了吗？", ImContent.clean("你吃饭了吗？"))
        assertEquals("明天几点开会？", ImContent.clean("明天几点开会？"))
    }

    @Test
    fun `换行和连续空格压成一个空格`() {
        assertEquals("我 晚点 到", ImContent.clean("我  晚点\n\n到"))
    }

    @Test
    fun `空内容与纯标点算没内容`() {
        assertEquals("", ImContent.clean(null))
        assertEquals("", ImContent.clean("   "))
        assertEquals("", ImContent.clean("，，。"))
        assertEquals("", ImContent.clean("「」"))
    }

    @Test
    fun `太长会截断 但不会截成空`() {
        val long = "啊".repeat(400)
        val cleaned = ImContent.clean(long)
        assertEquals(ImContent.MAX_LENGTH, cleaned.length)
        assertTrue(cleaned.isNotEmpty())
    }
}
