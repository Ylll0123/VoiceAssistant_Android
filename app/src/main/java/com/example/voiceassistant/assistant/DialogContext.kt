package com.example.voiceassistant.assistant

/**
 * 多轮对话上下文。
 *
 * 只保存两样东西：
 * - [lastIntent] 上一轮命中的意图（比如 date / arithmetic / joke / guess）
 * - 若干「槽位」slot（比如上次算出的数值、猜数字游戏的目标值）
 *
 * 有效期 [timeoutMs]（默认 60 秒）：超过这个时间没有新的交互就自动作废，
 * 避免用户隔了很久说一句「那后天呢」时被套用到很早之前的上下文上。
 *
 * 时间来源通过 [clock] 注入，方便在单元测试里模拟时间流逝。
 */
class DialogContext(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    var lastIntent: String? = null
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
        updatedAt = 0L
        slots.clear()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
