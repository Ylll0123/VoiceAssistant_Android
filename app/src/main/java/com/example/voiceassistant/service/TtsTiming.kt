package com.example.voiceassistant.service

/**
 * 播报时长的粗略估算（纯函数，可直接单测）。
 *
 * 只用来当**兜底**：正常播报靠 `UtteranceProgressListener.onDone` 收尾，
 * 这里算的是一个「绝不可能念不完」的上限，所以估得宽松些也不影响体验。
 */
object TtsTiming {

    /** 中文播报语速约每秒 4~5 字，按 240ms/字 估，再留 4 秒启动余量 */
    private const val PER_CHAR_MS = 240L

    /** 引擎启动、排队、缓冲的固定余量 */
    private const val BASE_MS = 4_000L

    /** 再长的回复也不等超过 2 分钟 */
    private const val MAX_MS = 120_000L

    /**
     * 这句话最晚应该在多久内念完（毫秒）。
     *
     * @param length 去掉表情后的字符数；非正数按 1 个字处理（只留启动余量）
     */
    fun watchdogMs(length: Int): Long {
        val chars = if (length > 0) length else 0
        return (BASE_MS + chars * PER_CHAR_MS).coerceAtMost(MAX_MS)
    }
}
