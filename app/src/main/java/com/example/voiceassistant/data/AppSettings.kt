package com.example.voiceassistant.data

import android.content.Context
import android.content.SharedPreferences
import com.example.voiceassistant.handler.llm.CloudLlmHandler

/**
 * 应用设置的本地持久化（SharedPreferences，零新增依赖）。
 *
 * 只留真正会变的几项：出声、语音引擎、对话模式、界面语言、云端模型配置。
 * 回答方式、唤醒词那一整套已经删掉——固定成默认行为，不再让用户选，
 * 少一个开关就少一处「设了没反应」。
 */
class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 语音

    /** 是否出声播报 */
    var speakEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEAK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEAK, value).apply()

    /**
     * 指定的语音合成引擎包名；空串表示用系统默认。
     *
     * 有些手机自带的 TTS 引擎念不了中文，[com.example.voiceassistant.service.TtsManager]
     * 会自己去找一台能念的，这一项只是「用户手动指定过」时的偏好。
     */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()

    // ---------------------------------------------------------------- 对话

    /**
     * 对话模式：qa（问答）/ msg（消息）/ study（学习）。
     *
     * 落盘是为了下次打开还停在同一个模式——用户切到消息模式往往是为了
     * 连着回几个人，退出应用再进来还是这个模式才顺手。
     */
    var assistantMode: String
        get() = prefs.getString(KEY_MODE, "qa").orEmpty().ifEmpty { "qa" }
        set(value) = prefs.edit().putString(KEY_MODE, value).apply()

    // ---------------------------------------------------------------- 界面语言

    /**
     * 界面语言标签：zh / en / ja / ko。
     *
     * 语言是在 [com.example.voiceassistant.data.LocaleHelper] 里套 Configuration 实现的，
     * 这里存一份是为了冷启动第一帧就按它重建 Context。
     */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANGUAGE_ZH).orEmpty()
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    // ---------------------------------------------------------------- 云端大模型

    /**
     * 是否启用云端大模型（AI 大脑）。
     *
     * 默认开启：新装用户打开就是「已接入 GLM-4-Flash-250414」，只差填一个 API Key。
     * 没有 Key 时 [CloudLlmHandler.CloudConfig.isReady] 为 false，
     * 路由根本不会去调云端，所以这个默认值不会让离线可用性变差、也不会偷偷花钱。
     */
    var cloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD, true)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD, value).apply()

    /**
     * 工作方式：0 不用云端 / 1 本地优先 / 2 AI 优先。
     * 存的是 [CloudLlmHandler.CloudMode.code]，读出来时用 [CloudLlmHandler.CloudMode.of] 还原。
     *
     * 默认「本地优先」：能本地答的绝不花钱问云端，答不好才交给免费模型。
     */
    var cloudMode: Int
        get() = prefs.getInt(KEY_CLOUD_MODE, CloudLlmHandler.CloudMode.LOCAL_FIRST.code)
        set(value) = prefs.edit().putInt(KEY_CLOUD_MODE, value.coerceIn(0, 2)).apply()

    /** 接口地址（Base URL），默认是智谱的 OpenAI 兼容地址 */
    var cloudEndpoint: String
        get() = prefs.getString(KEY_CLOUD_URL, CloudLlmHandler.DEFAULT_PROVIDER.baseUrl).orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_URL, value.trim()).apply()

    /**
     * API Key。本地保存，只在本机使用，不上传给除所选服务商以外的任何地方。
     *
     * 默认值就是包里内置的那把 Key（见 [CloudLlmHandler.DEFAULT_API_KEY]）：
     * 装上就能用，不用先注册；用户在「关于 → AI 模型」里改过之后，
     * 存的是他自己的，内置那把不再参与。
     */
    var cloudKey: String
        get() = prefs.getString(KEY_CLOUD_KEY, CloudLlmHandler.DEFAULT_API_KEY).orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_KEY, value.trim()).apply()

    /** 模型名，默认 glm-4-flash-250414（智谱免费模型） */
    var cloudModel: String
        get() = prefs.getString(KEY_CLOUD_MODEL, CloudLlmHandler.DEFAULT_PROVIDER.model).orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_MODEL, value.trim()).apply()

    /** 人设（system prompt）：留空表示用 [CloudLlmHandler.DEFAULT_SYSTEM_PROMPT] */
    var cloudPrompt: String
        get() = prefs.getString(KEY_CLOUD_PROMPT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CLOUD_PROMPT, value).apply()

    /** 读成一份完整配置对象，交给 [CloudLlmHandler] 使用 */
    fun cloudConfig(): CloudLlmHandler.CloudConfig = CloudLlmHandler.CloudConfig(
        enabled = cloudEnabled,
        mode = CloudLlmHandler.CloudMode.of(cloudMode),
        baseUrl = cloudEndpoint,
        apiKey = cloudKey,
        model = cloudModel,
        // 人设保存过就写死在本机了，历史版本留下的默认人设要靠 migratePrompt 换成新版，
        // 否则改常量对已经保存过配置的用户完全不生效
        systemPrompt = CloudLlmHandler.migratePrompt(
            cloudPrompt.ifBlank { CloudLlmHandler.DEFAULT_SYSTEM_PROMPT }
        )
    )

    /** 保存一份完整配置对象（设置页点保存时调用） */
    fun saveCloud(config: CloudLlmHandler.CloudConfig) {
        cloudEnabled = config.enabled
        cloudMode = config.mode.code
        cloudEndpoint = config.baseUrl
        cloudKey = config.apiKey
        cloudModel = config.model
        cloudPrompt = config.systemPrompt
    }

    // ----------------------------------------------------------------

    /** 恢复出厂设置：清掉本页设置（聊天记录与提醒由各自的存储管理） */
    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_assistant_settings"
        private const val KEY_SPEAK = "speak_enabled"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_MODE = "assistant_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CLOUD = "cloud_enabled"
        private const val KEY_CLOUD_MODE = "cloud_mode"
        private const val KEY_CLOUD_URL = "cloud_endpoint"
        private const val KEY_CLOUD_KEY = "cloud_key"
        private const val KEY_CLOUD_MODEL = "cloud_model"
        private const val KEY_CLOUD_PROMPT = "cloud_prompt"

        /** 默认界面语言：中文 */
        const val LANGUAGE_ZH = "zh"

        /**
         * 只读语言标签。
         *
         * 单独一个静态入口，是因为 [android.app.Application.attachBaseContext] 里
         * 还拿不到 `applicationContext`，没法 new 一个 AppSettings 出来。
         */
        fun readLanguage(context: Context): String =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LANGUAGE_ZH)
                .orEmpty()
                .ifEmpty { LANGUAGE_ZH }

        /** 设置页提供的四种语言（标签 → 用各自语言写的名字） */
        val LANGUAGES: List<Pair<String, String>> = listOf(
            LANGUAGE_ZH to "中文",
            "en" to "English",
            "ja" to "日本語",
            "ko" to "한국어"
        )
    }
}
