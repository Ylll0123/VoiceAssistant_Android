package com.example.voiceassistant.core

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.assistant.OnlineQuery
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.network.LlmMessage
import com.example.voiceassistant.network.LlmResult
import com.example.voiceassistant.tool.ToolReply

/**
 * 核心层与各处理器之间的**契约**。
 *
 * 调度核心只认识这四个接口，不认识任何具体实现——
 * 于是换大模型、换搜索源、加新工具都不用碰 [AssistantRouter]，
 * 单测里也能用假实现断言"到底按什么顺序问了谁"。
 */

/** 本地问答：命中就返回，永不触网 */
interface LocalSource {
    fun answer(candidates: List<String>): AssistantResponse
}

/** 本机工具：提醒 / 音量 / 时间 / 计算，0 请求 */
interface ToolSource {
    /**
     * 这句话是不是在让本机做事（纯判定，无副作用）。
     *
     * @param local 本地引擎这一轮的结果；时间 / 计算类工具采信它的意图
     */
    fun claims(raw: String, local: AssistantResponse): Boolean

    /** 执行工具指令；没命中返回 null，交给后面的路线 */
    fun handle(raw: String, local: AssistantResponse): ToolReply?
}

/** 联网查询：免费，但不保证有结果 */
interface WebSource {
    fun execute(plan: OnlineQuery.Plan): com.example.voiceassistant.handler.web.WebHandler.Result?
}

/** 云端大模型：唯一花钱的一环 */
interface CloudSource {
    fun call(
        config: CloudLlmHandler.CloudConfig,
        history: List<LlmMessage>,
        input: String,
        extraSystem: String
    ): LlmResult
}
