package com.example.voiceassistant.assistant

/**
 * 一次对话请求。
 *
 * - [raw] 用户原始输入，回复里引用原话时用
 * - [text] 归一化后的输入（去标点空格、小写、剥离客套前缀），规则匹配用
 * - [context] 多轮上下文，处理「那后天呢」这类省略句时读它
 */
data class AssistantRequest(
    val raw: String,
    val text: String,
    val context: DialogContext
)

/**
 * 规则命中结果。
 *
 * @param text 回复内容；只要设置了 [rerun]，这段文字会被忽略
 * @param intent 意图标识，写回上下文供下一轮使用
 * @param slots 需要记住的槽位（例如上次计算的结果、猜数字的目标值）
 * @param rerun 把省略句补全成完整说法后重新理解一遍。
 *              多轮追问用它复用已有规则，避免在每个追问分支里重复实现逻辑，
 *              例如「那东京呢」→「东京现在几点」、「那 20 公里呢」→「20 公里是多少英里」
 */
data class RuleReply(
    val text: String,
    val intent: String,
    val slots: Map<String, String> = emptyMap(),
    val rerun: String? = null
)

/**
 * 一条对话规则（意图）。
 *
 * 约定：
 * - 返回非 null 表示这条规则命中，引擎直接采用这个回复；
 * - 返回 null 表示不归我管，交给下一条规则。
 *
 * 规则里请统一用 [AssistantRequest.text] 做匹配（已归一化），
 * 关键词判断走 [String.containsAny]，自动获得识别容错能力。
 */
interface IntentRule {

    /** 意图标识，同时用于写上下文和日志排查 */
    val intent: String

    fun reply(request: AssistantRequest): RuleReply?
}

/** 判断归一化文本里是否包含任意关键词（带模糊容错） */
internal fun String.containsAny(vararg keywords: String): Boolean =
    FuzzyMatcher.containsAnyFuzzy(this, *keywords)

/** 归一化文本里提取第一个整数 */
internal fun String.firstIntOrNull(): Int? =
    Regex("\\d{1,9}").find(this)?.value?.toIntOrNull()

/** 归一化文本里提取第一个小数 */
internal fun String.firstDoubleOrNull(): Double? =
    Regex("\\d+(\\.\\d+)?").find(this)?.value?.toDoubleOrNull()
