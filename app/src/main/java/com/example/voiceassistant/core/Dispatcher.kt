package com.example.voiceassistant.core

import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.ServiceBus
import com.example.voiceassistant.nlu.LocalNlu
import java.time.LocalDateTime

/**
 * 路由层需要知道的、关于「待确认发送」的全部信息。
 *
 * 抽成接口而不是直接依赖 [MsgSupply]，是因为后者要 Android Context 和无障碍服务，
 * 那样 [Dispatcher] 就再也进不了 JVM 单测了——而这里的分支恰恰最该被测。
 */
interface PendingSend {

    /** 有没有一条「准备好了、等用户点头」的消息 */
    fun hasPendingSend(): Boolean

    /** 用户反悔了：丢掉待发内容；返回是否真的丢掉了 */
    fun discardPending(): Boolean
}

/**
 * 路由层：**一句话该由谁处理，全在这里决定**。
 *
 * ```
 * 用户这句话
 *   ├─ 有「等着确认的发送」？
 *   │    ├─ 「确认 / 发吧」 → msg/confirm_send
 *   │    ├─ 「算了 / 别发」 → 丢掉待发内容
 *   │    └─ 其它 → 当作新指令，待发内容留着不动
 *   ├─ 本地 NLU 认得？     → 直接调总线（不联网、不花钱、不等）
 *   ├─ 云端 AI 关了？      → 直说「只能做本机操作」
 *   ├─ 没网？              → 直说「要联网」
 *   └─ 都不是              → 交给大模型（它会用同一批服务去动手）
 * ```
 *
 * ## 为什么要单独一层
 * 这段判断以前散在 Activity 里，和大模型调用、界面刷新混在一起，
 * 加一个「本地优先」就得改十几处。抽出来之后：
 * - 判断逻辑是纯的，喂一句话进去、拿一个结果出来，**能单测**；
 * - 界面只负责「把结果放上屏」，不知道背后是本地还是模型。
 *
 * ## 顺序为什么是「确认 → 本地 → 模型」
 * 「确认」必须最先判：用户上一句刚被问「确认发送吗？」，这句「好」显然是回答那个，
 * 不能又被当成新指令去建闹钟。
 */
class Dispatcher(
    private val bus: ServiceBus,
    private val ai: AiChat,
    private val msg: PendingSend,
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) {

    /** 一句话的处理结果 */
    sealed class Result {

        /**
         * 本地服务直接办完了。
         *
         * @param service 实际调的服务名，界面上可以用来显示「本机处理」
         */
        data class Local(val service: String, val reply: BusReply) : Result()

        /** 交给大模型了 */
        data class Ask(val answer: AiChat.Answer) : Result()

        /** 没办：说清原因（关掉了、没网、要开权限） */
        data class Blocked(val message: String) : Result()
    }

    /**
     * 处理一句话。
     *
     * **会阻塞**（本地服务里可能有无障碍点击、联网），必须在后台线程调用。
     *
     * @param online 当前网络状态由外部传：这一层不碰 Android，单测才能跑
     */
    fun dispatch(
        text: String,
        history: List<com.example.voiceassistant.network.LlmMessage>,
        styleHint: String,
        online: Boolean
    ): Result {
        val input = text.trim()
        if (input.isEmpty()) return Result.Blocked("说点什么吧。")

        // ① 有待确认的发送：先看这句是不是在回答「发不发」
        if (msg.hasPendingSend()) {
            when (confirmKind(input)) {
                ConfirmKind.YES -> return runLocal("msg/confirm_send")
                ConfirmKind.NO -> {
                    val dropped = msg.discardPending()
                    return Result.Local(
                        "msg/discard",
                        BusReply.ok(if (dropped) "好，不发了。" else "已经没有待发的消息了。")
                    )
                }
                ConfirmKind.OTHER -> Unit // 当新指令处理，待发内容保留到过期
            }
        }

        // ② 本地能认领的指令，一律不碰网络
        val hit = LocalNlu.route(input, now())
        if (hit != null) {
            return runLocal(hit.service, hit.args)
        }

        // ③ 云端 AI 被关掉：本机能力还能用，问答不行
        if (!LlmSwitch.enabled) {
            return Result.Blocked(
                "云端 AI 现在是关的，问答、翻译这些用不了。" +
                    "闹钟、手电、音量、打开应用这些本机操作还能用。"
            )
        }

        // ④ 没网就别发请求了，省得等超时
        if (!online) {
            return Result.Blocked("现在没联网，问答用不了。")
        }

        // ⑤ 交给大模型
        return Result.Ask(ai.ask(history, input, styleHint))
    }

    /** 跑一个本地服务；名字不认识时说实话，不要装作办成了 */
    private fun runLocal(
        service: String,
        args: com.example.voiceassistant.bus.BusArgs = com.example.voiceassistant.bus.BusArgs.EMPTY
    ): Result {
        val reply = bus.call(service, args)
        return Result.Local(service, reply)
    }

    // ------------------------------------------------------------------ 确认判断

    enum class ConfirmKind { YES, NO, OTHER }

    /**
     * 这句是不是在回答「确认发送吗？」
     *
     * 判断刻意做得**保守**：只认很短、很明确的话。
     * 「好，那你顺便帮我订个闹钟」这种带后续指令的，会落到 OTHER，
     * 避免把用户的真实意图当成一个「好」字吞掉。
     */
    fun confirmKind(text: String): ConfirmKind {
        val clean = text.trim()
            .trim('。', '！', '!', '，', ',', '？', '?', ' ', '~', '～')

        if (clean in YES_WORDS) return ConfirmKind.YES
        if (clean in NO_WORDS) return ConfirmKind.NO
        // 超出一句话的长度基本不是单纯回答，交回正常流程
        if (clean.length > 8) return ConfirmKind.OTHER
        // 前缀式：「确认发送」「发吧发吧」
        if (YES_PREFIX.any { clean.startsWith(it) }) return ConfirmKind.YES
        if (NO_PREFIX.any { clean.startsWith(it) }) return ConfirmKind.NO
        return ConfirmKind.OTHER
    }

    private companion object {
        val YES_WORDS = setOf(
            "确认", "确定", "是", "对", "嗯", "好", "行", "可以", "发", "发送", "发吧",
            "确认发送", "发出去", "好的", "是的", "没错", "就这样", "可以发", "对啊"
        )
        val NO_WORDS = setOf(
            "不", "别", "算了", "取消", "不用", "不要", "不发", "别发", "先别发",
            "等等", "等一下", "不了", "算了别发"
        )
        val YES_PREFIX = listOf("确认", "确定", "发吧", "发送")
        val NO_PREFIX = listOf("取消", "别发", "不发", "算了")
    }
}
