package com.example.voiceassistant.im

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面判定规则：这里判错一次，消息就会被填进搜索栏。
 * 所以几何、提示语、id 三条判据都要单独钉住。
 */
class ImPageRulesTest {

    private val screen = 2000

    @Test
    fun `顶部输入框是搜索框 底部输入框是聊天框`() {
        // 微信搜索页的搜索框在顶部
        assertTrue(ImPageRules.isSearchBox(150, screen, null, null, emptyList()))
        assertFalse(ImPageRules.isChatInput(150, screen, null, null, emptyList()))

        // 聊天页的输入框在底部
        assertFalse(ImPageRules.isSearchBox(1850, screen, null, null, emptyList()))
        assertTrue(ImPageRules.isChatInput(1850, screen, null, null, emptyList()))
    }

    @Test
    fun `几何边界`() {
        val topEdge = (screen * ImPageRules.TOP_FRACTION).toInt() - 1
        val bottomEdge = (screen * ImPageRules.BOTTOM_FRACTION).toInt() + 1
        assertTrue(ImPageRules.isSearchBox(topEdge, screen, null, null, emptyList()))
        assertTrue(ImPageRules.isChatInput(bottomEdge, screen, null, null, emptyList()))
        // 中间那段（搜索结果列表区）谁都不是，避免误认
        val middle = screen / 2
        assertFalse(ImPageRules.isSearchBox(middle, screen, null, null, emptyList()))
        assertFalse(ImPageRules.isChatInput(middle, screen, null, null, emptyList()))
    }

    @Test
    fun `提示文字是搜索 就算在底部也不认成聊天框`() {
        assertTrue(ImPageRules.isSearchBox(1800, screen, "搜索", null, emptyList()))
        assertFalse(ImPageRules.isChatInput(1800, screen, "搜索", null, emptyList()))
        assertTrue(ImPageRules.isSearchHint(null, "搜索"))
        assertTrue(ImPageRules.isSearchHint("Search", null))
        assertFalse(ImPageRules.isSearchHint("发消息", "你好"))
    }

    @Test
    fun `id 带 search 判为搜索框`() {
        val ids = listOf("com.tencent.mm:id/search_input")
        assertTrue(ImPageRules.isSearchBox(1800, screen, null, null, ids))
        assertFalse(ImPageRules.isChatInput(1800, screen, null, null, ids))
        assertTrue(ImPageRules.hasSearchId(listOf("com.tencent.mm:id/SEARCH_bar")))
        assertFalse(ImPageRules.hasSearchId(listOf("com.tencent.mm:id/chat_input")))
    }

    @Test
    fun `量不到位置和屏幕高度时靠语义判断`() {
        // 位置未知：不像搜索框的输入框可以当聊天框用
        assertTrue(ImPageRules.isChatInput(-1, screen, null, null, emptyList()))
        assertFalse(ImPageRules.isChatInput(-1, screen, "搜索", null, emptyList()))
        assertFalse(ImPageRules.isSearchBox(-1, screen, null, null, emptyList()))
        // 屏幕高度未知：不按几何判死，交给语义
        assertFalse(ImPageRules.isSearchBox(800, 0, null, null, emptyList()))
        assertTrue(ImPageRules.isChatInput(800, 0, null, null, emptyList()))
    }

    @Test
    fun `搜索结果里只有真联系人能点`() {
        assertTrue(ImPageRules.usableContact("妈妈"))
        assertTrue(ImPageRules.usableContact("张三（同事）"))
        // 这些行点下去是网页搜索，正是「搜到人却没进去发消息」的元凶
        assertFalse(ImPageRules.usableContact("搜索 妈妈"))
        assertFalse(ImPageRules.usableContact("搜一搜"))
        assertFalse(ImPageRules.usableContact("网络查找：妈妈"))
        assertFalse(ImPageRules.usableContact("   "))
        assertFalse(ImPageRules.usableContact(null))
    }
}
