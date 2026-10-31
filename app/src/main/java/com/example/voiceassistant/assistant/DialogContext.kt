package com.example.voiceassistant.assistant

/**
 * 多轮对话上下文。
 *
 * 保存这几类信息：
 * - [lastIntent] 上一轮命中的意图（date / arithmetic / timezone / chat ...）
 * - 若干「槽位」slot（上次算出的数值、猜数字的目标值、当前话题 id）
 * - [topicId] 当前聊到的话题（加班、累了、考试……），闲聊能不能「接着上文」全靠它
 * - [recentReplies] 最近说过的几句回复，用来避免同一句话反复说
 * - [pendingUtterance] 待用户确认的那句话（「你是不是想问：现在几点了？」）
 * - [lastHeard] 上一轮实际采用的识别文本，用于界面上回显「我听到的是…」
 *
 * 有效期 [timeoutMs]（默认 3 分钟）：超过这个时间没有新的交互就自动作废，
 * 避免用户隔了很久说一句「那后天呢」时被套用到很早之前的上下文上。
 * 话题的有效期更长（[topicTimeoutMs]，默认 5 分钟），因为聊天中途插一句
 * 「现在几点」不应该把刚聊的话题弄丢。
 *
 * 时间来源通过 [clock] 注入，方便在单元测试里模拟时间流逝。
 */
class DialogContext(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val topicTimeoutMs: Long = DEFAULT_TOPIC_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    var lastIntent: String? = null
        private set

    /** 上一轮实际采用的输入（多候选时可能不是识别结果的第一条） */
    var lastHeard: String? = null
        private set

    /** 待确认的候选说法；用户回「是 / 对 / 第一个」就执行它 */
    var pendingUtterance: String? = null
        private set

    /** 当前话题 id；[activeTopic] 会额外检查是否过期 */
    var topicId: String? = null
        private set

    /** 当前话题已经聊了几轮（第一句算第 1 轮） */
    var topicTurn: Int = 0
        private set

    /** 本次会话一共处理了几轮 */
    var turns: Int = 0
        private set

    /** 其中闲聊占了几轮，回复变体按它轮换 */
    var chatTurns: Int = 0
        private set

    private var updatedAt: Long = 0L
    private var topicUpdatedAt: Long = 0L
    private val slots = mutableMapOf<String, String>()

    /** 最近说过的回复（最多 [MAX_RECENT_REPLIES] 条），用于避免复读 */
    private val recentReplies = ArrayDeque<String>()

    /** 上下文是否还在有效期内 */
    fun isAlive(): Boolean = lastIntent != null && clock() - updatedAt <= timeoutMs

    /** 上一轮是否是 [intent] 且未过期 */
    fun hasIntent(intent: String): Boolean = isAlive() && lastIntent == intent

    /** 当前话题：没聊过、或已经超过话题有效期，都返回 null */
    fun activeTopic(): String? =
        if (topicId != null && clock() - topicUpdatedAt <= topicTimeoutMs) topicId else null

    /**
     * 记住这一轮：写入意图并更新槽位。
     *
     * 槽位里带 [SLOT_TOPIC] 时顺手更新话题计数：
     * 换了话题就从第 1 轮重新数，同一个话题继续聊就累加。
     * 如果旧上下文已过期，先清空槽位，避免读到上一段的残留值。
     */
    fun remember(intent: String, newSlots: Map<String, String> = emptyMap()) {
        if (!isAlive()) slots.clear()

        val newTopic = newSlots[SLOT_TOPIC]
        if (newTopic != null) {
            if (newTopic == topicId && activeTopic() != null) {
                topicTurn += 1
            } else {
                topicId = newTopic
                topicTurn = 1
            }
            topicUpdatedAt = clock()
        } else if (activeTopic() == null) {
            topicId = null
            topicTurn = 0
        }

        lastIntent = intent
        updatedAt = clock()
        slots.putAll(newSlots)
        // 一旦有新的正式一轮，之前的待确认就作废
        pendingUtterance = null

        turns += 1
        if (intent == INTENT_CHAT) chatTurns += 1
    }

    /** 记下这一轮实际采用的识别文本（不影响有效期判断） */
    fun rememberHeard(text: String) {
        lastHeard = text
    }

    /** 记下刚说出口的一句回复，供下一轮挑选变体时避重 */
    fun markReply(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        recentReplies.addLast(value)
        while (recentReplies.size > MAX_RECENT_REPLIES) recentReplies.removeFirst()
    }

    /** 这句话最近是不是刚说过（包含即算，便于比对「原句」和「原句+追问」） */
    fun wasSaidRecently(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        return recentReplies.any { it.contains(value) }
    }

    /** 最近说过的回复条数，设置页展示用 */
    fun recentReplyCount(): Int = recentReplies.size

    /** 记下一个待确认的说法，等下一轮用户回答 */
    fun setPending(utterance: String) {
        pendingUtterance = utterance
        updatedAt = clock()
        lastIntent = INTENT_CLARIFY
    }

    /** 取走待确认说法（读一次就清掉，避免重复触发） */
    fun consumePending(): String? {
        val value = if (hasIntent(INTENT_CLARIFY)) pendingUtterance else null
        pendingUtterance = null
        return value
    }

    fun clearPending() {
        pendingUtterance = null
    }

    /** 读取槽位；上下文过期则视为不存在 */
    fun slot(key: String): String? = if (isAlive()) slots[key] else null

    /** 当前生效的槽位快照，用于设置页里的调试展示 */
    fun slotsSnapshot(): Map<String, String> = if (isAlive()) slots.toMap() else emptyMap()

    /** 剩余有效时间（毫秒），便于界面提示「上下文还剩几秒」 */
    fun remainingMs(): Long {
        if (!isAlive()) return 0
        return (timeoutMs - (clock() - updatedAt)).coerceAtLeast(0)
    }

    fun clear() {
        lastIntent = null
        lastHeard = null
        pendingUtterance = null
        updatedAt = 0L
        slots.clear()
        topicId = null
        topicTurn = 0
        topicUpdatedAt = 0L
        turns = 0
        chatTurns = 0
        recentReplies.clear()
    }

    companion object {
        /** 3 分钟：一次演示里从「问天气」走到「追问」通常十几秒，留足余量 */
        const val DEFAULT_TIMEOUT_MS = 180_000L

        /** 话题记 5 分钟：中间插一句「现在几点」不至于把聊天线索弄断 */
        const val DEFAULT_TOPIC_TIMEOUT_MS = 300_000L

        /** 「你是不是想问…」这类确认轮的意图标识 */
        const val INTENT_CLARIFY = "clarify"

        /** 闲聊意图标识 */
        const val INTENT_CHAT = "chat"

        /** 槽位里存放话题 id 的键 */
        const val SLOT_TOPIC = "topic"

        /** 记住最近 12 句回复，够覆盖三四种变体的一轮循环 */
        private const val MAX_RECENT_REPLIES = 12
    }
}
