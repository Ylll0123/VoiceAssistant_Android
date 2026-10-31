package com.example.voiceassistant.assistant

/**
 * 思维模式回答引擎：**本地答不上来时，怎么答得像一个会思考的助手**。
 *
 * ## 它解决什么
 * 走到这里的话，规则、闲聊、记忆、知识库都已经接不住了。传统做法只有三种：
 * 搜网络（慢，而且用户明确说不想一不懂就搜）、让小模型拼一句（没逻辑）、
 * 回一句固定的「我不懂」（每次都一样）。
 *
 * 这里的做法是**先判断这句话在问什么**，然后给对应的「思维方式」：
 *
 * | 用户在做什么 | 给什么 |
 * |---|---|
 * | 倾诉情绪 | 先接住，再给一个最小出口，最后反问 |
 * | 两个东西比高低 | 先定评判标准，再谈好坏 |
 * | 问为什么会这样 | 给找原因的顺序和验证办法 |
 * | 拿不定主意 | 给决策三步 / 检验法 / 试一小步 |
 * | 问怎么办 | 把大问题拆成今天能做的第一个动作 |
 * | 要我的看法 | 给判断的三层，并说清为什么不直接给结论 |
 * | 要我讲解 | 先给框架，让用户选往哪深入 |
 * | 问一个我不知道的事实 | **明确承认不知道**，再给查证思路 |
 * | 完全没听懂 | 换成六种不同的接话方式，把话头递回去 |
 *
 * ## 三条原则
 * 1. **不编事实**：没有把握的答案就说没有把握，绝不用听起来像的句子糊弄；
 * 2. **每次都要有下文**：结尾抛一个问题回给用户，对话才不会断在这里；
 * 3. **不重复**：同一个意思准备 4~6 套说法，按轮数轮换并避开最近说过的那一套。
 *
 * 纯逻辑（不碰 Android / 网络），可以直接在单元测试里断言。
 */
object ReplyCrafter {

    /** 一句话在问什么 */
    enum class Mode {
        /** 情绪倾诉 */
        EMOTION,

        /** 两者比较 / 选哪个好 */
        COMPARE,

        /** 问原因 */
        CAUSE,

        /** 拿不定主意 */
        CHOICE,

        /** 求办法 */
        METHOD,

        /** 求看法 */
        OPINION,

        /** 求讲解 */
        EXPLAIN,

        /** 求事实（本地没有答案） */
        FACT,

        /** 没听懂 / 没法归类 */
        UNKNOWN
    }

    // ------------------------------------------------------------------ 识别

    private val EMOTION_CUES = listOf(
        "好累", "很累", "太累", "心累", "疲惫", "撑不住", "受不了", "崩溃", "想哭",
        "好烦", "很烦", "烦躁", "好焦虑", "焦虑", "压力大", "压力好大", "紧张", "慌",
        "难过", "难受", "低落", "心情不好", "不开心", "委屈", "孤独", "孤单", "迷茫",
        "失眠", "睡不着", "emo", "抑郁", "生气", "好气", "讨厌死了", "emo了",
        "开心", "好开心", "太开心", "高兴", "兴奋", "激动", "爽"
    )

    private val COMPARE_CUES = listOf(
        "有什么区别", "有啥区别", "区别是什么", "差别在哪", "差异在哪",
        "哪个好", "哪个好一点", "哪个更好", "选哪个好", "哪个强", "哪个划算",
        "相比", "对比", "和哪个", "比起来", "优缺点", "哪个更好一些"
    )

    private val CAUSE_CUES = listOf(
        "为什么", "为啥", "为何", "原因是什么", "是什么原因", "为什么会", "怎么会",
        "根源在哪", "什么导致的", "怎么会这样", "出于什么"
    )

    private val CHOICE_CUES = listOf(
        "该不该", "要不要", "值不值", "值不值得", "好不好", "行不行",
        "选哪个", "怎么选", "如何抉择", "该怎么选", "怎么选择", "选择哪个",
        "换不换", "买不买", "去不去", "要不要", "纠结", "犹豫", "犹豫要不要",
        "我到底", "还是放弃", "是走还是留"
    )

    private val METHOD_CUES = listOf(
        "怎么办", "怎么做", "怎么搞", "怎么弄", "咋办", "如何做", "怎么才能", "怎样才能",
        "该怎么", "怎么才好", "如何是好", "如何提升", "怎么提高", "怎么改善", "怎么练",
        "怎么办才好", "没头绪", "没有头绪", "无从下手", "不知从哪下手", "给个办法", "有什么办法"
    )

    private val OPINION_CUES = listOf(
        "你觉得", "你怎么看", "你怎么想", "什么看法", "有什么意见", "你的意见",
        "这样对吗", "这样好吗", "这样行吗", "靠谱吗", "值得吗", "是不是错的", "问题出在哪"
    )

    private val EXPLAIN_CUES = listOf(
        "讲讲", "讲一下", "给我讲", "解释一下", "解释下", "通俗讲", "通俗地讲",
        "简单说说", "普及一下", "怎么理解", "如何理解", "入门", "原理是什么",
        "是怎么回事", "指的是什么", "是指什么", "什么意思啊", "搞不懂", "听不懂", "看不懂"
    )

    private val FACT_CUES = listOf(
        "是什么", "什么是", "是谁", "哪一年", "什么时候", "多大", "多高", "多远",
        "多少人有", "谁发明", "谁写的", "谁的", "在哪有", "哪里有", "是哪国人"
    )

    /**
     * 判断这句话属于哪种思维模式。
     *
     * 顺序有意安排：情绪 > 比较 > 归因 > 选择 > 求法 > 看法 > 讲解 > 事实。
     * 例如「我不知道该怎么选」同时含选择词和求法词，按更具体的「选择」处理。
     */
    fun detect(raw: String): Mode {
        val text = FuzzyMatcher.normalize(raw)
        if (text.isEmpty()) return Mode.UNKNOWN

        if (EMOTION_CUES.any { text.contains(it) }) return Mode.EMOTION
        if (COMPARE_CUES.any { text.contains(it) }) return Mode.COMPARE
        if (CAUSE_CUES.any { text.contains(it) }) return Mode.CAUSE
        if (CHOICE_CUES.any { text.contains(it) }) return Mode.CHOICE
        if (METHOD_CUES.any { text.contains(it) }) return Mode.METHOD
        if (OPINION_CUES.any { text.contains(it) }) return Mode.OPINION
        if (EXPLAIN_CUES.any { text.contains(it) }) return Mode.EXPLAIN
        if (FACT_CUES.any { text.contains(it) }) return Mode.FACT

        // 没有任何提问线索 → 不接管。
        // 这种情况可能是错别字（「讲个校话」）、命令句、或者纯粹的听写噪声，
        // 交给引擎的「最接近的示例 → 你是不是想问…」去纠正，比给一套思维框架更准。
        return Mode.UNKNOWN
    }

    /** 某个模式下的全部模板（用于统计和小模型训练） */
    fun poolOf(mode: Mode): List<String> = when (mode) {
        Mode.EMOTION -> ThoughtBank.EMOTION
        Mode.COMPARE -> ThoughtBank.COMPARE
        Mode.CAUSE -> ThoughtBank.CAUSE
        Mode.CHOICE -> ThoughtBank.CHOICE
        Mode.METHOD -> ThoughtBank.METHOD
        Mode.OPINION -> ThoughtBank.OPINION
        Mode.EXPLAIN -> ThoughtBank.EXPLAIN
        Mode.FACT -> ThoughtBank.FACT
        Mode.UNKNOWN -> ThoughtBank.UNKNOWN
    }

    // ------------------------------------------------------------------ 组装

    /**
     * 生成一句「有思路」的回答。
     *
     * @param raw 用户这一句
     * @param context 会话上下文：提供轮数做轮换种子、避开最近说过的说法
     * @param withStyle 是否加开场语 / 收尾钩子（简洁风格下关掉）
     * @return 一句话；输入为空返回 null
     */
    fun craft(raw: String, context: DialogContext? = null, withStyle: Boolean = true): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val mode = detect(trimmed)
        val seed = (context?.turns ?: 0) * 31 + mode.ordinal * 7
        val recent: (String) -> Boolean = { context?.wasSaidRecently(it) ?: false }

        val body = pick(answersFor(trimmed, mode), seed, recent)
            .replace("{t}", DialogLogic.stripToTopic(trimmed))
        if (!withStyle) return body

        return decorate(body, mode, seed, recent)
    }

    /**
     * 情绪类回答要分方向：**低落**（「不高兴」「提不起劲」）和一般情绪宣泄
     * 用的模板不一样。判断依据交给 [Polarity.moodOf]（含否定式表达识别）。
     */
    private fun answersFor(raw: String, mode: Mode): List<String> {
        if (mode != Mode.EMOTION) return poolOf(mode)
        val text = FuzzyMatcher.normalize(raw)
        return if (Polarity.moodOf(text) < 0) ThoughtBank.DOWN else ThoughtBank.EMOTION
    }

    /**
     * 「完全没听懂」时的回复（多种说法轮换，替代以前那一句固定话术）。
     */
    fun unknownReply(context: DialogContext? = null): String {
        val seed = (context?.turns ?: 0) * 13 + 5
        val recent: (String) -> Boolean = { context?.wasSaidRecently(it) ?: false }
        return pick(ThoughtBank.UNKNOWN, seed, recent)
    }

    /** 引导用户教一句的提示；隔几轮才出现一次，免得像在推销功能 */
    private val TEACH_HINTS = listOf(
        "你也可以说「我教你」把答案告诉我，下次我就接得住这句了。",
        "想让我学会这句，说「我教你」，然后告诉我该怎么答。",
        "或者你说「我教你」，把这句该怎么答告诉我。",
        "",
        ""
    )

    /**
     * 最终兜底：换着花样说「这句我接不住」，并隔几轮提示一次可以教它。
     * 以前这里永远返回同一句话，是「每次回答都一样」最主要的来源。
     */
    fun fallbackReply(context: DialogContext? = null): String {
        val base = unknownReply(context)
        val seed = abs((context?.turns ?: 0) * 17 + 3)
        val hint = TEACH_HINTS[seed % TEACH_HINTS.size]
        return if (hint.isEmpty()) base else "$base$hint"
    }

    /** 加开场语和收尾钩子，让每次听到的措辞都不太一样 */
    private fun decorate(body: String, mode: Mode, seed: Int, recent: (String) -> Boolean): String {
        val trimmed = body.trim()
        val endsWithQuestion = trimmed.endsWith('？') || trimmed.endsWith('?') ||
            trimmed.endsWith('。') && mode == Mode.EMOTION

        val out = StringBuilder()
        // 情绪类自带共情开场，不再叠加，避免听着像套话
        if (mode != Mode.EMOTION && seed % 3 == 1) {
            out.append(pick(ThoughtBank.OPENERS, seed + 101, recent))
        }
        out.append(trimmed)
        // 已经有问句的模板不再挂钩子，防止一句里两个问句
        if (!endsWithQuestion && seed % 2 == 0) {
            out.append(pick(ThoughtBank.HOOKS, seed + 202, recent))
        }
        return out.toString()
    }

    /**
     * 按**闲聊轮数**确定性轮换地取一套说法（不给 context 时固定是第一套）。
     *
     * [craft] 用随机种子，适合只要「不一样」的场景；
     * 这个用轮数取模，适合要「可预测、第几轮说哪句完全确定」的场景（比如推理框架）。
     * 同时避开最近说过的那几条，连续问同一句不会一字不差。
     */
    fun rotate(mode: Mode, topic: String, context: DialogContext? = null): String {
        val pool = poolOf(mode)
        // 用总轮数而不是闲聊轮数：这类回答的意图不是 INTENT_CHAT，chatTurns 不会递增
        val offset = context?.turns ?: 0
        val recent: (String) -> Boolean = { context?.wasSaidRecently(it) ?: false }
        val ordered = pool.filter { !recent(it) }.ifEmpty { pool }
        return ordered[abs(offset) % ordered.size].replace("{t}", topic)
    }

    /** 统计：一共多少类提问、多少套说法（设置页展示用） */
    fun stats(): Pair<Int, Int> =
        Mode.values().size to Mode.values().sumOf { poolOf(it).size }

    /** 每种模式的典型问法：既是自测样本，也是小模型的训练材料 */
    private val SAMPLE_CUES: Map<Mode, List<String>> = mapOf(
        Mode.EMOTION to listOf(
            "我好累", "最近好烦", "我有点焦虑", "心情不太好", "压力好大", "我睡不着"
        ),
        Mode.COMPARE to listOf(
            "这两个有什么区别", "它们哪个好", "两者相比怎么选", "优缺点是什么", "差异在哪"
        ),
        Mode.CAUSE to listOf(
            "为什么会这样", "这是什么原因", "为什么会变成这样", "根源在哪", "怎么会这样"
        ),
        Mode.CHOICE to listOf(
            "我该不该做", "要不要去做", "我不知道该怎么选", "我很纠结", "选哪个好呢"
        ),
        Mode.METHOD to listOf(
            "这该怎么办", "我该怎么做", "完全没有头绪", "不知从哪下手", "怎么才能做好"
        ),
        Mode.OPINION to listOf(
            "你觉得呢", "你怎么看这件事", "这样对吗", "说说你的看法", "问题出在哪"
        ),
        Mode.EXPLAIN to listOf(
            "讲讲这是什么", "通俗解释一下", "该怎么理解它", "是怎么回事", "我看不懂"
        ),
        Mode.FACT to listOf(
            "这是什么东西", "它是什么", "这个是谁", "这是在什么时候", "这是什么原理"
        ),
        Mode.UNKNOWN to listOf(
            "我不知道该说什么", "说不上来", "有点说不清", "我也搞不明白", "这很难讲"
        )
    )

    /**
     * 给本地小模型的训练材料：典型问法 → 对应的思维式回答。
     *
     * 灌进去之后，模型不只是学会「说这种话」，还能通过 recall 检索
     * 把类似的话直接映射到一个有结构的答复上。代价是模型文件变大，
     * 但这正是用户允许的（"可以适当扩大占用空间"）。
     */
    fun trainingPairs(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (mode in Mode.values()) {
            val cues = SAMPLE_CUES[mode] ?: continue
            val pool = poolOf(mode)
            cues.forEachIndexed { index, cue ->
                val answer = pool[index % pool.size].replace("{t}", cue)
                result += cue to answer
            }
        }
        return result
    }

    /**
     * 从一组模板里挑一条：先过滤最近说过，再按种子轮换。
     *
     * 用 [Random]（Kotlin 标准库，JVM 单测里可用）而不是 java.util.Random，
     * 同一种子结果确定，方便测试复现。
     */
    private fun pick(options: List<String>, seed: Int, recent: (String) -> Boolean): String {
        if (options.isEmpty()) return ""
        if (options.size == 1) return options.first()
        val fresh = options.filter { !recent(it) }
        val pool = fresh.ifEmpty { options }
        val rnd = kotlin.random.Random(abs(seed))
        return pool[rnd.nextInt(pool.size)]
    }

    private fun abs(v: Int): Int = if (v == Int.MIN_VALUE) 0 else kotlin.math.abs(v)
}
