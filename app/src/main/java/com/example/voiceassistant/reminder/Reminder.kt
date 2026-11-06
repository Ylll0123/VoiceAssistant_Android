package com.example.voiceassistant.reminder

/**
 * 提醒中心的数据模型（提醒模块自己的领域对象）。
 *
 * 字段按「一条提醒要能被独立调度」来定：
 * 内容、什么时候响、要不要重复、现在是什么状态、怎么提醒、以及
 * 它是**哪一轮对话**建出来的（[ctxId]）——最后这个字段是多轮指代的凭据：
 * 用户说「把它改成四点」时，对话管理器拿上一条的 id 就能改，不用重说一遍。
 */
data class Reminder(
    /** 自增主键；0 表示还没入库 */
    val id: Int = 0,
    /** 要提醒什么（已经剥掉时间表达，比如「交作业」） */
    val content: String,
    /** 触发时刻（毫秒）；0 表示没说时间，只在清单里躺着 */
    val triggerAt: Long = 0L,
    /** 建出来的时刻 */
    val createdAt: Long = System.currentTimeMillis(),
    /** 重复规则：一次性 / 每天 / 每周 */
    val repeat: Repeat = Repeat.NONE,
    /** 生命周期状态 */
    val status: Status = Status.PENDING,
    /**
     * 提醒方式（位掩码）：弹通知 + 语音播报。
     *
     * 用位掩码而不是两个布尔，是因为「只要弹窗不出声」这种组合很常见，
     * 存一个整数就够了；想加「响铃 / 震动」也不用改表结构。
     */
    val notifyMode: Int = NOTIFY_POPUP or NOTIFY_TTS,
    /** 建这条提醒的那轮对话的会话 id，用于指代消解 */
    val ctxId: String? = null
) {

    /** 有时间且在等触发 = 需要排闹钟 */
    val scheduled: Boolean get() = triggerAt > 0L && status == Status.PENDING

    fun withNotify(mode: Int): Reminder = copy(notifyMode = mode)

    fun has(mode: Int): Boolean = notifyMode and mode != 0

    /** 重复规则 */
    enum class Repeat(val code: String, val label: String) {
        NONE("none", "一次"),
        DAILY("daily", "每天"),
        WEEKLY("week", "每周");

        companion object {
            fun of(code: String?): Repeat = entries.firstOrNull { it.code == code } ?: NONE
        }
    }

    /** 状态：等触发 / 已触发 / 已取消 */
    enum class Status(val code: String) {
        PENDING("pending"),
        TRIGGERED("triggered"),
        CANCELED("canceled");

        companion object {
            fun of(code: String?): Status = entries.firstOrNull { it.code == code } ?: PENDING
        }
    }

    companion object {
        /** 弹通知 */
        const val NOTIFY_POPUP = 1

        /** 语音播报 */
        const val NOTIFY_TTS = 2
    }
}
