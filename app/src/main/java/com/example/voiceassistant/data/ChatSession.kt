package com.example.voiceassistant.data

import com.example.voiceassistant.network.LlmMessage

/**
 * 会话状态：当前这次对话的消息列表。
 *
 * 由 Activity 持有，界面通过 [listener] 收到变更通知。
 * 「多轮对话」在这里落地：每次提问时用 [recentTurns] 取最近几轮，
 * 作为上下文一起发给大模型——指代、追问都由模型自己接，本机不再存槽位。
 *
 * 切换对话记录时用 [load] 整段换掉；改字 / 删除只动内存，
 * 落盘交给 [onChanged]（Activity 接到后写 [ConversationStore]）。
 */
class ChatSession {

    interface Listener {
        fun onMessagesChanged(messages: List<ChatMessage>)
    }

    private val items = mutableListOf<ChatMessage>()

    var listener: Listener? = null

    /**
     * 每次消息列表变化后的额外回调，用来落盘。
     *
     * 单独一个槽位而不是塞进 [Listener]：界面切页会换 listener，
     * 落盘这件事跟谁在看屏幕无关，不能跟着一起被换掉。
     */
    var onChanged: ((List<ChatMessage>) -> Unit)? = null

    val messages: List<ChatMessage> get() = items

    /** 整段替换（打开某条对话记录 / 新建时调用） */
    fun load(saved: List<ChatMessage>) {
        items.clear()
        items.addAll(saved)
        listener?.onMessagesChanged(items.toList())
    }

    fun addUser(text: String) {
        items.add(ChatMessage(text = text, isUser = true))
        notifyChanged()
    }

    fun addAssistant(text: String, intent: String? = null) {
        items.add(ChatMessage(text = text, isUser = false, intent = intent))
        notifyChanged()
    }

    /** 最后一条用户消息的下标；没有返回 -1 */
    fun lastUserIndex(): Int = items.indexOfLast { it.isUser }

    /** 最后一条用户消息之后是否已经有回答 */
    fun hasReplyAfterUser(): Boolean {
        val index = lastUserIndex()
        return index >= 0 && index < items.lastIndex
    }

    fun messageAt(index: Int): ChatMessage? = items.getOrNull(index)

    /**
     * 第 [index] 条**之前**的最近几轮对话。
     *
     * 改问题时要重新问一次：这段历史不能包含被改的那句（它是新问题），
     * 更不能包含它后面那条已经作废的回答。
     */
    fun recentTurnsBefore(index: Int, maxTurns: Int): List<LlmMessage> {
        if (maxTurns <= 0) return emptyList()
        val head = items.subList(0, index.coerceIn(0, items.size))
        val turns = mutableListOf<LlmMessage>()
        var cursor = head.lastIndex
        while (cursor >= 1 && turns.size < maxTurns * 2) {
            val answer = head[cursor]
            val question = head.getOrNull(cursor - 1)
            if (answer.isUser || question == null || !question.isUser) {
                cursor -= 1
                continue
            }
            turns += LlmMessage.assistant(answer.text)
            turns += LlmMessage.user(question.text)
            cursor -= 2
        }
        return turns.asReversed()
    }

    /**
     * 作废第 [index] 条之后的回答。
     *
     * 用户改了问题，旧答案就对不上了——留着会变成「问 A 答 B」。
     * 只删紧邻的那一条助手消息，更早的历史一律不动。
     */
    fun dropAnswerAfter(index: Int): Boolean {
        val next = items.getOrNull(index + 1) ?: return false
        if (next.isUser) return false
        items.removeAt(index + 1)
        notifyChanged()
        return true
    }

    /**
     * 最近若干轮对话，给大模型当上下文。
     *
     * 取法是「用户一句 + 助手一句」成对往前扫，只留完整的对；
     * 末尾落单的用户句（还没回答的那句）不算一轮。
     *
     * @param maxTurns 最多几轮（一轮 = 用户 + 助手各一句）
     */
    fun recentTurns(maxTurns: Int): List<LlmMessage> {
        if (maxTurns <= 0) return emptyList()
        val turns = mutableListOf<LlmMessage>()
        var index = items.lastIndex
        while (index >= 1 && turns.size < maxTurns * 2) {
            val assistant = items[index]
            val user = items.getOrNull(index - 1)
            if (assistant.isUser || user == null || !user.isUser) {
                index -= 1
                continue
            }
            turns += LlmMessage.assistant(assistant.text)
            turns += LlmMessage.user(user.text)
            index -= 2
        }
        return turns.asReversed()
    }

    /**
     * 改掉某条**用户消息**的文字。
     *
     * 只改显示，不重新回答：重答会把后面的上下文全冲掉。
     * 想要新答案，改完再发一遍即可。
     */
    fun updateUserText(index: Int, text: String): Boolean {
        val item = items.getOrNull(index) ?: return false
        if (!item.isUser) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        items[index] = item.copy(text = trimmed)
        notifyChanged()
        return true
    }

    /**
     * 删掉一轮：用户那句 + 紧跟其后的助手回答。
     *
     * 连带删掉回答，是因为只删提问会剩一句没头没尾的回复。
     */
    fun deleteTurn(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        if (!item.isUser) return false
        val next = items.getOrNull(index + 1)
        if (next != null && !next.isUser) {
            items.removeAt(index + 1)
        }
        items.removeAt(index)
        notifyChanged()
        return true
    }

    fun clear() {
        items.clear()
        notifyChanged()
    }

    fun attach(target: Listener?) {
        listener = target
        target?.onMessagesChanged(items.toList())
    }

    private fun notifyChanged() {
        listener?.onMessagesChanged(items.toList())
        onChanged?.invoke(items.toList())
    }
}
