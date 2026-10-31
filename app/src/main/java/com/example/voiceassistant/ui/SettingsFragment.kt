package com.example.voiceassistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

        bindVoice()
        bindStyle()
        bindLearning()

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
        updateEngineLabel()
        updateModelState()
        refreshStatus()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }
}
