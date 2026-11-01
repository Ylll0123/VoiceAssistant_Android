package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.AssistantResponse

/**
 * 工具层：时间 / 日期（「几点了」「今天几号」「下周三是几号」）。
 *
 * ## 判定方式：**看引擎命中的意图，不猜关键词**
 * 一开始是按关键词判的，结果「黑洞是什么时候形成的」被当成问时间——
 * 它确实含「什么时候」，但问的是成因不是钟点，而且本地常识库答得上来。
 * 关键词这条路走不通（中文里「什么时候 / 几号」到处都是），
 * 所以改成：只有当引擎的规则表**真的命中了时间类规则**时才认领，
 * 意图是权威信号，关键词只作为兜底。
 */
object TimeTool {

    const val NAME = "time"

    /** 引擎命中这些意图 = 这句话确实是时间 / 日期问题 */
    private val INTENTS = setOf("time", "date", "weekday_calc", "timezone")

    /** 兜底关键词：引擎没给意图时（例如单测里直接调工具）再靠它判断 */
    private val CUES = listOf(
        "几点", "几号", "什么时候", "日期", "星期", "周几", "礼拜", "时区"
    )

    /**
     * 是不是在问时间 / 日期。
     *
     * @param local 本地引擎这一轮的结果：[AssistantResponse.intent] 是权威信号
     */
    fun matches(raw: String, local: AssistantResponse? = null): Boolean {
        if (raw.isBlank()) return false
        if (local != null) return local.intent in INTENTS
        return CUES.any { raw.contains(it) }
    }
}
