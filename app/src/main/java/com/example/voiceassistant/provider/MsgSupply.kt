package com.example.voiceassistant.provider

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.core.PendingSend
import com.example.voiceassistant.im.ImAutomator
import com.example.voiceassistant.im.ImPlatform

/**
 * 消息域：`msg/…`
 *
 * ## 只送到输入框，不替人点发送
 * 微信/QQ 的用户协议禁止自动化操作，它们是按行为特征判定的——从进页面到点发送
 * 只隔几百毫秒，正常人做不到。自动提交换取的是**封号**，不是便利。
 *
 * 所以这里的边界画在「输入框」：程序负责打开应用、找人、进聊天、把话填好，
 * **最后一个动作留给用户**。和复制粘贴没区别，风控上等同于人自己在用。
 *
 * 这三条约束写在总线层而不是界面层，因为**大模型也会调这个服务**：
 * 只在界面上拦一道，模型一句 `msg_send_text` 就绕过去了。
 */
class MsgSupply(
    private val automator: ImAutomator,
    /** 读当前会话最近几条消息：用户问「我上一条跟他说的什么」时用 */
    private val recentMessages: () -> List<String> = { emptyList() }
) : PendingSend {

    val all: List<Supply> = listOf(sendText(), canSend(), queryHistory())

    // ------------------------------------------------------------------ 发文本

    private fun sendText(): Supply = object : Supply {
        override val name = "msg/send_text"
        override val summary =
            "给联系人发消息。参数 platform（wechat / qq，也认中文别名）、to（联系人名）、content（要发的内容）。" +
                "会打开对应应用、进入和这个人的聊天页、把内容填进输入框，然后停下来——" +
                "发送键留给用户自己点，不要声称已经发出去了。"

        override fun invoke(args: BusArgs): BusReply {
            val platform = ImPlatform.of(args.str("platform"))
                ?: return BusReply.fail("只能发微信或 QQ，别的还不支持。")
            val to = args.str("to") ?: return BusReply.fail("要发给谁？")
            val content = args.str("content") ?: return BusReply.fail("要发什么？")

            return when (val outcome = automator.deliver(platform, to, content)) {
                is ImAutomator.Outcome.Done -> BusReply.ok(outcome.message)
                is ImAutomator.Outcome.Failed -> BusReply.fail(outcome.message)
            }
        }
    }

    private fun canSend(): Supply = object : Supply {
        override val name = "msg/query_can_send"
        override val summary =
            "检查现在能不能自动把消息填进聊天框（应用是否安装、无障碍是否连上、系统分享通路是否可用）。"

        override fun invoke(args: BusArgs): BusReply {
            val platform = ImPlatform.of(args.str("platform")) ?: ImPlatform.WECHAT
            val diagnosis = automator.diagnose(platform)
            return if (diagnosis.contains("没装")) BusReply.fail(diagnosis)
            else BusReply.ok(diagnosis)
        }
    }

    // ------------------------------------------------------------------ 聊天记录

    private fun queryHistory(): Supply = object : Supply {
        override val name = "msg/query_history"
        override val summary =
            "查最近聊过什么。参数 count（默认 5）。注意：只能查**本应用里**和助手的对话，" +
                "读不到微信/QQ 的聊天记录（系统不允许）。"

        override fun invoke(args: BusArgs): BusReply {
            val count = args.int("count")?.coerceIn(1, 20) ?: 5
            val messages = recentMessages().takeLast(count)
            if (messages.isEmpty()) return BusReply.ok("咱们还没聊过什么。")
            val body = messages.joinToString("\n") { "· $it" }
            return BusReply.ok(
                "最近聊的这几句：\n$body\n（微信/QQ 里的记录我读不到，系统不让。）"
            )
        }
    }

    /** 无障碍设置入口：界面上「去开权限」按钮用得到 */
    fun openAccessibilitySettings(): Boolean = automator.openAccessibilitySettings()

    /**
     * 方案 B 之后**没有「等着确认的发送」**了：内容一填进输入框，剩下的就交给用户。
     *
     * 接口保留是因为 [com.example.voiceassistant.core.Dispatcher] 依赖这个抽象
     * （不依赖具体实现，才能在没有 Android 的环境里单测），不是为了留后门。
     */
    override fun hasPendingSend(): Boolean = false

    override fun discardPending(): Boolean = false
}
