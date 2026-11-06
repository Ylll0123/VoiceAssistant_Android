package com.example.voiceassistant.reminder

/**
 * 对话模块眼中的「提醒中心」。**只有这六个动作**。
 *
 * 为什么要有这层接口：对话管理器的职责是「理解用户要什么」，
 * 至于提醒存在哪、怎么排闹钟、要不要发通知，它一概不该知道。
 * 中间隔一个接口，两边就能各自单测——对话管理器用假实现跑多轮，
 * 提醒中心用假存储跑状态流转，谁也不用等谁。
 */
interface ReminderPort {

    /** 新建一条提醒；[at] 为 0 表示没说时间 */
    fun create(
        content: String,
        at: Long,
        repeat: Reminder.Repeat = Reminder.Repeat.NONE,
        ctxId: String? = null
    ): Reminder

    /** 改时间（指代消解的落点：知道 id 就能改） */
    fun updateTime(id: Int, at: Long): Reminder?

    /** 稍后再提醒：从原时间（已过期则从现在）往后挪 N 分钟 */
    fun snooze(id: Int, minutes: Long): Reminder?

    /** 删掉一条 */
    fun cancel(id: Int): Boolean

    /** 清空；返回清掉几条 */
    fun cancelAll(): Int

    /** 还没到点的提醒（按触发时刻排序，没时间的排最后） */
    fun list(): List<Reminder>
}
