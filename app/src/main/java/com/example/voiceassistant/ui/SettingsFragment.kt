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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * 设置页：只留最常用的几项。
 *
 * 回答方式、语音播报、语言、清空对话、账号、恢复默认——一屏放得下。
 * 对话页顶栏只剩菜单和设置两个图标，所以「播报开关」挪到这里，
 * 「连续对话」直接取消（v4.0 起改成按住说话，不再自动收音）。
 *
 * 大模型也不在这里摆「开关」：默认就接的是 GLM-4-Flash-250414（免费），
 * 用户没必要先理解什么叫「接入」。只在「关于」里留一行模型名，
 * 点开才填 Key、换服务商——那是维护入口，不是日常选项。
 */
class SettingsFragment : Fragment() {

    private lateinit var toggleStyle: MaterialButtonToggleGroup
    private lateinit var switchSpeak: MaterialSwitch
    private lateinit var switchWake: MaterialSwitch
    private lateinit var switchWakeBeep: MaterialSwitch
    private lateinit var tvWakeSensValue: TextView
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

        toggleStyle = view.findViewById(R.id.toggleStyle)
        switchSpeak = view.findViewById(R.id.switchSpeak)
        switchWake = view.findViewById(R.id.switchWake)
        switchWakeBeep = view.findViewById(R.id.switchWakeBeep)
        tvWakeSensValue = view.findViewById(R.id.tvWakeSensitivityValue)
        tvLanguageValue = view.findViewById(R.id.tvLanguageValue)
        tvModelValue = view.findViewById(R.id.tvModelValue)
        tvAccount = view.findViewById(R.id.tvAccount)
        tvVersion = view.findViewById(R.id.tvVersion)

        bindStyle()
        bindSpeak()
        bindWake()
        bindWakeTuning()
        bindAccessibility()
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

    // ------------------------------------------------------------------ 播报

    private fun bindSpeak() {
        switchSpeak.isChecked = host.speakEnabled
        switchSpeak.setOnCheckedChangeListener { _, checked ->
            if (checked != host.speakEnabled) host.setSpeakEnabled(checked)
        }
    }

    // ------------------------------------------------------------------ 唤醒词

    /**
     * 常驻唤醒开关。
     *
     * 开关状态回来时按 [VoiceAssistantActivity.isWakeEnabled] 重读一次：
     * 服务起不来（没录音权限、系统限制后台麦克风）时那边会把落盘值改回去，
     * 这里要跟着弹回来，不能停在「开」的位置上。
     */
    private fun bindWake() {
        switchWake.isChecked = host.isWakeEnabled()
        switchWake.setOnCheckedChangeListener { _, checked ->
            if (checked == host.isWakeEnabled()) return@setOnCheckedChangeListener
            host.setWakeEnabled(checked)
            switchWake.isChecked = host.isWakeEnabled()
            switchWake.jumpDrawablesToCurrentState()
        }
    }

    /**
     * 唤醒调参：灵敏度三档、应答提示音、后台保活引导。
     *
     * 这三项解决的是同一个问题：**开关亮着，却怎么叫都不应**。
     * 叫不醒无非三层——听得见但没匹配上（灵敏度）、叫醒了却没反馈（提示音）、
     * 或者服务被系统掐了（电池优化）。把三层都给用户一个入口，别让他只能干瞪眼。
     */
    private fun bindWakeTuning() {
        tvWakeSensValue.text = sensitivityLabel(host.wakeSensitivity())
        requireView().findViewById<View>(R.id.rowWakeSensitivity).setOnClickListener {
            showSensitivityPicker()
        }

        switchWakeBeep.isChecked = host.isWakeBeep()
        switchWakeBeep.setOnCheckedChangeListener { _, checked ->
            if (checked != host.isWakeBeep()) host.setWakeBeep(checked)
        }

        requireView().findViewById<View>(R.id.rowBattery).setOnClickListener {
            host.openBatterySettings()
        }
    }

    private fun showSensitivityPicker() {
        val labels = arrayOf(
            getString(R.string.settings_wake_sens_low),
            getString(R.string.settings_wake_sens_mid),
            getString(R.string.settings_wake_sens_high)
        )
        requireContext().appDialog()
            .setTitle(R.string.settings_wake_sensitivity)
            .setSingleChoiceItems(labels, host.wakeSensitivity()) { dialog, which ->
                dialog.dismiss()
                host.setWakeSensitivity(which)
                tvWakeSensValue.text = sensitivityLabel(which)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showWhite()
    }

    private fun sensitivityLabel(level: Int): String = when (level) {
        0 -> getString(R.string.settings_wake_sens_low)
        2 -> getString(R.string.settings_wake_sens_high)
        else -> getString(R.string.settings_wake_sens_mid)
    }

    /** 无障碍设置入口：自动填字要靠它，得让用户能自己找得到 */
    private fun bindAccessibility() {
        requireView().findViewById<View>(R.id.rowAccessibility).setOnClickListener {
            host.openAccessibilitySettings()
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

    /**
     * 「关于」里那一行模型入口。
     *
     * 设置页不再摆「接入大模型」开关：默认就接的是 GLM-4-Flash-250414。
     * 这一行只显示当前模型名，没填 Key 时补一句「待填 Key」——
     * GLM 免费但仍然要一个 Key，不说清楚会让人以为已经能用了。
     */
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
        // 唤醒可能被系统掐掉（后台限制 / 用户撤了麦克风权限），回来时以真实状态为准
        switchWake.isChecked = host.isWakeEnabled()
        tvWakeSensValue.text = sensitivityLabel(host.wakeSensitivity())
        switchWakeBeep.isChecked = host.isWakeBeep()
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
}
