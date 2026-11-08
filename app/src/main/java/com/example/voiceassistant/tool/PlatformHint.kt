package com.example.voiceassistant.tool

/**
 * 「用户到底说了哪个平台」——**从用户原话里认，不从模型嘴里认**。
 *
 * ## 为什么要有这一层
 * `msg_send_text` 的 platform 是个可选参数，schema 里写着「默认 wechat」。
 * 模型看到用户说「给妈妈发条 QQ 消息」，经常图省事整个参数不填，
 * 于是服务拿到空值 → 默认微信 → **用户明明说的 QQ，跳出来的是微信**。
 *
 * 这不是模型笨，是我们在接口上给了它偷懒的机会。补救办法不是反复在提示词里
 * 求它别偷懒（下次换模型又失效），而是**在动手之前用用户原话校对一遍**：
 * 用户这句里出现过「QQ / 企鹅」，那就是 QQ，模型说什么都不算。
 *
 * 纯函数，没有任何 Android 依赖，方便单测钉住。
 */
object PlatformHint {

    /** 会带 platform 参数的工具 */
    private val PLATFORM_TOOLS = setOf("msg_send_text", "msg_query_can_send")

    private val QQ_WORDS = listOf("qq", "QQ", "Qq", "企鹅", "扣扣", "q q")
    private val WECHAT_WORDS = listOf("微信", "wechat", "WeChat", "weixin", "威信", "V信", "vx")

    /**
     * 从一句话里认出平台：认出来返回 `qq` / `wechat`，认不出返回 null。
     *
     * QQ 先判：「QQ」和「微信」同时出现时（「把微信的消息转到QQ」），
     * 用户要动手的那一头通常是最后提到的那个；这里取**最后出现**的那个词，
     * 谁在后面谁说了算。
     */
    fun of(text: String): String? {
        val qqAt = lastIndexOfAny(text, QQ_WORDS)
        val wxAt = lastIndexOfAny(text, WECHAT_WORDS)
        return when {
            qqAt < 0 && wxAt < 0 -> null
            qqAt < 0 -> "wechat"
            wxAt < 0 -> "qq"
            qqAt > wxAt -> "qq"
            else -> "wechat"
        }
    }

    private fun lastIndexOfAny(text: String, words: List<String>): Int =
        words.maxOfOrNull { text.lastIndexOf(it) } ?: -1

    /**
     * 给工具参数补/纠正 platform。
     *
     * 只动 [PLATFORM_TOOLS] 里的工具；别的工具就算有同名字段也不碰。
     *
     * @param userText 用户这一轮的原话（不是模型生成的）
     */
    fun fill(tool: String, args: Map<String, String>, userText: String): Map<String, String> {
        if (tool !in PLATFORM_TOOLS) return args
        val said = of(userText) ?: return args
        val given = args["platform"]?.trim().orEmpty().lowercase()
        // 模型没填、或填的和用户说的不一致，一律以用户原话为准
        val wrong = given.isEmpty() || !matches(given, said)
        if (!wrong) return args
        return args + ("platform" to said)
    }

    private fun matches(given: String, said: String): Boolean {
        val qq = listOf("qq", "企鹅", "mobileqq")
        val wx = listOf("wechat", "weixin", "wx", "微信", "mm")
        return if (said == "qq") qq.any { given.contains(it) } else wx.any { given.contains(it) }
    }
}
