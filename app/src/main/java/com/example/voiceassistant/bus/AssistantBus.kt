package com.example.voiceassistant.bus

/**
 * 全局唯一的事件总线实例。
 *
 * 为什么是全局单例而不是每个页面 new 一个：
 * 提醒是**系统广播**触发的（[com.example.voiceassistant.reminder.ReminderReceiver]），
 * 那时唯一的「接收方」就是当前活着的界面，广播里根本拿不到界面实例。
 * 靠一根进程级的总线，提醒中心只管喊一声，谁在听谁响应——
 * 界面不在前台也不会有问题（没人订阅，[EventBus.publish] 返回 0 而已）。
 */
val AssistantBus: EventBus = EventBus()
