package com.example.voiceassistant.core

import com.example.voiceassistant.assistant.QueryUnderstanding
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.network.LlmMessage

/**
 * 核心层：对话历史管理。
 *
 * ## 三个职责
 * 1. **落盘一轮对话**（[recordUser] / [recordAssistant]）——会话消息只有这一个写入口，
 *    界面不再直接碰 [ChatSession]，免得"理解过程"这类临时条目混进历史；
 * 2. **给云端准备上下文**（[cloudTurns]）——限轮数 + 每条限长；
 * 3. **判断要不要带上下文**（[needsContext]）——这是省 token 的关键，
 *    见下面说明。
 *
 * ## 为什么历史要"按需"而不是每轮都带
 * 历史是每轮都要重发的，纯粹是成本。而本地已经做过指代消解：
 * 「它怎么样」会被改写成「上海怎么样」——这种自足的问句，
 * 模型不需要前文也能答。只有确实存在指代、或出现明显的追问词时，
 * 才把历史带上（[needsContext]）。
 *
 * ## 轮数上限
 * 定 4 轮：比"完全不带"连贯，又不至于像最初那样无限增长。
 * 另外每条再截断到 40 字，长问答不会被整段重发。
 */
class HistoryManager(
    private val session: ChatSession,
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val clipChars: Int = DEFAULT_CLIP_CHARS
) {

    /** 记一条用户消息（立刻上屏，不等回答） */
    fun recordUser(text: String) {
        if (text.isBlank()) return
        session.addUser(text)
    }

    /** 记一条助手消息（答案落地时调用；会先清掉理解过程） */
    fun recordAssistant(text: String, intent: String? = null) {
        if (text.isBlank()) return
        session.clearThinking()
        session.addAssistant(text, intent)
    }

    /** 这一轮要带的历史；[useHistory] 为 false 时返回空表（省 token） */
    fun cloudTurns(useHistory: Boolean, max: Int = maxTurns): List<LlmMessage> {
        if (!useHistory || max <= 0) return emptyList()
        return session.recentTurns(max).map { it.copy(content = TextCleaner.clip(it.content, clipChars)) }
    }

    /** 最近若干条用户说过的话（本地指代消解 / 上下文承接用） */
    fun userLines(max: Int = QueryUnderstanding.LOCAL_MEMORY_TURNS): List<String> =
        session.recentUserLines(max)

    /**
     * 这一轮要不要带历史上下文。
     *
     * 两种情况需要：
     * - 存在指代：原话被改写过（[QueryUnderstanding.Understanding.resolved] ≠ raw）；
     * - 明显是追问：出现「那 / 还 / 然后呢 / 为什么」这类词。
     */
    fun needsContext(understanding: QueryUnderstanding.Understanding): Boolean {
        if (understanding.resolved != understanding.raw) return true
        return FOLLOW_UP_CUES.any { understanding.raw.contains(it) }
    }

    fun clear() {
        session.clear()
    }

    val messages: List<com.example.voiceassistant.data.ChatMessage> get() = session.messages

    companion object {
        /** 给云端带几轮上下文（一轮 = 用户 + 助手各一句） */
        const val DEFAULT_MAX_TURNS = 4

        /** 每条上下文最多几个字 */
        const val DEFAULT_CLIP_CHARS = 40

        private val FOLLOW_UP_CUES = listOf(
            "它", "他", "她", "这个", "那个", "那里", "这边", "上面",
            "刚才", "为什么", "然后呢", "还有吗", "再", "还"
        )
    }
}
