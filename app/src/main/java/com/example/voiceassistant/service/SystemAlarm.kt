package com.example.voiceassistant.service

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import java.time.Instant
import java.time.ZoneId

/**
 * 服务层：**直接设手机自带的闹钟**。
 *
 * ## 为什么不再自己排
 * 应用内提醒依赖三件我们自己控制不了的事：进程活着、AlarmManager 精确触发、
 * 通知没被用户关掉。任何一环断了，用户看到的就是「闹钟没响」。
 * 系统时钟应用不会被省电策略杀掉，响铃走的是系统通道——一次设置，之后不归我们管，
 * 这比我们替他记着可靠得多。
 *
 * ## 什么时候退回来
 * 手机里没有时钟应用（极少数定制 ROM）、或者系统禁止后台启动界面，
 * 这时 [setAlarm] / [setTimer] 返回 false，调用方回退到应用内提醒——
 * 两条路至少有一条能响。
 *
 * ## 界面会不会跳出来
 * 一律带 `EXTRA_SKIP_UI=true`：建闹钟不该把用户从对话里弹走。
 * 少数时钟应用不认这个标志，那就会停在它的编辑页（用户点一下保存即可），
 * 我们仍然按「已建好」回报，并说明要确认一下。
 */
object SystemAlarm {

    private const val TAG = "SystemAlarm"

    /** 这台手机有没有时钟应用能接 SET_ALARM */
    fun canSetAlarm(context: Context): Boolean = resolves(context, alarmIntent())

    /** 有没有时钟应用能接 SET_TIMER（倒计时） */
    fun canSetTimer(context: Context): Boolean = resolves(context, timerIntent(60))

    /**
     * 建一个系统闹钟。
     *
     * @param atMillis 触发时刻
     * @param label 闹钟标签（多数时钟应用会显示在响铃界面）
     * @param weekDays 要重复的星期（[java.util.Calendar] 的 MONDAY..SUNDAY）；空 = 只响一次
     */
    fun setAlarm(
        context: Context,
        atMillis: Long,
        label: String,
        weekDays: List<Int> = emptyList()
    ): Boolean {
        val local = Instant.ofEpochMilli(atMillis).atZone(ZoneId.systemDefault())
        val intent = alarmIntent().apply {
            putExtra(AlarmClock.EXTRA_HOUR, local.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, local.minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.take(60))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (weekDays.isNotEmpty()) {
                // 重复闹钟：时钟应用靠这一组星期判断「每天 / 每周几」
                putExtra(AlarmClock.EXTRA_DAYS, ArrayList(weekDays))
            }
        }
        return start(context, intent)
    }

    /** 建一个系统倒计时（秒） */
    fun setTimer(context: Context, seconds: Int, label: String): Boolean {
        if (seconds <= 0) return false
        val intent = timerIntent(seconds).apply {
            putExtra(AlarmClock.EXTRA_MESSAGE, label.take(60))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return start(context, intent)
    }

    /**
     * 手机里能接系统闹钟的应用名。
     *
     * 用在「建好了」这句回话里：不同 ROM 的时钟应用名字不一样（时钟 / 闹钟 / Clock），
     * 说对名字用户才找得到去哪关。
     */
    fun clockAppName(context: Context): String {
        val intent = alarmIntent()
        val infos = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentActivities(
                    intent,
                    android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, 0)
            }
        }.getOrDefault(emptyList())
        val label = infos.firstOrNull()?.loadLabel(context.packageManager)?.toString().orEmpty()
        return label.ifBlank { "时钟" }
    }

    // ------------------------------------------------------------------ 内部

    private fun alarmIntent(): Intent =
        Intent(AlarmClock.ACTION_SET_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun timerIntent(seconds: Int): Intent =
        Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun resolves(context: Context, intent: Intent): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(0L)
            ) != null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0) != null
        }
    }.getOrDefault(false)

    private fun start(context: Context, intent: Intent): Boolean {
        if (!resolves(context, intent)) return false
        val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
        if (!ok) Log.w(TAG, "系统闹钟没拉起来：${intent.action}")
        return ok
    }
}
