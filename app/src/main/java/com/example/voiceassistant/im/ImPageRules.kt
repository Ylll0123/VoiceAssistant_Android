package com.example.voiceassistant.im

/**
 * 「页面上这个输入框到底是搜索框还是聊天输入框」的判定规则。
 *
 * 抽成纯函数是有原因的：这里判错一次，就会出现**把消息内容填进搜索栏**，
 * 用户看到的现象是「搜了个人，却在搜索框里搜了一句话」。
 * 纯函数能在 JVM 单测里把各种界面尺寸、提示文字、id 兜一遍，不用真机试错。
 *
 * 两个判据叠加，任何一条命中就算：
 * 1. **语义**：提示文字是「搜索」、或者 id 里带 search；
 * 2. **几何**：搜索框在屏幕上半，聊天输入框在底部。
 *
 * 微信、QQ 各版本改 id 改文案是常事，但「搜索在顶、聊天输入在底」这个布局
 * 十几年没变过，所以几何是更耐久的兜底。
 */
object ImPageRules {

    private val SEARCH_WORDS = listOf("搜索", "搜一搜", "Search")

    /** 屏幕上半这段算搜索框 */
    const val TOP_FRACTION = 0.45f

    /** 屏幕这段以下算聊天输入框 */
    const val BOTTOM_FRACTION = 0.55f

    /** 提示语/文字像搜索 */
    fun isSearchHint(hint: String?, text: String?): Boolean {
        val h = hint.orEmpty()
        val t = text.orEmpty()
        val hintHit = SEARCH_WORDS.any { h.contains(it, ignoreCase = true) }
        // 搜索框里用户输入的内容可能是任意字，所以文字只认「以搜索开头」这种占位文案
        val textHit = SEARCH_WORDS.any { t == it || t.startsWith(it) }
        return hintHit || textHit
    }

    /** 自身或祖先的资源 id 带 search */
    fun hasSearchId(ids: List<String>): Boolean = ids.any { it.lowercase().contains("search") }

    /** 位置在屏幕上半 */
    fun isTop(centerY: Int, screenHeight: Int): Boolean =
        screenHeight > 0 && centerY >= 0 && centerY < TOP_FRACTION * screenHeight

    /**
     * 是不是搜索框。
     *
     * [centerY] 传 -1 表示量不到位置，此时只靠语义判断。
     */
    fun isSearchBox(
        centerY: Int,
        screenHeight: Int,
        hint: String?,
        text: String?,
        ids: List<String>
    ): Boolean {
        if (isSearchHint(hint, text) || hasSearchId(ids)) return true
        return isTop(centerY, screenHeight)
    }

    /**
     * 是不是聊天输入框：不像搜索框，且落在下半屏。
     *
     * 量不到屏幕高度或位置时放过——这时候宁可相信语义判断，
     * 也不要把一个明明能用的输入框拒掉。
     */
    fun isChatInput(
        centerY: Int,
        screenHeight: Int,
        hint: String?,
        text: String?,
        ids: List<String>
    ): Boolean {
        if (isSearchHint(hint, text) || hasSearchId(ids)) return false
        if (screenHeight <= 0 || centerY < 0) return true
        return centerY > BOTTOM_FRACTION * screenHeight
    }

    /**
     * 搜索结果里这条文字能不能当联系人点。
     *
     * 「搜索「妈妈」」「网络查找」「搜一搜」这些行点下去是网页搜索，
     * 不是聊天——以前正是点了它们，才「搜到对象却没进去发消息」。
     */
    fun usableContact(text: String?): Boolean {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return false
        if (SEARCH_WORDS.any { t.startsWith(it) }) return false
        if (t.contains("网络查找")) return false
        return true
    }
}
