package com.example.voiceassistant.data

/** 一条对话消息 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    /** 产生时间，用于「刚刚 / 几分钟前」之类的展示 */
    val at: Long = System.currentTimeMillis(),
    /** 助手消息命中的意图，便于设置页展示和后续埋点；用户消息为 null */
    val intent: String? = null,
    /**
     * 条目类型：[KIND_NORMAL] 是普通对话气泡；
     * [KIND_THINKING] 是「理解过程」——淡色小字，最终答案出现时被移除。
     */
    val kind: String = KIND_NORMAL
) {
    companion object {
        const val KIND_NORMAL = "normal"
        const val KIND_THINKING = "thinking"

        fun thinking(text: String) = ChatMessage(text = text, isUser = false, kind = KIND_THINKING)
    }
}
