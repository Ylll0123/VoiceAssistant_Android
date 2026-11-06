package com.example.voiceassistant.core

import android.os.Handler
import com.example.voiceassistant.bus.EventBus
import com.example.voiceassistant.dialog.DialogueManager
import com.example.voiceassistant.fuzzy.FuzzyEngine
import com.example.voiceassistant.fuzzy.IntentResult
import java.util.concurrent.Executor

/**
 * 一句话的完整处理流水线：**语音链路 → 模糊容错 → 对话管理 → （必要时）问答链路**。
 *
 * ```
 * submit(candidates)
 *   │
 *   ├─ publish(RawText)                    ① 语音链路的产出
 *   ├─ fuzzy.recognize(raw)                 ② 归一化 + 纠错 + 意图 + 置信度
 *   ├─ publish(IntentRecognized)            ③ 引擎的结论（谁都能看）
 *   │
 *   ├─ 是提醒域？ ──是──▶ dialogue.handle() ──▶ publish(ReplyReady)   ← 0 请求 0 token
 *   │                └─ 槽位不全 → 追问（也走 ReplyReady）
 *   │
 *   └─ 不是 ──▶ router.plan() ──▶ publish(TurnPlanned)
 *                              └─▶ 需要联网？ → 子线程 → publish(ReplyReady)
 *                                 不需要   → publish(ReplyReady)（零等待）
 * ```
 *
 * ## 为什么提醒域要「抢先」处理
 * 提醒是**有副作用的指令**，不能走「先给个本地答案、再让云端补充」那一套：
 * 一旦云端也参与，用户会看到两套说法，提醒还可能被建两遍。
 * 所以提醒域一旦命中（且置信度够），整条链路就到此为止，用模板回话——
 * 这也是框架里「提醒类操作优先模板，不调用大模型」的落点。
 *
 * ## 线程约定
 * [submit] 请在主线程调用；[ReplyReady] 一律在主线程发出
 * （网络阶段由 [AssistantRouter.runAsync] 回到主线程后再发）。
 */
class AssistantPipeline(
    private val bus: EventBus,
    private val fuzzy: FuzzyEngine,
    private val dialog: DialogueManager,
    private val router: AssistantRouter,
    private val history: HistoryManager,
    private val executor: Executor,
    private val main: Handler,
    /** 每一轮重新取一次配置：用户在设置页改了联网 / 云端开关要立刻生效 */
    private val config: () -> AssistantRouter.Config,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * 提交一轮输入。
     *
     * @param turn 轮次号（界面自增，用来丢弃过期结果）
     * @param candidates ASR 的多条候选，第一条为主
     */
    fun submit(turn: Int, candidates: List<String>, source: String = RawText.SOURCE_ASR) {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return
        val raw = cleaned.first()

        // ① 语音链路的产出先广播出去（日志 / 回显 / 调试都能订它）
        bus.publish(RawText(turn, cleaned, source))

        // ② 模糊容错引擎：识别 + 纠错 + 置信度
        val intent = runCatching { fuzzy.recognize(raw, clock()) }.getOrNull()
        if (intent != null) bus.publish(IntentRecognized(turn, raw, intent))

        // ③ 提醒域：对话管理器接手（含「上一轮在等时间」这种跨轮情况）
        if (intent != null && dialog.claims(intent)) {
            val outcome = runCatching { dialog.handle(raw, intent) }.getOrNull()
            when (outcome) {
                is DialogueManager.Outcome.Handled -> {
                    replyWith(turn, raw, outcome.text, outcome.intentId, intent)
                    return
                }
                is DialogueManager.Outcome.Ask -> {
                    replyWith(turn, raw, outcome.text, outcome.intentId, intent)
                    return
                }
                else -> Unit // NotMine：继续走下面的问答链路
            }
        }

        // ④ 普通问答：本地 → 联网 → 云端（原有调度核心，一行没改）
        val plan = router.plan(cleaned, config())
        bus.publish(TurnPlanned(turn, plan))
        if (!plan.needsNetwork) {
            bus.publish(ReplyReady(turn, router.replyOf(plan), raw))
            return
        }
        router.runAsync(executor, main, plan, config()) { reply ->
            bus.publish(ReplyReady(turn, reply, raw))
        }
    }

    /**
     * 提醒域的结果直接上屏。
     *
     * [history] 只在这里记用户那句——问答链路是在 `router.plan` 里记的，
     * 两条路各自记一次，绝不会重复（重复就会出现两个一样的提问气泡）。
     */
    private fun replyWith(
        turn: Int,
        raw: String,
        text: String,
        intentId: String,
        intent: IntentResult
    ) {
        history.recordUser(raw)
        val reply = AssistantRouter.Reply(
            text = text,
            source = AssistantRouter.Source.TOOL,
            intent = intentId,
            heard = null,
            note = "对话管理：${intent.intentId}（置信度 ${"%.2f".format(intent.confidence)}）"
        )
        bus.publish(ReplyReady(turn, reply, raw))
    }
}
