package com.example.voiceassistant.assistant

/**
 * 本地对话引擎（规则引擎）。
 *
 * 一次请求的处理流程：
 * 1. 归一化输入（[FuzzyMatcher.normalize]：去标点空格、同义词归一、剥离客套前缀）
 * 2. 如果上一轮是「你是不是想问…」的确认轮，先按用户的回答处理（是 / 不是 / 换话题）
 * 3. 剥掉纠错前缀（「不对，我是说…」）后重新理解
 * 4. 按顺序询问每条规则，第一条命中的采用其回复；规则可以要求「重讲一遍」（[RuleReply.rerun]）
 * 5. 命中后把意图和槽位写回 [context]，供下一轮的多轮追问使用
 * 6. 一条都没命中时，用相似度找最接近的示例说法，反过来问用户「你是不是想问…」
 *
 * ## 关于「听得清」
 * 语音识别结果不止一条：系统会按置信度给出若干候选。
 * [respond] 接受整组候选，逐个尝试理解，取第一条能命中的。
 * 这样首选结果识别歪了（「打开日立」）时，第二条「打开日历」还能救回来。
 */
class AssistantEngine(
    private val dialogContext: DialogContext = DialogContext(),
    private val rules: List<IntentRule> = AssistantRules.all()
) {

    /** 当前会话的上下文（上一轮意图 + 槽位），设置页里会用它做调试展示 */
    fun context(): DialogContext = dialogContext

    /** 只关心回复文本时的简化入口 */
    fun reply(rawInput: String): String = respond(rawInput).text

    /** 单条输入的入口（文字输入、技能页示例走这里） */
    fun respond(rawInput: String): AssistantResponse = respond(listOf(rawInput))

    /**
     * 多候选入口：语音识别会把几条可能结果一起送进来，按置信度从高到低排列。
     * 逐条尝试，取第一条能理解出意图的；全都理解不了才走兜底。
     */
    fun respond(candidates: List<String>): AssistantResponse {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return AssistantResponse(LISTENING, INTENT_EMPTY)

        // ① 上一轮问过「你是不是想问…」：先看这句是不是在回答那个问题
        val pending = dialogContext.consumePending()
        if (pending != null) {
            when (classifyConfirm(cleaned.first())) {
                Confirm.YES -> return respondInternal(pending, depth = 0)
                Confirm.NO -> return AssistantResponse(CLARIFY_REJECTED, INTENT_CLARIFY_REJECTED)
                Confirm.UNKNOWN -> Unit // 用户换了话题，当作普通输入继续
            }
        }

        // ② 纠错说法：「不对，我是说讲个笑话」→ 只用后半句重新理解
        val corrected = stripCorrection(cleaned.first())
        if (corrected != null) {
            return respondInternal(corrected, depth = 0)
        }

        // ③ 多候选择优：按识别置信度顺序，第一条能命中的就用它
        var firstMatched: Pair<String, RuleReply>? = null
        for (candidate in cleaned) {
            val reply = matchOnce(candidate)
            if (reply != null) {
                firstMatched = candidate to reply
                break
            }
        }

        if (firstMatched == null) {
            // ④ 谁都没命中：找最像的示例说法，反过来向用户确认（多轮的开端）
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
        return commit(heard, reply)
    }

    fun resetContext() = dialogContext.clear()

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

    /** 命中并写回上下文 */
    private fun commit(heard: String, reply: RuleReply): AssistantResponse {
        dialogContext.remember(reply.intent, reply.slots)
        dialogContext.rememberHeard(heard)
        return AssistantResponse(reply.text, reply.intent, heard)
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

        const val FALLBACK =
            "这句我还答不上来。可以试试：现在几点、今天几号、帮我算 12*8、讲个笑话，或者去「技能」页看看所有能做的事。"

        const val CLARIFY_REJECTED =
            "好，那我们换个说法。你可以说：现在几点、讲个笑话，或者点底部的「技能」页看全部功能。"

        const val INTENT_EMPTY = "empty"
        const val INTENT_FALLBACK = "fallback"
        const val INTENT_CLARIFY = DialogContext.INTENT_CLARIFY
        const val INTENT_CLARIFY_REJECTED = "clarify_rejected"

        /** 建议的相似度门槛：太低会瞎猜，太高又救不回来 */
        private const val SUGGEST_THRESHOLD = 0.72

        /** 「重讲一遍」最多嵌套两层，防止规则互相触发形成死循环 */
        private const val MAX_RERUN_DEPTH = 2

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
