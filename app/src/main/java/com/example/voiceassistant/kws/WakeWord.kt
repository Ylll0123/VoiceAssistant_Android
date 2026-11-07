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

    /** 灵敏度：0-100，越高越容易被叫醒，也越容易误唤醒 */
    @Volatile
    var sensitivity: Int = 60

    /** 严格匹配 = 低灵敏度（≥ 75 视为「别老误唤醒」） */
    val strict: Boolean get() = sensitivity < 75

    @Volatile
    var enabled: Boolean = false

    /** 最近一次命中的时间戳，界面据此显示「刚被叫醒」 */
    @Volatile
    var lastWakeAt: Long = 0L
}
