package com.example.voiceassistant.kws

/**
 * 唤醒词的匹配规则与全局配置（纯函数 + 常量，可单测）。
 *
 * ## 为什么不是真正的 KWS
 * 低功耗唤醒词（KWS）需要专门的引擎（讯飞离线唤醒、sherpa-onnx、Porcupine），
 * 它们能在 DSP 层面持续监听、功耗 <20mA。本项目没接这些 SDK，
 * 用的是**系统语音识别的循环监听**：说完一句 → 识别 → 看有没有唤醒词 → 再听。
 *
 * 代价必须说清楚：
 * - 耗电明显高于真 KWS（识别引擎一直在跑）；
 * - 部分机型离线识别包不全时会走网络，那就成了「联网才叫得醒」；
 * - 有 MID 级别的误唤醒，靠 [sensitivity] 收紧匹配能压一部分。
 *
 * 好处是零新增依赖、今天就能跑，接口（`kws/…`）与真 KWS 一致，将来换引擎不用改调用方。
 */
object WakeWord {

    /** 默认唤醒词 */
    const val DEFAULT = "小豆小豆"

    /** 会话窗口默认时长：命中唤醒词后这段时间内可以连续追问，不用再喊 */
    const val DEFAULT_SESSION_MS = 60_000L

    /**
     * 判断一句识别结果里有没有唤醒词。
     *
     * @param spoken 系统识别出来的一句话
     * @param wakeWord 用户设定的唤醒词
     * @param strict true = 必须完整包含；false = 允许只命中前两个字
     *
     * 之所以要「宽松」这一档：中文 ASR 对叠词的尾巴很不稳，
     * 「小豆小豆」经常只回来「小豆」或者「小豆小」，严格匹配就会叫不醒。
     */
    fun matches(spoken: String, wakeWord: String = DEFAULT, strict: Boolean = false): Boolean {
        val heard = normalize(spoken)
        val want = normalize(wakeWord)
        if (heard.isEmpty() || want.isEmpty()) return false
        if (heard.contains(want)) return true
        if (strict) return false
        // 宽松模式：唤醒词前两个字出现过就算（「小豆」→ 命中「小豆小豆」）
        val head = want.take(2)
        return head.length == 2 && heard.contains(head)
    }

    /** 把识别文本里的标点和空格去掉，避免「小豆，小豆。」这种断句导致匹配失败 */
    fun normalize(raw: String): String = raw
        .replace(Regex("[\\s，,。.、！!？?：:；;~～]"), "")
        .lowercase()

    /** 从一句话里剥掉唤醒词，剩下的才是真正的指令 */
    fun strip(raw: String, wakeWord: String = DEFAULT): String {
        var text = raw.trim()
        listOf(wakeWord, "小豆", "小豆小").forEach { text = text.replace(it, "") }
        return text.trim('，', ',', '。', ' ', '！', '!')
    }

    /**
     * 一轮识别之间的间隔（毫秒）。
     *
     * 这里没有「唤醒得分」可调：系统识别只给文字，不给置信度。
     * 能做的是把**循环的松紧**交给灵敏度——高灵敏时错误立刻重开，
     * 低灵敏时宁可多歇一会儿，少听几句也就少误唤醒。
     *
     * 纯函数，单测钉住：节奏变了用户是有感觉的。
     */
    fun quietRetryMs(sensitivity: Int): Long = when {
        sensitivity >= HIGH -> 350L
        sensitivity >= LOW -> 700L
        else -> 1_100L
    }

    /** 说完多久算「说完了」。越小越灵敏，但可能把一句话切断 */
    fun silenceMs(sensitivity: Int): Long = when {
        sensitivity >= HIGH -> 700L
        sensitivity >= LOW -> 900L
        else -> 1_100L
    }

    /**
     * 设置页三档 → 内部灵敏度数值。
     *
     * 0 低灵敏（必须说全唤醒词）/ 1 中（默认）/ 2 高灵敏（容易叫醒，也容易误唤醒）。
     * 存的是**档位**而不是数字：以后调档位对应关系，老用户的选择不会突然变味。
     */
    fun sensitivityOfLevel(level: Int): Int = when (level) {
        0 -> 20
        2 -> 85
        else -> 55
    }

    /** 三档的门槛：≥HIGH 算高灵敏，<LOW 算低灵敏 */
    const val HIGH = 70
    const val LOW = 30
}

/**
 * 唤醒状态机的两个阶段。
 *
 * ```
 * ① 休眠监听：只跑识别循环，等唤醒词
 *    ↓ 命中
 * ② 会话窗口：正常对话，期间不用再喊；静默超时 / 说完「退下」回到 ①
 * ```
 *
 * 只有两态，是因为三态以上在这个规模里纯属自找麻烦：
 * 「正在识别」「正在播报」这些都属于窗口内的细节，交给界面状态机管。
 */
enum class WakeStage {
    /** 休眠：等唤醒词 */
    SLEEPING,

    /** 会话窗口内：可以直接说话 */
    ACTIVE;

    fun next(woke: Boolean, windowExpired: Boolean): WakeStage = when {
        woke -> ACTIVE
        windowExpired -> SLEEPING
        else -> this
    }
}

/**
 * 唤醒模块的全局配置。
 *
 * 放单例而不是 Service 里，是因为 `kws/…` 服务要在服务启动前就能读到/改到这些值
 * （用户先设唤醒词、后开监听是常见顺序）。
 */
object WakeConfig {

    @Volatile
    var wakeWord: String = WakeWord.DEFAULT

    @Volatile
    var sessionMs: Long = WakeWord.DEFAULT_SESSION_MS

    /**
     * 灵敏度：0-100，越高越容易被叫醒，也越容易误唤醒。
     *
     * 这个数字不是「模型打分阈值」——我们用的是系统识别，拿不到分数。
     * 它决定的是**匹配规则和循环节奏**：
     * - 低（≤30）：必须完整说出唤醒词，每轮之间歇久一点；
     * - 中（31-69）：允许只说前两个字，节奏正常（默认）；
     * - 高（≥70）：允许只说前两个字，而且一旦「听到人说话」就立刻重开一轮，不等静音超时。
     */
    @Volatile
    var sensitivity: Int = 55

    /**
     * 严格匹配 = 必须完整说出唤醒词。
     *
     * 只有灵敏度很低（<30）才这么要求。**默认必须宽松**：
     * 中文 ASR 对叠词的尾巴很不稳，「小豆小豆」经常只回来「小豆」「小豆小」，
     * 严格匹配的表现就是「开关开着、怎么叫都没反应」——这正是用户反馈的「唤醒用不了」。
     * 唤醒不了的代价比偶尔误唤醒大得多：误唤醒只是应一声，叫不醒就是功能坏了。
     */
    val strict: Boolean get() = sensitivity < 30

    /**
     * 最近一次**听到**的内容（不管有没有命中唤醒词）。
     *
     * 这是排查「叫不醒」最关键的一行：如果这里一直没更新，说明引擎连人声都没拿到
     * （权限 / 中文包 / 麦克风被别的应用占着）；如果有内容但没命中，
     * 那是唤醒词或灵敏度的问题，方向完全不同。
     */
    @Volatile
    var lastHeard: String = ""

    @Volatile
    var lastHeardAt: Long = 0L

    @Volatile
    var enabled: Boolean = false

    /** 叫醒后是否应一声（短提示音）。默认开：不看屏幕也知道叫上了 */
    @Volatile
    var beepEnabled: Boolean = true

    /** 最近一次命中的时间戳，界面据此显示「刚被叫醒」 */
    @Volatile
    var lastWakeAt: Long = 0L

    /**
     * 对话界面还活着吗（onStart 置 true、onStop 置 false）。
     *
     * 唤醒服务用它决定「叫醒之后怎么办」：
     * - 界面活着 → 发进程内事件，界面自己开麦；
     * - 界面没了 → 事件没人收，只能发一条通知，等用户点进来。
     */
    @Volatile
    var uiAlive: Boolean = false

    /** 通知点击进来时带这一位：界面起来后直接开麦，不用再喊一次 */
    const val EXTRA_WAKE_OPEN = "wake_open"
}
