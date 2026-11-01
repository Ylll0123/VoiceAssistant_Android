package com.example.voiceassistant.tool


/**
 * 工具层：提醒。
 *
 * 当前是**会话内**的提醒清单（存在内存里），负责三件事：记下、列出、删除。
 *
 * ## 关于「到点提醒」
 * 真正的定时触发需要常驻调度（WorkManager + 开机恢复 + 通知渠道），
 * 那是伴生模块 **P3 通知提醒中心** 的范围，还没开工。
 * 所以这里在回答里如实说明「现在只记在会话里，到点提醒属于 P3 模块」——
 * 不做"假装能响"的假功能。等 P3 落地，把 [ReminderSink] 换成
 * Room + WorkManager 的实现即可，上层的判定和话术都不用动。
 */
object ReminderTool {

    const val NAME = "reminder"

    /** 一条提醒 */
    data class Reminder(
        val id: Int,
        val text: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /** 提醒的落点。现在走内存，P3 会换成 Room + WorkManager */
    interface ReminderSink {
        fun add(text: String): Reminder
        fun all(): List<Reminder>
        fun remove(id: Int): Boolean
        fun clear()
    }

    private val ADD_CUES = listOf(
        "提醒我", "提醒一下", "记得提醒", "定个提醒", "添加提醒", "设个提醒",
        "设置提醒", "建个提醒", "记一下提醒", "帮我记着"
    )

    private val LIST_CUES = listOf(
        "我的提醒", "查看提醒", "有哪些提醒", "有什么提醒", "提醒列表",
        "提醒事项", "看看提醒", "提醒都有什么"
    )

    private val DELETE_CUES = listOf(
        "删除提醒", "取消提醒", "移除提醒", "删掉提醒", "清空提醒", "去掉提醒"
    )

    private val FILLERS = listOf(
        "提醒我", "提醒一下", "记得提醒", "定个提醒", "添加提醒", "设个提醒",
        "设置提醒", "建个提醒", "记一下提醒", "帮我记着", "提醒",
        "我的提醒", "查看提醒", "有哪些提醒", "有什么提醒", "提醒列表",
        "提醒事项", "看看提醒", "提醒都有什么",
        "删除提醒", "取消提醒", "移除提醒", "删掉提醒", "清空提醒", "去掉提醒"
    )

    /** 默认的内存落点 */
    private val memorySink = MemorySink()

    var sink: ReminderSink = memorySink

    /** 是不是在说提醒 */
    fun matches(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (TeachGuard.isTeaching(text)) return false
        return ADD_CUES.any { text.contains(it) } ||
            LIST_CUES.any { text.contains(it) } ||
            DELETE_CUES.any { text.contains(it) }
    }

    /**
     * 执行提醒指令。
     *
     * 支持：
     * - 「提醒我明天交报告」→ 记下
     * - 「我的提醒」→ 列出
     * - 「删除提醒 2」/「删除提醒」→ 删第 2 条 / 询问删哪条
     */
    fun handle(raw: String): ToolReply? {
        if (!matches(raw)) return null
        val text = raw.trim()

        if (DELETE_CUES.any { text.contains(it) }) {
            val index = firstNumber(text)
            if (index == null) {
                return ToolReply(
                    if (sink.all().isEmpty()) {
                        "现在没有提醒。"
                    } else {
                        "要删第几条？现在有 ${sink.all().size} 条，说「删除提醒 1」这样就行。"
                    },
                    ToolIntent.REMINDER,
                    NAME
                )
            }
            val removed = sink.remove(index)
            return ToolReply(
                if (removed) "删掉了第 $index 条提醒。" else "第 $index 条不存在。",
                ToolIntent.REMINDER,
                NAME
            )
        }

        if (LIST_CUES.any { text.contains(it) }) {
            val items = sink.all()
            if (items.isEmpty()) {
                return ToolReply("现在没有提醒。说「提醒我……」我给你记一条。", ToolIntent.REMINDER, NAME)
            }
            val body = items.mapIndexed { i, item -> "${i + 1}. ${item.text}" }.joinToString("；")
            return ToolReply("你有 ${items.size} 条提醒：$body。", ToolIntent.REMINDER, NAME)
        }

        val content = text.let { raw0 ->
            var value = raw0
            for (cue in FILLERS) {
                if (value.startsWith(cue) && value.length > cue.length) {
                    value = value.removePrefix(cue)
                    break
                }
            }
            value.trim('，', ',', '。', '.', '！', '!', ' ', '：', ':')
        }
        if (content.length < 2) {
            return ToolReply("要提醒你什么？比如说「提醒我明天交报告」。", ToolIntent.REMINDER, NAME)
        }
        val reminder = sink.add(content)
        return ToolReply(
            "记下了：${reminder.text}。（存在这次会话里；到点自动提醒属于 P3 模块。）",
            ToolIntent.REMINDER,
            NAME
        )
    }

    /** 取出句子里的第一个阿拉伯数字，没有返回 null */
    private fun firstNumber(text: String): Int? =
        Regex("[0-9]+").find(text)?.value?.toIntOrNull()

    /** 内存版落点：进程内有效，重启清空 */
    private class MemorySink : ReminderSink {
        private val items = mutableListOf<Reminder>()
        private var seq = 0

        @Synchronized
        override fun add(text: String): Reminder {
            val reminder = Reminder(++seq, text)
            items.add(reminder)
            return reminder
        }

        @Synchronized
        override fun all(): List<Reminder> = items.toList()

        @Synchronized
        override fun remove(id: Int): Boolean = items.removeAll { it.id == id }

        @Synchronized
        override fun clear() {
            items.clear()
            // 编号也一起归零：清空后再记的提醒应当重新从 1 开始，
            // 否则界面上列的是「1.」，实际编号却是 7，删不掉。
            seq = 0
        }
    }
}
