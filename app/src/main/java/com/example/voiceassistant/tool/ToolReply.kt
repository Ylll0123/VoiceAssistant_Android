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

    /** 计时 / 倒计时 */
    const val TIMER = "timer"

    /** 打开应用之类的设备操作 */
    const val DEVICE = "device"

    /** 手电筒 / 电量：单独标出来，设置页统计时能和「开应用」区分开 */
    const val TORCH = "torch"
    const val BATTERY = "battery"

    /** 让助手闭嘴（静音 / 停止播报），UI 层据此 stopSpeaking */
    const val TTS_STOP = "tts_stop"

    /** 复读 / 朗读：原样说出后面的字 */
    const val ECHO = "echo"
}
