package com.example.voiceassistant.core

/**
 * 云端 AI 的总开关（`llm/toggle`）。
 *
 * ## 为什么需要一个全局开关
 * 用户要的不只是「没网时自动禁用」，还要能**主动关掉**：
 * 省流量、怕费额度、或者就想让助手只剩「关手电、定闹钟」这些本机能力。
 *
 * 关掉之后的边界很清楚：
 * - 本地 NLU 命中的指令照常执行（它们本来就不联网）；
 * - 问答、翻译、写文案一律回一句「云端 AI 已关闭」；
 * - 网络状态变化不会偷偷把它打开——用户关的就是关的。
 */
object LlmSwitch {

    @Volatile
    var enabled: Boolean = true
        private set

    /** 最近一次被谁关掉的（界面上要解释原因时用） */
    @Volatile
    var lastReason: String = ""
        private set

    fun set(on: Boolean, reason: String = ""): Boolean {
        enabled = on
        lastReason = if (on) "" else reason
        return enabled
    }
}

/**
 * 对话模式：决定往 system prompt 里塞哪一段说话要求。
 *
 * 这套东西存在的理由很实在——同一个模型，问「什么是傅里叶变换」和
 * 「帮我回一句明天见」，需要的语气、长度、格式完全不一样。
 * 与其让用户每次都说「说短点」，不如给三个模式，一句话切换。
 */
object AssistantMode {

    const val QA = "qa"
    const val MSG = "msg"
    const val STUDY = "study"

    @Volatile
    var current: String = QA
        private set

    /** 切换模式；返回切换后的模式名（不认识的就留在原模式） */
    fun switchTo(raw: String?): String {
        val next = when (raw?.lowercase()) {
            "msg", "message", "消息", "消息模式" -> MSG
            "study", "学习", "学习模式", "背单词" -> STUDY
            "qa", "问答", "问答模式", "默认" -> QA
            else -> current
        }
        current = next
        return next
    }

    /** 人话名字，播报和气泡里用 */
    fun label(mode: String = current): String = when (mode) {
        MSG -> "消息模式"
        STUDY -> "学习模式"
        else -> "问答模式"
    }

    /** 拼进 system prompt 的那一段 */
    fun hint(mode: String = current): String = when (mode) {
        MSG ->
            "现在是消息模式：你的输出会被直接当消息发出去。" +
                "只给一句能直接发的话，不要解释、不要引号、不要「你可以说」。"
        STUDY ->
            "现在是学习模式：讲解要循序渐进，先结论再展开，" +
                "遇到专业词先用一句大白话解释一遍。"
        else -> ""
    }
}
