package com.example.voiceassistant.reminder

import android.content.Context
import com.example.voiceassistant.data.JsonLite
import com.example.voiceassistant.tool.ReminderTool

/**
 * 提醒的**持久化落点**：把清单写进 SharedPreferences，重启还在。
 *
 * 只负责存与取，不碰闹钟。排闹钟由构造时传进来的 [schedule] / [unschedule]
 * 两个回调决定——这样这一层能在单测里用假回调跑通「加一条会排闹钟」，
 * 而不需要真的碰 AlarmManager。
 *
 * ## 为什么不用 Room
 * 一条提醒就四个字段，量级是几十条。为它引 Room + KSP 编译期插件，
 * 构建时间和 APK 体积都不划算；[encode] / [decode] 是纯函数，
 * 反而更容易写往返测试。
 */
class ReminderStore(
    context: Context,
    /** 新提醒入库后调用；App 里传的是排闹钟 */
    private val schedule: (ReminderTool.Reminder) -> Unit = {},
    /** 提醒被删 / 清空时调用，用来撤闹钟 */
    private val unschedule: (Int) -> Unit = {}
) : ReminderTool.Sink {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun add(text: String, triggerAt: Long): ReminderTool.Reminder {
        val items = load().toMutableList()
        val id = (items.maxOfOrNull { it.id } ?: 0) + 1
        val reminder = ReminderTool.Reminder(id, text, triggerAt)
        items.add(reminder)
        save(items)
        if (reminder.scheduled) runCatching { schedule(reminder) }
        return reminder
    }

    @Synchronized
    override fun all(): List<ReminderTool.Reminder> = load()

    @Synchronized
    override fun remove(id: Int): Boolean {
        val items = load().toMutableList()
        val removed = items.removeAll { it.id == id }
        if (removed) {
            save(items)
            runCatching { unschedule(id) }
        }
        return removed
    }

    @Synchronized
    override fun clear() {
        val items = load()
        save(emptyList())
        for (item in items) runCatching { unschedule(item.id) }
    }

    private fun load(): List<ReminderTool.Reminder> =
        decode(prefs.getString(KEY_ITEMS, null).orEmpty())

    private fun save(items: List<ReminderTool.Reminder>) {
        prefs.edit().putString(KEY_ITEMS, encode(items)).apply()
    }

    companion object {

        private const val PREF_NAME = "reminders"
        private const val KEY_ITEMS = "items"

        /**
         * 序列化成 JSON 数组。
         *
         * 只有四个字段，手写比引序列化库省事；重点是 [escape] 要把引号、
         * 反斜杠、换行转掉——用户说一句带引号的话不能把整份清单写坏。
         */
        fun encode(items: List<ReminderTool.Reminder>): String {
            if (items.isEmpty()) return "[]"
            return items.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
                """{"id":${item.id},"text":"${escape(item.text)}",""" +
                    """"at":${item.triggerAt},"created":${item.createdAt}}"""
            }
        }

        /** 解析；坏数据只跳过那一条，整份读不出来就退化成空表 */
        fun decode(raw: String): List<ReminderTool.Reminder> {
            if (raw.isBlank()) return emptyList()
            val root = JsonLite.parseOrNull(raw) ?: return emptyList()
            return JsonLite.arr(root).mapNotNull { element ->
                val fields = JsonLite.obj(element)
                val id = JsonLite.int(fields["id"]) ?: return@mapNotNull null
                val text = JsonLite.str(fields["text"])?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ReminderTool.Reminder(
                    id = id,
                    text = text,
                    triggerAt = JsonLite.num(fields["at"])?.toLong() ?: 0L,
                    createdAt = JsonLite.num(fields["created"])?.toLong() ?: 0L
                )
            }
        }

        private fun escape(text: String): String {
            val sb = StringBuilder(text.length + 8)
            for (ch in text) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch < ' ') sb.append(' ') else sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
