package com.example.voiceassistant.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.voiceassistant.R
import com.example.voiceassistant.tool.ReminderTool
import com.example.voiceassistant.ui.VoiceAssistantActivity

/**
 * 提醒的**系统层**：排闹钟、发通知。
 *
 * 这一层刻意做得很薄——它只跟 Android 打交道，不含任何业务判断
 * （该提醒什么、什么时候提醒，都是 [ReminderTool] 和 [ReminderTime] 算好的）。
 *
 * ## 为什么用 AlarmManager 而不是 WorkManager
 * WorkManager 适合「不着急、能等系统攒批」的任务，实际触发时间可能漂移
 * 十几分钟。提醒是用户说「8 点叫我」的事，漂移就等于失约，
 * 所以用 `setExactAndAllowWhileIdle`。它能在 Doze 下准时唤醒。
 *
 * ## 降级：没有精确闹钟权限时改用「闹钟」
 * Android 14 起，新装的 App 默认**没有** `SCHEDULE_EXACT_ALARM`
 * （`canScheduleExactAlarms()` 返回 false）。这时若退回
 * `setAndAllowWhileIdle`，系统会给一个**一小时**的窗口——提醒可能晚一个小时，
 * 等于没提醒。所以改用 [AlarmManager.setAlarmClock]：
 * 它不需要那个权限、本身就是精确的，代价只是状态栏多一个闹钟图标。
 */
object ReminderAlarm {

    const val CHANNEL_ID = "reminder"

    private const val ACTION_FIRE = "com.example.voiceassistant.REMINDER_FIRE"
    private const val ACTION_DISMISS = "com.example.voiceassistant.REMINDER_DISMISS"
    private const val ACTION_SNOOZE = "com.example.voiceassistant.REMINDER_SNOOZE"

    const val EXTRA_ID = "reminder_id"
    const val EXTRA_TEXT = "reminder_text"

    /** 稍后再提醒的间隔 */
    const val SNOOZE_MINUTES = 10L

    // ------------------------------------------------------------------ 通知渠道

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notify_channel_reminder),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notify_channel_reminder_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------ 闹钟

    fun schedule(context: Context, reminder: ReminderTool.Reminder) {
        if (!reminder.scheduled) return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = fireIntent(context, reminder.id, reminder.text)
        val at = reminder.triggerAt

        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            manager.canScheduleExactAlarms()
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                // 没有精确闹钟权限：用「系统闹钟」这条路，同样是准点的
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(at, openAppIntent(context, reminder.id)),
                    pending
                )
            }
        } catch (_: SecurityException) {
            runCatching {
                manager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(at, openAppIntent(context, reminder.id)),
                    pending
                )
            }
        }
    }

    /** setAlarmClock 要求一个「点闹钟图标要打开谁」的 PendingIntent */
    private fun openAppIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
        context,
        id * 10 + 3,
        Intent(context, VoiceAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun cancel(context: Context, id: Int) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE),
            flags()
        )
        manager.cancel(pending)
        pending.cancel()
        context.getSystemService(NotificationManager::class.java)?.cancel(id)
    }

    /** 把库里所有还没到点的提醒重新排一遍（开机 / 应用升级后调用） */
    fun rescheduleAll(context: Context, now: Long = System.currentTimeMillis()) {
        for (reminder in Reminders.store(context).all()) {
            if (reminder.scheduled && reminder.triggerAt > now) schedule(context, reminder)
        }
    }

    // ------------------------------------------------------------------ 通知

    fun notifyNow(context: Context, id: Int, text: String) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val open = Intent(context, VoiceAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(
            context, id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notify_reminder_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(content)
            .addAction(0, context.getString(R.string.notify_action_snooze), broadcast(
                context, id, text, ACTION_SNOOZE
            ))
            .addAction(0, context.getString(R.string.notify_action_done), broadcast(
                context, id, text, ACTION_DISMISS
            ))
            .build()

        runCatching { manager.notify(id, notification) }
    }

    private fun broadcast(
        context: Context,
        id: Int,
        text: String,
        action: String
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TEXT, text)
        // requestCode 用 id*10+1/2，避免和闹钟那条 PendingIntent 撞车
        val code = id * 10 + if (action == ACTION_SNOOZE) 1 else 2
        return PendingIntent.getBroadcast(
            context, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun fireIntent(context: Context, id: Int, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun flags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** 通知能不能发出来（Android 13+ 需要用户授权） */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

/**
 * 闹钟到点后的落点：发通知，并把这条提醒从清单里摘掉。
 *
 * 摘掉是刻意的：闹钟已经响过，留在清单里只会变成一条永远「已过期」的记录；
 * 真想再提醒一次，用户说一句话就行，比在一堆过期项里翻要快。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(ReminderAlarm.EXTRA_ID, 0)
        val text = intent.getStringExtra(ReminderAlarm.EXTRA_TEXT).orEmpty()
        if (id <= 0 || text.isBlank()) return

        when (intent.action) {
            "com.example.voiceassistant.REMINDER_FIRE" -> {
                // 先摘清单再发通知：用户看到通知时，清单已经干净了
                runCatching { Reminders.store(context).remove(id) }
                ReminderAlarm.notifyNow(context, id, text)
            }

            "com.example.voiceassistant.REMINDER_SNOOZE" -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(id)
                val at = System.currentTimeMillis() + ReminderAlarm.SNOOZE_MINUTES * 60_000L
                runCatching { Reminders.store(context).add(text, at) }
            }

            "com.example.voiceassistant.REMINDER_DISMISS" -> {
                context.getSystemService(NotificationManager::class.java)?.cancel(id)
                runCatching { Reminders.store(context).remove(id) }
            }
        }
    }
}

/**
 * 开机 / 应用升级后把还没到点的提醒重新排上。
 *
 * 闹钟不是持久化的：重启手机、覆盖安装 APK、被系统清理，
 * AlarmManager 里的登记都会丢。提醒本身存在 SharedPreferences 里，
 * 开机时照着重排一遍就恢复了。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                runCatching {
                    ReminderAlarm.ensureChannel(context)
                    ReminderAlarm.rescheduleAll(context)
                }
            }
        }
    }
}
