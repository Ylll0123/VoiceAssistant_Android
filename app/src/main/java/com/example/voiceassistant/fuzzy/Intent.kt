package com.example.voiceassistant.fuzzy

import com.example.voiceassistant.reminder.Reminder

/**
 * 模糊容错引擎能识别出来的意图。
 *
 * 只覆盖「指令类」说法：问答题（天气 / 百科 / 闲聊）不在这里，
 * 它们不需要槽位，交给后面的问答链路更合适。
 */
enum class IntentKind(val code: String) {

    /** 新建提醒：「明天八点提醒我交作业」 */
    SET_REMIND("set_remind"),

    /** 查看提醒：「我的提醒」「有哪些提醒」 */
    LIST_REMIND("list_remind"),

    /** 修改提醒时间：「改成四点」「推迟一小时」 */
    UPDATE_REMIND("update_remind"),

    /** 取消某条提醒：「取消提醒」「不要第二个了」 */
    CANCEL_REMIND("cancel_remind"),

    /** 清空提醒：「提醒全删了」 */
    CLEAR_REMIND("clear_remind"),

    /** 再提醒一次：「十分钟后再提醒我」 */
    SNOOZE_REMIND("snooze_remind"),

    /** 计时 / 倒计时：「计时五分钟」 */
    SET_TIMER("set_timer"),

    /** 取消计时 */
    CANCEL_TIMER("cancel_timer"),

    /** 没听懂 / 不属于指令域 */
    UNKNOWN("unknown");

    /** 是不是「提醒中心」负责的意图（对话管理器据此决定要不要接手） */
    val isReminderDomain: Boolean
        get() = this in setOf(
            SET_REMIND, LIST_REMIND, UPDATE_REMIND,
            CANCEL_REMIND, CLEAR_REMIND, SNOOZE_REMIND
        )
}

/**
 * 识别出来的槽位。
 *
 * 框架里写的是 `Map<String,String>`；这里用具名属性，效果一样，
 * 但少了一层「键名写错只能运行时才发现」的风险。要往外传时用 [asMap]。
 */
data class Slots(
    /** 解析出的绝对时刻（毫秒）；0 表示这句话没说时间 */
    val timeAt: Long = 0L,
    /** 时间的人话说明（「明天 08:00」） */
    val timeLabel: String = "",
    /** 要提醒的内容（已剥掉时间表达） */
    val content: String = "",
    /** 清单序号（1 开始）；0 表示没指定第几条 */
    val index: Int = 0,
    /** 重复规则 */
    val repeat: Reminder.Repeat = Reminder.Repeat.NONE,
    /** 相对位移（「推迟一小时」= +3600000）；0 表示不是相对说法 */
    val shiftMs: Long = 0L
) {
    fun asMap(): Map<String, String> = buildMap {
        if (timeAt > 0) put("time", timeLabel)
        if (timeAt > 0) put("trigger_at", timeAt.toString())
        if (content.isNotBlank()) put("content", content)
        if (index > 0) put("index", index.toString())
        if (repeat != Reminder.Repeat.NONE) put("repeat", repeat.code)
        if (shiftMs != 0L) put("shift_ms", shiftMs.toString())
    }
}

/**
 * 识别结果：**标准化的意图**，供对话管理器消费。
 *
 * [confidence] 是这套设计的关键——高置信直接执行，中置信追问确认，
 * 低置信（低于 [FuzzyEngine.ACCEPT]）就当作没听懂，绝不能硬猜着去建提醒。
 */
data class IntentResult(
    val kind: IntentKind,
    /** 0.0 ~ 1.0；UNKNOWN 时为 0 */
    val confidence: Double,
    /** 用户原话 */
    val raw: String,
    /** 归一化之后的文本（去语气词、标点、全角） */
    val normalized: String,
    /** 纠错之后的文本（同音字已修正） */
    val corrected: String,
    val slots: Slots = Slots(),
    /** 改过哪些字，用来在界面上解释「我按什么理解的」 */
    val fixes: List<String> = emptyList()
) {
    val intentId: String get() = kind.code

    /** 置信度高到可以直接执行，不必追问 */
    val confident: Boolean get() = confidence >= FuzzyEngine.HIGH

    /** 该不该让提醒/对话模块接手这句话 */
    val actionable: Boolean get() = kind != IntentKind.UNKNOWN && confidence >= FuzzyEngine.ACCEPT
}
