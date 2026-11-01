package com.example.voiceassistant.tool

import com.example.voiceassistant.service.AudioController

/**
 * 工具层：音量 / 静音 / 停止播报。
 *
 * 真正在本机执行的一类工具：直接改系统音量，不走网络也不走模型（0 token）。
 *
 * ## 为什么音量要放在工具层最前面
 * 这类话在语义上很"短"，不接工具的话会掉进闲聊或兜底：
 * 闲聊会给一句"听不见吗"之类的套话，而用户要的是**音量真的变小**。
 * 所以 [com.example.voiceassistant.tool.ToolHandler] 在路由最前面
 * 先认领这类指令，命中就直接执行，后面的路线一律不再走。
 */
object VolumeTool {

    const val NAME = "volume"

    private val MUTE_CUES = listOf("静音", "别出声", "关掉声音", "不要出声", "闭嘴")
    private val UNMUTE_CUES = listOf("取消静音", "恢复声音", "打开声音", "可以说话了", "解除静音")
    private val UP_CUES = listOf("大点声", "大声点", "声音大一点", "声音大点", "调高音量", "音量调高", "音量大一点", "大一点声")
    private val DOWN_CUES = listOf("小点声", "小声点", "声音小一点", "声音小点", "调低音量", "音量调低", "音量小一点", "小一点声")
    private val STOP_CUES = listOf("别说了", "停止播报", "不要说了", "别念了", "停一下", "先别说")

    /** 是不是在让我调声音 / 闭嘴 */
    fun matches(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        return MUTE_CUES.any { text.contains(it) } ||
            UNMUTE_CUES.any { text.contains(it) } ||
            UP_CUES.any { text.contains(it) } ||
            DOWN_CUES.any { text.contains(it) } ||
            STOP_CUES.any { text.contains(it) }
    }

    /**
     * 执行音量指令。
     *
     * @param audio 系统音量控制器；为 null（比如单测环境）时只回话不改音量
     */
    fun handle(raw: String, audio: AudioController?): ToolReply? {
        if (!matches(raw)) return null
        val text = raw.trim()

        return when {
            STOP_CUES.any { text.contains(it) } ->
                ToolReply("好，我不说了。", ToolIntent.TTS_STOP, NAME)

            // 「取消静音」里含「静音」，反过来判就会被静音分支吃掉，
            // 于是越说「取消静音」越没声音——所以先判恢复、再判静音。
            UNMUTE_CUES.any { text.contains(it) } -> {
                audio?.setMuted(false)
                ToolReply("好，声音恢复了。", ToolIntent.VOLUME, NAME)
            }

            MUTE_CUES.any { text.contains(it) } -> {
                audio?.setMuted(true)
                ToolReply("好，静音了。说「取消静音」我就恢复。", ToolIntent.VOLUME, NAME)
            }

            UP_CUES.any { text.contains(it) } -> {
                audio?.adjustVolume(up = true)
                ToolReply("调大了一点。", ToolIntent.VOLUME, NAME)
            }

            DOWN_CUES.any { text.contains(it) } -> {
                audio?.adjustVolume(up = false)
                ToolReply("调小了一点。", ToolIntent.VOLUME, NAME)
            }

            else -> null
        }
    }
}
