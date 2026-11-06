package com.example.voiceassistant.reminder

import android.content.Context
import com.example.voiceassistant.tool.ReminderTool

/**
 * 提醒模块的**唯一安装入口**。
 *
 * App 启动时调一次 [install]：建通知渠道 → 建 SQLite 仓库 →
 * 把老版本存在 SharedPreferences 里的提醒搬过来 →
 * 把工具层的关键词兜底通道也指向同一个提醒中心 →
 * 把重启 / 覆盖安装后丢掉的闹钟重新排上。
 *
 * 之后工具层、对话层、广播接收器都只需要 `Reminders.center(context)`，
 * 谁都不用自己 new 一个（那样会有两份缓存、两次排闹钟）。
 */
object Reminders {

    @Volatile
    private var instance: ReminderCenter? = null

    /**
     * 装上提醒能力（幂等，重复调用只是复用同一个中心）。
     *
     * @param context 任意 Context，内部会转成 applicationContext 防止泄漏
     */
    fun install(context: Context) {
        val app = context.applicationContext
        runCatching { ReminderAlarm.ensureChannel(app) }
        val center = center(app)

        // 关键词兜底通道也走同一个中心：两套存储迟早会不一致
        ReminderTool.sink = center

        runCatching { migrateFromPrefs(app, center) }
        runCatching { center.prune() }
        runCatching { center.rescheduleAll() }
    }

    /** 提醒中心（进程级单例） */
    fun center(context: Context): ReminderCenter {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val app = context.applicationContext
            val created = ReminderCenter(
                repo = ReminderDb(app),
                schedule = { ReminderAlarm.schedule(app, it) },
                cancelAlarm = { ReminderAlarm.cancel(app, it) }
            )
            instance = created
            return created
        }
    }

    /**
     * 把 v3.3 及更早版本存在 SharedPreferences 里的提醒搬进 SQLite。
     *
     * 只在**新表为空**时搬：否则用户升级后每次启动都会把旧提醒重新灌一遍。
     * 搬完立刻删掉旧键，下次启动就走不到这里了。
     */
    private fun migrateFromPrefs(context: Context, center: ReminderCenter) {
        val prefs = context.getSharedPreferences(ReminderCodec.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(ReminderCodec.KEY_ITEMS, null) ?: return
        if (center.list().isEmpty()) {
            ReminderCodec.decode(raw).forEach { old ->
                if (old.content.isNotBlank()) {
                    center.create(old.content, old.triggerAt, old.repeat, old.ctxId)
                }
            }
        }
        prefs.edit().remove(ReminderCodec.KEY_ITEMS).apply()
    }
}
