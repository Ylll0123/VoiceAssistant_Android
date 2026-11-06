package com.example.voiceassistant.reminder

/**
 * 提醒的存储接口。
 *
 * 提醒中心只认这个接口：正式运行时是 SQLite（[ReminderDb]），
 * 单测里换成内存实现。这样「该不该重排、状态怎么变」这类业务逻辑
 * 完全脱离 Android，可以像普通函数一样测。
 */
interface ReminderRepository {

    /** 全表（含已触发 / 已取消的历史记录） */
    fun all(): List<Reminder>

    /** 插入并回填自增 id */
    fun insert(reminder: Reminder): Reminder

    /** 按 id 覆盖整行；返回是否命中 */
    fun update(reminder: Reminder): Boolean

    fun delete(id: Int): Boolean

    fun deleteAll()

    /** 清掉早于 [before] 的已触发记录，避免表无限长 */
    fun pruneTriggered(before: Long): Int
}
