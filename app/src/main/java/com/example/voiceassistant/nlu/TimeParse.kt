package com.example.voiceassistant.nlu

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 时间的统一入口：**机器格式和中文口语都从这儿进来**。
 *
 * 为什么需要两种：
 * - 大模型（function calling）被约定传 `yyyy-MM-dd HH:mm`，它不会说「今晚八点」；
 * - 本地 NLU 拿到的是「今晚八点」，得当场换算，不能为了一个时间点去请求模型。
 *
 * 两种都走这里，供给方就不必关心「这个时间是模型给的还是用户嘴说的」。
 */
object TimeParse {

    /** 解析成时间点；说不清返回 null（调用方负责追问或交回模型） */
    fun parse(raw: String?, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 1) 机器格式：模型最常给的三种
        runCatching { LocalDateTime.parse(text, DATE_TIME) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(text, DATE_TIME_SECONDS) }.getOrNull()?.let { return it }
        runCatching { LocalDateTime.parse(text, ISO_T) }.getOrNull()?.let { return it }

        runCatching { LocalDate.parse(text, DATE_ONLY) }.getOrNull()?.let {
            return it.atTime(9, 0)
        }

        // 「20:00」这种只有时分：今天已过就顺延明天
        runCatching { LocalTime.parse(text, TIME_ONLY) }.getOrNull()?.let { time ->
            val today = now.toLocalDate().atTime(time)
            return if (today.isBefore(now)) today.plusDays(1) else today
        }

        // 2) 中文口语
        return TimeWords.parse(text, now)
    }

    /** 解析成 epoch 毫秒 */
    fun epoch(raw: String?, now: LocalDateTime = LocalDateTime.now()): Long? =
        parse(raw, now)?.let { it.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }

    /** 反过来：epoch → `yyyy-MM-dd HH:mm`（给模型和界面看） */
    fun format(at: Long): String =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(at), java.time.ZoneId.systemDefault())
            .format(DATE_TIME)

    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val DATE_TIME_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val ISO_T: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
}
