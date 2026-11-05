package com.example.voiceassistant.tool

/**
 * 工具层：**计时器 / 倒计时**。
 *
 * 「计时」和「提醒」其实是同一件事的两面：
 * 提醒是「某个时刻叫我」，计时是「过多久叫我」。
 * 所以计时不另建一套调度，直接复用提醒的落点（[ReminderTool.sink]）——
 * 到点一样是闹钟 + 通知，只是文案不同。
 *
 * ## 边界
 * 这里只认**时长**（「5 分钟」「半小时」「2 小时」）。
 * 说了时刻（「8 点」）那是提醒，不是计时，交给 [ReminderTool]。
 */
object TimerTool {

    const val NAME = "timer"

    /** 计时到点后写进清单的文案，也用来识别「取消计时」要删哪条 */
    private const val DONE_TEXT = "计时到了"

    private val CANCEL_CUES = listOf("取消计时", "停止计时", "取消倒计时", "关掉计时")

    /** 是不是在说计时 */
    fun matches(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (TeachGuard.isTeaching(text)) return false
        if (CANCEL_CUES.any { text.contains(it) }) return true
        if (text.contains("计时") || text.contains("倒计时")) return true
        // 「闹钟」先说时长归计时（「定个 10 分钟的闹钟」），
        // 说时刻归提醒（「定个明天 8 点的闹钟」）
        return text.contains("闹钟") && ReminderTime.hasDuration(text)
    }

    /**
     * 执行计时指令。
     *
     * @param now 当前时刻，注入便于单测
     */
    fun handle(raw: String, now: Long = System.currentTimeMillis()): ToolReply? {
        if (!matches(raw)) return null
        val text = raw.trim()

        if (CANCEL_CUES.any { text.contains(it) }) {
            val target = ReminderTool.sink.all().lastOrNull { it.text == DONE_TEXT }
                ?: return reply("现在没有在跑的计时。")
            ReminderTool.sink.remove(target.id)
            return reply("计时停了。")
        }

        val duration = ReminderTime.duration(text, now)
            ?: return reply("计时多久？比如说「计时 5 分钟」或者「定个半小时的闹钟」。")

        ReminderTool.sink.add(DONE_TEXT, duration.at)
        return reply("好，${duration.label}叫你。")
    }

    private fun reply(text: String) = ToolReply(text, ToolIntent.TIMER, NAME)
}
