package com.example.voiceassistant.assistant

import com.example.voiceassistant.memory.MemoryHit
import com.example.voiceassistant.memory.MemoryRepository
import com.example.voiceassistant.memory.MemorySearch

/**
 * 本地对话引擎。
 *
 * 一次请求的处理流程：
 * 1. 归一化输入（[FuzzyMatcher.normalize]：去标点空格、同义词归一、剥离客套前缀）
 * 2. 如果上一轮在等「教我说」的答案，这句就是答案，写进本地记忆库
 * 3. 显式教学指令（「记住：下班了 = 早点回家」）直接入库
 * 4. 如果上一轮是「你是不是想问…」的确认轮，先按用户的回答处理（是 / 不是 / 换话题）
 * 5. 剥掉纠错前缀（「不对，我是说…」）后重新理解
 * 6. **用户教过的话优先**：记忆库里命中度很高的「教过的说法」直接作答，语料和规则都不抢
 * 7. 按顺序询问每条规则，第一条命中的采用其回复；规则可以要求「重讲一遍」（[RuleReply.rerun]）
 *    末尾的 [ChatRule] 接日常闲聊：话题语料 + 上下文承接 + 多变体回复
 * 8. 规则都不命中时，**查本地记忆库**：以前教过或预置过的说法，用相似度检索作答
 * 9. 记忆里也没有，问**内置小模型**（[NgramModel]，字符级 n-gram，
 *    随对话持续学习）：它能生成一句相关的话就拿来用
 * 10. 模型也接不住，才提示「你可以教我」，并把这句话记下来等答案
 *
 * ## 越聊越会的原因
 * 规则是写死的，其余三层都是「长出来的」：
 * - 语料库（[DialogCorpus]）预置了几十个日常话题、每种话题多种说法，闲聊不重复；
 * - 记忆库每教一条就多一种问法，而且一条答案可以用「|」写几种变体，答的时候轮换；
 * - 小模型把每轮对话都当作训练样本，聊得越多，兜底生成越有样子。
 * 全部离线，不联网。
 */
class AssistantEngine(
    private val dialogContext: DialogContext = DialogContext(),
    private val rules: List<IntentRule> = AssistantRules.all(),
    private val memory: MemoryRepository? = null,
    private val model: NgramModel? = null
) {

    /** 正在等用户给答案的那句问题 */
    private var pendingTeachQuestion: String? = null

    /** 上一句没答上来的话，用户说「我教你」时用它当问题 */
    private var lastUnanswered: String? = null

    /**
     * 自学习钩子：这句话本地实在答不上来时通知调用方。
     * Activity 收到后把它收进「待学习」列表，用户在记忆页可以一键教会。
     */
    var onUnanswered: ((String) -> Unit)? = null

    /** 当前会话的上下文（上一轮意图 + 槽位），设置页里会用它做调试展示 */
    fun context(): DialogContext = dialogContext

    /** 记忆库，界面（记忆页 / 设置页）读它 */
    fun memoryRepository(): MemoryRepository? = memory

    /** 本地小模型，设置页读它展示学习进度 */
    fun localModel(): NgramModel? = model

    /** 只关心回复文本时的简化入口 */
    fun reply(rawInput: String): String = respond(rawInput).text

    /** 单条输入的入口（文字输入、技能页示例走这里） */
    fun respond(rawInput: String): AssistantResponse = respond(listOf(rawInput))

    /**
     * 多候选入口：语音识别会把几条可能结果一起送进来，按置信度从高到低排列。
     * 逐条尝试，取第一条能理解出意图的；全都理解不了才走兜底。
     */
    fun respond(candidates: List<String>): AssistantResponse {
        val response = handle(candidates)
        learnTurn(candidates.firstOrNull().orEmpty(), response)
        return response
    }

    /** 把每一轮对话喂给本地小模型：聊得越多，兜底生成越有样子 */
    private fun learnTurn(input: String, response: AssistantResponse) {
        val m = model ?: return
        if (input.isBlank()) return
        // 成对的问答才学得到"问句尾接答案头"，系统的提示语不当训练材料
        if (response.intent !in UNLEARNABLE_INTENTS) {
            m.observePair(input, response.text)
        } else {
            m.observe(input)
        }
    }

    private fun handle(candidates: List<String>): AssistantResponse {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return AssistantResponse(LISTENING, INTENT_EMPTY)

        val first = cleaned.first()

        // ⓪ 正在等「这句话该怎么回答」：用户这句就是答案
        //    「算了」任何时候都能取消教学，不会掉进兜底
        if (TeachCommand.isCancel(first)) {
            val wasWaiting = pendingTeachQuestion != null
            pendingTeachQuestion = null
            lastUnanswered = null
            return AssistantResponse(
                if (wasWaiting) "好，那先不教了。" else "好，那就先这样。",
                INTENT_TEACH_CANCEL
            )
        }

        pendingTeachQuestion?.let { question ->
            return teach(question, first)
        }

        // ① 显式教学：「记住：下班了 = 早点回家」
        TeachCommand.parse(first)?.let { command ->
            return teach(command.question, command.answer)
        }

        // ② 只说了「我教你」：拿上一句没答上来的话当问题，追问答案
        if (memory != null && TeachCommand.isBareRequest(first)) {
            val target = lastUnanswered
            return if (target != null) {
                pendingTeachQuestion = target
                AssistantResponse(
                    "好，那我问你「$target」的时候，该怎么回答？直接说答案就行。",
                    INTENT_TEACH_PROMPT,
                    first
                )
            } else {
                AssistantResponse(
                    "好呀。你可以直接说「记住：下班了 = 早点回家」这种格式，我一学就会。",
                    INTENT_TEACH_PROMPT
                )
            }
        }

        // ③ 上一轮问过「你是不是想问…」：先看这句是不是在回答那个问题
        val pending = dialogContext.consumePending()
        if (pending != null) {
            when (classifyConfirm(cleaned.first())) {
                Confirm.YES -> return respondInternal(pending, depth = 0)
                Confirm.NO -> return AssistantResponse(CLARIFY_REJECTED, INTENT_CLARIFY_REJECTED)
                Confirm.UNKNOWN -> Unit // 用户换了话题，当作普通输入继续
            }
        }

        // ④ 纠错说法：「不对，我是说讲个笑话」→ 只用后半句重新理解
        val corrected = stripCorrection(cleaned.first())
        if (corrected != null) {
            return respondInternal(corrected, depth = 0)
        }

        // ⑤ 多候选择优：按识别置信度顺序，第一条能命中的就用它
        var firstMatched: Pair<String, RuleReply>? = null
        for (candidate in cleaned) {
            val reply = matchOnce(candidate)
            if (reply != null) {
                // 闲聊语料的优先级低于用户教过的话：
                // 教过的说法就算同时落进某个闲聊话题，也按教的内容答
                if (reply.intent == DialogContext.INTENT_CHAT) {
                    val taughtHit = taughtHitFor(candidate)
                    if (taughtHit != null) {
                        memory?.bumpHit(taughtHit.entry.id)
                        autoLearn(candidate, taughtHit)
                        return commit(
                            taughtHit.entry.question,
                            RuleReply(memoryAnswer(taughtHit.entry.answer), INTENT_MEMORY)
                        )
                    }
                }
                firstMatched = candidate to reply
                break
            }
        }

        if (firstMatched == null) {
            // ⑥ 规则答不上来：查本地记忆库（教过的 + 预置的）
            val hit = memory?.let { MemorySearch.best(cleaned.first(), it.entries()) }
            if (hit != null) {
                memory.bumpHit(hit.entry.id)
                // 自学习：说法相似但不完全一样 → 记住这个新说法，下回直接会答
                autoLearn(cleaned.first(), hit)
                return commit(hit.entry.question, RuleReply(memoryAnswer(hit.entry.answer), INTENT_MEMORY))
            }

            // ⑦ 记忆里也没有：查内置常识知识库（几百条有标准答案的日常问题）
            val knowledge = KnowledgeBase.match(cleaned.first(), dialogContext)
            if (knowledge != null) {
                val continued = dialogContext.isSubject(knowledge.qa.category) &&
                    dialogContext.hasIntent(INTENT_KNOWLEDGE)
                dialogContext.rememberSubject(knowledge.qa.category)
                val text = if (continued && wantsBridge()) {
                    bridgeWord(dialogContext.turns) + knowledge.answer
                } else {
                    knowledge.answer
                }
                return commit(cleaned.first(), RuleReply(text, INTENT_KNOWLEDGE))
            }

            // ⑧ 知识库也没有：问内置小模型能不能生成一句相关的话
            if (AssistantConfig.localModel && model != null) {
                val generated = model.generate(cleaned.first())
                if (generated != null) {
                    // 模型的回答只是「像那么回事」，允许用户接着说「我教你」纠正
                    lastUnanswered = cleaned.first()
                    return commit(cleaned.first(), RuleReply(generated, INTENT_LOCAL_MODEL))
                }
            }

            // ⑧ 模型也接不住：记下这句话，等用户决定要不要教
            lastUnanswered = cleaned.first()
            onUnanswered?.invoke(cleaned.first())

            // ⑧ 找最像的示例说法，反过来向用户确认（多轮的开端）
            val suggestion = suggest(cleaned.first())
            return if (suggestion != null) {
                dialogContext.setPending(suggestion)
                AssistantResponse(
                    "这句我没太听懂。你是不是想问「$suggestion」？说「是」我就执行，说「不是」我们换个说法。",
                    INTENT_CLARIFY
                )
            } else {
                AssistantResponse(FALLBACK, INTENT_FALLBACK)
            }
        }

        val (heard, reply) = firstMatched
        lastUnanswered = null
        return commit(heard, reply)
    }

    fun resetContext() {
        dialogContext.clear()
        pendingTeachQuestion = null
        lastUnanswered = null
    }

    /** 把一条问答写进本地记忆库 */
    private fun teach(question: String, answer: String): AssistantResponse {
        val repo = memory ?: return AssistantResponse(FALLBACK, INTENT_FALLBACK)
        val entry = repo.remember(question, answer, MemoryRepository.SOURCE_TAUGHT)
        pendingTeachQuestion = null
        lastUnanswered = null
        // 教的答案也是小模型的训练材料
        model?.observe(answer)
        val multi = if (entry.answer.contains('|') || entry.answer.contains('｜')) {
            "这一句我准备了 ${entry.answer.split('|', '｜').size} 种说法，会轮换着讲。"
        } else {
            "想让我换着说，可以用「|」隔开多教几种回答。"
        }
        return AssistantResponse(
            "记住了。以后你说「${entry.question}」，我就回答：${entry.answer}$multi",
            INTENT_TAUGHT
        )
    }

    // ------------------------------------------------------------------ 内部实现

    /** 单条输入走一遍规则表；返回 null 表示一条都没命中 */
    private fun matchOnce(raw: String, depth: Int = 0): RuleReply? {
        val normalized = FuzzyMatcher.normalize(raw)
        if (normalized.isEmpty()) return null

        val request = AssistantRequest(raw, normalized, dialogContext)
        for (rule in rules) {
            val reply = rule.reply(request) ?: continue
            // 规则可以要求「换一种说法重讲一遍」，比如「那东京呢」→「东京现在几点」
            val rerun = reply.rerun
            if (rerun != null && depth < MAX_RERUN_DEPTH) {
                return matchOnce(rerun, depth + 1) ?: reply.copy(rerun = null)
            }
            return reply
        }
        return null
    }

    /** 命中并写回上下文；同时把回复记下来，下一轮挑变体时避重 */
    private fun commit(heard: String, reply: RuleReply): AssistantResponse {
        dialogContext.remember(reply.intent, reply.slots)
        dialogContext.rememberHeard(heard)
        dialogContext.markReply(reply.text)
        return AssistantResponse(reply.text, reply.intent, heard)
    }

    /**
     * 记忆答案支持多种变体：教的时候用「|」隔开（「记住：下班了 = 早点回家|路上慢点」），
     * 答的时候轮换着说，避开最近刚说过的。
     */
    private fun memoryAnswer(raw: String): String {
        val variants = raw.split('|', '｜').map { it.trim() }.filter { it.isNotEmpty() }
        if (variants.size <= 1) return raw
        val fresh = variants.filter { !dialogContext.wasSaidRecently(it) }
        val pool = fresh.ifEmpty { variants }
        return pool[dialogContext.turns % pool.size]
    }

    /**
     * 自学习：把「相似的新说法」自动变成新记忆。
     *
     * 场景：用户教过「下班了 → 早点回家」，后来随口说「我下班了啊」。
     * 这句在记忆里没有，但和教过的那条很像，于是：
     * 1. 用教过的答案回答（保证答得对）；
     * 2. 把「我下班了啊」这条**新说法**也写进记忆库（source = learned）。
     *
     * 之后再说这句就不需要相似匹配了，是直接命中。
     * 只从用户教过（或已经自学过）的条目扩散，避免把预置语料也复制成一大堆记忆。
     */
    private fun autoLearn(heard: String, hit: MemoryHit) {
        if (!AssistantConfig.autoLearn) return
        // 完全一样的说法没什么可学的，太不像的怕学错
        if (hit.score >= EXACT_SCORE || hit.score < AUTO_LEARN_SCORE) return
        if (!(hit.entry.isTaught || hit.entry.isLearned)) return

        val repo = memory ?: return
        val normalized = FuzzyMatcher.normalize(heard)
        if (normalized.length < MIN_LEARN_LENGTH) return
        if (repo.entries().any { it.normalized == normalized }) return

        repo.remember(heard, hit.entry.answer, MemoryRepository.SOURCE_LEARNED)
    }

    /** 简洁风格不追加承接语 */
    private fun wantsBridge(): Boolean =
        AssistantConfig.replyStyle != AssistantConfig.ReplyStyle.CONCISE

    /** 连续聊同一主题时的承接语，按轮数轮换，避免每次都一样 */
    private fun bridgeWord(turns: Int): String =
        BRIDGE_WORDS[turns % BRIDGE_WORDS.size]

    /** 在「教过的」记忆里找最像的一条；找不到或没有记忆库返回 null */
    private fun taughtHitFor(text: String): MemoryHit? {
        val repo = memory ?: return null
        val taught = repo.entries().filter { it.isTaught }
        if (taught.isEmpty()) return null
        return MemorySearch.best(text, taught)
    }

    /** 处理一条（可能来自重讲或确认的）输入 */
    private fun respondInternal(raw: String, depth: Int): AssistantResponse {
        val reply = matchOnce(raw, depth)
            ?: return AssistantResponse(FALLBACK, INTENT_FALLBACK)
        return commit(raw, reply)
    }

    /** 在全部示例说法里找最接近的一句（不能是用户自己说的那句，否则等于复读） */
    private fun suggest(input: String): String? {
        val normalized = FuzzyMatcher.normalize(input)
        if (normalized.length < 3) return null
        val candidates = allExamples.filter { FuzzyMatcher.normalize(it) != normalized }
        return FuzzyMatcher.nearest(normalized, candidates, SUGGEST_THRESHOLD)
    }

    private val allExamples: List<String> by lazy {
        SkillCatalog.skills.flatMap { it.examples }
    }

    /** 判断用户对「你是不是想问…」的回答 */
    private fun classifyConfirm(input: String): Confirm {
        val text = FuzzyMatcher.normalize(input)
        if (text.length > 6) return Confirm.UNKNOWN
        val yes = listOf("是", "是的", "对", "对的", "嗯", "好", "可以", "没错", "就是", "行", "第一个", "1", "yes")
        val no = listOf("不", "不是", "不对", "错", "不要", "没有", "换一个", "第二个", "2", "no")
        // 先判否定：「不是」里含「是」，不先判会被当成肯定
        return when {
            no.any { text == it } -> Confirm.NO
            yes.any { text == it } -> Confirm.YES
            else -> Confirm.UNKNOWN
        }
    }

    /** 剥掉「不对 / 我是说 / 我说的是」这类纠错前缀，返回后半句 */
    private fun stripCorrection(input: String): String? {
        var text = input
        var stripped = false
        repeat(3) {
            val match = CORRECTION_PREFIX.find(text) ?: return@repeat
            val rest = text.substring(match.range.last + 1).trim().trim('，', ',', '。', ' ')
            if (rest.isEmpty()) return@repeat
            text = rest
            stripped = true
        }
        return if (stripped) text else null
    }

    private enum class Confirm { YES, NO, UNKNOWN }

    companion object {
        const val LISTENING = "我在听，你说吧。"

        /** 这些意图的回复是系统提示语，不作为小模型的训练材料 */
        private val UNLEARNABLE_INTENTS = setOf(
            INTENT_EMPTY, INTENT_FALLBACK, INTENT_CLARIFY, INTENT_CLARIFY_REJECTED,
            INTENT_TEACH_PROMPT, INTENT_TAUGHT, INTENT_TEACH_CANCEL
        )

        const val FALLBACK =
            "这句我还答不上来。想让我记住的话，说「我教你」，然后把答案告诉我；" +
                "也可以试试：现在几点、帮我算 12*8、讲个笑话。"

        const val CLARIFY_REJECTED =
            "好，那我们换个说法。你可以说：现在几点、讲个笑话，或者点底部的「技能」页看全部功能。"

        const val INTENT_EMPTY = "empty"
        const val INTENT_FALLBACK = "fallback"
        const val INTENT_CLARIFY = DialogContext.INTENT_CLARIFY
        const val INTENT_CLARIFY_REJECTED = "clarify_rejected"

        /** 从本地记忆库答出来的 */
        const val INTENT_MEMORY = "memory"

        /** 刚学会一条 */
        const val INTENT_TAUGHT = "taught"

        /** 正在等用户给答案 */
        const val INTENT_TEACH_PROMPT = "teach_prompt"

        /** 用户取消教学 */
        const val INTENT_TEACH_CANCEL = "teach_cancel"

        /** 回答来自内置小模型的生成 */
        const val INTENT_LOCAL_MODEL = "local_model"

        /** 回答来自内置常识知识库 */
        const val INTENT_KNOWLEDGE = "knowledge"

        /** 建议的相似度门槛：太低会瞎猜，太高又救不回来 */
        private const val SUGGEST_THRESHOLD = 0.72

        /** 记忆里已有完全一样的说法时不必再学一遍 */
        private const val EXACT_SCORE = 0.995

        /** 自学习的相似度门槛：比记忆检索门槛 0.72 再高一点，宁可少学也别学错 */
        private const val AUTO_LEARN_SCORE = 0.76

        /** 太短的说法（两三个字）语义太泛，不自动记 */
        private const val MIN_LEARN_LENGTH = 4

        /** 「重讲一遍」最多嵌套两层，防止规则互相触发形成死循环 */
        private const val MAX_RERUN_DEPTH = 2

        /** 连续聊同一主题时的承接语：让上下两轮读起来是连着的 */
        private val BRIDGE_WORDS = listOf(
            "接着说，", "顺着这个话题，", "再往下说，", "接着刚才的，"
        )

        /** 纠错前缀：「不对，我是说…」 */
        private val CORRECTION_PREFIX =
            Regex("^(不对|不是|错了|说错了|我说的是|我是说|我指的是|重新说)[，,]?")
    }
}

/**
 * 一次完整响应。
 *
 * @param text 要展示/播报的回复
 * @param intent 命中的意图
 * @param heard 实际采用的说法；多候选时可能不是识别结果的第一条，
 *              界面可以用它提示「我听到的是：…」
 */
data class AssistantResponse(
    val text: String,
    val intent: String,
    val heard: String? = null
)
