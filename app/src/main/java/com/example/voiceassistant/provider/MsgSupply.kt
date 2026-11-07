package com.example.voiceassistant.provider

import android.content.Context
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.core.PendingSend
import com.example.voiceassistant.im.ImAutomator
import com.example.voiceassistant.im.ImPlatform

/**
 * 消息域：`msg/…`
 *
 * 这是全套服务里唯一**会动别的应用**的一域，所以规矩比别处严：
 * 1. `msg/send_text` **只准备、不发送**，等 `msg/confirm_send` 才真点；
 * 2. 找不到完全同名的联系人直接失败，不猜；
 * 3. 每次准备生成 traceId，重复确认不会重复发。
 *
 * 这些约束写在总线层而不是界面层——因为**大模型也会调这个服务**：
 * 如果只在界面上做确认，模型一句 `msg/send_text` 就直接发出去了。
 * 把「必须二次确认」钉在服务里，两条入口都绕不过去。
 */
class MsgSupply(
    context: Context,
    private val automator: ImAutomator,
    /** 读当前会话最近几条消息：用户问「我上一条跟他说的什么」时用 */
    private val recentMessages: () -> List<String> = { emptyList() },
    /**
     * 设置里的「发送前免确认」。
     *
     * 默认 false：先复述一遍再问「确认吗？」。用户自己打开这个开关之后，
     * `msg/send_text` 会直接执行到底——那时候「确认」这层是用户在设置里授权的。
     */
    private val autoSend: () -> Boolean = { false }
) : PendingSend {

    private val app: Context = context.applicationContext

    val all: List<Supply> = listOf(sendText(), confirmSend(), canSend(), queryHistory())

    // ------------------------------------------------------------------ 发文本

    private fun sendText(): Supply = object : Supply {
        override val name = "msg/send_text"
        override val summary =
            "给联系人发消息。参数 platform（wechat / qq，也认中文别名）、to（联系人名）、content（要发的内容）。" +
                "默认只准备、不打开应用；用户确认后由 msg/confirm_send 全自动进入聊天页并发送。" +
                "开了「发送前免确认」设置时，这个服务会直接发出去。"

        override fun invoke(args: BusArgs): BusReply {
            val platform = ImPlatform.of(args.str("platform"))
                ?: return BusReply.fail("只能发微信或 QQ，别的还不支持。")
            val to = args.str("to") ?: return BusReply.fail("要发给谁？")
            val content = args.str("content") ?: return BusReply.fail("要发什么？")

            return when (val outcome = automator.prepare(platform, to, content)) {
                is ImAutomator.Outcome.NeedConfirm -> {
                    // 免确认模式：不问了，直接执行（会阻塞到发完）
                    if (autoSend()) {
                        when (val done = automator.confirm(outcome.traceId)) {
                            is ImAutomator.Outcome.Done -> BusReply.ok(done.message)
                            is ImAutomator.Outcome.Failed -> BusReply.fail(done.message)
                            is ImAutomator.Outcome.NeedConfirm ->
                                BusReply.confirm(outcome.preview, outcome.traceId, "to" to to)
                        }
                    } else {
                        BusReply.confirm(outcome.preview, outcome.traceId, "to" to to)
                    }
                }

                is ImAutomator.Outcome.Done -> BusReply.ok(outcome.message)

                is ImAutomator.Outcome.Failed -> BusReply.fail(outcome.message)
            }
        }
    }

    private fun confirmSend(): Supply = object : Supply {
        override val name = "msg/confirm_send"
        override val summary = "确认并真正发送上一条待发的消息：自动进入聊天页、填字、点发送并校验结果。参数 trace_id 由 msg/send_text 返回。"

        override fun invoke(args: BusArgs): BusReply {
            val traceId = args.str("trace_id") ?: automator.latestTraceId()
                ?: return BusReply.fail("现在没有等你确认的消息。")
            return when (val outcome = automator.confirm(traceId)) {
                is ImAutomator.Outcome.Done -> BusReply.ok(outcome.message)
                is ImAutomator.Outcome.Failed -> BusReply.fail(outcome.message)
                is ImAutomator.Outcome.NeedConfirm -> BusReply.fail("还没准备好，稍等一下。")
            }
        }
    }

    private fun canSend(): Supply = object : Supply {
        override val name = "msg/query_can_send"
        override val summary = "检查现在能不能自动发消息（应用是否安装、无障碍是否连上、系统分享通路是否可用）。"

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

    /** 当前有没有待确认的发送（界面用它拦住下一句话） */
    override fun hasPendingSend(): Boolean = automator.hasPending()

    /** 用户说「算了」时丢掉待发内容 */
    override fun discardPending(): Boolean =
        automator.latestTraceId()?.let { automator.discard(it) } ?: false
}
