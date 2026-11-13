package com.example.voiceassistant.im

/**
 * 发出去的那句话怎么算「合格」。
 *
 * ## 为什么要在这一层再洗一遍
 * 内容有两条来路：本地规则从原话里切出来、大模型填进 `content` 参数。
 * 两条来路都会带上不该发的东西——
 * - 本地切出来的常常残留「说」「问」或者句首的逗号冒号；
 * - 模型喜欢给内容套引号，偶尔还写「好的，内容是：……」；
 * - 语音识别会带换行和连续空格。
 *
 * 以前这些原样发出去，用户看到的是一句带壳的话。这里一次性收拾干净，
 * 两条来路共用同一条规则，也就能被单测钉住。
 *
 * ## 为什么不改标点、不改语气
 * 发出去的是**用户的原话**，不是我们润色过的版本。
 * 问句照样是问句——「你吃饭了吗」必须原样发出去，不能被当成一个要回答的问题。
 */
object ImContent {

    /** 一条消息的长度上限：再长就不是「发条消息」了，多半是识别串了 */
    const val MAX_LENGTH = 180

    /**
     * 清洗一条待发送内容。
     *
     * @return 可以直接发出去的正文；不像正文时返回空串，由调用方决定怎么问。
     */
    fun clean(raw: String?): String {
        if (raw.isNullOrBlank()) return ""

        var text = raw.trim()

        // 模型偶尔带壳：「好的，内容是：xxx」「你可以这样说：xxx」
        text = text.removePrefix("好的").removePrefix("可以")
        text = text.trimStart('，', ',', '：', ':', '。', ' ')
        text = text.replaceFirst(Regex("^(?:内容是?|你可以这样说|这样说|回复内容)[：:]\\s*"), "")

        // 一层层剥引号：模型常套两层
        repeat(2) {
            text = text.trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
                .removeSurrounding("「", "」")
                .removeSurrounding("“", "”")
                .removeSurrounding("『", "』")
        }

        // 句首的引子与标点：「说一声，我晚点到」→「我晚点到」
        text = text.replaceFirst(
            Regex("^(?:说一声|说一下|说一句|说句|转告|带个话|捎句话?)\\s*[，,：:]?\\s*"),
            ""
        )
        text = text.trimStart('，', ',', '：', ':', '、', '。', '；', ';')

        // 语音识别会带换行和连续空格；正文里只需要单个空格
        text = text.replace(Regex("[\\r\\n]+"), " ")
        text = text.replace(Regex("[ \\t]{2,}"), " ")

        text = text.trim()
        if (text.isEmpty()) return ""
        // 只剩标点也当没内容
        if (text.all { !it.isLetterOrDigit() }) return ""
        return text.take(MAX_LENGTH)
    }
}
