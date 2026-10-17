package com.example.voiceassistant.data

/** 一条对话消息 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean
)
