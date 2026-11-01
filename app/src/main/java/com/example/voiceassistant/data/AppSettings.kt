package com.example.voiceassistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置的本地持久化（SharedPreferences，零新增依赖）。
 *
 * 之前设置页里改完语速，下次打开又回到 1.0，就是因为没有这一层。
 * 现在所有开关和滑块都会立刻写盘，启动时 [MainActivity] 再整体灌回引擎和语音控制器。
 *
 * 「待学习问题」也放在这里：它最多 40 条短文本，没必要单独建表。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 语音

    /** 是否出声播报 */
    var speakEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK, value).apply()

    /** 连续对话：播完自动再开麦克风 */
    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS, true)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS, value).apply()

    /**
     * 指定的语音合成引擎包名；空串表示用系统默认。
     *
     * 有些手机自带的 TTS 引擎会忽略语速设置（滑了没反应），
     * 换成 Google 语音服务后语速就正常了——设置页里可以直接切换。
     */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()

    // ---------------------------------------------------------------- 对话风格

    /** 回答详细程度：0 简洁 / 1 标准 / 2 详细 */
    var replyStyle: Int
        get() = prefs.getInt(KEY_STYLE, 1)
        set(value) = prefs.edit().putInt(KEY_STYLE, value.coerceIn(0, 2)).apply()

    /** 回答完是否主动反问一句，把话头递回去 */
    var proactiveFollowUp: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_UP, true)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_UP, value).apply()

    /**
     * 是否显示「理解过程」：回答出来之前，先用淡色小字展示本地是怎么拆分、
     * 识别和归纳这句话的，答案出现时整条移除。
     *
     * 关掉后回复更快（少了展示用的停顿），功能完全不受影响。
     */
    var showThinking: Boolean
        get() = prefs.getBoolean(KEY_THINKING, true)
        set(value) = prefs.edit().putBoolean(KEY_THINKING, value).apply()

    // ---------------------------------------------------------------- 自学习

    /** 是否开启自学习：相似说法自动迁移答案，并自动记住新说法 */
    var autoLearn: Boolean
        get() = prefs.getBoolean(KEY_AUTO_LEARN, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_LEARN, value).apply()

    /** 是否启用内置本地小模型（n-gram 生成兜底，完全离线） */
    var localModel: Boolean
        get() = prefs.getBoolean(KEY_LOCAL_MODEL, true)
        set(value) = prefs.edit().putBoolean(KEY_LOCAL_MODEL, value).apply()

    /**
     * 是否联网查询：实时天气走 Open-Meteo，答不上来的问题查维基百科。
     * 默认开启，但**联网失败不影响使用**——会退回本地内置内容。
     */
    var onlineSearch: Boolean
        get() = prefs.getBoolean(KEY_ONLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_ONLINE, value).apply()

    // ---------------------------------------------------------------- 云端大模型

    /** 是否启用云端大模型（AI 大脑） */
    var cloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD, value).apply()

    /**
     * 工作方式：0 不用云端 / 1 本地优先 / 2 AI 优先。
     * 存的是 [CloudBrain.CloudMode.code]，读出来时用 [CloudBrain.CloudMode.of] 还原。
     */
    var cloudMode: Int
        get() = prefs.getInt(KEY_CLOUD_MODE, 0)
        set(value) = prefs.edit().putInt(KEY_CLOUD_MODE, value.coerceIn(0, 2)).apply()

    /** 接口地址（Base URL），例如 https://api.deepseek.com */
    var cloudEndpoint: String
        get() = prefs.getString(KEY_CLOUD_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_URL, value.trim()).apply()

    /** API Key。本地保存，只在本机使用，不上传给除所选服务商以外的任何地方 */
    var cloudKey: String
        get() = prefs.getString(KEY_CLOUD_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_KEY, value.trim()).apply()

    /** 模型名，例如 deepseek-flash / qwen-plus */
    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value.trim()).apply()

    /** 人设（system prompt）：留空表示用 [CloudBrain.DEFAULT_SYSTEM_PROMPT] */
    var cloudPrompt: String
        get() = prefs.getString(KEY_CLOUD_PROMPT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_PROMPT, value).apply()

    /** 读成一份完整配置对象，交给 [CloudBrain] 使用 */
    fun cloudConfig(): CloudBrain.CloudConfig = CloudBrain.CloudConfig(
        enabled = cloudEnabled,
        mode = CloudBrain.CloudMode.of(cloudMode),
        baseUrl = cloudEndpoint,
        apiKey = cloudKey,
        model = cloudModel,
        // 人设保存过就写死在本机了，老版本那份长人设要靠 migratePrompt 换成极简版，
        // 否则改常量对已经保存过配置的用户完全不生效
        systemPrompt = CloudBrain.migratePrompt(
            cloudPrompt.ifBlank { CloudBrain.DEFAULT_SYSTEM_PROMPT }
        )
    )

    /** 保存一份完整配置对象（设置页点保存时调用） */
    fun saveCloud(config: CloudBrain.CloudConfig) {
        cloudEnabled = config.enabled
        cloudMode = config.mode.code
        cloudEndpoint = config.baseUrl
        cloudKey = config.apiKey
        cloudModel = config.model
        cloudPrompt = config.systemPrompt
    }

    /** 答不上来的话先收进这里，用户在记忆页一键教会 */
    var pendingQuestions: List<String>
        get() {
            val raw = prefs.getString(KEY_PENDING, "").orEmpty()
            if (raw.isEmpty()) return emptyList()
            return raw.split(SEP).filter { it.isNotBlank() }
        }
        set(value) {
            val raw = value.map { it.replace(SEP, " ") }.distinct()
                .take(MAX_PENDING).joinToString(SEP)
            prefs.edit().putString(KEY_PENDING, raw).apply()
        }

    fun addPending(question: String) {
        val cleaned = question.trim()
        if (cleaned.length < 2) return
        if (pendingQuestions.any { it == cleaned }) return
        pendingQuestions = listOf(cleaned) + pendingQuestions
    }

    fun removePending(question: String) {
        pendingQuestions = pendingQuestions.filter { it != question }
    }

    // ----------------------------------------------------------------

    /** 恢复出厂设置：只清设置，不动记忆库（记忆是用户资产） */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_assistant_settings"
        private const val KEY_SPEAK = "speak_enabled"
        private const val KEY_CONTINUOUS = "continuous"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_STYLE = "reply_style"
        private const val KEY_FOLLOW_UP = "proactive_follow_up"
        private const val KEY_THINKING = "show_thinking"
        private const val KEY_AUTO_LEARN = "auto_learn"
        private const val KEY_LOCAL_MODEL = "local_model"
        private const val KEY_ONLINE = "online_search"
        private const val KEY_PENDING = "pending_questions"
        private const val KEY_CLOUD = "cloud_enabled"
        private const val KEY_CLOUD_MODE = "cloud_mode"
        private const val KEY_CLOUD_URL = "cloud_endpoint"
        private const val KEY_CLOUD_KEY = "cloud_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_CLOUD_PROMPT = "cloud_prompt"

        private const val SEP = "␟"
        private const val MAX_PENDING = 40
    }
}
