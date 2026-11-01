package com.example.voiceassistant.assistant

/**
 * 串行调度：**先本地，再联网，最后才问云端**。
 *
 * ## 它解决三个问题
 * 1. **本地和云端抢回答**：以前是「本地答案先上屏，云端回来再顶掉」，
 *    用户看到同一条气泡被改两次。现在由这里先定好唯一的路线，
 *    后台按路线跑完，**只上屏一次**（见 MainActivity 的 runChain）。
 * 2. **该联网的没联网**：天气、实时数据、本地查不到的事实，一律先联网查；
 *    没有城市名时按定位查（[OnlineQuery.weatherPlan]）。
 * 3. **token 花得太快**：联网搜索是免费的、云端是按 token 付费的，
 *    所以顺序固定为「免费搜索 → 付费生成」，搜到了就不再问云端
 *    （[Decision.cloudFallback] 为 false 时连降级都不会发生）。
 *
 * ## 路线
 * ```
 * 本地规则/知识库 ──答得好──▶ LOCAL（0 次请求，0 token）
 *       │答不好 / 是实时问题
 *       ▼
 *   联网搜索（天气 / 百科）──查到──▶ WEB（0 token）
 *       │没查到 且 配了云端
 *       ▼
 *   云端大模型 ──▶ CLOUD（唯一花钱的一步）
 *       │失败
 *       ▼
 *   本地答案（常年气候 / 思维模式）
 * ```
 *
 * 纯函数，不碰网络、不引 data 层的类型（云端是否可用只通过布尔参数传入），
 * 所以整张决策表都能在单元测试里断言。
 */
object AnswerRouter {

    /** 三条路线 */
    enum class Route {
        /** 本地答，直接上屏 */
        LOCAL,

        /** 先联网查（免费） */
        WEB,

        /** 直接问云端大模型 */
        CLOUD
    }

    /** 一次调度决策 */
    data class Decision(
        val route: Route,
        /** 用户原话（兜底播报、状态栏要用） */
        val raw: String = "",
        /** 联网计划；[Route.WEB] 时非空 */
        val plan: OnlineQuery.Plan? = null,
        /** 发给云端的问题（本地归纳后的简要问句） */
        val cloudInput: String = "",
        /** 联网/云端都失败时，是否还能降级到云端（WEB 路线的兜底） */
        val cloudFallback: Boolean = false,
        /** 最终兜底答案（本地那条） */
        val fallbackText: String = "",
        val fallbackIntent: String = "",
        /** 云端请求要不要带历史上下文（只有追问 / 有指代时才带，省 token） */
        val useHistory: Boolean = false,
        /** 思考过程里要显示的这一步（"联网查实时天气" / "问问 AI"） */
        val note: String = ""
    ) {
        /** 这条路线会不会产生网络请求 */
        val needsNetwork: Boolean get() = route != Route.LOCAL
    }

    // ------------------------------------------------------------------ 意图分组

    /**
     * 本机功能：**任何时候都不交给联网或云端**。
     * 教学、槽位追问这类一旦被大模型接手，用户就再也教不会本地助手了。
     */
    private val LOCAL_ONLY = setOf(
        AssistantEngine.INTENT_EMPTY,
        AssistantEngine.INTENT_TEACH_PROMPT,
        AssistantEngine.INTENT_TAUGHT,
        AssistantEngine.INTENT_TEACH_CANCEL,
        DialogContext.INTENT_CLARIFY,
        AssistantEngine.INTENT_CLARIFY_REJECTED,
        DialogContext.INTENT_SLOT,
        AssistantEngine.INTENT_MEMORY
    )

    /**
     * 本地已经答得很好的意图：事实确定、秒回、不需要外部知识。
     * 走这条路 = 0 次网络请求 = 0 token。
     */
    private val LOCAL_GOOD = setOf(
        "time", "date", "weekday_calc", "timezone",           // 时间日期
        "arithmetic", "calc_continue", "unit_convert",        // 计算换算
        "translate",                                          // 内置词典
        "joke", "quote", "random", "story",                   // 娱乐
        "greeting", "farewell", "thanks", "support", "identity", // 寒暄 / 身份
        DialogContext.INTENT_CHAT,                            // 闲聊（语料 + 情感）
        AssistantEngine.INTENT_KNOWLEDGE                      // 内置常识知识库命中
    )

    /**
     * 本地答不好、值得**先搜再问**的意图。
     *
     * 只有问「事实 / 原因 / 比较 / 办法」才值得联网搜——
     * 情绪倾诉（「最近好累啊」）和求看法（「我该不该考研」）
     * 搜索也搜不出答案，直接交给云端更合适。
     */
    private val SEARCHABLE = setOf(
        QueryUnderstanding.Intent.FACT,
        QueryUnderstanding.Intent.CAUSE,
        QueryUnderstanding.Intent.COMPARE,
        QueryUnderstanding.Intent.METHOD,
        QueryUnderstanding.Intent.WEATHER
    )

    // ------------------------------------------------------------------ 决策

    /**
     * 决定这一句走哪条路线。
     *
     * @param raw 用户原话
     * @param intent 本地命中的意图（[AssistantResponse.intent]）
     * @param localText 本地答案（联网和云端都失败时的兜底）
     * @param understanding 本地语义理解结果（意图类别 + 归纳后的简要问题）
     * @param onlineEnabled 设置里的「联网查询」开关
     * @param cloudReady 云端配置可用（填了 Key 且开关打开）
     * @param cloudFirst 用户选了「AI 优先」
     * @param fallbackCity 上一轮聊过的城市（「那里天气怎么样」用它）
     */
    fun decide(
        raw: String,
        intent: String,
        localText: String,
        understanding: QueryUnderstanding.Understanding,
        onlineEnabled: Boolean,
        cloudReady: Boolean,
        cloudFirst: Boolean,
        fallbackCity: CityClimate.CityClimate? = null
    ): Decision {
        val text = raw.trim()
        val brief = understanding.brief.ifBlank { text }
        val local = { note: String ->
            Decision(
                route = Route.LOCAL,
                raw = text,
                fallbackText = localText,
                fallbackIntent = intent,
                note = note
            )
        }

        // ① 本机功能：教学 / 槽位 / 空输入 → 永远本地
        if (intent in LOCAL_ONLY) return local("本地功能")

        // ② 实时天气：本地只有常年平均值，这条必须联网。
        //    没说城市也照样查（按网络定位），查不到才用本地常年数据兜底。
        if (intent != AssistantEngine.INTENT_KNOWLEDGE && isWeatherRequest(text, intent, understanding)) {
            val plan = OnlineQuery.weatherPlan(text, fallbackCity)
            if (onlineEnabled && plan != null) {
                return Decision(
                    route = Route.WEB,
                    raw = text,
                    plan = plan,
                    cloudFallback = false, // 天气是数据问题，不是生成问题，没必要再问大模型
                    fallbackText = localText,
                    fallbackIntent = intent,
                    note = if (plan.city == null) "按定位查实时天气" else "联网查${plan.city.city}的实时天气"
                )
            }
            return local("联网已关闭")
        }

        // ③ 用户明确说「帮我查 / 百度 / 最新消息」→ 联网搜；搜不到再问云端
        if (OnlineQuery.wantsWeb(text)) {
            val plan = OnlineQuery.searchPlan(brief)
            if (onlineEnabled && plan != null) {
                return Decision(
                    route = Route.WEB,
                    raw = text,
                    plan = plan,
                    cloudFallback = cloudReady,
                    fallbackText = localText,
                    fallbackIntent = intent,
                    useHistory = needsHistory(understanding),
                    note = "上网查「${plan.query}」"
                )
            }
            if (cloudReady) return cloudDecision(text, brief, localText, intent, understanding, "问问 AI")
            return local("联网已关闭")
        }

        // ④ 本地答得很好 → 直接上屏，一次请求都不发。
        //    用户选了「AI 优先」时不走这条：他要的就是每句都过一遍大模型，
        //    即便本地也有答案（本机功能 ① 和天气 ② 仍然优先，那是正确性问题）。
        if (intent in LOCAL_GOOD && !cloudFirst) return local("本地已答")

        // ⑤ 本地没答好：**先免费搜**
        if (onlineEnabled && understanding.intent in SEARCHABLE) {
            val plan = OnlineQuery.searchPlan(brief)
            if (plan != null) {
                return Decision(
                    route = Route.WEB,
                    raw = text,
                    plan = plan,
                    cloudFallback = cloudReady,
                    fallbackText = localText,
                    fallbackIntent = intent,
                    useHistory = needsHistory(understanding),
                    note = "上网查「${plan.query}」"
                )
            }
        }

        // ⑥ 搜索不适用（情绪 / 主观）或没搜到：交给云端
        if (cloudReady) return cloudDecision(text, brief, localText, intent, understanding, "问问 AI")

        // ⑦ 没配云端：AI 优先也只能是本地答案
        return local(if (cloudFirst) "没配 AI 接口" else "本地兜底")
    }

    /**
     * 是不是在问实时天气 / 出行穿衣（这两类答案取决于今天的真实天气）。
     *
     * 只认**意图**，不看关键词：句子里出现「下雨」未必在问天气
     * （「为什么会下雨」问的是原理，本地常识库就能答），
     * 拿关键词硬套会把知识题送去查天气，答非所问还白跑一次网络。
     */
    fun isWeatherRequest(
        text: String,
        intent: String,
        understanding: QueryUnderstanding.Understanding
    ): Boolean {
        if (intent == "weather" || intent == "outing") return true
        return understanding.intent == QueryUnderstanding.Intent.WEATHER
    }

    /**
     * 云端请求要不要带历史上下文。
     *
     * 历史每轮都要重发，是纯成本。本地的指代消解已经把「它怎么样」
     * 改写成「上海怎么样」，这种情况下模型拿一句自足的问题就够了；
     * 只有确实存在指代（或明显的追问词）时才把历史带上。
     */
    fun needsHistory(understanding: QueryUnderstanding.Understanding): Boolean {
        if (understanding.resolved != understanding.raw) return true
        val text = understanding.raw
        return FOLLOW_UP_CUES.any { text.contains(it) }
    }

    private val FOLLOW_UP_CUES = listOf(
        "它", "他", "她", "这个", "那个", "那里", "这边", "上面", "刚才", "为什么", "然后呢", "还有吗"
    )

    private fun cloudDecision(
        raw: String,
        brief: String,
        localText: String,
        intent: String,
        understanding: QueryUnderstanding.Understanding,
        note: String
    ): Decision = Decision(
        route = Route.CLOUD,
        raw = raw,
        cloudInput = brief,
        cloudFallback = false,
        fallbackText = localText,
        fallbackIntent = intent,
        useHistory = needsHistory(understanding),
        note = note
    )
}
