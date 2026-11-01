package com.example.voiceassistant.core

import android.os.Handler
import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.assistant.CityClimate
import com.example.voiceassistant.assistant.OnlineQuery
import com.example.voiceassistant.assistant.QueryUnderstanding
import com.example.voiceassistant.network.Http
import com.example.voiceassistant.network.LlmResult
import java.util.concurrent.Executor
import com.example.voiceassistant.handler.llm.CloudLlmHandler

/**
 * 核心层：**调度总入口**。
 *
 * 所有输入（语音识别结果 / 文字输入 / 技能页示例）都先到这里，
 * 按固定顺序短路执行，命中就返回，绝不往下多走一步：
 *
 * ```
 * ① ToolHandler     本机工具（提醒 / 音量 / 时间 / 计算）  -> 0 请求
 * ② QaLocalHandler  本地问答（规则/闲聊/记忆/知识库）    -> 0 请求
 * ③ WebHandler      按需联网（天气 / 百科）            -> 0 token
 * ④ CloudLlmHandler 云端大模型                        -> 唯一花钱的一步
 * ⑤ 兜底             上面全失败 -> 本地那条答案
 * ```
 *
 * ## 「本地和云端抢回答」是怎么根治的
 * 以前是「本地答案先上屏，云端回来再顶掉」，同一条气泡被改两次。
 * 现在整条链在**子线程串行**跑完，**只拿回一个结果**，界面**只上屏一次**。
 * 期间显示的只是淡色小字的思考过程，答案出现即清除。
 *
 * ## 两阶段 API
 * - [plan]：纯本地阶段（语义理解 + 本地答案 + 路线决策），很快，可在主线程调用；
 * - [run]：可能耗时的网络阶段（联网 → 云端），**阻塞**，只能在子线程调用；
 * - [process]：两者拼起来，一次性拿到结果（单测里用它最方便）。
 *
 * 拆成两段是为了让 UI 能先知道「要不要等」，不用等就直接上屏。
 */
class AssistantRouter(
    private val history: HistoryManager,
    private val toolHandler: ToolSource,
    private val qaHandler: LocalSource,
    private val webHandler: WebSource,
    private val llmHandler: CloudSource
) {

    /** 这个答案是谁给的 */
    enum class Source {
        /** 本机工具 */
        TOOL,

        /** 本地知识 / 规则 / 小模型 */
        LOCAL,

        /** 联网查到的 */
        WEB,

        /** 云端大模型 */
        CLOUD
    }

    /** 一轮对话的最终结果 */
    data class Reply(
        val text: String,
        val source: Source,
        val intent: String?,
        /** 实际采用的说法（多候选时可能不是识别结果第一条） */
        val heard: String?,
        /** 这一步在做什么，给「思考过程」显示 */
        val note: String = "",
        /** 联网 / 云端失败的原因（状态栏展示） */
        val error: String? = null,
        /** 要念出来的文本；默认与 [text] 一致，兜底时会去掉括号里的说明 */
        val speakText: String = text
    )

    /** 本地阶段算出来的「这一轮怎么走」 */
    data class TurnPlan(
        /** 用户原话 */
        val raw: String,
        /** 识别候选（已清洗） */
        val candidates: List<String>,
        /** 本地语义理解结果 */
        val understanding: QueryUnderstanding.Understanding,
        /** 本地引擎给出的答案 */
        val local: AssistantResponse,
        /** 路由决策 */
        val decision: RoutePlanner.Decision
    ) {
        /** 这一轮要不要跑网络 */
        val needsNetwork: Boolean get() = decision.needsNetwork
    }

    /** 一次调度要带上的运行期配置 */
    data class Config(
        /** 设置里的「联网查询」开关 */
        val onlineEnabled: Boolean = true,
        /** 云端配置；未配置时 [CloudLlmHandler.CloudConfig.isReady] 为 false */
        val cloud: CloudLlmHandler.CloudConfig = CloudLlmHandler.CloudConfig(),
        /** 上一轮聊过的城市，供「那里天气怎么样」兜底 */
        val fallbackCity: CityClimate.CityClimate? = null,
        /** 云端最多带几轮历史 */
        val maxHistoryTurns: Int = HistoryManager.DEFAULT_MAX_TURNS
    )

    /**
     * 本地阶段：理解这句话、让本地引擎先答一次、定好路线。
     *
     * 这一阶段**不碰网络**，可以放心在主线程调用。
     * 副作用：把用户这一句写进会话历史（用户气泡要立刻上屏）。
     */
    fun plan(input: List<String>, config: Config): TurnPlan {
        val cleaned = input.map { it.trim() }.filter { it.isNotEmpty() }
        val raw = cleaned.firstOrNull().orEmpty()

        // 记忆要在把这一句写进历史**之前**取，否则指代消解会拿当前这句当上下文
        val memory = history.userLines()
        history.recordUser(raw)

        if (TextCleaner.isBlank(raw)) {
            return TurnPlan(
                raw = raw,
                candidates = cleaned,
                understanding = QueryUnderstanding.analyze(raw, memory),
                local = AssistantResponse(AssistantEngine.LISTENING, AssistantEngine.INTENT_EMPTY),
                decision = RoutePlanner.Decision(
                    route = RoutePlanner.Route.LOCAL,
                    raw = raw,
                    note = "空输入"
                )
            )
        }

        val understanding = QueryUnderstanding.analyze(raw, memory)

        // ② 本地问答：整组候选交给引擎挑一条能理解的。
        //    只调一次——工具路线也要用这条答案兜底，
        //    重复调用会让引擎把同一轮对话学两遍。
        val local = qaHandler.answer(cleaned)

        // ① 本机工具：提醒 / 音量 / 停止播报，命中就直接执行
        //    （教学指令由 ToolHandler 内部挡掉，不会被误抢）
        val tool = if (toolHandler.claims(raw, local)) toolHandler.handle(raw, local) else null
        if (tool != null) {
            return TurnPlan(
                raw = raw,
                candidates = cleaned,
                understanding = understanding,
                local = AssistantResponse(tool.text, tool.intent, raw),
                decision = RoutePlanner.Decision(
                    route = RoutePlanner.Route.LOCAL,
                    raw = raw,
                    note = "${tool.tool}：已执行"
                )
            )
        }

        val decision = RoutePlanner.decide(
            raw = raw,
            intent = local.intent,
            localText = local.text,
            understanding = understanding,
            onlineEnabled = config.onlineEnabled,
            cloudReady = config.cloud.isReady(),
            cloudFirst = config.cloud.mode == CloudLlmHandler.CloudMode.CLOUD_FIRST,
            fallbackCity = config.fallbackCity
        )
        return TurnPlan(raw, cleaned, understanding, local, decision)
    }

    /** 本地阶段已经算好的答案，直接上屏（零等待） */
    fun replyOf(plan: TurnPlan): Reply = Reply(
        text = plan.local.text,
        source = Source.LOCAL,
        intent = plan.local.intent,
        heard = plan.local.heard,
        note = plan.decision.note
    )

    /**
     * 网络阶段（**阻塞**，只能在子线程调用）：联网 → 云端 → 本地兜底。
     *
     * 顺序是有意为之：联网搜索免费、云端按 token 付费，
     * 所以能搜到就绝不多花一次调用。路线由 [TurnPlan.decision] 定好，
     * 这里只负责执行，并且**只回传一个结果**——没有「先上屏再替换」这回事。
     */
    fun run(plan: TurnPlan, config: Config): Reply {
        val decision = plan.decision

        // ③ 联网查询（天气 / 百科），免费
        val webPlan = decision.plan
        if (decision.route == RoutePlanner.Route.WEB && webPlan != null) {
            val web = runCatching { webHandler.execute(webPlan) }.getOrNull()
            if (web != null) {
                return Reply(
                    text = web.text,
                    source = Source.WEB,
                    intent = web.intent,
                    heard = plan.local.heard,
                    note = decision.note
                )
            }
        }

        // ④ 云端大模型（这一步才花钱）
        val cloudReady = config.cloud.isReady()
        val askCloud = decision.route == RoutePlanner.Route.CLOUD ||
            (decision.cloudFallback && cloudReady)
        if (askCloud) {
            val input = CloudLlmHandler.pickInput(
                plan.raw,
                decision.cloudInput.ifBlank { plan.understanding.brief }
            )
            val turns = history.cloudTurns(decision.useHistory, config.maxHistoryTurns)
            val result = runCatching {
                llmHandler.call(config.cloud, turns, input, buildExtraSystem())
            }.getOrElse { LlmResult.Fail(Http.describe(it, config.cloud.timeoutMs)) }

            if (result is LlmResult.Ok) {
                // 云端的好答案也喂给本地小模型：要学的是「人怎么问」，所以用原话
                history.observeCloudPair(plan.raw, result.text)
                return Reply(
                    text = result.text,
                    source = Source.CLOUD,
                    intent = AssistantEngine.INTENT_CLOUD,
                    heard = plan.local.heard,
                    note = decision.note
                )
            }
            return replyFallback(plan, (result as LlmResult.Fail).reason)
        }

        // ⑤ 联网没查到、也没配云端：本地答案 + 一句说明
        return replyFallback(plan, null)
    }

    /**
     * 一步到位：本地阶段 + 网络阶段。
     *
     * 注意 [run] 会阻塞（里面有网络请求），所以 [process] 也**只能在子线程调用**。
     */
    fun process(input: List<String>, config: Config): Reply {
        val plan = plan(input, config)
        if (!plan.needsNetwork) return replyOf(plan)
        return run(plan, config)
    }

    /**
     * 异步跑完整一轮。
     *
     * 本地阶段在**调用线程**（主线程）执行，所以能马上知道要不要等；
     * 只有需要联网时才把 [run] 派发到 [executor]，结果回到 [main] 线程。
     *
     * @param onPlan 本地阶段完成回调（UI 用它决定要不要显示「思考过程」）
     * @param onResult 最终结果回调
     */
    fun processAsync(
        executor: Executor,
        main: Handler,
        input: List<String>,
        config: Config,
        onPlan: (TurnPlan) -> Unit,
        onResult: (Reply) -> Unit
    ) {
        val plan = plan(input, config)
        onPlan(plan)
        if (!plan.needsNetwork) {
            main.post { onResult(replyOf(plan)) }
            return
        }
        executor.execute {
            val reply = runCatching { run(plan, config) }
                .getOrElse {
                    Reply(
                        text = plan.local.text,
                        source = Source.LOCAL,
                        intent = plan.local.intent,
                        heard = plan.local.heard,
                        error = it.message
                    )
                }
            main.post { onResult(reply) }
        }
    }

    /** 兜底：本地答案 + 一句说明（念的时候只念正文） */
    private fun replyFallback(plan: TurnPlan, reason: String?): Reply = Reply(
        text = fallbackText(plan.decision, plan.local.text),
        source = Source.LOCAL,
        intent = plan.decision.fallbackIntent,
        heard = plan.local.heard,
        note = plan.decision.note,
        error = reason,
        speakText = plan.local.text
    )

    /** 兜底文案：说清「没查到」的原因，但不把责任推给用户 */
    private fun fallbackText(decision: RoutePlanner.Decision, localText: String): String =
        when (decision.plan?.kind) {
            OnlineQuery.Kind.WEATHER ->
                "$localText\n（我想联网查实时天气，这次没连上，上面是本地的常年气候参考。）"
            OnlineQuery.Kind.SEARCH ->
                "$localText\n（联网这次没查到，上面是我本地给的分析。）"
            else -> localText
        }

    /**
     * 组装附加到 system 提示里的上下文：当前时间 + 用户教过的说法。
     *
     * 时间让模型答得出「今天」「明天」指的是哪一天；
     * 教过的说法保证云端不会和用户亲手教的内容唱反调。
     *
     * 这段每轮都要跟着请求发出去，是 system 里最贵的一段，所以只带 2 条、
     * 并且去掉引导套话。不整个去掉的原因是它能防止云端和用户教的内容打架——
     * 这是功能性需要，不是装饰性的上下文。
     */
    private fun buildExtraSystem(): String {
        val now = java.time.LocalDateTime.now()
        val sb = StringBuilder()
        sb.append("现在是 ${now.year} 年 ${now.monthValue} 月 ${now.dayOfMonth} 日，")
            .append("${now.hour} 点 ${String.format("%02d", now.minute)} 分左右。")

        val taught = history.taughtMemories(TAUGHT_MEMORY_LIMIT)
        if (taught.isNotEmpty()) {
            sb.append("\n教过：")
            for ((question, answer) in taught) {
                sb.append("\n「$question」→「$answer」")
            }
        }
        return sb.toString()
    }

    private companion object {
        /** 注入 system 的教学记忆条数（每轮都重发，尽量少） */
        const val TAUGHT_MEMORY_LIMIT = 2
    }
}
