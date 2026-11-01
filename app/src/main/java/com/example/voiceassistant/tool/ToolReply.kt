package com.example.voiceassistant.tool

/** 工具层：一次工具调用的结果 */
data class ToolReply(
    val text: String,
    val intent: String,
    /** 工具名，便于设置页 / 日志展示是谁答的 */
    val tool: String
)

/** 工具层产出的意图常量（与规则层意图并列，供路由统计与展示） */
object ToolIntent {
    const val TIME = "time"
    const val CALC = "calc"
    const val REMINDER = "reminder"
    const val VOLUME = "volume"

    /** 让助手闭嘴（静音 / 停止播报），UI 层据此 stopSpeaking */
    const val TTS_STOP = "tts_stop"
}
