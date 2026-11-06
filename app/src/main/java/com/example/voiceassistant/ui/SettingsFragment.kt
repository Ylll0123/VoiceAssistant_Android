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
import com.example.voiceassistant.assistant.SkillCatalog
import com.example.voiceassistant.data.AccountStore
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置页：只留最常用的几项。
 *
 * 回答方式、语言、大模型开关、清空对话、恢复默认——五件事，一屏放得下。
 * 语音播报和连续对话不在这里：它们在对话页顶栏有图标，随手就能点，
 * 设置页再放一份只是重复。
 */
class SettingsFragment : Fragment() {

    private lateinit var toggleStyle: MaterialButtonToggleGroup
    private lateinit var tvLanguageValue: TextView
    private lateinit var switchModel: SwitchMaterial
    private lateinit var tvCloudState: TextView
    private lateinit var tvCloudEndpoint: TextView
    private lateinit var tvCloudModel: TextView
    private lateinit var tvCloudKey: TextView
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

        toggleStyle = view.findViewById(R.id.toggleStyle)
        tvLanguageValue = view.findViewById(R.id.tvLanguageValue)
        switchModel = view.findViewById(R.id.switchModel)
        tvCloudState = view.findViewById(R.id.tvCloudState)
        tvCloudEndpoint = view.findViewById(R.id.tvCloudEndpoint)
        tvCloudModel = view.findViewById(R.id.tvCloudModel)
        tvCloudKey = view.findViewById(R.id.tvCloudKey)
        tvAccount = view.findViewById(R.id.tvAccount)
        tvVersion = view.findViewById(R.id.tvVersion)

        bindStyle()
        bindLanguage()
        bindModel()
        bindActions()
        refresh()
    }

    // ------------------------------------------------------------------ 回答方式

    private fun bindStyle() {
        // 老版本有「标准」一档，现在只有两档：标准按「详细」处理，不让用户看到空选
        toggleStyle.check(
            if (host.replyStyle == 0) R.id.btnStyleConcise else R.id.btnStyleDetailed
        )
        toggleStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val style = if (checkedId == R.id.btnStyleConcise) 0 else 2
            if (host.replyStyle != style) host.setReplyStyle(style)
        }
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_language_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                applyLanguage(tags[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyLanguage(tag: String) {
        if (host.appSettings().language == tag) {
            tvLanguageValue.text = languageLabel(tag)
            return
        }
        host.appSettings().language = tag
        requireActivity().recreate()
    }

    // ------------------------------------------------------------------ 大模型

    private fun bindModel() {
        switchModel.isChecked = host.cloudConfig.enabled
        switchModel.setOnCheckedChangeListener { _, checked ->
            if (host.cloudConfig.enabled == checked) return@setOnCheckedChangeListener
            val mode = if (checked && host.cloudConfig.mode == CloudLlmHandler.CloudMode.OFF) {
                CloudLlmHandler.CloudMode.LOCAL_FIRST
            } else if (!checked) {
                CloudLlmHandler.CloudMode.OFF
            } else {
                host.cloudConfig.mode
            }
            applyCloud(host.cloudConfig.copy(enabled = checked, mode = mode))
        }

        requireView().findViewById<MaterialButton>(R.id.btnCloudEdit)
            .setOnClickListener { showCloudEditor() }
        requireView().findViewById<MaterialButton>(R.id.btnCloudTest)
            .setOnClickListener { runCloudTest() }
    }

    private fun applyCloud(config: CloudLlmHandler.CloudConfig) {
        host.setCloudConfig(config)
        updateCloudState()
    }

    private fun updateCloudState() {
        val cfg = host.cloudConfig
        val unset = getString(R.string.action_unset)

        tvCloudEndpoint.text = getString(R.string.settings_cloud_endpoint, cfg.baseUrl.ifBlank { unset })
        tvCloudModel.text = getString(R.string.settings_cloud_model, cfg.model.ifBlank { unset })
        tvCloudKey.text = getString(
            R.string.settings_cloud_key,
            if (cfg.apiKey.isBlank()) unset else cfg.maskedKey()
        )

        tvCloudState.text = when {
            !cfg.enabled -> getString(R.string.settings_model_off_hint)
            !cfg.isReady() -> getString(
                R.string.settings_cloud_state_missing,
                missingFields(cfg).joinToString("、")
            )
            else -> getString(R.string.settings_cloud_state_ready, providerLabel(cfg), cfg.model)
        }
    }

    private fun missingFields(cfg: CloudLlmHandler.CloudConfig): List<String> {
        val missing = mutableListOf<String>()
        if (cfg.baseUrl.isBlank()) missing += getString(R.string.settings_cloud_missing_url)
        if (cfg.apiKey.isBlank()) missing += getString(R.string.settings_cloud_missing_key)
        if (cfg.model.isBlank()) missing += getString(R.string.settings_cloud_missing_model)
        return missing
    }

    /** 按 Base URL 反查服务商名（自己填的地址显示第一个内置项） */
    private fun providerLabel(cfg: CloudLlmHandler.CloudConfig): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val hit = CloudLlmHandler.PROVIDERS.firstOrNull {
            it.baseUrl.isNotEmpty() && it.baseUrl.trimEnd('/') == base
        }
        return hit?.label ?: CloudLlmHandler.PROVIDERS.first().label
    }

    /**
     * 填写接口四项配置。
     *
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

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_cloud_title)
            .setMessage(R.string.settings_cloud_hint)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val next = cfg.copy(
                    enabled = true,
                    mode = if (cfg.mode == CloudLlmHandler.CloudMode.OFF) {
                        CloudLlmHandler.CloudMode.LOCAL_FIRST
                    } else {
                        cfg.mode
                    },
                    baseUrl = inputBase.text.toString(),
                    apiKey = inputKey.text.toString(),
                    model = inputModel.text.toString(),
                    systemPrompt = inputPrompt.text.toString().trim()
                        .ifBlank { CloudLlmHandler.DEFAULT_SYSTEM_PROMPT }
                )
                applyCloud(next)
                switchModel.isChecked = true
                toast(getString(R.string.settings_cloud_saved, next.model.ifBlank { next.baseUrl }))
            }
            .show()
    }

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
        tvCloudState.text = getString(R.string.settings_cloud_testing)
        host.testCloud(cfg) { result ->
            // Toast 会把服务端原文截掉一半，排查问题不方便：
            // 把完整结果留在状态行里，它不会消失，可以逐字对照
            tvCloudState.text = result
            toast(result)
        }
    }

    // ------------------------------------------------------------------ 其他动作

    private fun bindActions() {
        requireView().findViewById<MaterialButton>(R.id.btnClearChat).setOnClickListener {
            host.clearChat()
            toast(getString(R.string.settings_clear_chat_done))
        }
        requireView().findViewById<MaterialButton>(R.id.btnResetSettings).setOnClickListener {
            confirmReset()
        }
        requireView().findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            confirmLogout()
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_done)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                // 先记下当前语言：reset 会把偏好清空，之后就读不出来了
                val previous = host.appSettings().language
                // 先恢复开关，再恢复语言（改语言会重建界面，放最后才不会被吞掉）
                host.resetSettings()
                bindStyle()
                bindModel()
                refresh()
                toast(getString(R.string.settings_reset_done))
                if (previous != AppSettings.LANGUAGE_ZH) {
                    host.appSettings().language = AppSettings.LANGUAGE_ZH
                    requireActivity().recreate()
                }
            }
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_logout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                AccountStore(requireContext()).signOut()
                host.gotoLogin()
            }
            .show()
    }

    private fun refresh() {
        updateCloudState()
        tvAccount.text = AccountStore(requireContext()).currentUser
            ?: getString(R.string.settings_account_guest)
        tvVersion.text = getString(
            R.string.settings_version,
            appVersionName(),
            SkillCatalog.skills.size
        )
        tvLanguageValue.text = languageLabel(host.appSettings().language)
        switchModel.isChecked = host.cloudConfig.enabled
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
}
