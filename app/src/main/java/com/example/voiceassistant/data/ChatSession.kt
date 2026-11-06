package com.example.voiceassistant.data

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.network.LlmMessage

/**
 * 会话状态：消息列表 + 对话引擎（含多轮上下文）。
 *
 * 由 Activity 持有，多个页面共享同一份，所以从「对话」切到「技能」再切回来，
 * 消息和上下文都还在。
 *
 * 界面通过 [listener] 收到变更通知；Fragment 重建时用 [messages] 拉全量重绘。
 */
class ChatSession(
    val engine: AssistantEngine = AssistantEngine()
) {

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

    /** 用上次落盘的记录恢复对话（启动时调用一次） */
    fun restore(saved: List<ChatMessage>) {
        if (items.isNotEmpty() || saved.isEmpty()) return
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

    /** 文字输入 / 技能页示例：只有一条说法 */
    fun send(userText: String): AssistantResponse = send(listOf(userText))

    /**
     * 处理一次输入。
     *
     * 语音识别会给出多条候选（按置信度排序），整组交给引擎挑选；
     * 气泡里显示系统首选的那条，用户看到的就是「手机听到的话」。
     * 如果引擎最终采用了别的候选，回复里会说明，状态栏也会提示实际理解的说法。
     */
    fun send(candidates: List<String>): AssistantResponse {
        val primary = candidates.firstOrNull()?.trim().orEmpty()
        addUser(primary)
        val response = engine.respond(candidates)
        addAssistant(response.text, response.intent)
        return response
    }

    /**
     * 只让引擎出回答，不把回答写进消息列表。
     *
     * 「理解过程」要先于答案显示在屏幕上，所以由调用方决定何时插入助手消息。
     */
    fun respondOnly(candidates: List<String>): AssistantResponse {
        val primary = candidates.firstOrNull()?.trim().orEmpty()
        addUser(primary)
        return engine.respond(candidates)
    }

    // ------------------------------------------------------------ 理解过程（淡色小字）

    /** 起一条新的理解过程（会先清掉上一条残留的） */
    fun startThinking(firstLine: String) {
        clearThinking()
        items.add(ChatMessage.thinking(firstLine))
        notifyChanged()
    }

    /** 往当前这条理解过程里追加一行（没有就新建） */
    fun appendThinking(line: String) {
        val index = items.indexOfLast { it.kind == ChatMessage.KIND_THINKING }
        if (index < 0) {
            items.add(ChatMessage.thinking(line))
        } else {
            val old = items[index]
            items[index] = old.copy(text = "${old.text}\n$line")
        }
        notifyChanged()
    }

    /** 答案出来后把理解过程整条移除——用户只看到最终回答 */
    fun clearThinking() {
        if (items.removeAll { it.kind == ChatMessage.KIND_THINKING }) notifyChanged()
    }

    /** 最近若干条用户说过的话（本地逻辑分析 / 指代消解用） */
    fun recentUserLines(max: Int): List<String> {
        if (max <= 0) return emptyList()
        return items.filter { it.isUser }.map { it.text }.takeLast(max)
    }

    /** 最后一条助手消息的下标；联网结果回来后要改的就是它 */
    fun lastAssistantIndex(): Int =
        items.indexOfLast { !it.isUser && it.kind != ChatMessage.KIND_THINKING }

    /**
     * 最近若干轮对话，给云端大模型当上下文（保证回答接得上前面的话）。
     *
     * 取法是「用户一句 + 助手一句」成对往后扫，只保留完整的对；
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
            // 理解过程条目不参与上下文，直接跳过
            if (assistant.kind == ChatMessage.KIND_THINKING) {
                index -= 1
                continue
            }
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
     * 替换一条助手消息的内容。
     *
     * 联网查询是异步的：本地先给一句（常年气候数据 / "我上网查一下"），
     * 结果回来后把这条替换掉，用户看到的是同一条气泡刷新，而不是又冒一条。
     */
    fun replaceAssistant(index: Int, text: String, intent: String? = null) {
        if (index in items.indices && !items[index].isUser) {
            items[index] = ChatMessage(text = text, isUser = false, intent = intent)
            notifyChanged()
        }
    }

    /** 最近一次助手命中的意图，设置页展示用 */
    fun lastIntent(): String? = items.lastOrNull { !it.isUser }?.intent

    /** 取某一条（点击气泡后要先知道点的是哪条） */
    fun messageAt(index: Int): ChatMessage? = items.getOrNull(index)

    /**
     * 改掉某条**用户消息**的文字。
     *
     * 只改显示，不重新回答：重新回答会打断流水线、把后面的上下文全冲掉。
     * 想要新答案，改完再发一遍即可——界面上也是这么提示的。
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
     * 连带删掉回答，是因为只删提问会让对话剩一句没头没尾的回复；
     * 「理解过程」这类临时条目本来就不落盘，不参与删除。
     */
    fun deleteTurn(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        if (!item.isUser) return false
        val next = items.getOrNull(index + 1)
        if (next != null && !next.isUser && next.kind == ChatMessage.KIND_NORMAL) {
            items.removeAt(index + 1)
        }
        items.removeAt(index)
        notifyChanged()
        return true
    }

    fun clear() {
        items.clear()
        engine.resetContext()
        notifyChanged()
    }

    /** 初始化欢迎语（只在空会话时加） */
    fun ensureWelcome(text: String) {
        if (items.isEmpty()) addAssistant(text)
    }

    fun attach(target: Listener?) {
        listener = target
        target?.onMessagesChanged(items.toList())
    }

    private fun notifyChanged() {
        val snapshot = items.toList()
        listener?.onMessagesChanged(snapshot)
        onChanged?.invoke(snapshot)
    }
}
