package com.example.voiceassistant

import android.app.Application
import android.content.Context
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.LocaleHelper

/**
 * 进程入口：把上次选的界面语言装进 Application 的 Context。
 *
 * 在 [attachBaseContext] 里做，而不是 onCreate：
 * 这时 Application 的资源还没被任何代码取用，换掉 Configuration 之后，
 * 后面所有从 Application 派生出去的 Context（服务、通知）也都会是选定语言。
 *
 * Activity 那边还有一层同名覆盖（见 VoiceAssistantActivity / LoginActivity）：
 * Activity 的 Context 是单独构造的，不继承 Application 的 Configuration。
 */
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base, AppSettings.readLanguage(base)))
    }
}
