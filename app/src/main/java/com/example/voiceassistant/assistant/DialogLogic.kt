package com.example.voiceassistant.assistant

/**
 * 对话逻辑层（"答得像 AI"的关键：接得住上文、缺参数会问、不会的就给推理框架）。
 *
 * ## 它补的是规则表和小模型之间的那一块
 * 规则表只会答写死的问题，小模型只会"学着语料说话"，两者都不处理**对话结构**。
 * 真人聊天里大量的话其实是残缺的：
 * - 「为什么？」——省略了主语，得回到上一句才知道在问什么；
 * - 「那北京呢？」——省略了整个谓语；
 * - 「它多少钱？」——用代词指上一句说过的东西；
 * - 「翻译」——根本没给参数。
 * 这一层把残缺的话**补全成完整问句**，再交给已有的规则去答，
 * 规则一个字都不用改就支持了上下文串联。
 *
 * ## 三件事
 * 1. [expand]：省略 / 指代 → 完整句子（配合 [IntentRule] 的 rerun 机制重讲一遍）
 * 2. [missingSlot] / [fillSlot]：参数缺失时反问，下一句把答案补进槽位再执行
 * 3. [frameAnswer]：本地确实不知道答案时，**不编事实**，
 *    给一套思考 / 决策 / 归因的框架，把话头接住并往下问
 *
 * 全是纯函数，不碰 Android，可以直接在单元测试里断言。
 */
object DialogLogic {

    // ------------------------------------------------------------------ ① 上下文补全

    /** 指代上一句实体的代词 */
    private val PRONOUNS = listOf(
        "这个城市", "那个城市", "这座城市", "这个东西", "那个东西",
        "这里", "那里", "这边", "那边", "这个", "那个", "这种", "那种",
        "它", "他", "她"
    )

    /** 地点类代词：替换成城市名之外还要考虑「这里」= 用户在的地方（未知，按上文城市处理） */
    private val PLACE_PRONOUNS = listOf(
        "这里", "那里", "这边", "那边", "这座城市", "这个城市", "那个城市"
    )

    /** 「继续说」类的追问 */
    private val CONTINUE_CUES = listOf(
        "还有吗", "还有呢", "还有别的吗", "还有没有", "再说点", "再多讲点", "再讲点",
        "再说说", "继续说", "继续讲", "接着说", "然后呢", "另外呢", "除此之外",
        "再详细点", "详细点", "详细说说", "具体说说", "具体点", "举个例子", "举个例", "比如呢"
    )

    /** 「问原因」类的追问 */
    private val REASON_CUES = listOf(
        "为什么", "为啥", "为何", "怎么会这样", "怎么会", "为什么这样", "凭什么"
    )

    /** 「问办法」类的追问 */
    private val HOW_CUES = listOf(
        "那怎么办", "那咋办", "怎么办才好", "该怎么做", "怎么做呢", "怎么做", "怎么弄",
        "有什么办法", "有办法吗", "怎么办"
    )

    /**
     * 把一句承接上文的话补全成完整问句。
     *
     * @param input 用户这一轮说的（原始文本）
     * @param context 会话上下文，从中取上一句的主题 / 实体
     * @return 补全后的完整句子；**null 表示这句话本身就是完整的**，不需要改写
     */
    fun expand(input: String, context: DialogContext): String? {
        val text = FuzzyMatcher.normalize(input)
        if (text.isEmpty()) return null

        val entity = entityOf(context)
        val intent = context.lastIntent
        val subject = context.lastSubject

        // ① 「继续说」→ 就同一件事再问一层
        if (CONTINUE_CUES.any { text == it || text.endsWith(it) } && !entity.isNullOrEmpty()) {
            return when (intent) {
                "weather" -> "${entity}这两天天气还有什么要注意的"
                "translate" -> null
                else -> "${entity}还有什么要注意的"
            }
        }

        // ② 「为什么」→ 追问上一件事的原因
        if (REASON_CUES.any { text == it || text.endsWith(it) } && text.length <= 6 && !entity.isNullOrEmpty()) {
            return "${entity}为什么会这样"
        }

        // ③ 「怎么办」→ 追问上一件事的解法
        if (HOW_CUES.any { text == it || text.endsWith(it) } && text.length <= 8 && !entity.isNullOrEmpty()) {
            return "${entity}该怎么办"
        }

        // ④ 代词指代 → 换成上一句说过的实体
        if (!entity.isNullOrEmpty()) {
            val hit = PRONOUNS.firstOrNull { text.contains(it) }
            if (hit != null) {
                val replaced = text.replace(hit, entity)
                return if (replaced != text && replaced.length >= entity.length + 1) replaced else null
            }
        }

        // ⑤ 只说了地点代词/城市名，且上一轮在聊天气：沿用谓语
        if (intent == "weather" && PLACE_PRONOUNS.any { text == it }) {
            val city = CityClimate.find(text)
            return city?.let { "${it.city}天气怎么样" }
                ?: context.entitySlot()?.let { "${it}天气怎么样" }
        }

        // ⑥ 只报了一个词，上一轮是翻译：当成下一个要翻译的词
        if (intent == "translate" && subject != null && text.length in 1..6 && isChineseWord(text)) {
            return "${text}用英语怎么说"
        }

        return null
    }

    /** 从上下文里取「上一句聊到的那个东西」 */
    private fun entityOf(context: DialogContext): String? {
        // 优先用显式记下来的实体槽位，没有就退回到主题
        return context.entitySlot() ?: context.lastSubject
    }

    /** 是不是纯中文词（用来判断用户是不是只丢了一个词过来） */
    private fun isChineseWord(text: String): Boolean =
        text.isNotEmpty() && text.all { it in '\u4e00'..'\u9fa5' }

    // ------------------------------------------------------------------ ② 槽位澄清

    /** 「翻译」里真正的动词部分，去掉之后剩下的是不是还有词 */
    private val TRANSLATE_CUES = listOf(
        "用英语怎么说", "用英语怎么讲", "用英文怎么说", "英语怎么说", "英文怎么说",
        "用英语怎么写", "英语怎么写", "英文怎么写", "用英语说", "用英文说",
        "的英语是什么", "的英文是什么", "英语是什么", "英文是什么",
        "翻译成英语", "翻译成英文", "用英语", "用英文",
        "是什么意思", "什么意思", "中文是什么", "怎么翻译", "翻译一下", "翻译成中文",
        "翻译", "英文", "英语"
    )

    /**
     * 这句话是不是缺参数。
     *
     * @return 缺失的槽位名（目前只有 "translate"）；null 表示参数齐全或压根不需要参数
     */
    fun missingSlot(input: String): String? {
        val raw = input.trim()
        val text = FuzzyMatcher.normalize(raw)
        if (text.isEmpty()) return null
        if (Dictionary.isEnglish(raw)) return null // 整句是英文单词，本身就是参数

        val hasCue = TRANSLATE_CUES.any { text.contains(it) }
        if (!hasCue) return null

        var rest = text
        for (cue in TRANSLATE_CUES) rest = rest.replace(cue, "")
        rest = rest.trim('的', '是', '说', '讲', '写', '成', '为', '在', '了', '啊', '呢', '呀', '？', '?', '，', ',', '。', '.', ' ')
        // 只剩语气 / 空 → 确实是"想翻译但没说词"
        return if (rest.isEmpty() || rest.length > 10) "translate" else null
    }

    /** 槽位问题的问法（反问用户，让他把缺的参数说出来） */
    fun slotQuestion(slot: String): String = when (slot) {
        "translate" -> "你要翻译哪个词？直接说这个词就行，比如「苹果」。"
        else -> "还差点信息，你把要问的对象说清楚我再答。"
    }

    /**
     * 槽位回填：用户补了参数，拼成一句完整的话交给规则去答。
     * @return 完整问句；参数还是不合法就返回 null
     */
    fun fillSlot(slot: String, answer: String): String? {
        val word = answer.trim()
            .trim('的', '是', '说', '讲', '写', '成', '为', '在', '了', '啊', '呢', '呀', '？', '?', '，', ',', '。', '.', ' ')
        if (word.isEmpty() || word.length > 10) return null
        return when (slot) {
            "translate" -> if (Dictionary.isEnglish(word)) word else "${word}用英语怎么说"
            else -> null
        }
    }

    // ------------------------------------------------------------------ ③ 推理框架

    /** 做选择的说法 */
    private val DECISION_CUES = listOf(
        "该不该", "要不要", "值不值", "值不值得", "好不好", "行不行",
        "选哪个", "怎么选", "如何抉择", "换不换", "买不买", "去不去",
        "建议我", "我该", "是去还是", "还是不", "我到底"
    )

    /** 求解法的说法 */
    private val SOLVE_CUES = listOf(
        "怎么办", "怎么做", "怎么搞", "如何做", "该怎么", "怎么才好", "如何是好",
        "老是失败", "总是做不好", "不知从哪下手", "无从下手", "没有头绪", "没头绪"
    )

    /** 要评价 / 要意见的说法 */
    private val OPINION_CUES = listOf(
        "你觉得", "你怎么看", "有什么看法", "这样对吗", "这样好吗", "问题出在哪", "出在哪"
    )

    /** 问成因的说法 */
    private val CAUSE_CUES = listOf(
        "为什么", "为啥", "为何", "原因是什么", "是什么原因", "怎么会", "根源在哪"
    )

    /**
     * 本地确实答不上来时，给一套**有结构的思路**。
     *
     * 原则是**不编事实**：不知道确切答案就明说不知道，
     * 但给出可执行的分析步骤，并把话头接回用户（"你先说说…"），
     * 这样下一轮还能继续，比一句"我不懂"有用得多。
     *
     * @return 结构化的一段话；null 表示这句话不适合走这条路（交给小模型兜底）
     */
    fun frameAnswer(input: String): String? = frameAnswer(input, null)

    /**
     * 本地确实答不上来时，给一套**有结构的思路**。
     *
     * 这里复用 [ReplyCrafter] 的说法池（同一个问题的多种思路只在 [ThoughtBank] 里写一次），
     * 按轮数轮换，所以同一句连着问也会换一套说法，不会一字不差地复读。
     *
     * @param context 会话上下文：提供轮数做轮换、避开最近说过的说法；null 时固定用第一套
     * @return 结构化的一段话；null 表示这句话不适合走这条路（交给小模型兜底）
     */
    fun frameAnswer(input: String, context: DialogContext?): String? {
        val raw = input.trim()
        val text = FuzzyMatcher.normalize(raw)
        if (text.length < 3) return null
        val topic = "「${stripToTopic(raw)}」"
        if (topic.length <= 2) return null

        return when {
            // 做选择：给决策顺序
            DECISION_CUES.any { text.contains(it) } ->
                ReplyCrafter.rotate(ReplyCrafter.Mode.CHOICE, topic, context)

            // 求办法：给拆解顺序
            SOLVE_CUES.any { text.contains(it) } ->
                ReplyCrafter.rotate(ReplyCrafter.Mode.METHOD, topic, context)

            // 问原因：给归因顺序，并说明不敢瞎猜
            CAUSE_CUES.any { text.contains(it) } ->
                ReplyCrafter.rotate(ReplyCrafter.Mode.CAUSE, topic, context)

            // 要评价：给"事实—影响—取舍"三层
            OPINION_CUES.any { text.contains(it) } ->
                ReplyCrafter.rotate(ReplyCrafter.Mode.OPINION, topic, context)

            else -> null
        }
    }

    /** 从原始问句里剥出「事情本身」当小标题：去掉客套话、语气词和句尾标点 */
    fun stripToTopic(raw: String): String {
        return raw.trim()
            .trim('？', '?', '！', '!', '。', '.', '，', ',', '啊', '呢', '呀', '吧', '嘛', ' ')
            .removePrefix("你说")
            .removePrefix("我想问")
            .removePrefix("问一下")
            .removePrefix("请问")
            .removePrefix("我想知道")
            .removePrefix("帮我查一下")
            .removePrefix("给我讲讲")
            .removePrefix("介绍一下")
            .removePrefix("讲讲")
            .removePrefix("说说")
            .trim()
            .let { if (it.length > 24) it.substring(0, 24) else it }
    }
}
