package com.example.voiceassistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 对话记录的**持久化**：一个账号一份会话列表 + 每个会话各自的聊天记录。
 *
 * ## 为什么要分「会话」
 * 以前只有一个全局列表，聊久了全混在一起；现在抽屉里能列出「昨天问台风的那次」，
 * 点一下切回去，上下文仍然是那一轮接得上的。每个会话的消息互相独立。
 *
 * ## 存储
 * 仍然用 SharedPreferences + 手写 JSON（量级很小，不引数据库）：
 * ```
 * index_<账号>        [ {id,title,at,upd,named}, … ]   会话列表，按 upd 倒序
 * msgs_<账号>_<id>    [ {text,user,at}, … ]             某一次会话的消息
 * current_<账号>      当前打开的是哪一次
 * ```
 * 键里带 [UserScope] 分区，换账号登进来看到的是另一套，互不可见。
 *
 * ## 迁移
 * v3.x 只有一个 `chat_history/items_<账号>` 的平铺列表。升级后把它整段搬进
 * 一个新会话（标题用首句），搬完删掉旧键，避免下次重复搬。
 */
class ConversationStore(context: Context) {

    private val app = context.applicationContext

    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val scope = UserScope.of(app)

    private val indexKey = "index_$scope"
    private val currentKey = "current_$scope"

    init {
        migrateLegacy()
    }

    // ------------------------------------------------------------------ 会话

    /** 全部会话，最近说过的排最前 */
    fun list(): List<Conversation> =
        decodeIndex(prefs.getString(indexKey, null)).sortedByDescending { it.updatedAt }

    /** 现在打开的是哪一个；没有就建一个 */
    fun current(): Conversation {
        val id = prefs.getString(currentKey, null)
        val found = id?.let { saved -> list().firstOrNull { it.id == saved } }
        if (found != null) return found

        val latest = list().firstOrNull() ?: return create()
        prefs.edit().putString(currentKey, latest.id).apply()
        return latest
    }

    /** 新建一个空会话并切成当前 */
    fun create(title: String = Conversation.UNTITLED): Conversation {
        val now = System.currentTimeMillis()
        val conversation = Conversation(
            id = newId(now),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        val all = (list() + conversation).take(Conversation.MAX)
        prefs.edit()
            .putString(indexKey, encodeIndex(all))
            .putString(currentKey, conversation.id)
            .apply()
        return conversation
    }

    /** 切到某个会话 */
    fun select(id: String) {
        prefs.edit().putString(currentKey, id).apply()
    }

    /** 改标题（大模型总结完，或者用户手动改） */
    fun rename(id: String, title: String, named: Boolean = true) {
        val cleaned = title.trim().take(MAX_TITLE)
        if (cleaned.isEmpty()) return
        val all = list().map {
            if (it.id == id) it.copy(title = cleaned, named = named) else it
        }
        prefs.edit().putString(indexKey, encodeIndex(all)).apply()
    }

    /** 说了一句：刷新 updatedAt，让这次会话浮到最上面 */
    fun touch(id: String, at: Long = System.currentTimeMillis()) {
        val all = list().map { if (it.id == id) it.copy(updatedAt = at) else it }
        prefs.edit().putString(indexKey, encodeIndex(all)).apply()
    }

    /** 删掉一次会话（连同它的消息）；当前会话被删时自动切到最近一次 */
    fun delete(id: String) {
        val all = list().filterNot { it.id == id }
        val editor = prefs.edit()
            .putString(indexKey, encodeIndex(all))
            .remove(messagesKey(id))
        if (prefs.getString(currentKey, null) == id) {
            val next = all.maxByOrNull { it.updatedAt }
            if (next != null) editor.putString(currentKey, next.id) else editor.remove(currentKey)
        }
        editor.apply()
    }

    // ------------------------------------------------------------------ 消息

    fun messages(id: String): List<ChatMessage> =
        decodeMessages(prefs.getString(messagesKey(id), null))

    fun saveMessages(id: String, messages: List<ChatMessage>) {
        prefs.edit().putString(messagesKey(id), encodeMessages(messages)).apply()
    }

    private fun messagesKey(id: String) = "msgs_${scope}_$id"

    // ------------------------------------------------------------------ 迁移

    /**
     * 把 v3.x 那份「一个账号一条长列表」搬成第一个会话。
     *
     * 只在「还没有任何会话」时搬，搬完删旧键；旧键里本来就没东西就什么也不做。
     */
    private fun migrateLegacy() {
        if (prefs.contains(indexKey)) return
        val legacyPrefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyKey = "items_$scope"
        val raw = legacyPrefs.getString(legacyKey, null) ?: return
        val messages = decodeMessages(raw)
        legacyPrefs.edit().remove(legacyKey).apply()
        if (messages.isEmpty()) return

        val now = System.currentTimeMillis()
        val first = messages.firstOrNull { it.isUser }?.text?.trim().orEmpty()
        val conversation = Conversation(
            id = newId(now),
            title = first.ifEmpty { Conversation.UNTITLED }.take(MAX_TITLE),
            createdAt = messages.firstOrNull()?.at ?: now,
            updatedAt = messages.lastOrNull()?.at ?: now
        )
        prefs.edit()
            .putString(indexKey, encodeIndex(listOf(conversation)))
            .putString(currentKey, conversation.id)
            .putString(messagesKey(conversation.id), encodeMessages(messages))
            .apply()
    }

    private fun newId(now: Long): String = now.toString(36) + "-" + (idSeed++ % 1000)

    // ------------------------------------------------------------------ 编解码（纯函数，可单测）

    companion object {

        private const val PREF_NAME = "conversations"

        /** v3.x 的旧文件名 */
        private const val LEGACY_PREFS = "chat_history"

        /** 标题最多几个字（模型偶尔会写一长句） */
        const val MAX_TITLE = 16

        /** 一次会话最多留多少条消息 */
        const val MAX_MESSAGES = 200

        private var idSeed = 0

        fun encodeIndex(list: List<Conversation>): String {
            if (list.isEmpty()) return "[]"
            return list.joinToString(separator = ",", prefix = "[", postfix = "]") { c ->
                """{"id":"${escape(c.id)}","title":"${escape(c.title)}",""" +
                    """"at":${c.createdAt},"upd":${c.updatedAt},""" +
                    """"named":${if (c.named) 1 else 0}}"""
            }
        }

        fun decodeIndex(raw: String?): List<Conversation> {
            if (raw.isNullOrBlank()) return emptyList()
            val root = JsonLite.parseOrNull(raw) ?: return emptyList()
            return JsonLite.arr(root).mapNotNull { element ->
                val fields = JsonLite.obj(element)
                val id = JsonLite.str(fields["id"])?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Conversation(
                    id = id,
                    title = JsonLite.str(fields["title"])?.takeIf { it.isNotBlank() }
                        ?: Conversation.UNTITLED,
                    createdAt = JsonLite.num(fields["at"])?.toLong() ?: 0L,
                    updatedAt = JsonLite.num(fields["upd"])?.toLong() ?: 0L,
                    named = (JsonLite.int(fields["named"]) ?: 0) == 1
                )
            }
        }

        fun encodeMessages(messages: List<ChatMessage>): String {
            val kept = messages
                .filter { it.text.isNotBlank() }
                .takeLast(MAX_MESSAGES)
            if (kept.isEmpty()) return "[]"
            return kept.joinToString(separator = ",", prefix = "[", postfix = "]") { message ->
                """{"text":"${escape(message.text)}",""" +
                    """"user":${if (message.isUser) 1 else 0},"at":${message.at}}"""
            }
        }

        fun decodeMessages(raw: String?): List<ChatMessage> {
            if (raw.isNullOrBlank()) return emptyList()
            val root = JsonLite.parseOrNull(raw) ?: return emptyList()
            return JsonLite.arr(root).mapNotNull { element ->
                val fields = JsonLite.obj(element)
                val text = JsonLite.str(fields["text"])?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ChatMessage(
                    text = text,
                    isUser = (JsonLite.int(fields["user"]) ?: 0) == 1,
                    at = JsonLite.num(fields["at"])?.toLong() ?: 0L
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
