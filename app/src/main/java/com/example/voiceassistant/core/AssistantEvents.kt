package com.example.voiceassistant.core

import com.example.voiceassistant.bus.Event
import com.example.voiceassistant.fuzzy.IntentResult

/**
 * 对话链路上的四个事件，全部发到 [com.example.voiceassistant.bus.AssistantBus]。
 *
 * 一条语音的完整旅程（每一站都发一条事件，谁想看谁订阅）：
 * ```
 * RawText  ──▶ IntentRecognized ──▶ TurnPlanned ──▶ ReplyReady
 * 语音链路       模糊容错引擎          调度核心         界面 / TTS
 * ```
 *
 * 事件里都带 [turn]（轮次号）：界面用它丢弃「用户已经说了下一句之后
 * 才回来的旧结果」。没有这个字段，慢网络下会出现「新问题的答案被旧答案顶掉」。
 */

/** 语音链路（或输入框）产出了一段原始文本 */
data class RawText(
    val turn: Int,
    val candidates: List<String>,
    /** 来源：asr / text / skill（技能页示例） */
    val source: String = SOURCE_ASR
) : Event {
    companion object {
        const val SOURCE_ASR = "asr"
        const val SOURCE_TEXT = "text"
        const val SOURCE_SKILL = "skill"
    }
}

/** 模糊容错引擎给出的标准意图（置信度、槽位都在里面） */
data class IntentRecognized(
    val turn: Int,
    val raw: String,
    val result: IntentResult
) : Event

/** 本地阶段算完了：这一轮走哪条路线、本地答案是什么 */
data class TurnPlanned(
    val turn: Int,
    val plan: AssistantRouter.TurnPlan
) : Event

/** 最终的回复（只发一次：界面只上屏一次，不会出现「先答一遍再改一遍」） */
data class ReplyReady(
    val turn: Int,
    val reply: AssistantRouter.Reply,
    /** 这一轮的主输入，界面用它判断「是不是多候选里的另一条」 */
    val primary: String
) : Event
