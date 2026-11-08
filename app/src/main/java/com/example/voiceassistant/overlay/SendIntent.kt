package com.example.voiceassistant.overlay

/**
 * 认出「要发消息，但还没说发给谁」这类半句话。
 *
 * 小爱同学那套体验的第一步就是这个：用户说「给微信好友发消息」，
 * 它不问「发什么」，而是先把通讯录摆出来让你挑人。
 *
 * 判断办法很笨但很稳：把「发 / 消息 / 微信 / QQ / 好友 / 给 / 帮我」这些
 * 功能性词全部剥掉，看**还剩不剩东西**。
 * - 「给微信好友发消息」→ 什么都不剩 → 只是在说要发消息 → 该挑人；
 * - 「给妈妈发微信说晚点到」→ 剩「妈妈说晚点到」→ 名字和内容都说了 → 不该插嘴，
 *   交给原有的确认流程。
 *
 * 一旦这里误判，用户会莫名被问一句「你要发给谁」——所以宁可保守：
 * 剩下任何有实义的字，都当作「这句子已经说完了」。
 */
object SendIntent {

    /** 收到了一句「要发消息」，但缺收件人 */
    data class Bare(val platform: String)

    /** 功能性词，剥掉之后不影响「说没说人名/内容」的判断 */
    private val FILLER = listOf(
        "帮我", "帮忙", "请", "麻烦", "给", "跟", "和",
        "发", "发送", "送去", "传", "回",
        "一条", "条", "一个", "个", "一句", "句", "一下",
        "消息", "信息", "短信", "话",
        "微信", "wechat", "weixin", "qq", "QQ", "企鹅", "好友", "朋友",
        "吧", "呀", "啊", "呗", "呢", "了", "我", "要", "想", "用", "在",
        "，", ",", "。", "！", "!", "？", "?", "：", ":", " "
    )

    /** 长的先剥，短的后剥，避免「发送」被拆成「发」+「送」 */
    private val FILLER_SORTED = FILLER.sortedByDescending { it.length }

    /**
     * 是这种半句话就返回平台（默认微信），否则 null。
     *
     * @param text 用户原话
     */
    fun parse(text: String): Bare? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        // 必须真的在说「发消息」
        val mentionsSend = raw.contains("发") || raw.contains("传")
        val mentionsMsg = raw.contains("消息") || raw.contains("信息") ||
            raw.contains("微信") || raw.contains("QQ", true) || raw.contains("企鹅")
        if (!mentionsSend || !mentionsMsg) return null

        var rest = raw
        // 长词先剥：「发送」不能被当成「发」+「送」拆开
        for (word in FILLER_SORTED) rest = rest.replace(word, "")
        // 剥完还有实义内容，说明人名或内容已经说了，不该插嘴
        if (rest.isNotBlank()) return null

        return Bare(if (raw.contains("QQ", true) || raw.contains("企鹅")) "qq" else "wechat")
    }

    /** 用户说「算了 / 取消 / 不用了」 */
    fun cancel(text: String): Boolean {
        val clean = text.trim().trim('。', '，', '！', '!', ' ', '~')
        return clean in CANCEL_WORDS
    }

    private val CANCEL_WORDS = setOf(
        "算了", "取消", "不用了", "不用", "不发了", "别发了", "先不发", "退出", "关掉"
    )
}
