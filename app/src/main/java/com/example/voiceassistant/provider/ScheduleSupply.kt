package com.example.voiceassistant.provider

import android.content.Context
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.nlu.TimeParse
import com.example.voiceassistant.nlu.TimeWords
import com.example.voiceassistant.reminder.Reminder
import com.example.voiceassistant.reminder.Reminders
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 日程与提醒域：`schedule/…`
 *
 * 这一域是「本地优先」收益最大的地方——「八点提醒我交作业」完全可以在本地算完就落库，
 * 一次网络请求都不用。所以 [com.example.voiceassistant.nlu.LocalNlu] 认领它，
 * 大模型那边也有同名工具，两条路最后都落到这几个 [Supply] 上。
 *
 * 底层复用 v3 就写好的 [Reminders]（SQLite + AlarmManager + 通知），
 * 没有重新造一套，闹钟的精确触发、重复规则、重启重排都还是原来那套。
 */
class ScheduleSupply(
    context: Context,
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) {

    private val app: Context = context.applicationContext

    /** 注册进总线的一批服务 */
    val all: List<Supply> = listOf(addAlarm(), addTodo(), query(), cancel())

    // ------------------------------------------------------------------ 新增

    private fun addAlarm(): Supply = object : Supply {
        override val name = "schedule/add_alarm"
        override val summary =
            "定闹钟 / 提醒。参数 content 是做什么，at 是触发时间（yyyy-MM-dd HH:mm），" +
                "或者用 minutes 表示「多少分钟后」。用户没说时间就别调，先问清几点。"

        override fun invoke(args: BusArgs): BusReply {
            val content = args.str("content")?.take(60)?.ifBlank { "提醒" } ?: "提醒"

            // 两种给法：绝对时间 or 相对分钟数
            val at: Long = when {
                args.str("at") != null -> {
                    val parsed = TimeParse.epoch(args.str("at"), now())
                        ?: return BusReply.fail("时间没看懂，需要 yyyy-MM-dd HH:mm 这样的格式，请再说一次几点。")
                    parsed
                }
                args.num("minutes") != null -> {
                    val minutes = args.num("minutes")!!
                    if (minutes <= 0) return BusReply.fail("计时时长要大于零。")
                    System.currentTimeMillis() + (minutes * 60_000).toLong()
                }
                else -> return BusReply.fail("没说几点提醒，先问清时间再建。")
            }

            // 时间已经过去：不硬建（会立刻响，像 bug），让调用方确认
            if (at <= System.currentTimeMillis() - 60_000) {
                return BusReply.fail("这个时间已经过了（${TimeParse.format(at)}），要不要换个时间？")
            }

            val repeat = when (args.str("repeat")) {
                "daily" -> Reminder.Repeat.DAILY
                "weekly" -> Reminder.Repeat.WEEKLY
                else -> Reminder.Repeat.NONE
            }
            val saved = Reminders.center(app).create(content, at, repeat)
            val suffix = when (repeat) {
                Reminder.Repeat.DAILY -> "，每天"
                Reminder.Repeat.WEEKLY -> "，每周"
                else -> ""
            }
            return BusReply.ok(
                "已建好：$content，${TimeParse.format(saved.triggerAt)}$suffix。",
                "id" to saved.id.toString()
            )
        }
    }

    private fun addTodo(): Supply = object : Supply {
        override val name = "schedule/add_todo"
        override val summary = "记一条待办（没有具体时间，不会响闹钟）。参数 content 是待办内容。"

        override fun invoke(args: BusArgs): BusReply {
            val content = args.str("content")?.take(60)
                ?: return BusReply.fail("没听清要记什么。")
            // triggerAt = 0 表示「没定时间」：提醒列表里排在最后，也不会排闹钟
            val saved = Reminders.center(app).create(content, 0L, Reminder.Repeat.NONE)
            return BusReply.ok("记下了：$content（还没定时间）。", "id" to saved.id.toString())
        }
    }

    // ------------------------------------------------------------------ 查询

    private fun query(): Supply = object : Supply {
        override val name = "schedule/query"
        override val summary =
            "查看待办和闹钟。参数 scope：all（全部，默认）/ today（今天）/ timed（只看到点的）。"

        override fun invoke(args: BusArgs): BusReply {
            val scope = args.str("scope") ?: "all"
            val pending = Reminders.center(app).list()
                .filter { it.status == Reminder.Status.PENDING }

            val filtered = when (scope) {
                "today" -> {
                    val today: LocalDate = now().toLocalDate()
                    pending.filter {
                        it.triggerAt > 0 &&
                            java.time.Instant.ofEpochMilli(it.triggerAt)
                                .atZone(java.time.ZoneId.systemDefault())
                                .toLocalDate() == today
                    }
                }
                "timed" -> pending.filter { it.triggerAt > 0 }
                else -> pending
            }

            if (filtered.isEmpty()) {
                return when (scope) {
                    "today" -> BusReply.ok("今天没有安排。")
                    "timed" -> BusReply.ok("现在没有定好时间的闹钟。")
                    else -> BusReply.ok("现在没有任何待办或提醒。")
                }
            }

            val head = when (scope) {
                "today" -> "今天有 ${filtered.size} 项：\n"
                else -> "共 ${filtered.size} 项：\n"
            }
            val body = filtered.take(12).joinToString("\n") { item ->
                if (item.triggerAt > 0) {
                    "· ${item.content} — ${TimeWords.describe(
                        java.time.Instant.ofEpochMilli(item.triggerAt)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(), now()
                    )}"
                } else {
                    "· ${item.content} — 没定时间"
                }
            }
            val more = if (filtered.size > 12) "\n（还有 ${filtered.size - 12} 项没列出来）" else ""
            return BusReply.ok(head + body + more, "count" to filtered.size.toString())
        }
    }

    // ------------------------------------------------------------------ 取消

    private fun cancel(): Supply = object : Supply {
        override val name = "schedule/cancel"
        override val summary =
            "取消待办或闹钟。参数 keyword 是内容里的关键词，或 id（数字）。" +
                "匹配到多条时不要自己挑，把候选说给用户听。"

        override fun invoke(args: BusArgs): BusReply {
            val center = Reminders.center(app)
            val pending = center.list().filter { it.status == Reminder.Status.PENDING }

            // 有 id 就直接按 id
            args.int("id")?.let { id ->
                val target = pending.firstOrNull { it.id == id }
                    ?: return BusReply.fail("没找到编号是 $id 的提醒。")
                center.cancel(target.id)
                return BusReply.ok("已取消：${target.content}。")
            }

            val keyword = args.str("keyword")
                ?: return BusReply.fail("没听清要取消哪一条。")

            // 「全部」是最常见的批量说法，单独放行
            if (keyword == "全部" || keyword == "所有") {
                val count = center.cancelAll()
                return BusReply.ok("已经清空，取消了 $count 条。")
            }

            val hits = pending.filter { it.content.contains(keyword, ignoreCase = true) }
            return when {
                hits.isEmpty() -> BusReply.fail("没找到包含「$keyword」的提醒。")
                hits.size == 1 -> {
                    center.cancel(hits.first().id)
                    BusReply.ok("已取消：${hits.first().content}。")
                }
                else -> BusReply.fail(
                    "有多条包含「$keyword」：" +
                        hits.joinToString("；") { it.content } + "。要取消哪一条？"
                )
            }
        }
    }
}
