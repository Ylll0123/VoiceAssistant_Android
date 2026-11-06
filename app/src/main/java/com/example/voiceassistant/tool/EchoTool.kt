package com.example.voiceassistant.tool

/**
 * 工具层：**复读 / 朗读**。
 *
 * 「说出『我爱中国』」「帮我朗读这段话」「跟我念：好好学习」——
 * 直接把后面的字原样说回去，不问也不搜，本机 0 请求。
 *
 * ## 为什么只在工具层做
 * 这类话的"答案"就是输入本身，走本地问答会被规则 / 小模型
 * 改写成别的；走联网更是浪费。直接在这里截下来原样返回最稳。
 *
 * ## 动词表刻意收得窄
 * 只有「说出 / 念出 / 朗读 / 复读 / 跟我读 / 跟我念 / 重复 / 背诵」
 * 这类**明确表示"照着念"**的词才认领；
 * 单独的「说」不认领——「说个笑话」「说说天气」是在提问，不是让复读。
 */
object EchoTool {

    const val NAME = "echo"

    private val PATTERN = Regex(
        "^(?:请|麻烦|帮我|给我)?\\s*" +
            "(说出|念出|朗读|复读|跟我读|跟我念|背诵|重复一遍|重复一下|说一遍|念一遍)" +
            "[\\s:：,，、]*(.+)$"
    )

    /** 这句话是不是在让助手照着念（纯判定，无副作用） */
    fun matches(raw: String): Boolean = extract(raw) != null

    /** 命中就原样返回后面的字；没命中返回 null */
    fun handle(raw: String): ToolReply? {
        val content = extract(raw) ?: return null
        return ToolReply(content, ToolIntent.ECHO, NAME)
    }

    /** 抽出要念的那段；内容为空或有疑问就返回 null（交给后面的路线） */
    fun extract(raw: String): String? {
        val match = PATTERN.find(raw.trim()) ?: return null
        val content = match.groupValues[2]
            .trim()
            .trim('。', '！', '？', '.', '!', '?')
        // 只喊了动词没给内容（"说出来了""朗读一下"），别瞎念，交给问答路线解释
        if (content.isBlank()) return null
        return content
    }
}
