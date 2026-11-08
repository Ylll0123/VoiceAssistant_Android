package com.example.voiceassistant.im

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话列表挑人名的规则。
 *
 * 这一层是**唯一**会替用户决定「发到谁」的入口（悬浮助手把结果直接摆出来），
 * 所以每条过滤规则都要有反例钉着：认错了就是发给别人了。
 */
class ImContactsTest {

    private val W = 1080
    private val H = 2400

    /** 一行会话：名字在左，时间在右 */
    private fun row(name: String, top: Int, left: Int = 60, right: Int = 400) =
        ImContacts.Item(
            text = name,
            left = left,
            right = right,
            top = top,
            bottom = top + 80,
            clickable = true
        )

    private fun pick(vararg items: ImContacts.Item, searchBottom: Int = 300, limit: Int = 6) =
        ImContacts.pick(items.toList(), W, H, searchBottom, limit)

    @Test
    fun `正常会话列表按从上到下取出人名`() {
        val out = pick(
            row("老张", 340),
            row("妈妈", 460),
            row("李思旭", 580)
        )
        assertEquals(listOf("老张", "妈妈", "李思旭"), out)
    }

    @Test
    fun `时间戳不会被当成人名`() {
        val out = pick(
            row("妈妈", 340),
            row("昨天", 460),
            row("星期三", 580),
            row("00:03", 700),
            row("11月7日", 820)
        )
        assertEquals(listOf("妈妈"), out)
    }

    @Test
    fun `消息预览带冒号不会被当成人名`() {
        val out = pick(
            row("老爸: 坟墩已经通了", 340),
            row("石馨瑜: [图片]", 460),
            row("妈妈", 580)
        )
        assertEquals(listOf("妈妈"), out)
    }

    @Test
    fun `底部 tab 和标题栏的黑名单词被排除`() {
        val out = pick(
            row("微信", 100),
            row("通讯录", 2200),
            row("妈妈", 340)
        )
        assertEquals(listOf("妈妈"), out)
    }

    @Test
    fun `搜索栏上面的东西不算会话`() {
        val out = pick(
            row("妈妈", 120),
            row("李思旭", 600),
            searchBottom = 300
        )
        assertEquals(listOf("李思旭"), out)
    }

    @Test
    fun `右半屏的文字（时间、未读数）不算会话`() {
        val out = pick(
            row("妈妈", 340),
            row("9", 340, left = 900, right = 960),
            row("李思旭", 460)
        )
        assertEquals(listOf("妈妈", "李思旭"), out)
    }

    @Test
    fun `输入框节点不算会话`() {
        val out = pick(
            ImContacts.Item("妈妈", 60, 400, 340, 420, editable = true),
            row("李思旭", 460)
        )
        assertEquals(listOf("李思旭"), out)
    }

    @Test
    fun `同一个名字只留一次`() {
        val out = pick(
            row("妈妈", 340),
            row("妈妈", 460),
            row("李思旭", 580)
        )
        assertEquals(listOf("妈妈", "李思旭"), out)
    }

    @Test
    fun `超过长度上限的名字算预览不算人名`() {
        assertFalse(ImContacts.looksLikeName("这是一条特别长的群公告内容不该被当成人名"))
        assertTrue(ImContacts.looksLikeName("妈妈说晚点到"))
    }

    @Test
    fun `纯数字和纯符号不是人名`() {
        assertFalse(ImContacts.looksLikeName("12"))
        assertFalse(ImContacts.looksLikeName("..."))
        assertFalse(ImContacts.looksLikeName("[表情]"))
    }

    @Test
    fun `limit 生效`() {
        val out = pick(
            row("甲", 340), row("乙", 460), row("丙", 580), row("丁", 700),
            limit = 2
        )
        assertEquals(listOf("甲", "乙"), out)
    }

    @Test
    fun `屏幕尺寸量不到时返回空表而不是瞎猜`() {
        val items = listOf(row("妈妈", 340))
        assertEquals(
            emptyList<String>(),
            ImContacts.pick(items, 0, 0, 0, 6)
        )
    }

    // ------------------------------------------------------------------ 选人

    private val names = listOf("妈妈", "李思旭", "文件传输助手")

    @Test
    fun `说明字直接命中`() {
        assertEquals("妈妈", ImContacts.choose("妈妈", names))
    }

    @Test
    fun `说「第几个」按序号选`() {
        assertEquals("妈妈", ImContacts.choose("第一个", names))
        assertEquals("李思旭", ImContacts.choose("第 2 个", names))
        assertEquals("文件传输助手", ImContacts.choose("3", names))
    }

    @Test
    fun `说中文数字也认`() {
        assertEquals("李思旭", ImContacts.choose("二", names))
    }

    @Test
    fun `认不出来就返回 null 让上层再问一句`() {
        assertEquals(null, ImContacts.choose("那个谁", names))
        assertEquals(null, ImContacts.choose("", names))
        assertEquals(null, ImContacts.choose("妈妈", emptyList()))
    }

    @Test
    fun `序号越界不会取到别人`() {
        assertEquals(null, ImContacts.choose("第 9 个", names))
    }
}
