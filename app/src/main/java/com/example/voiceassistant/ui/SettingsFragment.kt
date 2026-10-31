package com.example.voiceassistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.voiceassistant.MainActivity
import com.example.voiceassistant.R
import com.example.voiceassistant.assistant.AssistantRules
import com.example.voiceassistant.assistant.SkillCatalog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Locale

/**
 * 设置页：语音开关、语速，以及多轮上下文的实时状态。
 *
 * 「对话状态」那张卡片其实是给答辩用的可视化：能直接看到上一轮意图、
 * 上下文剩余秒数和槽位，证明多轮上下文是真实存在的状态机，不是写死的回复。
 */
class SettingsFragment : Fragment() {

    private lateinit var switchContinuous: SwitchMaterial
    private lateinit var switchSpeak: SwitchMaterial
    private lateinit var seekRate: SeekBar
    private lateinit var tvRateValue: TextView
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
        seekRate = view.findViewById(R.id.seekRate)
        tvRateValue = view.findViewById(R.id.tvRateValue)
        tvLastIntent = view.findViewById(R.id.tvLastIntent)
        tvContext = view.findViewById(R.id.tvContext)
        tvMessageCount = view.findViewById(R.id.tvMessageCount)
        tvRecognizerStatus = view.findViewById(R.id.tvRecognizerStatus)
        tvVersion = view.findViewById(R.id.tvVersion)

        bindSwitches()
        bindRateSlider()
        view.findViewById<MaterialButton>(R.id.btnClearChat).setOnClickListener {
            host.clearChat()
            refreshStatus()
        }
        refreshStatus()
    }

    private fun bindSwitches() {
        switchContinuous.isChecked = host.continuousMode
        switchSpeak.isChecked = host.speakEnabled

        switchContinuous.setOnCheckedChangeListener { _, checked ->
            host.setContinuousMode(checked)
        }
        switchSpeak.setOnCheckedChangeListener { _, checked ->
            host.setSpeakEnabled(checked)
        }
    }

    private fun bindRateSlider() {
        val progress = (((host.speechRate - MIN_RATE) / (MAX_RATE - MIN_RATE)) * 100)
            .toInt()
            .coerceIn(0, 100)
        seekRate.progress = progress
        updateRateLabel(host.speechRate)

        seekRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val rate = MIN_RATE + progress / 100f * (MAX_RATE - MIN_RATE)
                host.setSpeechRate(rate)
                updateRateLabel(rate)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun updateRateLabel(rate: Float) {
        tvRateValue.text = getString(
            R.string.settings_rate_value,
            String.format(Locale.US, "%.1f", rate)
        )
    }

    private fun refreshStatus() {
        val context = host.session.engine.context()
        val lastIntent = host.session.lastIntent()

        tvLastIntent.text = getString(
            R.string.settings_last_intent,
            lastIntent ?: "无"
        )

        tvContext.text = if (context.isAlive()) {
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

        tvMessageCount.text = getString(
            R.string.settings_message_count,
            host.session.messages.size
        )

        tvRecognizerStatus.text = getString(
            if (host.speech.isRecognitionAvailable()) R.string.settings_recognizer_ok
            else R.string.settings_recognizer_missing
        )

        tvVersion.text = getString(
            R.string.settings_version,
            appVersionName(),
            AssistantRules.all().size,
            SkillCatalog.skills.size
        )
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0)
            .versionName
    }.getOrNull() ?: "1.0"

    override fun onResume() {
        super.onResume()
        if (isAdded) {
            switchContinuous.isChecked = host.continuousMode
            switchSpeak.isChecked = host.speakEnabled
            refreshStatus()
        }
    }

    private companion object {
        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.8f
    }
}
