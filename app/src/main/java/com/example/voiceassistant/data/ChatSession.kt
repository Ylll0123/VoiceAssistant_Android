package com.example.voiceassistant.data

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.AssistantResponse

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

    val messages: List<ChatMessage> get() = items

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

    /** 最近一次助手命中的意图，设置页展示用 */
    fun lastIntent(): String? = items.lastOrNull { !it.isUser }?.intent

    /**
     * 改写最后一条助手消息。
     *
     * 云端兜底是异步的：本地先落一条「让我想想」，拿到结果后再把它替换掉，
     * 这样界面上不会出现两条答案，也不会因为网络慢而卡住整段对话。
     */
    fun updateLastAssistant(text: String, intent: String? = null): Boolean {
        val index = items.indexOfLast { !it.isUser }
        if (index < 0) return false
        items[index] = items[index].copy(text = text, intent = intent)
        notifyChanged()
        return true
    }

    /** 最近几轮对话（问，答），云端请求带上下文用 */
    fun recentPairs(limit: Int = 3): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        var i = items.size - 1
        while (i > 0 && pairs.size < limit) {
            val answer = items[i]
            if (!answer.isUser) {
                val askIndex = (i - 1 downTo 0).firstOrNull { items[it].isUser }
                if (askIndex != null) {
                    pairs.add(0, items[askIndex].text to answer.text)
                    i = askIndex - 1
                    continue
                }
            }
            i--
        }
        return pairs
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
        listener?.onMessagesChanged(items.toList())
    }
}
