package com.example.voiceassistant.assistant

/**
 * 联网决策：这句话值不值得联网、要联网查什么。
 *
 * ## 原则：**本地能答好的就不联网**
 * 联网要花 1~3 秒，还依赖网络。所以：
 * - 时间、计算、单位换算、闲聊、记忆里教过的内容 → 本地答，秒回，不联网；
 * - **实时天气** → 本地只有常年平均值，联网能给出真实数据，值得等；
 * - 本地彻底答不上来（走到兜底 / 小模型瞎编） → 联网查百科，至少给个靠谱来源。
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
        /** 查询关键词 */
        val query: String,
        val city: CityClimate.CityClimate? = null
    )

    /** 本地答不上来、值得去网上查一次的意图 */
    private val SEARCHABLE_INTENTS = setOf(
        AssistantEngine.INTENT_FALLBACK,
        AssistantEngine.INTENT_LOCAL_MODEL,
        AssistantEngine.INTENT_CLARIFY_REJECTED,
        DialogContext.INTENT_CLARIFY
    )

    /** 出现这些词说明问的是实时天气 / 气温 */
    private val WEATHER_CUES = listOf(
        "天气", "气温", "温度", "多少度", "几度", "下雨", "下雪", "湿度", "刮风", "台风", "空气质量"
    )

    /** 问句里这些只是套话，去掉之后剩下的才是真正的查询主题 */
    private val QUERY_PREFIXES = listOf(
        "请告诉我", "告诉我", "我想知道", "我想了解一下", "想了解一下", "想知道",
        "帮我查一下", "帮我查查", "帮我搜索", "帮我搜", "查一下", "查查", "搜索", "搜一下",
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

    /** 这些话题问了也查不出所以然（要实时数据 / 是我的身份），不联网 */
    private val SKIP_KEYWORDS = listOf(
        "我教你", "记住", "几点", "几号", "星期", "算", "等于", "笑话", "故事",
        "打开", "关闭", "提醒", "闹钟"
    )

    /**
     * 决定要不要联网。
     *
     * @param input 用户这一句（原始文本）
     * @param intent 本地给出的意图（[AssistantResponse.intent]）
     * @param fallbackCity 上一次查过的城市；用户说「那里/这里」时用它
     * @return 联网计划；null 表示本地已经答好了，不必联网
     */
    fun plan(input: String, intent: String?, fallbackCity: CityClimate.CityClimate? = null): Plan? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val text = FuzzyMatcher.normalize(raw)

        // ① 实时天气：城市明确（或者刚聊过某个城市）才值得联网
        if (WEATHER_CUES.any { text.contains(it) }) {
            val city = CityClimate.find(raw) ?: fallbackCity
            if (city != null) return Plan(Kind.WEATHER, city.city, city)
        }

        // ② 本地答不上来 → 去网上查；太短或者有跳过词就别查了
        if (intent in SEARCHABLE_INTENTS) {
            if (SKIP_KEYWORDS.any { text.contains(it) }) return null
            val topic = extractTopic(raw)
            if (topic.length >= 2) return Plan(Kind.SEARCH, topic)
        }

        return null
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
        topic = topic
            .removePrefix("什么是")
            .removePrefix("为什么")
            .trim('的', '了', '是', '？', '?', '，', ',', '。', '.', ' ')
        return topic
    }
}
