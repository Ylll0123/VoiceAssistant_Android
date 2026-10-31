package com.example.voiceassistant.assistant

/**
 * 离线常识知识库（"听得懂"的第二层：问答知识）。
 *
 * ## 它解决什么问题
 * 规则表只覆盖"能算出来"的技能（几点、算数、单位换算……），闲聊语料只覆盖情绪话题。
 * 中间那一大片——**天气原理、穿衣、出行、健康、学习、生活窍门、安全常识、科学常识**——
 * 以前只能落到小模型的随机生成上，答出来"像话但没逻辑"。
 *
 * 这一层把这片空白补上：每条知识是一条**问答对**（一个问题多种问法、一个答案多种说法），
 * 用户问到就用相似度检索出最相关的一条，直接把答案给他——
 * 答案是人写的，所以天然逻辑严密、有前后因果。
 *
 * ## 匹配怎么做的
 * 三条通道，取最高分：
 * 1. **整句包含**：「为什么会下雨」里含「为什么会下雨」→ 满分通道；
 * 2. **整句相似**：问法略有出入（「下雨是怎么形成的」）→ 编辑距离相似度；
 * 3. **关键词命中**：只说了「台风」两个字也认——关键词是每条知识的**内容词**，
 *    「怎么 / 为什么」这类虚词不参与，避免瞎猜。
 *
 * 命中门槛 [MIN_SCORE]（0.76）：低于它宁可交给小模型或提示教学，也不硬答。
 *
 * ## 和小模型的分工
 * 知识库答**有标准答案**的问题（逻辑严密、可复用）；
 * 小模型答**没有标准答案**的闲聊兜底（靠统计生成）。
 * 知识库的所有问答对同时会灌进小模型做训练，所以"模型变大"和"答得准"是同一件事。
 *
 * 语料按主题拆成几个分组对象（[KbLife]、[KbStudy]、[KbTech]、[KbScience]），
 * 每个分组内部用 [kbGroup] 构造器写，新增知识只要往对应分组里加一行。
 */
object KnowledgeBase {

    /** 全部知识条目（分组懒加载，第一次用到才建） */
    val entries: List<KnowledgeQA> by lazy {
        KbLife.entries + KbStudy.entries + KbTech.entries + KbScience.entries
    }

    /** 知识条数，设置页展示用 */
    fun size(): Int = entries.size

    /** 收录的问法总数（同一条知识有多种说法），设置页展示用 */
    fun questionCount(): Int = entries.sumOf { it.questions.size }

    /** 收录的答法总数，设置页展示用 */
    fun answerCount(): Int = entries.sumOf { it.answers.size }

    /** 知识分类，界面展示用 */
    fun categories(): List<String> = entries.map { it.category }.distinct()

    /**
     * 检索一条知识。
     *
     * @param rawInput 用户输入（原始文本，内部归一化）
     * @param context 会话上下文，用于答案变体轮换（避免连着说同一句）
     * @return 命中返回 [KnowledgeHit]；没达到门槛返回 null，交给小模型 / 教学提示
     */
    fun match(rawInput: String, context: DialogContext? = null): KnowledgeHit? {
        val text = FuzzyMatcher.normalize(rawInput)
        if (text.length < 2) return null

        var best: KnowledgeQA? = null
        var bestScore = 0.0
        for (qa in entries) {
            val score = scoreOf(text, qa)
            if (score > bestScore) {
                bestScore = score
                best = qa
            }
        }
        val hit = best ?: return null
        if (bestScore < MIN_SCORE) return null

        val answer = pickAnswer(hit, context)
        return KnowledgeHit(hit, bestScore, answer)
    }

    /** 只看某条知识和输入有多像（输入应为归一化后的文本） */
    fun scoreOf(text: String, qa: KnowledgeQA): Double {
        var best = 0.0
        for (question in qa.questions) {
            val normalized = FuzzyMatcher.normalize(question)
            if (normalized.length < 2) continue
            val score = when {
                text == normalized -> 1.0
                // 包含关系要两边都够长才认：否则「为什么」会被「为什么会下雨」认领走
                minOf(text.length, normalized.length) >= MIN_CONTAIN_LENGTH &&
                    (text.contains(normalized) || normalized.contains(text)) -> CONTAIN_SCORE
                else -> {
                    val similarity = FuzzyMatcher.similarity(text, normalized)
                    if (similarity >= SIMILAR_FLOOR) (similarity * 0.95).coerceAtMost(0.92) else 0.0
                }
            }
            if (score > best) best = score
        }
        // 关键词兜底：只说「台风」两个字也能召回相关知识
        val keys = qa.keys.ifEmpty { qa.questions }
        val hits = keys.count { key ->
            val normalizedKey = FuzzyMatcher.normalize(key)
            normalizedKey.length >= 2 && text.contains(normalizedKey)
        }
        if (hits > 0) {
            // 只有「月亮」这种两字关键词命中、而且整句又不像提问时，不认领
            // （「我想去月亮湾看海豚」不是问月亮的，认领了就是答非所问）
            val longHit = keys.any {
                val normalizedKey = FuzzyMatcher.normalize(it)
                normalizedKey.length >= 3 && text.contains(normalizedKey)
            }
            val questioned = QUESTION_CUES.any { text.contains(it) }
            val score = when {
                hits >= 2 -> KEY_SCORE + 0.04
                longHit || questioned -> KEY_SCORE
                else -> KEY_SCORE - WEAK_PENALTY
            }
            if (score > best) best = score
        }
        return best
    }

    /** 挑一种说法：避开最近说过的，剩下的按轮数轮换 */
    fun pickAnswer(qa: KnowledgeQA, context: DialogContext?): String {
        val pool = qa.answers.filter { it.isNotBlank() }
        if (pool.isEmpty()) return ""
        if (pool.size == 1) return decorate(pool.first(), qa, context)
        if (context == null) return decorate(pool.first(), qa, context)

        val fresh = pool.filter { !context.wasSaidRecently(it) }
        val candidates = fresh.ifEmpty { pool }
        val body = candidates[context.turns % candidates.size]
        return decorate(body, qa, context)
    }

    /** 详细风格 + 开启主动追问时，末尾递一句相关的话头 */
    private fun decorate(body: String, qa: KnowledgeQA, context: DialogContext?): String {
        val followUp = qa.followUp
        if (followUp == null || context == null) return body
        if (!AssistantConfig.proactiveFollowUp) return body
        if (AssistantConfig.replyStyle == AssistantConfig.ReplyStyle.CONCISE) return body
        val asks = body.contains('？') || body.contains('?')
        return if (asks) body else "$body $followUp"
    }

    /**
     * 给小模型训练用的问答对：问法 → 答案。
     * 小模型拿它做相邻训练（[NgramModel.observeTurn]），
     * 于是"问句的尾巴"能接上"答案的开头"，续写出来的话才有因果。
     */
    fun trainingPairs(): List<Pair<String, String>> =
        entries.flatMap { qa ->
            val answer = qa.answers.first()
            qa.questions.map { it to answer }
        }

    /** 命中门槛：低于它就不认领，交给后面的兜底 */
    const val MIN_SCORE = 0.76

    private const val CONTAIN_SCORE = 0.90
    private const val KEY_SCORE = 0.78
    private const val SIMILAR_FLOOR = 0.74

    /** 走"包含"通道时，两边至少要这么长（太短会被别的问句误认领） */
    private const val MIN_CONTAIN_LENGTH = 4

    /** 只命中两字关键词、整句又不像提问时扣的分：扣完低于门槛，等于不认领 */
    private const val WEAK_PENALTY = 0.08

    /** 判断整句是不是在提问：命中两字关键词时用它决定要不要认领 */
    private val QUESTION_CUES = listOf(
        "吗", "呢", "怎么", "什么", "多少", "为什么", "为啥", "如何", "几", "哪",
        "是不是", "能不能", "要不要", "有哪些", "怎么办", "有哪些"
    )
}

/**
 * 一条常识知识。
 *
 * @param category 分类名，界面和检索统计用
 * @param questions 同一个问题的多种问法（口音 / 说法差异都写进来）
 * @param answers 多种答法，轮换着说，避免复读
 * @param keys 核心内容词（「下雨」「台风」），只说关键词时靠它召回；
 *             不写就退化成用问法本身做包含判断
 * @param followUp 可选的追问话头，详细风格下接在答案后面
 */
data class KnowledgeQA(
    val id: String,
    val category: String,
    val questions: List<String>,
    val answers: List<String>,
    val keys: List<String> = emptyList(),
    val followUp: String? = null
)

/** 一次知识库命中 */
data class KnowledgeHit(
    val qa: KnowledgeQA,
    val score: Double,
    val answer: String
)

/**
 * 知识分组构造器：省掉分类名和 id 的重复书写。
 *
 * ```
 * private fun weatherFacts(): List<KnowledgeQA> = kbGroup("天气常识") {
 *     add(
 *         questions = listOf("为什么会下雨", "雨是怎么来的"),
 *         answers = listOf("……"),
 *         keys = listOf("下雨")
 *     )
 * }
 * ```
 */
fun kbGroup(category: String, block: KnowledgeGroupBuilder.() -> Unit): List<KnowledgeQA> {
    val builder = KnowledgeGroupBuilder(category)
    builder.block()
    return builder.build()
}

class KnowledgeGroupBuilder(private val category: String) {

    private val items = mutableListOf<KnowledgeQA>()

    fun add(
        questions: List<String>,
        answers: List<String>,
        keys: List<String> = emptyList(),
        followUp: String? = null
    ) {
        items += KnowledgeQA(
            id = "${category}_${items.size}",
            category = category,
            questions = questions,
            answers = answers,
            keys = keys,
            followUp = followUp
        )
    }

    fun build(): List<KnowledgeQA> = items.toList()
}
