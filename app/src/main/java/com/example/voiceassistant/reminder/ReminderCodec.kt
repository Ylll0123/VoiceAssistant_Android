package com.example.voiceassistant.reminder

import com.example.voiceassistant.data.JsonLite

/**
 * 旧版本提醒清单（SharedPreferences 里那份 JSON）的编解码。
 *
 * 提醒已经迁到 SQLite（见 [ReminderDb]），这个编解码器保留下来只做一件事：
 * **把老用户存在 SharedPreferences 里的提醒搬进新表**。
 * 升级不该让用户已设好的提醒凭空消失，这是数据迁移的底线。
 *
 * 顺手也把「坏数据不能拖垮整份清单」这条钉住：
 * 解析失败只跳过那一条，整份读不出来就退化成空表。
 */
object ReminderCodec {

    /** 旧清单所在的 SharedPreferences 文件名与键名 */
    const val PREFS_NAME = "reminders"
    const val KEY_ITEMS = "items"

    /** 序列化成 JSON 数组（只给测试 / 迁移用，正式存储已是 SQLite） */
    fun encode(items: List<Reminder>): String {
        if (items.isEmpty()) return "[]"
        return items.joinToString(separator = ",", prefix = "[", postfix = "]") { item ->
            """{"id":${item.id},"text":"${escape(item.content)}",""" +
                """"at":${item.triggerAt},"created":${item.createdAt}}"""
        }
    }

    /** 解析旧清单；坏数据只跳过那一条，整份读不出来就退化成空表 */
    fun decode(raw: String): List<Reminder> {
        if (raw.isBlank()) return emptyList()
        val root = JsonLite.parseOrNull(raw) ?: return emptyList()
        return JsonLite.arr(root).mapNotNull { element ->
            val fields = JsonLite.obj(element)
            val id = JsonLite.int(fields["id"]) ?: return@mapNotNull null
            val text = JsonLite.str(fields["text"])?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Reminder(
                id = id,
                content = text,
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
