package com.example.voiceassistant.tool

/**
 * 工具层：提醒清单。
 *
 * 负责三件事：记下、列出、删除。**到点响铃不在这一层**——
 * 这一层只产出「一条带触发时刻的提醒」，真正定时由
 * `reminder/ReminderAlarm`（AlarmManager + 通知）负责。
 * 这样判定与话术是纯逻辑、能单测，调度那部分是薄薄的系统胶水。
 *
 * ## 为什么要把时间抠出来
 * 「提醒我明天交报告」和「提醒我明天 8 点交报告」听起来差不多，
 * 但只有后者是**可执行**的提醒。所以这里先请 [ReminderTime] 解析一遍：
 * - 解析到时间 → 交给落点排闹钟，回答里明确说几点响；
 * - 解析不到 → 老老实实说「记下了，但没说时间，到点提醒需要时间」，
 *   不假装能响（这是本模块一开始就守住的底线）。
 *
 * ## 落点（[Sink]）
 * 默认是内存实现（单测 / 没有 Android 环境时用）；
 * App 里会替换成 `reminder/ReminderStore`（持久化 + 排闹钟），
 * 换掉落点不用改这里的任何判定逻辑。
 */
object ReminderTool {

    const val NAME = "reminder"

    /**
     * 一条提醒。
     *
     * @param triggerAt 触发时刻（毫秒）；0 表示没说时间，只在清单里躺着
     */
    data class Reminder(
        val id: Int,
        val text: String,
        val triggerAt: Long = 0L,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val scheduled: Boolean get() = triggerAt > 0L
    }

    /** 提醒的落点：存哪里、要不要排闹钟，由实现决定 */
    interface Sink {
        fun add(text: String, triggerAt: Long): Reminder
        fun all(): List<Reminder>
        fun remove(id: Int): Boolean
        fun clear()
    }

    private val ADD_CUES = listOf(
        "提醒我", "提醒一下", "记得提醒", "定个提醒", "添加提醒", "设个提醒",
        "设置提醒", "建个提醒", "记一下提醒", "帮我记着", "闹钟"
    )

    private val LIST_CUES = listOf(
        "我的提醒", "查看提醒", "有哪些提醒", "有什么提醒", "提醒列表",
        "提醒事项", "看看提醒", "提醒都有什么"
    )

    private val DELETE_CUES = listOf(
        "删除提醒", "取消提醒", "移除提醒", "删掉提醒", "清空提醒", "去掉提醒"
    )

    private val FILLERS = ADD_CUES + LIST_CUES + DELETE_CUES +
        listOf("定个", "设个", "加个", "来个", "提醒")

    /** 默认的内存落点（App 启动时会换成持久化实现） */
    private val memorySink = MemorySink()

    var sink: Sink = memorySink

    /** 是不是在说提醒 */
    fun matches(raw: String): Boolean {
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (TeachGuard.isTeaching(text)) return false
        // 「闹钟」太宽（「设置闹钟音」之类），只有带「提醒/叫醒」时才认领
        if (text.contains("闹钟") && !text.contains("提醒")) {
            return listOf("定", "设", "加", "来", "开").any { text.contains(it) }
        }
        return ADD_CUES.any { text.contains(it) } ||
            LIST_CUES.any { text.contains(it) } ||
            DELETE_CUES.any { text.contains(it) }
    }

    /**
     * 执行提醒指令。
     *
     * @param now 当前时刻，注入是为了让单测能钉死"今天/明天"的判定
     */
    fun handle(raw: String, now: Long = System.currentTimeMillis()): ToolReply? {
        if (!matches(raw)) return null
        val text = raw.trim()

        if (DELETE_CUES.any { text.contains(it) }) {
            val items = sink.all()
            if (text.contains("全部") || text.contains("所有")) {
                if (items.isEmpty()) return reply("现在没有提醒。")
                sink.clear()
                return reply("好，${items.size} 条提醒全删了。")
            }
            val index = firstNumber(text)
                ?: return reply(
                    if (items.isEmpty()) {
                        "现在没有提醒。"
                    } else {
                        "要删第几条？现在有 ${items.size} 条，说「删除提醒 1」这样就行。"
                    }
                )
            if (index !in 1..items.size) {
                return reply("第 $index 条不存在，现在只有 ${items.size} 条。")
            }
            // 按展示序号删：用户看到的是「1.」「2.」，编号未必连续
            val target = items[index - 1]
            sink.remove(target.id)
            return reply("删掉了第 $index 条提醒（${target.text}）。")
        }

        if (LIST_CUES.any { text.contains(it) }) {
            val items = sink.all()
            if (items.isEmpty()) return reply("现在没有提醒。说「提醒我明天 8 点……」我给你记一条。")
            val body = items.mapIndexed { i, item -> "${i + 1}. ${describe(item, now)}" }
                .joinToString("；")
            return reply("你有 ${items.size} 条提醒：$body。")
        }

        val content = stripCue(text)
        if (content.length < 2) {
            return reply("要提醒你什么？比如说「提醒我明天 8 点交报告」。")
        }

        val time = ReminderTime.resolve(content, now)
        // 存进清单的正文要把时间剥掉，否则列出来是「明天 08:00 明天 8 点交报告」
        val body = ReminderTime.stripTime(content).ifBlank { content }
        val reminder = sink.add(body, time?.at ?: 0L)

        return if (time != null) {
            // 只说了「定个明天 8 点的闹钟」、没说要提醒什么时，
            // 别再重复一遍「闹钟」，直接说几点叫你
            if (body == content || body.isBlank()) {
                reply("好，${time.label}叫你。")
            } else {
                reply("好，${time.label} 提醒你：$body。")
            }
        } else {
            reply(
                "先记下了：${reminder.text}。" +
                    "不过没说时间，我没法到点叫你——想定时的话说「明天 8 点提醒我${reminder.text}」。"
            )
        }
    }

    /** 清单里一行：有时间就带上时间 */
    private fun describe(item: Reminder, now: Long): String =
        if (item.scheduled) "${ReminderTime.describe(item.triggerAt, now)} ${item.text}" else item.text

    private fun reply(text: String) = ToolReply(text, ToolIntent.REMINDER, NAME)

    /** 去掉「提醒我」「帮我记着」这类前缀，留下真正要提醒的内容 */
    private fun stripCue(raw: String): String {
        var value = raw.trim()
        for (cue in FILLERS.sortedByDescending { it.length }) {
            if (value.startsWith(cue) && value.length > cue.length) {
                value = value.removePrefix(cue)
                break
            }
        }
        return value.trim('，', ',', '。', '.', '！', '!', ' ', '：', ':')
    }

    private fun firstNumber(text: String): Int? =
        Regex("[0-9]+").find(text)?.value?.toIntOrNull()

    /** 内存版落点：进程内有效，重启清空 */
    private class MemorySink : Sink {
        private val items = mutableListOf<Reminder>()
        private var seq = 0

        @Synchronized
        override fun add(text: String, triggerAt: Long): Reminder {
            val reminder = Reminder(++seq, text, triggerAt)
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
            // 编号也一起归零：清空后再记的提醒应当重新从 1 开始
            seq = 0
        }
    }
}
