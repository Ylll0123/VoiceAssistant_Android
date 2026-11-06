package com.example.voiceassistant.reminder

import com.example.voiceassistant.bus.Event

/**
 * 提醒到点了。
 *
 * 由 [ReminderReceiver]（系统广播里）发出，订阅者是谁它并不知道：
 * 界面活着就播报 + 闪一下状态，界面不在前台就没人接——
 * 这种「发完不管」正是解耦的好处，提醒中心不需要持有一堆 UI 引用。
 */
data class ReminderFired(
    val id: Int,
    val content: String,
    val repeat: Reminder.Repeat = Reminder.Repeat.NONE,
    val firedAt: Long = System.currentTimeMillis()
) : Event
