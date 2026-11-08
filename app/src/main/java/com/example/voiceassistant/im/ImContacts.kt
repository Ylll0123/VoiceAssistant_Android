package com.example.voiceassistant.im

/**
 * 从微信 / QQ 的会话列表里把**人名**挑出来。
 *
 * ## 为什么要读这个
 * 小爱同学那套「给微信好友发消息」的体验里，最关键的一步是：
 * 用户只说「给好友发消息」，助手**自己把最近的几个联系人列出来**，
 * 你说「第 3 个」就行——不用回忆备注名、也不用自己翻通讯录。
 *
 * 我们自己读屏幕比搜索更靠得住：搜索要等输入法、等联想、还可能填错框；
 * 而会话列表就在首页，点一下就能进聊天页。
 *
 * ## 只有几何和文字可用
 * 微信没有对外接口，拿到手的只有无障碍节点：一段文字 + 它在屏幕上的位置。
 * 所以判定全靠「名字长什么样、排在左边、在搜索栏下面」这几条：
 * - **左对齐**：名字在左半屏，时间在右半屏，这一条就能把「昨天 / 周三 / 00:03」筛掉；
 * - **不带冒号**：会话列表里「老爸: 坟墩已经通了」是消息预览，不是名字；
 * - **单行短文本**：名字极少超过 12 个字，长的基本是预览或群公告。
 *
 * 关键：这一层只做**过滤**，UI 上仍然让用户确认选的是谁。
 * 认错一个字的代价是用户重说一句，而不是把消息发错人。
 */
object ImContacts {

    /** 一个屏幕上的文字节点（把几何信息抽出来，纯函数才好测） */
    data class Item(
        val text: String,
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
        val clickable: Boolean = false,
        val editable: Boolean = false
    ) {
        val centerY: Int get() = (top + bottom) / 2
        val width: Int get() = (right - left).coerceAtLeast(0)
    }

    /** 聊天底部 tab、标题栏这些固定词，不能当人名 */
    private val BLACKLIST = setOf(
        "微信", "通讯录", "发现", "我", "搜索", "聊天", "联系人", "消息",
        "QQ", "动态", "看点", "设置", "小程序", "公众号", "微信运动",
        "文件传输助手", "服务通知", "订阅号消息"
    )

    private val TIME_ROW = Regex("^(\\d{1,2}:\\d{2}|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日天]|\\d{1,2}月\\d{1,2}日)$")

    /**
     * 挑出像人名/群名的行，按屏幕从上到下。
     *
     * @param searchBottom 搜索栏下边缘；它上面的都不算会话
     * @param screenWidth  用来判断「在左半边」
     */
    fun pick(
        items: List<Item>,
        screenWidth: Int,
        screenHeight: Int,
        searchBottom: Int,
        limit: Int = 6
    ): List<String> {
        if (screenWidth <= 0 || screenHeight <= 0) return emptyList()
        val out = ArrayList<String>(limit)
        for (item in items) {
            if (item.editable) continue
            val name = item.text.trim()
            if (!looksLikeName(name)) continue
            if (item.top < searchBottom - 8) continue
            if (item.centerY < screenHeight * 0.06 || item.centerY > screenHeight * 0.92) continue
            // 左半屏：名字从左边开始排
            if (item.left > screenWidth * 0.5) continue
            if (out.lastOrNull() == name) continue
            if (out.contains(name)) continue
            out += name
            if (out.size >= limit) break
        }
        return out
    }

    /** 单条文本像不像一个人名 / 群名 */
    fun looksLikeName(raw: String): Boolean {
        val name = raw.trim()
        if (name.isEmpty() || name.length > 12) return false
        if (name in BLACKLIST) return false
        if (TIME_ROW.matches(name)) return false
        // 消息预览带冒号：「老爸: 坟墩已经通了」「石馨瑜: [图片]」
        if (name.contains(':') || name.contains('：')) return false
        // 纯数字/纯符号（未读数、日期）
        if (name.none { it.isLetterOrCjk() }) return false
        // 时间轴、系统提示
        if (name.startsWith("[") && name.endsWith("]")) return false
        return true
    }

    private fun Char.isLetterOrCjk(): Boolean =
        isLetter() || code in 0x4E00..0x9FFF

    /**
     * 语音说「第 3 个 / 三 / 李思旭」时，认出选的是哪一个。
     *
     * 先认序号（中文数字也认），再认名字包含；都没认出来返回 null，
     * 上层会再问一句，而不是瞎猜一个发出去。
     */
    fun choose(spoken: String, names: List<String>): String? {
        if (names.isEmpty()) return null
        val text = spoken.trim()
        if (text.isEmpty()) return null

        // 名字直接说全（或说对一半）
        names.firstOrNull { it == text }?.let { return it }
        names.firstOrNull { text.contains(it) }?.let { return it }
        names.firstOrNull { it.contains(text) && text.length >= 2 }?.let { return it }

        val digits = NUMBER.find(text)?.groupValues?.get(1)
        val index = when {
            digits != null -> digits.toIntOrNull()
            text.any { it in CN_NUM } -> CN_NUM.indexOfFirst { it == text.first { c -> c in CN_NUM } } + 1
            else -> null
        } ?: return null

        return names.getOrNull(index - 1)
    }

    private val NUMBER = Regex("(\\d{1,2})\\s*(?:个|号|位|条)?")
    private val CN_NUM = "一二三四五六七八九十"
}
