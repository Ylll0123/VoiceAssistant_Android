package com.example.voiceassistant.tool

import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.core.TextCleaner
import com.example.voiceassistant.core.ToolSource
import com.example.voiceassistant.service.AudioController
import com.example.voiceassistant.service.DeviceController

/**
 * 处理器层：**工具分流器**。
 *
 * 位置在路由的最前面——**本机就能做的事，绝不发出去**（0 请求、0 token）。
 * 它只干两件事：认领（[claims]）和执行（[handle]）。
 *
 * ## 工具顺序（有讲究）
 * | 顺序 | 工具 | 判定依据 |
 * |---|---|---|
 * | ① | [TimerTool] 计时 / 倒计时 | 关键词 + 有没有时长 |
 * | ② | [ReminderTool] 提醒清单 | 关键词（「提醒我」等） |
 * | ③ | [VolumeTool] 音量 / 静音 / 闭嘴 | 关键词（「静音」「大点声」等） |
 * | ④ | [DeviceTool] 电量 / 手电筒 / 开应用 | 关键词 + 前缀 |
 * | ⑤ | [TimeTool] 时间日期 | **引擎命中的规则意图** |
 * | ⑥ | [CalcTool] 计算 | **引擎命中的规则意图** |
 *
 * ### 为什么计时排在提醒前面
 * 「定个 10 分钟闹钟」两边的关键词都沾。计时更具体（有时长），
 * 先认领它，回答就是干净的「好，10 分钟后叫你」；
 * 反过来会变成「10 分钟后提醒你：定个 10 分钟闹钟」——把指令本身念了一遍。
 *
 * ### 为什么时间 / 计算不看关键词
 * 中文里「什么时候」「几号」到处都是，硬套会把「黑洞是什么时候形成的」
 * 这类成因题抢走，反而答非所问。引擎的规则表已经判准了，直接采信最稳。
 *
 * ## 以后加新工具
 * 在 tool 包新增一个 object（提供 `matches` / `handle`），
 * 再在 [claims] 和 [handle] 里各注册一行，主流程不用动。
 */
class ToolHandler(
    private val audio: AudioController? = null,
    private val device: DeviceController? = null,
    /** 当前时刻，注入是为了让提醒 / 计时的单测不受运行时间影响 */
    private val clock: () -> Long = System::currentTimeMillis
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
        return TimerTool.matches(raw) ||
            ReminderTool.matches(raw) ||
            VolumeTool.matches(raw) ||
            DeviceTool.matches(raw, device) ||
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

        val now = clock()

        TimerTool.handle(raw, now)?.let { return it }
        ReminderTool.handle(raw, now)?.let { return it }
        VolumeTool.handle(raw, audio)?.let { return it }
        DeviceTool.handle(raw, device)?.let { return it }

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
