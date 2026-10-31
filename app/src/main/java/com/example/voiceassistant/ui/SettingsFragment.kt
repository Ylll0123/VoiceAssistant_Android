package com.example.voiceassistant.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.voiceassistant.MainActivity
import com.example.voiceassistant.R
import com.example.voiceassistant.assistant.AssistantRules
import com.example.voiceassistant.assistant.CityClimate
import com.example.voiceassistant.assistant.DialogCorpus
import com.example.voiceassistant.assistant.Dictionary
import com.example.voiceassistant.assistant.KnowledgeBase
import com.example.voiceassistant.assistant.ReplyCrafter
import com.example.voiceassistant.assistant.SkillCatalog
import com.example.voiceassistant.data.CloudBrain
import com.example.voiceassistant.memory.MemoryRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * 设置页：语音行为、说话方式、自学习与本地小模型，以及多轮上下文的实时状态。
 *
 * 「对话状态」那张卡片是给答辩用的可视化：能直接看到上一轮意图、
 * 上下文剩余秒数、当前话题和槽位，证明多轮上下文是真实存在的状态机。
 */
class SettingsFragment : Fragment() {

    private lateinit var switchContinuous: SwitchMaterial
    private lateinit var switchSpeak: SwitchMaterial
    private lateinit var switchFollowUp: SwitchMaterial
    private lateinit var switchAutoLearn: SwitchMaterial
    private lateinit var switchLocalModel: SwitchMaterial
    private lateinit var switchOnline: SwitchMaterial
    private lateinit var tvEngine: TextView
    private lateinit var toggleStyle: MaterialButtonToggleGroup
    private lateinit var tvLearnStat: TextView
    private lateinit var tvPendingStat: TextView
    private lateinit var tvModelState: TextView
    private lateinit var tvOnlineState: TextView
    private lateinit var tvLastIntent: TextView
    private lateinit var tvContext: TextView
    private lateinit var tvMessageCount: TextView
    private lateinit var tvRecognizerStatus: TextView
    private lateinit var tvVersion: TextView

    // AI 大脑（云端大模型）
    private lateinit var switchCloud: SwitchMaterial
    private lateinit var tvCloudState: TextView
    private lateinit var toggleCloudMode: MaterialButtonToggleGroup
    private lateinit var tvCloudProvider: TextView
    private lateinit var tvCloudEndpoint: TextView
    private lateinit var tvCloudModel: TextView
    private lateinit var tvCloudKey: TextView

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchContinuous = view.findViewById(R.id.switchContinuous)
        switchSpeak = view.findViewById(R.id.switchSpeak)
        switchFollowUp = view.findViewById(R.id.switchFollowUp)
        switchAutoLearn = view.findViewById(R.id.switchAutoLearn)
        switchLocalModel = view.findViewById(R.id.switchLocalModel)
        switchOnline = view.findViewById(R.id.switchOnline)
        tvOnlineState = view.findViewById(R.id.tvOnlineState)
        tvEngine = view.findViewById(R.id.tvEngine)
        toggleStyle = view.findViewById(R.id.toggleStyle)
        tvLearnStat = view.findViewById(R.id.tvLearnStat)
        tvPendingStat = view.findViewById(R.id.tvPendingStat)
        tvModelState = view.findViewById(R.id.tvModelState)
        tvLastIntent = view.findViewById(R.id.tvLastIntent)
        tvContext = view.findViewById(R.id.tvContext)
        tvMessageCount = view.findViewById(R.id.tvMessageCount)
        tvRecognizerStatus = view.findViewById(R.id.tvRecognizerStatus)
        tvVersion = view.findViewById(R.id.tvVersion)

        switchCloud = view.findViewById(R.id.switchCloud)
        tvCloudState = view.findViewById(R.id.tvCloudState)
        toggleCloudMode = view.findViewById(R.id.toggleCloudMode)
        tvCloudProvider = view.findViewById(R.id.tvCloudProvider)
        tvCloudEndpoint = view.findViewById(R.id.tvCloudEndpoint)
        tvCloudModel = view.findViewById(R.id.tvCloudModel)
        tvCloudKey = view.findViewById(R.id.tvCloudKey)

        bindVoice()
        bindStyle()
        bindLearning()
        bindCloud()

        view.findViewById<MaterialButton>(R.id.btnClearChat).setOnClickListener {
            host.clearChat()
            refreshStatus()
        }
        view.findViewById<MaterialButton>(R.id.btnResetSettings).setOnClickListener {
            confirmReset()
        }

        refreshStatus()
    }

    // ------------------------------------------------------------------ 语音

    private fun bindVoice() {
        switchContinuous.isChecked = host.continuousMode
        switchSpeak.isChecked = host.speakEnabled

        switchContinuous.setOnCheckedChangeListener { _, checked ->
            if (host.continuousMode != checked) host.setContinuousMode(checked)
        }
        switchSpeak.setOnCheckedChangeListener { _, checked ->
            if (host.speakEnabled != checked) host.setSpeakEnabled(checked)
        }

        updateEngineLabel()
        requireView().findViewById<MaterialButton>(R.id.btnEngine).setOnClickListener {
            showEnginePicker()
        }
    }

    private fun updateEngineLabel() {
        val current = host.speech.currentEngine()
        val label = if (current.isEmpty()) {
            getString(R.string.settings_engine_default)
        } else {
            host.speech.availableEngines()
                .firstOrNull { it.packageName == current }?.label ?: current
        }
        tvEngine.text = getString(R.string.settings_engine, label)
    }

    /**
     * 换语音引擎。
     * 有些手机自带的 TTS 引擎不实现 `setSpeechRate`（滑了没反应），
     * 换成 Google 语音服务或其它引擎后语速才真正生效。
     */
    private fun showEnginePicker() {
        val engines = host.speech.availableEngines()
        if (engines.isEmpty()) {
            toast(getString(R.string.settings_engine_default))
            return
        }
        val labels = (listOf(getString(R.string.settings_engine_default)) + engines.map { it.label })
            .toTypedArray()
        val packages = (listOf("") + engines.map { it.packageName })
        val currentIndex = packages.indexOf(host.speech.currentEngine()).coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_engine_title)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                host.setTtsEngine(packages[which])
                updateEngineLabel()
                dialog.dismiss()
                // 换完引擎立刻念一句，用户马上知道换成功了
                host.speech.preview(getString(R.string.settings_engine_preview))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 说话方式

    private fun bindStyle() {
        switchFollowUp.isChecked = host.proactiveFollowUp
        switchFollowUp.setOnCheckedChangeListener { _, checked ->
            if (host.proactiveFollowUp != checked) host.setProactiveFollowUp(checked)
        }

        // 先设到当前值，再挂监听，避免初始化时反手把设置改掉
        toggleStyle.check(styleButtonId(host.replyStyle))
        toggleStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val style = when (checkedId) {
                R.id.btnStyleConcise -> 0
                R.id.btnStyleDetailed -> 2
                else -> 1
            }
            if (host.replyStyle != style) host.setReplyStyle(style)
        }
    }

    private fun styleButtonId(style: Int): Int = when (style) {
        0 -> R.id.btnStyleConcise
        2 -> R.id.btnStyleDetailed
        else -> R.id.btnStyleStandard
    }

    // ------------------------------------------------------------------ 自学习与本地模型

    private fun bindLearning() {
        switchAutoLearn.isChecked = host.autoLearn
        switchAutoLearn.setOnCheckedChangeListener { _, checked ->
            if (host.autoLearn != checked) host.setAutoLearn(checked)
        }

        requireView().findViewById<MaterialButton>(R.id.btnGoTeach).setOnClickListener {
            host.selectTab(MainActivity.Tab.MEMORY)
        }

        switchLocalModel.isChecked = host.localModelEnabled
        switchLocalModel.setOnCheckedChangeListener { _, checked ->
            if (host.localModelEnabled != checked) host.setLocalModelEnabled(checked)
        }
        updateModelState()

        switchOnline.isChecked = host.onlineSearchEnabled
        switchOnline.setOnCheckedChangeListener { _, checked ->
            if (host.onlineSearchEnabled != checked) host.setOnlineSearchEnabled(checked)
            updateOnlineState()
        }
        updateOnlineState()
    }

    private fun updateOnlineState() {
        tvOnlineState.text = getString(
            if (host.onlineSearchEnabled) R.string.settings_online_state
            else R.string.settings_online_state_off
        )
    }

    private fun updateModelState() {
        val model = host.session.engine.localModel()
        if (model == null || !host.localModelEnabled) {
            tvModelState.text = getString(R.string.settings_model_state_off)
        } else {
            val (kinds, templates) = ReplyCrafter.stats()
            tvModelState.text = getString(
                R.string.settings_model_state,
                model.sentenceCount(),
                model.nodeCount()
            ) + "\n" + getString(R.string.settings_thought_state, kinds, templates)
        }
    }

    // ------------------------------------------------------------------ AI 大脑

    /**
     * 云端大模型配置。
     *
     * 默认整块是关的：没填 Key 就完全不联网，App 依旧是纯离线助手。
     * 用户填好之后有两种用法（见 [CloudBrain.CloudMode]），
     * 任何一种失败都会自动退回本地答案，不会让界面卡住。
     */
    private fun bindCloud() {
        switchCloud.isChecked = host.cloudConfig.enabled
        switchCloud.setOnCheckedChangeListener { _, checked ->
            applyCloud(host.cloudConfig.copy(enabled = checked))
        }

        // 先设值再挂监听，避免初始化时反手把配置改掉
        toggleCloudMode.check(cloudModeButtonId(host.cloudConfig.mode))
        toggleCloudMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnCloudLocal -> CloudBrain.CloudMode.LOCAL_FIRST
                R.id.btnCloudCloud -> CloudBrain.CloudMode.CLOUD_FIRST
                else -> CloudBrain.CloudMode.OFF
            }
            if (host.cloudConfig.mode != mode) applyCloud(host.cloudConfig.copy(mode = mode))
        }

        requireView().findViewById<MaterialButton>(R.id.btnCloudProvider)
            .setOnClickListener { showProviderPicker() }
        requireView().findViewById<MaterialButton>(R.id.btnCloudEdit)
            .setOnClickListener { showCloudEditor() }
        requireView().findViewById<MaterialButton>(R.id.btnCloudTest)
            .setOnClickListener { runCloudTest() }

        updateCloudState()
    }

    private fun cloudModeButtonId(mode: CloudBrain.CloudMode): Int = when (mode) {
        CloudBrain.CloudMode.LOCAL_FIRST -> R.id.btnCloudLocal
        CloudBrain.CloudMode.CLOUD_FIRST -> R.id.btnCloudCloud
        else -> R.id.btnCloudOff
    }

    /** 改完立刻写盘并刷新展示 */
    private fun applyCloud(config: CloudBrain.CloudConfig) {
        host.setCloudConfig(config)
        updateCloudState()
    }

    private fun updateCloudState() {
        val cfg = host.cloudConfig
        val unset = getString(R.string.action_unset)

        tvCloudProvider.text = getString(R.string.settings_cloud_provider, providerLabel(cfg))
        tvCloudEndpoint.text = getString(R.string.settings_cloud_endpoint, cfg.baseUrl.ifBlank { unset })
        tvCloudModel.text = getString(R.string.settings_cloud_model, cfg.model.ifBlank { unset })
        tvCloudKey.text = getString(
            R.string.settings_cloud_key,
            if (cfg.apiKey.isBlank()) unset else cfg.maskedKey()
        )

        tvCloudState.text = when {
            !cfg.enabled -> getString(R.string.settings_cloud_state_off)
            !cfg.isReady() -> getString(
                R.string.settings_cloud_state_missing,
                missingFields(cfg).joinToString("、")
            )
            else -> getString(R.string.settings_cloud_state_ready, providerLabel(cfg), cfg.model)
        }
    }

    private fun missingFields(cfg: CloudBrain.CloudConfig): List<String> {
        val missing = mutableListOf<String>()
        if (cfg.baseUrl.isBlank()) missing += getString(R.string.settings_cloud_missing_url)
        if (cfg.apiKey.isBlank()) missing += getString(R.string.settings_cloud_missing_key)
        if (cfg.model.isBlank()) missing += getString(R.string.settings_cloud_missing_model)
        return missing
    }

    /** 按 Base URL 反查服务商名（自己填的地址显示「自定义」） */
    private fun providerLabel(cfg: CloudBrain.CloudConfig): String {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val hit = CloudBrain.PROVIDERS.firstOrNull { it.baseUrl.isNotEmpty() && it.baseUrl.trimEnd('/') == base }
        return hit?.label ?: CloudBrain.PROVIDERS.first().label
    }

    /** 选服务商：只帮忙填地址和模型名，Key 永远要用户自己粘 */
    private fun showProviderPicker() {
        val labels = CloudBrain.PROVIDERS.map { it.label }.toTypedArray()
        val current = providerLabel(host.cloudConfig)
        val currentIndex = CloudBrain.PROVIDERS.indexOfFirst { it.label == current }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_cloud_provider_label)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                val picked = CloudBrain.PROVIDERS[which]
                if (picked.baseUrl.isEmpty()) {
                    dialog.dismiss()
                    showCloudEditor()
                } else {
                    applyCloud(
                        host.cloudConfig.copy(
                            baseUrl = picked.baseUrl,
                            model = picked.model,
                            enabled = true,
                            mode = if (host.cloudConfig.mode == CloudBrain.CloudMode.OFF) {
                                CloudBrain.CloudMode.LOCAL_FIRST
                            } else {
                                host.cloudConfig.mode
                            }
                        )
                    )
                    dialog.dismiss()
                    toast(
                        getString(R.string.settings_cloud_saved, picked.label) +
                            "（${getString(R.string.settings_cloud_key_label)}还差一步）"
                    )
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        val pad = (20 * resources.displayMetrics.density).toInt()

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
            if (cfg.systemPrompt == CloudBrain.DEFAULT_SYSTEM_PROMPT) "" else cfg.systemPrompt,
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
                val next = CloudBrain.CloudConfig(
                    enabled = true,
                    mode = if (cfg.mode == CloudBrain.CloudMode.OFF) {
                        CloudBrain.CloudMode.LOCAL_FIRST
                    } else {
                        cfg.mode
                    },
                    baseUrl = inputBase.text.toString(),
                    apiKey = inputKey.text.toString(),
                    model = inputModel.text.toString(),
                    systemPrompt = inputPrompt.text.toString().trim()
                        .ifBlank { CloudBrain.DEFAULT_SYSTEM_PROMPT }
                )
                applyCloud(next)
                switchCloud.isChecked = next.enabled
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
        toast(getString(R.string.settings_cloud_testing))
        host.testCloud(cfg) { result -> toast(result) }
    }

    // ------------------------------------------------------------------ 状态

    private fun refreshStatus() {
        val context = host.session.engine.context()
        val lastIntent = host.session.lastIntent()

        tvLastIntent.text = getString(
            R.string.settings_last_intent,
            lastIntent ?: "无"
        )

        val aliveText = if (context.isAlive()) {
            val seconds = (context.remainingMs() / 1000).toInt()
            val base = getString(
                R.string.settings_context_alive,
                context.lastIntent ?: "-",
                seconds
            )
            val slots = context.slotsSnapshot()
            if (slots.isEmpty()) {
                base
            } else {
                base + "\n" + getString(
                    R.string.settings_context_slots,
                    slots.entries.joinToString("，") { "${it.key}=${it.value}" }
                )
            }
        } else {
            getString(R.string.settings_context_idle)
        }

        // 话题卡片：证明「接着上文聊」是真实的状态，不是巧合
        val topicText = DialogCorpus.find(context.activeTopic())?.let { topic ->
            "\n" + getString(R.string.settings_topic, topic.title, context.topicTurn)
        } ?: ""
        tvContext.text = aliveText + topicText

        tvMessageCount.text = getString(
            R.string.settings_message_count,
            host.session.messages.size
        )

        tvRecognizerStatus.text = getString(
            if (host.speech.isRecognitionAvailable()) R.string.settings_recognizer_ok
            else R.string.settings_recognizer_missing
        )

        val entries = host.session.engine.memoryRepository()?.entries().orEmpty()
        val pending = host.appSettings().pendingQuestions.size
        tvLearnStat.text = getString(
            R.string.settings_learn_stat,
            entries.count { it.isTaught },
            entries.count { it.isLearned },
            entries.count { it.source == MemoryRepository.SOURCE_SEED }
        )
        tvPendingStat.text = getString(R.string.settings_pending_stat, pending)

        tvVersion.text = getString(
            R.string.settings_version,
            appVersionName(),
            AssistantRules.all().size,
            SkillCatalog.skills.size,
            entries.size
        ) + "\n" + getString(
            R.string.settings_corpus,
            DialogCorpus.topicCount(),
            DialogCorpus.sentenceCount(),
            context.turns,
            context.chatTurns
        ) + "\n" + getString(
            R.string.settings_knowledge,
            KnowledgeBase.size(),
            KnowledgeBase.questionCount(),
            KnowledgeBase.answerCount(),
            Dictionary.size(),
            CityClimate.size()
        )
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reset)
            .setMessage(R.string.settings_reset_done)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                host.resetSettings()
                bindVoice()
                bindStyle()
                bindLearning()
                bindCloud()
                refreshStatus()
                toast(getString(R.string.settings_reset_done))
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
    }.getOrNull() ?: "1.0"

    override fun onResume() {
        super.onResume()
        if (!isAdded) return
        switchContinuous.isChecked = host.continuousMode
        switchSpeak.isChecked = host.speakEnabled
        switchFollowUp.isChecked = host.proactiveFollowUp
        switchAutoLearn.isChecked = host.autoLearn
        switchLocalModel.isChecked = host.localModelEnabled
        switchOnline.isChecked = host.onlineSearchEnabled
        switchCloud.isChecked = host.cloudConfig.enabled
        toggleCloudMode.check(cloudModeButtonId(host.cloudConfig.mode))
        updateEngineLabel()
        updateModelState()
        updateOnlineState()
        updateCloudState()
        refreshStatus()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
