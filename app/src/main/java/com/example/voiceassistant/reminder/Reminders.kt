package com.example.voiceassistant.reminder

import android.content.Context
import com.example.voiceassistant.tool.ReminderTool

/**
 * 提醒模块的**唯一入口**。
 *
 * App 启动时调一次 [install]：建通知渠道、把工具层的落点换成持久化实现、
 * 把还没到点的提醒重新排上。之后工具层、调度层都不需要知道 Context。
 *
 * 这样设计的好处是：`ReminderTool` 保持纯逻辑（能在没有 Android 的单测里跑），
 * 所有系统依赖都收在这一个文件里。
 */
object Reminders {

    @Volatile
    private var store: ReminderStore? = null

    /**
     * 装上提醒能力（幂等，重复调用只是复用同一个落点）。
     *
     * @param context 任意 Context，内部会转成 applicationContext 防止泄漏
     */
    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { ReminderAlarm.ensureChannel(app) }
        ReminderTool.sink = store(app)
        runCatching { ReminderAlarm.rescheduleAll(app) }
    }

    /** 持久化的提醒清单；广播接收器里也用它 */
    fun store(context: Context): ReminderStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val app = context.applicationContext
            val created = ReminderStore(
                app,
                schedule = { ReminderAlarm.schedule(app, it) },
                unschedule = { ReminderAlarm.cancel(app, it) }
            )
            store = created
            return created
        }
    }
}
