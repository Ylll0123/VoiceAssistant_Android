package com.example.voiceassistant.provider

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.core.AssistantMode
import com.example.voiceassistant.core.LlmSwitch

/**
 * 状态与上下文域：`context/…`、`llm/toggle`
 *
 * 这几个服务不产生内容，只改「下一句话怎么被处理」：
 * 清上下文、切模式、开关云端 AI。
 *
 * 它们的状态放在 [AssistantMode] / [LlmSwitch] 两个单例里，而不是塞在这个类里——
 * `AiChat` 也要读它们，放单例才不用为了一个问题绕一圈拿到服务对象。
 *
 * ## 回调为什么从外面传
 * 「清空上下文」得动界面上的消息列表，「切模式」要落盘到设置里。
 * 这些是界面/存储的事，不该让一个服务去认识 Activity。
 * 所以这里只声明「需要做什么」，具体怎么做由宿主注入。
 */
class ContextSupply(
    /** 清空当前会话的消息，返回清掉的条数 */
    private val clearTurns: () -> Int = { 0 },
    /** 切换模式后要做的持久化/界面刷新；返回生效的模式名 */
    private val onModeChanged: (String) -> Unit = {},
    /** 开关变化后要做的持久化/界面刷新 */
    private val onLlmToggled: (Boolean) -> Unit = {}
) {

    val all: List<Supply> = listOf(clear(), switchPrompt(), toggle())

    private fun clear(): Supply = object : Supply {
        override val name = "context/clear"
        override val summary = "清空当前这段对话的上下文（消息都清掉，但会话记录还在）。"

        override fun invoke(args: BusArgs): BusReply {
            val count = runCatching { clearTurns() }.getOrDefault(0)
            return BusReply.ok("上下文清好了，之前那 $count 条不会再影响后面的回答。")
        }
    }

    private fun switchPrompt(): Supply = object : Supply {
        override val name = "context/switch_prompt"
        override val summary =
            "切换对话模式。参数 mode：qa（问答）/ msg（消息，只输出能直接发的话）/ study（学习）。"

        override fun invoke(args: BusArgs): BusReply {
            val raw = args.str("mode") ?: return BusReply.fail("要切到哪个模式？问答、消息，还是学习？")
            val before = AssistantMode.current
            val after = AssistantMode.switchTo(raw)
            if (after == before) {
                return BusReply.fail("没有「$raw」这个模式，现在还是${AssistantMode.label()}。")
            }
            runCatching { onModeChanged(after) }
            return BusReply.ok("切到${AssistantMode.label(after)}了。", "mode" to after)
        }
    }

    private fun toggle(): Supply = object : Supply {
        override val name = "llm/toggle"
        override val summary =
            "开关云端 AI。参数 on：true 打开、false 关闭。关掉之后只能做本机指令（闹钟、手电、音量等）。"

        override fun invoke(args: BusArgs): BusReply {
            // 没给参数就翻转，这样「把 AI 关掉 / 打开 AI」两种说法都能用
            val target = if (args.str("on") != null) args.bool("on") else !LlmSwitch.enabled
            LlmSwitch.set(target, reason = "用户手动关闭")
            runCatching { onLlmToggled(target) }
            return if (target) {
                BusReply.ok("云端 AI 打开了，可以正常问答。", "enabled" to "true")
            } else {
                BusReply.ok(
                    "云端 AI 关了。闹钟、手电、音量、打开应用这些本机操作还能用，" +
                        "问答和翻译就不行了。",
                    "enabled" to "false"
                )
            }
        }
    }
}
