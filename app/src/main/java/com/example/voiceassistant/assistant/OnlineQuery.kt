package com.example.voiceassistant.assistant

/**
 * 联网决策：这句话值不值得联网、要联网查什么。
 *
 * ## 原则（v3.0 重写）：**按需联网，能查就别让大模型猜**
 * 1. **实时天气** → 必联网（本地只有常年平均值，联网才有真实数据）；
 *    没有城市名时按**网络定位**查（"天气怎么样"也要答得出来）；
 * 2. 用户明确说「帮我查 / 百度 / 最新消息」→ 联网搜；
 * 3. 本地答不上来的**事实类**问题（「量子纠缠是什么」）→ 先联网搜（免费），
 *    搜不到再交给云端大模型；
 * 4. 时间、计算、翻译、闲聊、教过的内容 → 本地秒回，不联网。
 *
 * 联网是按顺序串行的：**先免费搜索，再付费问云端**，
 * 所以搜索命中就根本不会花 token（见 [AnswerRouter]）。
 *
 * 纯逻辑，不碰网络本身（真正上网在 [com.example.voiceassistant.data.WebSearch]），
 * 所以决定"该不该联网"这件事可以在单元测试里断言。
 */
object OnlineQuery {

    /** 联网的种类 */
    enum class Kind {
        /** 查实时天气 */
        WEATHER,

        /** 查百科 */
        SEARCH
    }

    /** 一次联网计划 */
    data class Plan(
        val kind: Kind,
        /** 查询关键词（天气时是城市名，可能为空表示按定位查） */
        val query: String,
        val city: CityClimate.CityClimate? = null,
        /** 第几天：0 今天、1 明天、2 后天 */
        val dayOffset: Int = 0
    ) {
        /** "今天 / 明天 / 后天"，回答里要说清是哪一天 */
        val dayLabel: String get() = when (dayOffset) {
            1 -> "明天"
            2 -> "后天"
            in 3..6 -> "${dayOffset}天后"
            else -> "今天"
        }
    }

    /**
     * **用户明确要求上网查**的说法。
     *
     * ## 为什么只认这几种
     * 早期版本是「本地答不上来就去搜」，结果用户随口说句心里话也会被拉去查资料，
     * 既慢又不像聊天。联网现在只认**显式请求**（查 / 搜 / 百度 / 网上 / 资料 / 新闻 / 实时），
     * 其余情况一律留在本机：**不知道就用思维模式给出分析路径**（见 [ReplyCrafter]），不搜。
     */
    private val EXPLICIT_WEB_CUES = listOf(
        "帮我查", "帮我搜", "帮我找", "帮我看看网上", "查一下", "查一查", "查查", "查个",
        "搜索一下", "搜一下", "搜搜", "搜素", "网上查", "网上搜", "上网查", "去网上",
        "百度一下", "百度搜", "必应搜", "谷歌搜", "上百度", "百度百科", "百科", "维基",
        "查资料", "找资料", "有资料", "查百科", "查新闻", "查股价", "查汇率", "查快递",
        "最新消息", "最新进展", "最新情况", "实时报价", "实时数据", "实时 COVID", "今天的新闻", "近况如何", "进展如何"
    )

    /**
     * 出现这些词说明问的是**实时**天气 / 气温，本地的常年气候数据不够用。
     *
     * 「带伞 / 穿什么」也在里面：这两个问题的答案取决于今天下不下雨、多少度，
     * 用常年平均答会误导用户——给它实时数据，穿衣建议才有意义。
     */
    val WEATHER_CUES = listOf(
        "天气", "气温", "温度", "多少度", "几度", "下雨", "下雪", "湿度", "刮风", "台风",
        "空气质量", "冷不冷", "热不热", "闷热", "紫外线", "大风", "带伞", "穿什么", "穿多少",
        "要穿", "适合出门", "出门", "回暖", "降温"
    )

    /** 「明天 / 后天 / 大后天」：联网查天气时要取对应那一天的数据 */
    private val DAY_OFFSETS: List<Pair<String, Int>> = listOf(
        "大后天" to 3, "后天" to 2, "明天" to 1, "明儿" to 1
    )

    /** 这句话问的是第几天（0 今天 / 1 明天 / 2 后天 …） */
    fun dayOffsetOf(text: String): Int {
        val raw = text.trim()
        for ((cue, offset) in DAY_OFFSETS) {
            if (raw.contains(cue)) return offset
        }
        return 0
    }

    /** 是否提到了具体某一天（回答里要带上"明天"这类限定词） */
    fun hasDayCue(text: String): Boolean =
        DAY_OFFSETS.any { (cue, _) -> text.contains(cue) } ||
            text.contains("今天") || text.contains("今日")

    /** 问句里这些只是套话，去掉之后剩下的才是真正的查询主题 */
    private val QUERY_PREFIXES = listOf(
        "请告诉我", "告诉我", "我想知道", "我想了解一下", "想了解一下", "想知道",
        "百度百科查查", "百度百科查", "百度搜一下", "百度一下", "百度搜", "上百度查查", "帮我查一下",
        "帮我查查", "帮我搜索", "帮我搜", "网上查查", "网上查", "网上搜", "搜索一下",
        "查一下", "查一查", "查查", "搜索", "搜一下",
        "请问", "介绍一下", "给我讲讲", "讲讲", "说说", "说一下", "什么是", "什么叫"
    )

    /** 句尾这些词也要去掉 */
    private val QUERY_SUFFIXES = listOf(
        // 顺序很重要：先匹配长后缀，「相对论讲的是啥」才不会被截成「相对论讲」
        "讲的是啥意思", "讲的是啥", "讲的是什么", "讲是什么", "在讲啥", "是什么东西",
        "是什么意思", "什么意思", "是什么意思啊", "是什么", "是啥",
        "的介绍", "介绍一下", "的资料", "的情况", "的原理", "的优点", "的缺点",
        "怎么读", "怎么写", "怎么用", "有哪些", "新闻", "最新消息", "的最新进展"
    )

    /** 这些话题问了也查不出所以然（是本机功能 / 我的身份 / 纯情绪），不联网 */
    private val SKIP_KEYWORDS = listOf(
        "我教你", "记住", "几点", "几号", "星期", "等于", "笑话", "故事",
        "打开", "关闭", "提醒", "闹钟"
    )

    /**
     * 决定要不要联网（兼容旧入口；新代码请用 [weatherPlan] / [searchPlan]）。
     *
     * @param input 用户这一句（原始文本）
     * @param intent 本地给出的意图（[AssistantResponse.intent]）
     * @param fallbackCity 上一次查过的城市；用户说「那里/这里」时用它
     * @return 联网计划；null 表示本地已经答好了，不必联网
     */
    fun plan(input: String, intent: String?, fallbackCity: CityClimate.CityClimate? = null): Plan? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        if (SKIP_KEYWORDS.any { raw.contains(it) }) return null
        // 常识知识库已经答好（「为什么会下雨」讲的是原理，不是今天的天气）
        if (intent == AssistantEngine.INTENT_KNOWLEDGE) return null

        weatherPlan(raw, fallbackCity)?.let { return it }
        if (wantsWeb(raw)) return searchPlan(raw)
        return null
    }

    /**
     * 天气联网计划。
     *
     * 城市拿不到也照样返回计划（[Plan.city] 为 null）：这时联网会按
     * **网络定位**查用户所在位置的天气——「天气怎么样」这种没带城市的问题
     * 以前会被判成"不联网"，只能回常年平均数据，现在能给出实时值。
     */
    fun weatherPlan(
        raw: String,
        fallbackCity: CityClimate.CityClimate? = null,
        dayOffset: Int? = null
    ): Plan? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (SKIP_KEYWORDS.any { text.contains(it) }) return null
        if (!WEATHER_CUES.any { text.contains(it) }) return null
        val city = CityClimate.find(text) ?: fallbackCity
        val offset = dayOffset ?: dayOffsetOf(text)
        return Plan(
            kind = Kind.WEATHER,
            query = city?.city.orEmpty(),
            city = city,
            dayOffset = offset
        )
    }

    /** 搜索联网计划：主题太短（「哦」「嗯」）不值得发一次请求 */
    fun searchPlan(topic: String): Plan? {
        val query = extractTopic(topic).ifBlank { topic.trim() }
        if (query.length < 2) return null
        return Plan(Kind.SEARCH, query)
    }

    /**
     * 这句话是不是在**明确要求上网查**。
     * 提供给界面 / 测试判断用（例如决定是否显示「联网中」提示）。
     */
    fun wantsWeb(raw: String): Boolean {
        val lowered = raw.trim().lowercase()
        if (lowered.isEmpty()) return false
        if (SKIP_KEYWORDS.any { lowered.contains(it) }) return false
        return EXPLICIT_WEB_CUES.any { lowered.contains(it) } ||
            EXPLICIT_WEB_CUES.any { FuzzyMatcher.normalize(raw).contains(it) }
    }

    /**
     * 从一句自然语言提问里抽出要查的主题：
     * 「帮我查一下人工智能是什么」→「人工智能」
     */
    fun extractTopic(raw: String): String {
        var topic = raw.trim()
            .trim('？', '?', '！', '!', '。', '.', '，', ',', ' ', '啊', '呢', '呀', '吧', '嘛')
        for (prefix in QUERY_PREFIXES) {
            if (topic.startsWith(prefix) && topic.length > prefix.length) {
                topic = topic.removePrefix(prefix)
                break
            }
        }
        for (suffix in QUERY_SUFFIXES) {
            if (topic.endsWith(suffix) && topic.length > suffix.length) {
                topic = topic.removeSuffix(suffix)
                break
            }
        }
        // 尾部的语气词（「量子纠缠到底是什么」→ 去掉「是什么」还剩「到底」）
        for (tail in QUERY_TAILS) {
            if (topic.endsWith(tail) && topic.length > tail.length) {
                topic = topic.removeSuffix(tail)
                break
            }
        }
        topic = topic
            .removePrefix("什么是")
            .removePrefix("为什么")
            .trim('的', '了', '是', '？', '?', '，', ',', '。', '.', ' ')
        return topic
    }

    /** 「量子纠缠到底」里的「到底」这类没有检索价值的尾巴 */
    private val QUERY_TAILS = listOf("到底", "究竟", "具体", "大概", "呢", "啊", "呀")
}
