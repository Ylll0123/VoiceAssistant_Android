package com.example.voiceassistant.ui

import android.text.InputType
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.voiceassistant.R
import com.example.voiceassistant.data.AccountStore
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.reminder.Reminders
import com.example.voiceassistant.service.TtsManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 设置页：**只留真正会变的东西**。
 *
 * 语音播报、语言、账号、关于——一屏放得下。
 *
 * 回答方式、语音唤醒、灵敏度、提示音、唤醒引擎、后台保活、无障碍入口全部删掉了：
 * 它们要么已经固定成默认行为（回答方式），要么整块功能不存在了（唤醒），
 * 要么本来就该由「用的时候再提示」来解决（无障碍）。留着只是让人多点几下。
 */
class SettingsFragment : Fragment() {

    private lateinit var switchSpeak: MaterialSwitch
    private lateinit var tvLanguageValue: TextView
    private lateinit var tvModelValue: TextView
    private lateinit var tvAccount: TextView
    private lateinit var tvVersion: TextView

    private val host: VoiceAssistantActivity
        get() = requireActivity() as VoiceAssistantActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchSpeak = view.findViewById(R.id.switchSpeak)
        tvLanguageValue = view.findViewById(R.id.tvLanguageValue)
        tvModelValue = view.findViewById(R.id.tvModelValue)
        tvAccount = view.findViewById(R.id.tvAccount)
        tvVersion = view.findViewById(R.id.tvVersion)

        bindSpeak()
        bindLanguage()
        bindModel()
        bindActions()
        refresh()
    }

    // ------------------------------------------------------------------ 播报

    /**
     * 播报开关。
     *
     * 打开时当场念一句：这个开关最怕「打开了却没声音」，用户还不知道问题出在哪。
     * 念不出来就按真实原因给一句能落地的引导（音量 / 语音包 / 没引擎）。
     */
    private fun bindSpeak() {
        switchSpeak.isChecked = host.speakEnabled
        switchSpeak.setOnCheckedChangeListener { _, checked ->
            if (checked == host.speakEnabled) return@setOnCheckedChangeListener
            host.setSpeakEnabled(checked)
            if (!checked) return@setOnCheckedChangeListener

            host.tts.preview(getString(R.string.speech_preview))
            switchSpeak.postDelayed({ explainSpeechProblem() }, SPEAK_PREVIEW_MS)
        }
    }

    /** 试听没出声时，把原因说清楚并给一个「去处理」的按钮 */
    private fun explainSpeechProblem() {
        val problem = host.tts.status
        if (problem == TtsManager.Status.READY || !isAdded) return

        val message = when (problem) {
            TtsManager.Status.MUTED -> getString(R.string.speech_muted)
            TtsManager.Status.NO_CHINESE -> getString(R.string.speech_no_chinese)
            else -> getString(R.string.speech_no_engine)
        }

        requireContext().appDialog()
            .setTitle(R.string.speech_problem_title)
            .setMessage(message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.speech_problem_fix) { _, _ ->
                if (problem == TtsManager.Status.MUTED) {
                    host.tts.raiseMediaVolume()
                    toast(getString(R.string.speech_volume_raised))
                } else if (!host.tts.openSystemSettings()) {
                    toast(getString(R.string.speech_settings_failed))
                }
            }
            .showWhite()
    }

    // ------------------------------------------------------------------ 语言

    private fun bindLanguage() {
        tvLanguageValue.text = languageLabel(host.appSettings().language)
        requireView().findViewById<View>(R.id.rowLanguage).setOnClickListener {
            showLanguagePicker()
        }
    }

    private fun languageLabel(tag: String): String =
        AppSettings.LANGUAGES.firstOrNull { it.first == tag }?.second
            ?: AppSettings.LANGUAGES.first().second

    /**
     * 选语言。
     *
     * 只做两件事：把选择写进偏好，然后让当前界面按新 Configuration 重建一次。
     * 语言是在 Activity 的 attachBaseContext 里换的（见 [LocaleHelper]），
     * 所以除了重建没有别的办法——但正好，重建一次最快也最干脆。
     */
    private fun showLanguagePicker() {
        val tags = AppSettings.LANGUAGES.map { it.first }
        val labels = AppSettings.LANGUAGES.map { it.second }.toTypedArray()
        val current = tags.indexOf(host.appSettings().language).coerceAtLeast(0)

        requireContext().appDialog()
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                applyLanguage(tags[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showWhite()
    }

    private fun applyLanguage(tag: String) {
        if (host.appSettings().language == tag) {
            tvLanguageValue.text = languageLabel(tag)
            return
        }
        host.appSettings().language = tag
        requireActivity().recreate()
    }

    // ------------------------------------------------------------------ 模型

    private fun bindModel() {
        requireView().findViewById<View>(R.id.rowModel).setOnClickListener {
            showCloudEditor()
        }
        updateModelRow()
    }

    private fun applyCloud(config: CloudLlmHandler.CloudConfig) {
        host.setCloudConfig(config)
        updateModelRow()
    }

    private fun updateModelRow() {
        val cfg = host.cloudConfig
        val name = cfg.model.ifBlank { CloudLlmHandler.DEFAULT_PROVIDER.model }
        tvModelValue.text = if (cfg.isReady()) {
            name
        } else {
            getString(R.string.settings_model_missing_key, name)
        }
    }

    private fun missingFields(cfg: CloudLlmHandler.CloudConfig): List<String> {
        val missing = mutableListOf<String>()
        if (cfg.baseUrl.isBlank()) missing += getString(R.string.settings_cloud_missing_url)
        if (cfg.apiKey.isBlank()) missing += getString(R.string.settings_cloud_missing_key)
        if (cfg.model.isBlank()) missing += getString(R.string.settings_cloud_missing_model)
        return missing
    }

    /**
     * 填写接口四项配置。
     *
     * 从「关于 → AI 模型」进入，不是日常选项：
     * 大多数人一次都不用打开——默认的 GLM-4-Flash-250414 只要填个 Key。
     * 用代码搭界面而不是另写一份 XML：只有四个输入框，
     * 而且这样不用管主题里有没有对应 style，任何 Material 版本都能编译过。
     */
    private fun showCloudEditor() {
        val cfg = host.cloudConfig
        val context = requireContext()
        val pad = dp(20)

        fun field(value: String, hint: String, singleLine: Boolean = true): EditText =
            EditText(context).apply {
                setText(value)
                this.hint = hint
                setSingleLine(singleLine)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                textSize = 14f
            }

        val inputBase = field(cfg.baseUrl, getString(R.string.settings_cloud_base_hint))
        val inputKey = field(cfg.apiKey, getString(R.string.settings_cloud_key_hint))
        val inputModel = field(cfg.model, getString(R.string.settings_cloud_model_hint))
        val inputPrompt = field(
            if (cfg.systemPrompt == CloudLlmHandler.DEFAULT_SYSTEM_PROMPT) "" else cfg.systemPrompt,
            getString(R.string.settings_cloud_prompt_hint),
            singleLine = false
        )

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(inputBase)
            addView(inputKey)
            addView(inputModel)
            addView(inputPrompt)
        }

        context.appDialog()
            .setTitle(R.string.settings_cloud_title)
            .setMessage(R.string.settings_cloud_hint)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.settings_cloud_test) { _, _ ->
                // 先落盘再测：否则测的是上一次存进去的配置，很容易误判
                applyCloud(
                    buildConfig(
                        cfg,
                        inputBase.text.toString(),
                        inputKey.text.toString(),
                        inputModel.text.toString(),
                        inputPrompt.text.toString()
                    )
                )
                runCloudTest()
            }
            .setPositiveButton(R.string.action_save) { _, _ ->
                val next = buildConfig(
                    cfg,
                    inputBase.text.toString(),
                    inputKey.text.toString(),
                    inputModel.text.toString(),
                    inputPrompt.text.toString()
                )
                applyCloud(next)
                toast(getString(R.string.settings_cloud_saved, next.model.ifBlank { next.baseUrl }))
            }
            .showWhite()
    }

    /** 把四个输入框读成一份配置：保存过就是想用，所以 enabled 恒为 true */
    private fun buildConfig(
        cfg: CloudLlmHandler.CloudConfig,
        base: String,
        key: String,
        model: String,
        prompt: String
    ): CloudLlmHandler.CloudConfig = cfg.copy(
        enabled = true,
        mode = if (cfg.mode == CloudLlmHandler.CloudMode.OFF) {
            CloudLlmHandler.CloudMode.LOCAL_FIRST
        } else {
            cfg.mode
        },
        baseUrl = base,
        apiKey = key,
        model = model,
        systemPrompt = prompt.trim().ifBlank { CloudLlmHandler.DEFAULT_SYSTEM_PROMPT }
    )

    /** 打一次最小请求，验证地址和 Key 对不对 */
    private fun runCloudTest() {
        val cfg = host.cloudConfig
        if (!cfg.isReady()) {
            toast(
                getString(
                    R.string.settings_cloud_state_missing,
                    missingFields(cfg).joinToString("、")
                )
            )
            return
        }
        toast(getString(R.string.settings_cloud_testing))
        host.testCloud(cfg) { result -> toast(result) }
    }

    // ------------------------------------------------------------------ 其他动作

    private fun bindActions() {
        requireView().findViewById<MaterialButton>(R.id.btnResetSettings).setOnClickListener {
            confirmReset()
        }
        requireView().findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            confirmLogout()
        }
    }

    private fun confirmReset() {
        requireContext().appDialog()
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_done)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                // 先记下当前语言：reset 会把偏好清空，之后就读不出来了
                val previous = host.appSettings().language
                host.resetSettings()
                refresh()
                toast(getString(R.string.settings_reset_done))
                if (previous != AppSettings.LANGUAGE_ZH) {
                    host.appSettings().language = AppSettings.LANGUAGE_ZH
                    requireActivity().recreate()
                }
            }
            .showWhite()
    }

    private fun confirmLogout() {
        requireContext().appDialog()
            .setTitle(R.string.settings_logout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                // 先撤销这个账号排的闹钟、丢掉提醒中心，再清身份：
                // 否则下一个账号登进来还会收到上一个人的提醒
                Reminders.detach(requireContext())
                AccountStore(requireContext()).signOut()
                host.gotoLogin()
            }
            .showWhite()
    }

    private fun refresh() {
        updateModelRow()
        switchSpeak.isChecked = host.speakEnabled
        tvAccount.text = AccountStore(requireContext()).currentUser.orEmpty()
        tvVersion.text = getString(R.string.settings_version, appVersionName())
        tvLanguageValue.text = languageLabel(host.appSettings().language)
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
    }.getOrNull() ?: "1.0"

    override fun onResume() {
        super.onResume()
        if (isAdded) refresh()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 试听这句话最长也就两秒，留点余量再问「到底出声没有」 */
        const val SPEAK_PREVIEW_MS = 2_600L
    }
}
