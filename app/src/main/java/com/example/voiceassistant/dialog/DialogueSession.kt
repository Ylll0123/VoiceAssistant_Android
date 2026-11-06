package com.example.voiceassistant.dialog

import com.example.voiceassistant.reminder.Reminder

/**
 * 一轮对话的**短期上下文**（只活在内存里）。
 *
 * 框架里把这叫「session 上下文」：轮次、上一轮的意图、还没填完的槽位、
 * 上一条提醒的 id、以及最近几轮问答。
 *
 * ## 两个刻意的边界
 * 1. **只放内存，且有超时**。上下文是「刚才那几句话」的接力棒，
 *    不是用户资产。放久了一定会串味（十分钟后说「改成四点」，
 *    谁也不记得改哪条），所以 [DEFAULT_TIMEOUT_MS] 到点就清空。
 * 2. **长期数据不放这里**。提醒清单在 SQLite 里，会话上下文只是
 *    「引用」了其中一条的 id（[refReminderId]），指代消解靠它，
 *    但真删真改还是走提醒中心。
 */
class DialogueSession(
    /** 会话 id，建提醒时写进 ctx_id，方便回头查「这条是哪次对话建的」 */
    val id: String = "sess_" + System.currentTimeMillis().toString(36),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    /** 当前在等什么 */
    enum class State {
        /** 空闲：没有半截的指令 */
        IDLE,

        /** 等用户补时间：「提醒我交作业」→「几点？」 */
        EXPECT_TIME
    }

    var state: State = State.IDLE
        private set

    /** 已经记下、只差时间的提醒内容 */
    var partialContent: String = ""
        private set

    /** 半截提醒的重复规则（「每天提醒我吃药」没说几点时先存着） */
    var partialRepeat: Reminder.Repeat = Reminder.Repeat.NONE
        private set

    /** 上一次操作过的提醒 id：指代消解（「把它改成四点」）的关键 */
    var refReminderId: Int? = null
        private set

    /** 最近一次活动时刻，用来判超时 */
    var lastActiveAt: Long = 0L
        private set

    /** 这一轮问了几次时间还没问出来（问两次就放弃，别把用户困住） */
    var timeRetries: Int = 0
        private set

    /** 最近几轮问答，给「查看上一轮」这类需求留个底（内存，最多 [MAX_HISTORY] 条） */
    private val rounds = ArrayDeque<Pair<String, String>>()

    val history: List<Pair<String, String>> get() = rounds.toList()

    /** 有半截指令在等用户回答 */
    val waiting: Boolean get() = state != State.IDLE

    /** 记一次活动（每轮开头调） */
    fun touch(now: Long) {
        lastActiveAt = now
    }

    /** 要求用户补时间 */
    fun expectTime(content: String, repeat: Reminder.Repeat, now: Long) {
        state = State.EXPECT_TIME
        partialContent = content
        partialRepeat = repeat
        lastActiveAt = now
    }

    fun countRetry() {
        timeRetries++
    }

    /** 记下「刚才操作的是哪一条」 */
    fun rememberRef(id: Int?) {
        refReminderId = id
    }

    /** 清掉半截指令（用户换话题 / 放弃 / 问太多次了） */
    fun resetPending() {
        state = State.IDLE
        partialContent = ""
        partialRepeat = Reminder.Repeat.NONE
        timeRetries = 0
    }

    /** 记一轮问答（不碰「半截指令」状态：追问那一轮也要留痕） */
    fun recordRound(user: String, reply: String, now: Long) {
        lastActiveAt = now
        rounds.addLast(user to reply)
        while (rounds.size > MAX_HISTORY) rounds.removeFirst()
    }

    /**
     * 超时就清空。
     *
     * 只清「半截指令」和「引用」，不清历史——历史是给人看的记录，
     * 清空了反而像丢了东西。
     */
    fun expire(now: Long): Boolean {
        if (lastActiveAt <= 0L) return false
        if (now - lastActiveAt <= timeoutMs) return false
        resetPending()
        refReminderId = null
        return true
    }

    /** 忘掉引用（目标被删掉时调，避免下一轮改到一条不存在的提醒） */
    fun clearRef(id: Int) {
        if (refReminderId == id) refReminderId = null
    }

    companion object {
        /** 空闲多久算换了个话题（框架建议 60~120 秒，取上限更宽容） */
        const val DEFAULT_TIMEOUT_MS = 120_000L

        /** 最多留几轮问答 */
        const val MAX_HISTORY = 5
    }
}
