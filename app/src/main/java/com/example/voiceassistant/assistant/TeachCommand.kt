package com.example.voiceassistant.assistant

/**
 * 「教一句话」的指令解析（纯函数，可单测）。
 *
 * 支持的说法（问法和答法之间用 = → 回答 / 就说 之类的词分隔）：
 *
 * - 记住：下班了 = 早点回家
 * - 我教你，说「下班了」就回答「早点回家」
 * - 当我说下班了的时候，你就说早点回家
 * - 下班了的答案是早点回家
 *
 * 只认显式教学，避免把普通对话误存成记忆。
 */
data class TeachCommand(
    val question: String,
    val answer: String
) {

    companion object {

        /** 分隔符，按长度从长到短尝试，避免「就回答」被「回答」截断 */
        private const val SEPARATOR = "=|＝|→|->|就回答|就答|你就说|就说是|就说|应该回答|回答|说的是"

        private val PATTERNS: List<Regex> = listOf(
            // 记住：下班了 = 早点回家
            Regex("^记住[：:，,。！! ]*(.+?)(?:$SEPARATOR)(.+)$"),
            // 我教你，说「下班了」就回答「早点回家」
            Regex("^我?教你[：:，,。！! ]*(?:说)?[「\"']?(.+?)[」\"']?(?:$SEPARATOR)[「\"']?(.+?)[」\"']?$"),
            // 当我说下班了的时候，你就说早点回家
            Regex("^当我说(.+?)(?:的时候|时)[，,]?(?:你)?(?:就|要|应该)?(?:说|回答|答)[：:，,]?(.+)$"),
            // 下班了的答案是早点回家
            Regex("^(.+?)(?:的答案是|应该回答|应该答|指的是)(.+)$")
        )

        /** 只有指令没有答案：「我教你」「记住」 */
        private val BARE_REQUEST = Regex("^(我教你|教你|教你说|教你说话|记住|记一下|学一下)[：:，,。！! ]*$")

        fun parse(raw: String): TeachCommand? {
            val text = raw.trim()
            if (text.length < 4) return null
            for (pattern in PATTERNS) {
                val match = pattern.find(text) ?: continue
                val question = cleanup(match.groupValues[1])
                val answer = cleanup(match.groupValues[2])
                if (question.isEmpty() || answer.isEmpty()) continue
                if (question.length > MAX_QUESTION_LENGTH) continue
                return TeachCommand(question, answer)
            }
            return null
        }

        /** 用户只说了「我教你」，等着他说答案 */
        fun isBareRequest(raw: String): Boolean =
            BARE_REQUEST.matches(FuzzyMatcher.normalize(raw).ifEmpty { raw.trim() }) ||
                BARE_REQUEST.matches(raw.trim())

        /** 用户中途放弃教学 */
        fun isCancel(raw: String): Boolean {
            val text = FuzzyMatcher.normalize(raw)
            return text in CANCEL_WORDS
        }

        private val CANCEL_WORDS = setOf("算了", "不教了", "取消", "不用了", "先不教了", "忘了它")

        private const val MAX_QUESTION_LENGTH = 30

        private fun cleanup(text: String): String = text
            .trim()
            .trim('「', '」', '"', '\'', '“', '”', '，', ',', '。', '：', ':', ' ')
            .trim()
    }
}
