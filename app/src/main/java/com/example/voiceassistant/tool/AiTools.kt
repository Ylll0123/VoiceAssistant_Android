package com.example.voiceassistant.tool

import android.content.Context
import com.example.voiceassistant.reminder.Reminders
import com.example.voiceassistant.service.AudioController
import com.example.voiceassistant.service.DeviceController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 交给大模型调用的**本机工具**（function calling）。
 *
 * 为什么要有这一层：v4.0 之后所有回答都由模型生成，但「建个提醒」「开手电筒」
 * 这类事模型只能描述、做不到。于是把这几件事声明成工具，模型自己决定何时调用，
 * 我们执行完把结果（一句人话）还给它，它再接着组织回答。
 *
 * ## 为什么只留这几个
 * 计算、换算、天气、闲聊全部由模型直接回答，不需要工具。
 * 只有**必须落到这台手机上**、模型不可能知道结果的动作才在这里：
 * 提醒 / 计时（要进数据库、要排闹钟）、手电筒、音量、电量。
 *
 * ## 时间怎么传
 * 模型不知道怎么表示时间戳，所以约定它传 `yyyy-MM-dd HH:mm` 的**本地时间字符串**，
 * 当前时间在 system 里告诉它。解析失败就退化成「没说时间」，不瞎猜。
 */
class AiTools(
    context: Context,
    private val device: DeviceController,
    private val audio: AudioController,
    /** 语音播报控制：模型说「别念了」时用 */
    private val stopSpeaking: () -> Unit = {},
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) {

    private val app = context.applicationContext

    /** 一次工具调用（从模型响应里解析出来的） */
    data class Call(val id: String, val name: String, val arguments: String)

    // ------------------------------------------------------------------ 声明

    /**
     * 工具清单（OpenAI function calling 的 `tools` 字段）。
     *
     * 描述写得细一点很划算：模型靠它决定该不该调、参数怎么填。
     */
    val schema: String get() = SCHEMA


    // ------------------------------------------------------------------ 执行

    /**
     * 执行一次调用，返回**给模型看的结果**（一句中文）。
     *
     * 任何异常都转成「失败了，原因是…」，让模型自己决定怎么向用户解释；
     * 这里不抛异常，否则整轮回答会断。
     */
    fun execute(call: Call): String = runCatching {
        when (call.name) {
            "create_reminder" -> createReminder(call.arguments)
            "list_reminders" -> listReminders()
            "cancel_reminder" -> cancelReminder(call.arguments)
            "set_timer" -> setTimer(call.arguments)
            "device" -> device(call.arguments)
            "stop_speaking" -> {
                stopSpeaking()
                "已停止朗读。"
            }
            else -> "没有这个工具：${call.name}"
        }
    }.getOrElse { "工具执行失败：${it.message.orEmpty()}" }

    // ------------------------------------------------------------------ 各工具

    private fun createReminder(args: String): String {
        val content = JsonArgs.str(args, "content")?.trim().orEmpty().ifEmpty { "提醒" }
        val at = parseTime(JsonArgs.str(args, "time")) ?: return "时间没看懂，需要 yyyy-MM-dd HH:mm 格式，请重新确认几点。"
        val repeat = when (JsonArgs.str(args, "repeat")) {
            "daily" -> com.example.voiceassistant.reminder.Reminder.Repeat.DAILY
            "weekly" -> com.example.voiceassistant.reminder.Reminder.Repeat.WEEKLY
            else -> com.example.voiceassistant.reminder.Reminder.Repeat.NONE
        }
        val saved = Reminders.center(app).create(content, at, repeat)
        return "已建好：${saved.content}，${format(at)}${repeatSuffix(repeat)}。"
    }

    private fun listReminders(): String {
        val all = Reminders.center(app).list().filter { it.status == com.example.voiceassistant.reminder.Reminder.Status.PENDING }
        if (all.isEmpty()) return "现在没有任何提醒。"
        return all.take(10).joinToString(separator = "\n", prefix = "共 ${all.size} 条：\n") { r ->
            "· ${r.content}（${if (r.triggerAt > 0) format(r.triggerAt) else "没说时间"}）"
        }
    }

    private fun cancelReminder(args: String): String {
        val keyword = JsonArgs.str(args, "keyword")?.trim().orEmpty()
        if (keyword.isEmpty()) return "没听清要取消哪一条。"
        val center = Reminders.center(app)
        val hits = center.list().filter { it.content.contains(keyword) }
        return when {
            hits.isEmpty() -> "没找到包含「$keyword」的提醒。"
            hits.size == 1 -> {
                center.cancel(hits.first().id)
                "已取消：${hits.first().content}。"
            }
            // 多条命中不替用户做主，把候选还给它
            else -> "有多条包含「$keyword」的提醒：" +
                hits.joinToString("；") { it.content } + "。请让用户说清是哪一条。"
        }
    }

    private fun setTimer(args: String): String {
        val minutes = JsonArgs.num(args, "minutes") ?: return "没听清要计时多久。"
        if (minutes <= 0) return "计时时长要大于零。"
        val label = JsonArgs.str(args, "label")?.trim().orEmpty().ifEmpty { "计时" }
        val at = System.currentTimeMillis() + (minutes * 60_000).toLong()
        Reminders.center(app).create(label, at)
        return "好，${trimNumber(minutes)} 分钟后提醒你：$label。"
    }

    private fun device(args: String): String = when (JsonArgs.str(args, "action")) {
        "torch_on" -> if (device.setTorch(true)) "手电筒已打开。" else "这台设备没有可用的闪光灯。"
        "torch_off" -> if (device.setTorch(false)) "手电筒已关闭。" else "这台设备没有可用的闪光灯。"
        "volume_up" -> {
            audio.adjustVolume(true)
            "音量调大了。"
        }
        "volume_down" -> {
            audio.adjustVolume(false)
            "音量调小了。"
        }
        "mute" -> {
            audio.setMuted(true)
            "已静音。"
        }
        "battery" -> device.battery() ?: "电量读不出来。"
        else -> "不认识的设备动作。"
    }

    // ------------------------------------------------------------------ 小工具

    /** 解析模型给的时间（用系统当前时间当基准） */
    private fun parseTime(raw: String?): Long? = parseTime(raw, now())

    private fun format(at: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault())
            .format(DATE_TIME)

    private fun repeatSuffix(repeat: com.example.voiceassistant.reminder.Reminder.Repeat): String =
        when (repeat) {
            com.example.voiceassistant.reminder.Reminder.Repeat.DAILY -> "，每天"
            com.example.voiceassistant.reminder.Reminder.Repeat.WEEKLY -> "，每周"
            else -> ""
        }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    companion object {

        /** 工具清单原文：单独放常量，单测可以直接校验它是合法 JSON */
        val SCHEMA: String = """
[
  {"type":"function","function":{
    "name":"create_reminder",
    "description":"在手机上新建一条提醒。用户说了要提醒什么、并且给了时间时调用。如果用户没说时间，不要调用，先问清几点。",
    "parameters":{"type":"object","properties":{
      "content":{"type":"string","description":"提醒的内容，去掉时间词，例如「交作业」"},
      "time":{"type":"string","description":"触发时间，格式必须是 yyyy-MM-dd HH:mm（24 小时制，本地时间）"},
      "repeat":{"type":"string","enum":["none","daily","weekly"],"description":"重复规则，默认 none"}
    },"required":["content","time"]}}},
  {"type":"function","function":{
    "name":"list_reminders",
    "description":"列出还没到点的提醒。用户问「我有哪些提醒」「今天要干什么」时调用。",
    "parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{
    "name":"cancel_reminder",
    "description":"取消提醒。按内容里的关键词匹配；匹配到多条时不要自己选，把候选告诉用户。",
    "parameters":{"type":"object","properties":{
      "keyword":{"type":"string","description":"要取消的提醒内容里的关键词，例如「交作业」"}
    },"required":["keyword"]}}},
  {"type":"function","function":{
    "name":"set_timer",
    "description":"倒计时。用户说「定个十分钟的闹钟」「X 分钟后叫我」时调用。",
    "parameters":{"type":"object","properties":{
      "minutes":{"type":"number","description":"多少分钟"},
      "label":{"type":"string","description":"这次计时叫什么，默认「计时」"}
    },"required":["minutes"]}}},
  {"type":"function","function":{
    "name":"device",
    "description":"操作手机硬件。用户让你开手电筒、调音量、静音、看电量时调用。",
    "parameters":{"type":"object","properties":{
      "action":{"type":"string","enum":["torch_on","torch_off","volume_up","volume_down","mute","battery"],
        "description":"要执行的动作"}
    },"required":["action"]}}},
  {"type":"function","function":{
    "name":"stop_speaking",
    "description":"立刻停止朗读。用户说「别念了」「闭嘴」「安静」时调用。",
    "parameters":{"type":"object","properties":{}}}}
]
""".trimIndent()

        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val DATE_TIME_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        /**
         * 解析模型给的时间；支持带秒、只有日期、只有时分三种退化写法。
         *
         * 纯函数，基准时间由 [reference] 传进来——单测里就不用跟真实时钟赛跑。
         */
        fun parseTime(raw: String?, reference: LocalDateTime): Long? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val dateTime = runCatching { LocalDateTime.parse(text, DATE_TIME) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text, DATE_TIME_SECONDS) }.getOrNull()
            if (dateTime != null) return epoch(dateTime)

            val date = runCatching { LocalDate.parse(text, DATE_ONLY) }.getOrNull()
            if (date != null) return epoch(date.atTime(9, 0))

            // 「15:30」这种：今天已过就顺延到明天，和用户的直觉一致
            val time = runCatching { LocalTime.parse(text, TIME_ONLY) }.getOrNull() ?: return null
            val today = reference.toLocalDate().atTime(time)
            val target = if (today.isBefore(reference)) today.plusDays(1) else today
            return epoch(target)
        }

        private fun epoch(at: LocalDateTime): Long =
            at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/**
 * 极简 JSON 参数读取。
 *
 * 模型返回的 `arguments` 是一个 JSON 字符串，字段很少，用 [JsonLite] 直接取，
 * 不引序列化库。取不到就返回 null，由调用方决定怎么兜。
 */
object JsonArgs {

    fun str(raw: String?, key: String): String? {
        val root = parse(raw) ?: return null
        return com.example.voiceassistant.data.JsonLite.str(com.example.voiceassistant.data.JsonLite.get(root, key))
    }

    fun num(raw: String?, key: String): Double? {
        val root = parse(raw) ?: return null
        return com.example.voiceassistant.data.JsonLite.num(com.example.voiceassistant.data.JsonLite.get(root, key))
    }

    private fun parse(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return runCatching { com.example.voiceassistant.data.JsonLite.parseOrNull(raw) }.getOrNull()
    }
}
