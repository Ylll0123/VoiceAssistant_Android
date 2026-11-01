package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.core.TextCleaner
import com.example.voiceassistant.core.ToolSource
import com.example.voiceassistant.service.AudioController

/**
 * 处理器层：**工具分流器**。
 *
 * 位置在路由的最前面——**本机就能做的事，绝不发出去**（0 请求、0 token）。
 * 它只干两件事：认领（[claims]）和执行（[handle]）。
 *
 * ## 两类工具，两种判定
 * | 工具 | 判定依据 | 执行 |
 * |---|---|---|
 * | [ReminderTool] 提醒清单 | 关键词（「提醒我」等） | 内存清单，真正定时属于 P3 |
 * | [VolumeTool] 音量 / 静音 / 闭嘴 | 关键词（「静音」「大点声」等） | 改系统音量 |
 * | [TimeTool] 时间日期 | **引擎命中的规则意图** | 采用引擎那条答案 |
 * | [CalcTool] 计算 | **引擎命中的规则意图** | 采用引擎那条答案（也可直接算） |
 *
 * 时间 / 计算刻意**不看关键词**：中文里「什么时候」「几号」到处都是，
 * 硬套会把「黑洞是什么时候形成的」这类成因题抢走，反而答非所问。
 * 引擎的规则表已经把这类说法判准了，直接采信它的意图最稳。
 *
 * ## 以后加新工具
 * 在 tool 包新增一个 object（提供 `matches` / `handle`），
 * 再在 [claims] 和 [handle] 里各注册一行，主流程不用动。
 */
class ToolHandler(
    private val audio: AudioController? = null
) : ToolSource {

    /**
     * 这句话是不是在让本机做事（纯判定，无副作用）。
     *
     * 教学指令优先：用户在教说法时，哪怕句子里有「提醒我」也不归工具管。
     *
     * @param local 本地引擎这一轮的结果，时间 / 计算类工具据此判定
     */
    override fun claims(raw: String, local: AssistantResponse): Boolean {
        if (TextCleaner.isBlank(raw)) return false
        if (TeachGuard.isTeaching(raw)) return false
        return ReminderTool.matches(raw) ||
            VolumeTool.matches(raw) ||
            TimeTool.matches(raw, local) ||
            CalcTool.matches(raw, local)
    }

    /**
     * 执行工具指令。
     *
     * @return 命中返回工具结果；没命中返回 null，交给后面的路线
     */
    override fun handle(raw: String, local: AssistantResponse): ToolReply? {
        if (!claims(raw, local)) return null

        ReminderTool.handle(raw)?.let { return it }
        VolumeTool.handle(raw, audio)?.let { return it }
        if (TimeTool.matches(raw, local)) {
            return ToolReply(local.text, ToolIntent.TIME, TimeTool.NAME)
        }
        if (CalcTool.matches(raw, local)) {
            return ToolReply(local.text, ToolIntent.CALC, CalcTool.NAME)
        }
        return null
    }
}

/**
 * 教学保护：正在教 / 明确的「记住：A = B」时，任何工具都不许抢这句话。
 *
 * 「记住：提醒我喝水」是教学，不是让助手真的去建提醒。
 */
object TeachGuard {
    fun isTeaching(raw: String): Boolean {
        val text = raw.trim()
        return text.startsWith("记住") ||
            (text.startsWith("记一下") && text.contains("="))
    }
}
