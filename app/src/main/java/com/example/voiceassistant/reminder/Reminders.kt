package com.example.voiceassistant.reminder

import android.content.Context
import com.example.voiceassistant.data.UserScope
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

    /** 当前这份中心属于哪个账号；账号一变就重建（见 [UserScope]） */
    @Volatile
    private var owner: String? = null

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

    /**
     * 提醒中心（按账号分区的进程级单例）。
     *
     * 账号变了就换一本账：否则 A 登录时建的中心会一直拿着 A 的数据库，
     * B 登进来看到的是 A 的提醒。判断放在这里而不是登录按钮上，
     * 是因为广播接收器、工具层也都会走 `center()`，都得拿到对的那一份。
     */
    fun center(context: Context): ReminderCenter {
        val app = context.applicationContext
        val current = UserScope.of(app)
        instance?.let { if (owner == current) return it }
        synchronized(this) {
            instance?.let { if (owner == current) return it }
            val created = ReminderCenter(
                repo = ReminderDb(app),
                schedule = { ReminderAlarm.schedule(app, it) },
                cancelAlarm = { ReminderAlarm.cancel(app, it) }
            )
            instance = created
            owner = current
            // 关键词兜底通道也得跟着换，否则还会往上一本账里写
            ReminderTool.sink = created
            return created
        }
    }

    /**
     * 登出前调用：把当前账号已排的闹钟全部撤掉，再丢掉中心。
     *
     * 不撤的话，A 的闹钟会留在 AlarmManager 里，到点却去 B 的库里找 id，
     * 轻则找不到、重则弹出一条不是自己的提醒。
     */
    fun detach(context: Context) {
        val app = context.applicationContext
        runCatching { center(app).list().forEach { ReminderAlarm.cancel(app, it.id) } }
        synchronized(this) {
            instance = null
            owner = null
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
