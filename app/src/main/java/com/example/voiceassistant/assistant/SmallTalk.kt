package com.example.voiceassistant.assistant

/**
 * 日常闲聊引擎：话题识别 + 上下文承接 + 回复变体。
 *
 * ## 为什么同一句话能有不同回答
 * 每个话题准备了 2~4 种回应（[ChatTopic.replies]），挑选时：
 * 1. 先排除最近刚说过的句子（[DialogContext.wasSaidRecently]）；
 * 2. 剩下的按「闲聊轮数」轮换取用——同一个话题聊得越久，越靠后的变体会出场。
 * 两层叠起来，连续问同一句话也不会复读。
 *
 * ## 怎么做到「根据上文回答」
 * 引擎每轮都把话题 id 记进上下文（[DialogContext.topicId]）。下一句进来时：
 * - 如果是「为什么 / 然后呢 / 那怎么办」这类**承接句**，直接沿用当前话题的展开或建议；
 * - 如果带「它 / 这个 / 这事」这类**指代词**，同样认为还在说当前话题；
 * - 否则才去做新话题匹配，匹配上就换话题，匹配不上交给后面的规则和兜底。
 *
 * ## 怎么避免抢话
 * [ChatRule] 排在规则表**最末尾**，算数、日期、游戏这些功能性意图先走；
 * 含数字的句子直接不接（那是计算和日期的地盘），关键词至少两个字起。
 */
object SmallTalk {

    /** 新话题匹配的最低得分：低于它宁可说不知道，也不硬聊 */
    const val MIN_SCORE = 0.78

    /** 挑一条回复：避开刚说过的，剩下的按轮数轮换（确定性好，可单测） */
    fun pickReply(pool: List<String>, context: DialogContext): String? {
        if (pool.isEmpty()) return null
        val fresh = pool.filter { !context.wasSaidRecently(it) }
        val candidates = fresh.ifEmpty { pool }
        return candidates[context.chatTurns % candidates.size]
    }

    /** 组装回复：主体 +（可选）反问，反问只在主体不是问句时追加 */
    fun assemble(topic: ChatTopic, body: String, context: DialogContext, switched: Boolean): String {
        val wantFollowUp = switched || context.chatTurns % 2 == 1
        val followUp = if (wantFollowUp && topic.followUps.isNotEmpty()) {
            pickReply(topic.followUps, context)
        } else {
            null
        }
        val asks = body.contains("？") || body.contains("?")
        return if (followUp != null && !asks) "$body $followUp" else body
    }

    /**
     * 闲聊主入口。返回 null 表示这句话不归闲聊管，继续走后面的规则。
     */
    fun reply(text: String, context: DialogContext): RuleReply? {
        val normalized = text.trim()
        if (normalized.isEmpty()) return null
        // 数字交给算数 / 日期 / 换算，闲聊不接
        if (normalized.any { it.isDigit() }) return null
        // 任务型请求（订机票、打开手电筒…）不是闲聊，宁可承认做不了
        if (TASK_VERBS.any { normalized.contains(it) }) return null

        val current = DialogCorpus.find(context.activeTopic())

        // ① 承接上文：「为什么」「然后呢」「那怎么办」「嗯嗯」——沿用当前话题
        //    注意「嗯嗯」会被归一化剥成一个「嗯」，所以承接句允许单字
        if (current != null) {
            val continued = continueTopic(normalized, current, context)
            if (continued != null) return continued
        }

        // ② 新话题识别：太短的句子没有话题信息
        if (normalized.length < 2) return null
        val (topic, score) = bestTopic(normalized) ?: return null
        val body = pickReply(topic.replies, context) ?: return null
        val switched = topic.id != context.activeTopic()
        val full = assemble(topic, body, context, switched)
        return RuleReply(
            text = full,
            intent = DialogContext.INTENT_CHAT,
            slots = mapOf(
                DialogContext.SLOT_TOPIC to topic.id,
                "topic_title" to topic.title
            )
        )
    }

    /** 承接句处理：识别「为什么 / 怎么办 / 我也是」并回到当前话题的内容池 */
    private fun continueTopic(text: String, topic: ChatTopic, context: DialogContext): RuleReply? {
        // 「那怎么办 / 我该怎么做」→ 话题里的具体建议
        if (containsAnyFuzzy(text, *ADVICE_CUES) || isPronounReference(text)) {
            val pool = topic.advice.ifEmpty { topic.more.ifEmpty { topic.replies } }
            val body = pickReply(pool, context) ?: return null
            return RuleReply(
                text = body,
                intent = DialogContext.INTENT_CHAT,
                slots = mapOf(
                    DialogContext.SLOT_TOPIC to topic.id,
                    "topic_title" to topic.title
                )
            )
        }

        // 「为什么 / 然后呢 / 真的吗」→ 话题展开
        if (containsAnyFuzzy(text, *MORE_CUES)) {
            val pool = topic.more.ifEmpty { topic.replies }
            val body = pickReply(pool, context) ?: return null
            return RuleReply(
                text = body,
                intent = DialogContext.INTENT_CHAT,
                slots = mapOf(
                    DialogContext.SLOT_TOPIC to topic.id,
                    "topic_title" to topic.title
                )
            )
        }

        // 「我也是 / 确实 / 有道理 / 嗯嗯」→ 简短接住，再递一个话头
        if (containsAnyFuzzy(text, *ECHO_CUES)) {
            val body = pickReply(ECHO_REPLIES, context) ?: return null
            val followUp = pickReply(topic.followUps, context)
            val full = if (followUp != null && !body.contains("？")) "$body $followUp" else body
            return RuleReply(
                text = full,
                intent = DialogContext.INTENT_CHAT,
                slots = mapOf(
                    DialogContext.SLOT_TOPIC to topic.id,
                    "topic_title" to topic.title
                )
            )
        }
        return null
    }

    /**
     * 新话题匹配：关键词命中为主（0.9+），相似说法兜底（≥0.78）。
     * 关键词在句中越靠后分越高——中文里中心语通常在后面（「周末加班」聊的是加班）。
     */
    fun bestTopic(text: String): Pair<ChatTopic, Double>? {
        var best: ChatTopic? = null
        var bestScore = 0.0
        for (topic in DialogCorpus.topics) {
            val score = scoreTopic(text, topic)
            if (score > bestScore) {
                bestScore = score
                best = topic
            }
        }
        return if (best != null && bestScore >= MIN_SCORE) best to bestScore else null
    }

    private fun scoreTopic(text: String, topic: ChatTopic): Double {
        var best = 0.0

        // 关键词：包含即高分，再按位置微调；3 字以上允许轻微识别误差
        for (keyword in topic.keywords) {
            val normalizedKeyword = FuzzyMatcher.normalize(keyword)
            if (normalizedKeyword.length < 2) continue
            val index = text.indexOf(normalizedKeyword)
            if (index >= 0) {
                val lengthBonus = (normalizedKeyword.length - 2) * 0.01
                val positionBonus = 0.02 * index.toDouble() / text.length
                val score = 0.90 + lengthBonus + positionBonus
                if (score > best) best = score
            } else if (normalizedKeyword.length >= 3 && FuzzyMatcher.containsFuzzy(text, normalizedKeyword)) {
                if (0.84 > best) best = 0.84
            }
        }

        // 相似说法：用户没带关键词时靠它兜住（「我撑不住了」→ 累）
        if (text.length >= 3) {
            for (sample in topic.samples) {
                val sim = FuzzyMatcher.similarity(text, FuzzyMatcher.normalize(sample))
                if (sim >= MIN_SCORE && sim > best) best = sim
            }
        }
        return best
    }

    /** 短指代句：「它怎么了」「这个怎么办」「这事我也烦」——默认还在说当前话题 */
    private fun isPronounReference(text: String): Boolean {
        if (text.length > 10) return false
        return text.startsWith("它") || text.startsWith("这事") ||
            text.startsWith("这个") || text.startsWith("那个") ||
            text.startsWith("这件事")
    }

    private fun containsAnyFuzzy(text: String, vararg cues: String): Boolean =
        cues.any { text.contains(it) }

    /** 「为什么 / 然后呢」：想接着听 */
    private val MORE_CUES = arrayOf(
        "为什么", "为啥", "然后呢", "后来呢", "还有呢", "真的吗", "是吗",
        "怎么说", "展开", "具体点", "举个例子", "什么意思", "怎么会", "继续说"
    )

    /** 「那怎么办 / 我该怎么做」：想要具体建议 */
    private val ADVICE_CUES = arrayOf(
        "怎么办", "咋办", "怎么处理", "该怎么做", "怎么改", "怎么破",
        "有什么办法", "有什么建议", "教教我", "怎么调节"
    )

    /** 「我也是 / 有道理 / 嗯嗯」：接住情绪，递回话头 */
    private val ECHO_CUES = arrayOf(
        "我也是", "确实", "有道理", "是啊", "对啊", "说得对", "你说得对",
        "嗯嗯", "嗯", "哦", "好吧", "懂了", "原来如此", "理解", "同情", "哈哈", "嘿"
    )

    private val ECHO_REPLIES = listOf(
        "嗯，我在听。", "是吧。", "嗯嗯，接着说。", "我记着呢，你继续。", "懂，这种感觉不好受。"
    )

    /** 命中这些词就当任务型请求，闲聊不接（宁可承认做不了，也不答非所问） */
    private val TASK_VERBS = listOf(
        "订", "打开", "关闭", "关掉", "预约", "下载", "安装", "导航", "播放", "发消息给", "打电话"
    )
}

/**
 * 规则表里的闲聊入口。放在规则表最末尾，功能性意图永远先走。
 */
object ChatRule : IntentRule {
    override val intent = DialogContext.INTENT_CHAT

    override fun reply(request: AssistantRequest): RuleReply? =
        SmallTalk.reply(request.text, request.context)
}
