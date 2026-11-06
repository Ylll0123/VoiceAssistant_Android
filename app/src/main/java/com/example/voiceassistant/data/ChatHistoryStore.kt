package com.example.voiceassistant.data

import android.content.Context

/**
 * 对话记录的**持久化落点**：写进 SharedPreferences，杀进程 / 重启后还在。
 *
 * ## 为什么要存
 * 会话原来只活在内存里，用户切后台被系统回收、或者主动杀掉重开，
 * 屏幕上就只剩一句欢迎语——这和市面上的语音助手不一致，
 * 上下文也跟着丢了，追问「那明天呢」会答不上来。
 *
 * ## 为什么不进 SQLite
 * 只保留最近 [MAX_MESSAGES] 条，量级很小；用与提醒模块相同的手写 JSON 编解码，
 * [encode] / [decode] 是纯函数，往返一致性能直接单测。
 *
 * ## 「理解过程」不落盘
 * 那种淡色小字是渲染过程中的临时条目（答案出来就被删），存进去只会徒增噪音。
 */
class ChatHistoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 每个账号一份历史：键名带上分区名（见 [UserScope]）。
     *
     * 不这么做的话，换个账号登进来第一眼看到的是上一个人的对话，
     * 「区分个人数据」就成了一句空话。
     */
    private val keyItems = "items_" + UserScope.of(context)

    init {
        migrateLegacy()
    }

    /** 读出上次的对话；文件损坏 / 为空时返回空表 */
    fun load(): List<ChatMessage> = decode(prefs.getString(keyItems, null))

    /** 覆盖保存（只留最近若干条） */
    fun save(messages: List<ChatMessage>) {
        prefs.edit().putString(keyItems, encode(messages)).apply()
    }

    /** 清空对话时一并把落盘内容删掉，否则重启会「复活」 */
    fun clear() {
        prefs.edit().remove(keyItems).apply()
    }

    /**
     * v3.6 及更早版本只有一个 `items` 键，没有归属。
     *
     * 升级后把它判给**当前登录的人**（没有归属的旧数据给人，比扔掉更合理），
     * 搬完就删，避免下次换账号时又被搬一遍。
     */
    private fun migrateLegacy() {
        val legacy = prefs.getString(LEGACY_KEY, null) ?: return
        if (!prefs.contains(keyItems)) {
            prefs.edit().putString(keyItems, legacy).remove(LEGACY_KEY).apply()
        } else {
            prefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    companion object {

        private const val PREF_NAME = "chat_history"
        private const val LEGACY_KEY = "items"

        /** 最多保留多少条：够接上下文、又不至于把 prefs 撑大 */
        const val MAX_MESSAGES = 40

        /**
         * 序列化成 JSON 数组。
         *
         * `isUser` 存成 1/0（[JsonLite] 没有布尔读取），`at` 保留原始时间戳，
         * 这样重启后气泡上的「刚刚 / 2 分钟前」仍然按真实时间算。
         */
        fun encode(messages: List<ChatMessage>): String {
            val kept = messages
                .filter { it.kind == ChatMessage.KIND_NORMAL && it.text.isNotBlank() }
                .takeLast(MAX_MESSAGES)
            if (kept.isEmpty()) return "[]"
            return kept.joinToString(separator = ",", prefix = "[", postfix = "]") { message ->
                """{"text":"${escape(message.text)}",""" +
                    """"user":${if (message.isUser) 1 else 0},""" +
                    """"at":${message.at},"intent":${message.intent?.let { "\"${escape(it)}\"" } ?: "null"}}"""
            }
        }

        /** 解析；坏掉的那一条跳过，整份读不出来就退化成空表 */
        fun decode(raw: String?): List<ChatMessage> {
            if (raw.isNullOrBlank()) return emptyList()
            val root = JsonLite.parseOrNull(raw) ?: return emptyList()
            return JsonLite.arr(root).mapNotNull { element ->
                val fields = JsonLite.obj(element)
                val text = JsonLite.str(fields["text"])?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ChatMessage(
                    text = text,
                    isUser = (JsonLite.int(fields["user"]) ?: 0) == 1,
                    at = JsonLite.num(fields["at"])?.toLong() ?: 0L,
                    intent = JsonLite.str(fields["intent"])?.takeIf { it.isNotBlank() }
                )
            }.takeLast(MAX_MESSAGES)
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
