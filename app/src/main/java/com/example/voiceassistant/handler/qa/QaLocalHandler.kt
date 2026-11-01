package com.example.voiceassistant.handler.qa

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.core.LocalSource

/**
 * 处理器层：**本地问答**。
 *
 * 输入一句话（或语音识别给出的多条候选），输出一个本地答案；
 * 它**永远会给出点什么**（兜底 / 思维模式 / 小模型生成），
 * 由上一层（[com.example.voiceassistant.core.AssistantRouter]）判断这个答案够不够好、
 * 要不要再去联网或问云端。
 *
 * ## 内部顺序（全部离线，0 token）
 * ```
 * 规则（时间/计算/换算/翻译/天气…）
 *   → 闲聊语料（几十个日常话题，多说法轮换）
 *   → 记忆库（用户教过的 + 预置的，支持相似说法自学习）
 *   → 常识知识库（200+ 条有标准答案的日常问题）
 *   → 推理框架（不会答但给拆解步骤）
 *   → 思维模式（按提问类型给思考路径）
 *   → 小模型（字符级 n-gram，随对话持续学习）
 * ```
 * 详细流程见 [AssistantEngine.respond]。
 *
 * ## 为什么不把规则重写成独立工具
 * 见 [com.example.voiceassistant.tool.ToolHandler]：时间 / 计算已经在规则里了，
 * 这里复用同一份实现，避免出现两套规则对不齐。
 */
class QaLocalHandler(
    private val engine: AssistantEngine,
    private val qaLib: QaLib = QaLib.empty()
) : LocalSource {

    /** 单条输入 */
    fun answer(raw: String): AssistantResponse = answer(listOf(raw))

    /**
     * 多候选择优：整组交给引擎，它挑一条能理解的。
     *
     * 先查可编辑问答表（[QaLib]）——那是用户亲手维护的内容，优先级最高。
     */
    override fun answer(candidates: List<String>): AssistantResponse {
        val primary = candidates.firstOrNull()?.trim().orEmpty()
        if (primary.isNotEmpty()) {
            qaLib.match(primary)?.let {
                return AssistantResponse(it, INTENT_QA_LIB, primary)
            }
        }
        return engine.respond(candidates)
    }

    companion object {
        /** 命中 assets 问答表时的意图标记 */
        const val INTENT_QA_LIB = "qa_lib"
    }
}
