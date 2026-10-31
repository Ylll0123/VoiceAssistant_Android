package com.example.voiceassistant.assistant

/**
 * 多轮对话上下文。
 *
 * 保存四类信息：
 * - [lastIntent] 上一轮命中的意图（date / arithmetic / timezone / guess ...）
 * - 若干「槽位」slot（上次算出的数值、猜数字的目标值、单位换算的两端单位）
 * - [pendingUtterance] 待用户确认的那句话（「你是不是想问：现在几点了？」）
 * - [lastHeard] 上一轮实际采用的识别文本，用于界面上回显「我听到的是…」
 *
 * 有效期 [timeoutMs]（默认 3 分钟）：超过这个时间没有新的交互就自动作废，
 * 避免用户隔了很久说一句「那后天呢」时被套用到很早之前的上下文上。
 * 时间来源通过 [clock] 注入，方便在单元测试里模拟时间流逝。
 */
class DialogContext(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
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

    private var updatedAt: Long = 0L
    private val slots = mutableMapOf<String, String>()

    /** 上下文是否还在有效期内 */
    fun isAlive(): Boolean = lastIntent != null && clock() - updatedAt <= timeoutMs

    /** 上一轮是否是 [intent] 且未过期 */
    fun hasIntent(intent: String): Boolean = isAlive() && lastIntent == intent

    /**
     * 记住这一轮：写入意图并更新槽位。
     * 如果旧上下文已过期，先清空槽位，避免读到上一段的残留值。
     */
    fun remember(intent: String, newSlots: Map<String, String> = emptyMap()) {
        if (!isAlive()) slots.clear()
        lastIntent = intent
        updatedAt = clock()
        slots.putAll(newSlots)
        // 一旦有新的正式一轮，之前的待确认就作废
        pendingUtterance = null
    }

    /** 记下这一轮实际采用的识别文本（不影响有效期判断） */
    fun rememberHeard(text: String) {
        lastHeard = text
    }

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
    }

    companion object {
        /** 3 分钟：一次演示里从「问天气」走到「追问」通常十几秒，留足余量 */
        const val DEFAULT_TIMEOUT_MS = 180_000L

        /** 「你是不是想问…」这类确认轮的意图标识 */
        const val INTENT_CLARIFY = "clarify"
    }
}
