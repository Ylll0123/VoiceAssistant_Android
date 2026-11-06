package com.example.voiceassistant.handler.qa

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.core.LocalSource

/**
 * 处理器层：**本地问答**。
 *
 * 输入一句话（或语音识别给出的多条候选），输出一个本地答案。
 * 它**永远会给出点什么**（命中的规则答复，或一句「本机不会」的兜底），
 * 由上一层（[com.example.voiceassistant.core.AssistantRouter]）判断这个答案够不够好、
 * 要不要再去联网或问云端。
 *
 * ## 内部顺序（全部离线，0 token）
 * ```
 * 规则（时间 / 日期 / 计算 / 换算 / 天气 / 娱乐 / 寒暄）
 *   → 最像的示例说法反问确认
 *   → 兜底（交给上层去联网或问大模型）
 * ```
 * 详细流程见 [AssistantEngine.respond]。
 *
 * ## 为什么不把规则重写成独立工具
 * 见 [com.example.voiceassistant.tool.ToolHandler]：时间 / 计算已经在规则里了，
 * 这里复用同一份实现，避免出现两套规则对不齐。
 */
class QaLocalHandler(
    private val engine: AssistantEngine
) : LocalSource {

    /** 单条输入 */
    fun answer(raw: String): AssistantResponse = answer(listOf(raw))

    /** 多候选择优：整组交给引擎，它挑一条能理解的 */
    override fun answer(candidates: List<String>): AssistantResponse = engine.respond(candidates)
}
