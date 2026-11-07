package com.example.voiceassistant.reminder

import java.time.ZoneId

/**
 * 通知提醒中心：**提醒这件事只有一个入口**。
 *
 * 增删改查、重复规则、状态流转、到点之后怎么办，全在这里。
 * 它不知道用户是怎么说出这句话的（语音？文字？温度传感器？），
 * 也不知道界面上长什么样——那些都是别人的事。
 *
 * ## 状态机
 * ```
 *            create
 *              ↓
 *          ┌────────┐   fire(一次性)   ┌───────────┐
 *          │pending │ ───────────────▶│ triggered │
 *          └────────┘                  └───────────┘
 *              │  ▲
 *     cancel   │  │ fire(每天/每周) + 计算下一次
 *              ▼  │
 *        ┌──────────┐
 *        │ canceled │
 *        └──────────┘
 * ```
 * 「已触发」不删行而是留状态，是为了让「刚才那条提醒」这种话还有据可查；
 * 表会靠 [ReminderRepository.pruneTriggered] 定期修剪，不会无限长。
 *
 * ## 为什么到点后要重排而不是重算
 * 「每天 8 点」在触发的那一瞬间就把下一次算好、排进 AlarmManager。
 * 如果等到用户下次打开 App 再补，中间那几天就永远不会响——这是
 * 很多课程作业里「重复提醒只会响一次」的根因。
 */
class ReminderCenter(
    private val repo: ReminderRepository,
    /** 排闹钟；App 里接到 AlarmManager，单测里记账 */
    private val schedule: (Reminder) -> Unit = {},
    /** 撤闹钟 + 撤通知 */
    private val cancelAlarm: (Int) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ReminderPort {

    // ------------------------------------------------------------------ ReminderPort

    @Synchronized
    override fun create(
        content: String,
        at: Long,
        repeat: Reminder.Repeat,
        ctxId: String?
    ): Reminder {
        val trimmed = content.trim()
        val saved = repo.insert(
            Reminder(
                content = trimmed,
                triggerAt = at,
                createdAt = clock(),
                repeat = repeat,
                status = Reminder.Status.PENDING,
                ctxId = ctxId
            )
        )
        if (saved.scheduled) runCatching { schedule(saved) }
        return saved
    }

    @Synchronized
    override fun updateTime(id: Int, at: Long): Reminder? {
        val current = find(id) ?: return null
        runCatching { cancelAlarm(id) }
        val updated = current.copy(triggerAt = at, status = Reminder.Status.PENDING)
        if (!repo.update(updated)) return null
        if (updated.scheduled) runCatching { schedule(updated) }
        return updated
    }

    @Synchronized
    override fun snooze(id: Int, minutes: Long): Reminder? {
        val current = find(id) ?: return null
        // 从「原时间」往后挪；原时间已经过去（通知刚响过）才从现在算
        val base = maxOf(current.triggerAt, clock())
        return updateTime(current.id, base + minutes * 60_000L)
    }

    @Synchronized
    override fun cancel(id: Int): Boolean {
        val current = find(id)
        if (current == null) {
            // 清单里没有也要撤一次闹钟：可能是已经被标记过、
            // 但 AlarmManager 里的登记还在（重复触发就是这么来的）
            runCatching { cancelAlarm(id) }
            return false
        }
        repo.update(current.copy(status = Reminder.Status.CANCELED))
        runCatching { cancelAlarm(id) }
        return true
    }

    @Synchronized
    override fun cancelAll(): Int {
        val pending = list()
        for (item in pending) {
            repo.update(item.copy(status = Reminder.Status.CANCELED))
            runCatching { cancelAlarm(item.id) }
        }
        return pending.size
    }

    /** 还没到点的提醒；没时间的排最后（它们本来就不会响） */
    @Synchronized
    override fun list(): List<Reminder> = repo.all()
        .filter { it.status == Reminder.Status.PENDING }
        .sortedWith(compareBy({ it.triggerAt == 0L }, { it.triggerAt }, { it.id }))

    // ------------------------------------------------------------------ 到点

    /**
     * 闹钟响了。
     *
     * @return 下一次的时间（重复提醒），一性次返回 null
     */
    @Synchronized
    fun fire(id: Int): Reminder? {
        val current = find(id) ?: return null
        val now = clock()
        if (current.repeat == Reminder.Repeat.NONE) {
            repo.update(current.copy(status = Reminder.Status.TRIGGERED))
            return null
        }
        val next = ReminderRepeat.nextAfter(current.triggerAt, current.repeat, now, zone)
        val updated = current.copy(triggerAt = next, status = Reminder.Status.PENDING)
        if (!repo.update(updated)) return null
        runCatching { schedule(updated) }
        return updated
    }

    /** 清掉历史记录（已触发且早于 [before] 天前） */
    @Synchronized
    fun prune(days: Int = 7) {
        runCatching { repo.pruneTriggered(clock() - days * 24L * 60 * 60 * 1000) }
    }

    /** App 启动时把还没到点的提醒全部重排（重启后 AlarmManager 是空的） */
    @Synchronized
    fun rescheduleAll(now: Long = clock()) {
        for (item in list()) {
            if (item.scheduled && item.triggerAt > now) runCatching { schedule(item) }
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun find(id: Int): Reminder? = repo.all().firstOrNull { it.id == id }
}
