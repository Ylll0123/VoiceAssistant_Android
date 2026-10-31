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
        private const val KEY_AUTO_LEARN = "auto_learn"
        private const val KEY_LOCAL_MODEL = "local_model"
        private const val KEY_ONLINE = "online_search"
        private const val KEY_PENDING = "pending_questions"

        private const val SEP = "␟"
        private const val MAX_PENDING = 40
    }
}
