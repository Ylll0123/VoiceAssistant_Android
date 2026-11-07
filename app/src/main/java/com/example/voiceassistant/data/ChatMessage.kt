package com.example.voiceassistant.data

/**
 * 一条对话消息。
 *
 * v4.0 起只有「用户说的」和「助手答的」两种，
 * 不再有「理解过程」那种中间态消息——思考状态改成顶栏上的一行小字，
 * 不占对话列表，也就不会被存进对话记录里。
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    /** 产生时间，用于「刚刚 / 几分钟前」之类的展示 */
    val at: Long = System.currentTimeMillis(),
    /** 助手消息命中的工具名，便于排查「它到底干了什么」；用户消息为 null */
    val intent: String? = null
)
