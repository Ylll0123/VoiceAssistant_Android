package com.example.voiceassistant.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.voiceassistant.MainActivity
import com.example.voiceassistant.R
import com.example.voiceassistant.assistant.AssistantRules
import com.example.voiceassistant.assistant.DialogCorpus
import com.example.voiceassistant.assistant.SkillCatalog
import com.example.voiceassistant.memory.MemoryRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/**
 * 设置页：语音行为、说话方式、自学习，以及多轮上下文的实时状态。
 *
 * ## 关于「语速调了没反应」
 * 原来是只在学习者改动时调用一次 `TextToSpeech.setSpeechRate`，
 * 而部分手机自带的 TTS 引擎会把参数缓存下来、进而在内部重置，
 * 结果滑块滑了、声音没变。现在改成：
 * 1. **每次开口前都重新写一遍**语速和音调（见 SpeechController.applyVoiceParams）；
 * 2. 滑块改成 **0.1 一档**（0.5x~2.0x），一格就是明显的一档；
 * 3. 加了**试听**按钮，当场就能确认；
 * 4. 实在不行还可以**换一个语音引擎**（有些 OEM 引擎根本不实现变速）。
 *
 * 「对话状态」那张卡片是给答辩用的可视化：能直接看到上一轮意图、
 * 上下文剩余秒数、当前话题和槽位，证明多轮上下文是真实存在的状态机。
 */
class SettingsFragment : Fragment() {

    private lateinit var switchContinuous: SwitchMaterial
    private lateinit var switchSpeak: SwitchMaterial
    private lateinit var switchFollowUp: SwitchMaterial
    private lateinit var switchAutoLearn: SwitchMaterial
    private lateinit var switchCloud: SwitchMaterial
    private lateinit var seekRate: SeekBar
    private lateinit var seekPitch: SeekBar
    private lateinit var tvRateValue: TextView
    private lateinit var tvPitchValue: TextView
    private lateinit var tvEngine: TextView
    private lateinit var toggleStyle: MaterialButtonToggleGroup
    private lateinit var tvLearnStat: TextView
    private lateinit var tvPendingStat: TextView
    private lateinit var tvCloudState: TextView
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
        switchCloud = view.findViewById(R.id.switchCloud)
        seekRate = view.findViewById(R.id.seekRate)
        seekPitch = view.findViewById(R.id.seekPitch)
        tvRateValue = view.findViewById(R.id.tvRateValue)
        tvPitchValue = view.findViewById(R.id.tvPitchValue)
        tvEngine = view.findViewById(R.id.tvEngine)
        toggleStyle = view.findViewById(R.id.toggleStyle)
        tvLearnStat = view.findViewById(R.id.tvLearnStat)
        tvPendingStat = view.findViewById(R.id.tvPendingStat)
        tvCloudState = view.findViewById(R.id.tvCloudState)
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

        // 0.5x ~ 2.0x，每 0.1 一档：一档就是听得出来的差别
        seekRate.max = RATE_STEPS
        seekRate.progress = rateToProgress(host.speechRate)
        updateRateLabel(host.speechRate)
        seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val rate = progressToRate(progress)
                updateRateLabel(rate)
                host.setSpeechRate(rate)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        // 0.7x ~ 1.6x，每 0.1 一档
        seekPitch.max = PITCH_STEPS
        seekPitch.progress = pitchToProgress(host.speechPitch)
        updatePitchLabel(host.speechPitch)
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pitch = progressToPitch(progress)
                updatePitchLabel(pitch)
                host.setSpeechPitch(pitch)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        requireView().findViewById<MaterialButton>(R.id.btnPreview).setOnClickListener {
            // 试听要绕过「语音播报」开关：不然关着的时候点了没声音，像是坏了
            host.speech.preview(getString(R.string.settings_preview_text))
        }

        updateEngineLabel()
        requireView().findViewById<MaterialButton>(R.id.btnEngine).setOnClickListener {
            showEnginePicker()
        }
    }

    private fun updateRateLabel(rate: Float) {
        tvRateValue.text = getString(
            R.string.settings_rate_value,
            String.format(Locale.US, "%.1f", rate),
            rateWord(rate)
        )
    }

    private fun updatePitchLabel(pitch: Float) {
        tvPitchValue.text = getString(
            R.string.settings_pitch_value,
            String.format(Locale.US, "%.1f", pitch)
        )
    }

    private fun rateWord(rate: Float): String = when {
        rate <= 0.7f -> getString(R.string.settings_rate_very_slow)
        rate <= 0.9f -> getString(R.string.settings_rate_slow)
        rate < 1.2f -> getString(R.string.settings_rate_normal)
        rate < 1.6f -> getString(R.string.settings_rate_fast)
        else -> getString(R.string.settings_rate_very_fast)
    }

    private fun rateToProgress(rate: Float): Int =
        ((rate - MIN_RATE) / RATE_STEP).toInt().coerceIn(0, RATE_STEPS)

    private fun progressToRate(progress: Int): Float = MIN_RATE + progress * RATE_STEP

    private fun pitchToProgress(pitch: Float): Int =
        ((pitch - MIN_PITCH) / PITCH_STEP).toInt().coerceIn(0, PITCH_STEPS)

    private fun progressToPitch(progress: Int): Float = MIN_PITCH + progress * PITCH_STEP

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
                host.speech.preview(getString(R.string.settings_preview_text))
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

    // ------------------------------------------------------------------ 自学习与智能

    private fun bindLearning() {
        switchAutoLearn.isChecked = host.autoLearn
        switchAutoLearn.setOnCheckedChangeListener { _, checked ->
            if (host.autoLearn != checked) host.setAutoLearn(checked)
        }

        requireView().findViewById<MaterialButton>(R.id.btnGoTeach).setOnClickListener {
            host.selectTab(MainActivity.Tab.MEMORY)
        }

        val settings = host.appSettings()
        switchCloud.isChecked = settings.cloudEnabled
        switchCloud.setOnCheckedChangeListener { _, checked ->
            settings.cloudEnabled = checked
            updateCloudState()
            // 刚打开就把配置弹出来，否则用户开了却不知道去哪填
            if (checked && !settings.isCloudUsable()) showCloudDialog()
        }

        requireView().findViewById<MaterialButton>(R.id.btnCloudConfig).setOnClickListener {
            showCloudDialog()
        }
        updateCloudState()
    }

    private fun updateCloudState() {
        val settings = host.appSettings()
        tvCloudState.text = when {
            !settings.cloudEnabled -> getString(R.string.settings_cloud_state_off)
            settings.isCloudUsable() ->
                getString(R.string.settings_cloud_state_ready, settings.cloudModel)
            else -> getString(R.string.settings_cloud_state_need_key)
        }
    }

    private fun showCloudDialog() {
        val context = requireContext()
        val settings = host.appSettings()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val etEndpoint = EditText(context).apply {
            setText(settings.cloudEndpoint)
            hint = getString(R.string.settings_cloud_endpoint_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val etModel = EditText(context).apply {
            setText(settings.cloudModel)
            hint = getString(R.string.settings_cloud_model_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val etKey = EditText(context).apply {
            setText(settings.cloudApiKey)
            hint = getString(R.string.settings_cloud_key_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val note = TextView(context).apply {
            text = getString(R.string.settings_cloud_note)
            textSize = 12f
        }
        container.addView(etEndpoint)
        container.addView(etModel)
        container.addView(etKey)
        container.addView(note)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_cloud_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                settings.cloudEndpoint = etEndpoint.text.toString().trim()
                settings.cloudModel = etModel.text.toString().trim()
                settings.cloudApiKey = etKey.text.toString().trim()
                updateCloudState()
            }
            .show()
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
        updateEngineLabel()
        updateCloudState()
        refreshStatus()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MIN_RATE = 0.5f
        const val RATE_STEP = 0.1f
        const val RATE_STEPS = 15

        const val MIN_PITCH = 0.7f
        const val PITCH_STEP = 0.1f
        const val PITCH_STEPS = 9
    }
}
