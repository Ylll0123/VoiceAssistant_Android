package com.example.voiceassistant.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 界面语言的落地方式：在 `attachBaseContext` 里换掉 Context 的 Configuration。
 *
 * 为什么不用 `AppCompatDelegate.setApplicationLocales`：
 * 在 Android 13+ 它会转调系统的 LocaleManager，冷启动时调往往还没来得及生效
 * （实测 API 34 上 `cmd locale get-app-locales` 仍然是空，界面还是英文）。
 * 自己包一层 Configuration 是同步的、确定的——Activity 一构造出来资源就已经是选定语言。
 *
 * 代价：通知栏那种用 Application context 拼出来的文案仍跟系统语言走。
 * 对本地助手来说，通知里主要是用户自己设的提醒内容，这个折中可接受。
 */
object LocaleHelper {

    /** 语言标签 → Locale；中文固定 zh-CN，避免落到 zh-Hant 之类的分支 */
    fun localeOf(tag: String): Locale = when (tag) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        "ja" -> Locale.JAPAN
        "ko" -> Locale.KOREAN
        else -> Locale.SIMPLIFIED_CHINESE
    }

    /**
     * 把一个 Context 换成指定语言的版本。
     *
     * 顺手 `Locale.setDefault`：日期 / 数字格式化、`String.format` 的大小写规则
     * 都会读这个默认值，不改的话会出现「界面日文、日期还是英文格式」的割裂。
     */
    fun wrap(base: Context, tag: String): Context {
        val locale = localeOf(tag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
