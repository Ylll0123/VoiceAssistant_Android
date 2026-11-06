package com.example.voiceassistant.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.voiceassistant.R
import com.example.voiceassistant.assistant.Skill
import com.example.voiceassistant.assistant.SkillCatalog
import com.example.voiceassistant.data.AppSettings

/**
 * 功能页：一行一个分类，点开才列出这一类的具体功能，再点一条才看到示例说法。
 *
 * 为什么做成「折叠」而不是像以前那样全铺开：
 * 七类二十八条全摊在屏幕上，用户根本找不到；折叠后首屏只有七行，
 * 想看什么点什么，而且每一层都只做一件事。
 *
 * 示例说法保留中文：引擎的规则是按中文说法写的，
 * 把示例翻译成英文点下去反而认不出来——那才是真正的「界面骗人」。
 * 所以标题和说明走 `skill_title_* / skill_desc_*` 四语言资源，
 * 示例原句不动，非中文界面在列表末尾加一句说明。
 */
class FeaturesFragment : Fragment() {

    private val host: VoiceAssistantActivity
        get() = requireActivity() as VoiceAssistantActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_features, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val box = view.findViewById<LinearLayout>(R.id.featureContainer)

        // 动态建视图一旦某个机型上出错，页面会显示原因而不是一片空白
        runCatching { build(box) }
            .onFailure { error ->
                Log.e(TAG, "功能列表构建失败", error)
                box.removeAllViews()
                box.addView(
                    messageView(getString(R.string.features_empty) + "\n" + (error.message ?: ""))
                )
            }

        if (box.childCount == 0) box.addView(messageView(getString(R.string.features_empty)))
    }

    private fun build(box: LinearLayout) {
        val inflater = LayoutInflater.from(requireContext())
        box.addView(buildMemoryGroup(inflater, box))

        SkillCatalog.categories.forEach { category ->
            val skills = SkillCatalog.byCategory(category)
            if (skills.isEmpty()) return@forEach

            val card = inflater.inflate(R.layout.item_feature_group, box, false)
            val body = card.findViewById<LinearLayout>(R.id.groupBody)
            card.findViewById<TextView>(R.id.tvGroupTitle).text = groupLabel(category)
            card.findViewById<TextView>(R.id.tvGroupCount).text =
                getString(R.string.features_count, skills.size)

            skills.forEach { skill -> body.addView(buildSkillRow(inflater, body, skill)) }
            bindToggle(card, body)
            box.addView(card)
        }

        // 非中文界面提示一句：示例是中文的（引擎只听中文），免得点下去一头雾水
        if (host.appSettings().language != AppSettings.LANGUAGE_ZH) {
            box.addView(messageView(getString(R.string.features_examples_note)))
        }
    }

    /** 固定的第一组：记忆与教学。它不来自技能目录，而是本地记忆的入口 */
    private fun buildMemoryGroup(inflater: LayoutInflater, parent: ViewGroup): View {
        val count = host.session.engine.memoryRepository()?.entries()?.size ?: 0
        val card = inflater.inflate(R.layout.item_feature_group, parent, false)
        card.findViewById<TextView>(R.id.tvGroupTitle).text =
            getString(R.string.features_memory_group)
        card.findViewById<TextView>(R.id.tvGroupCount).text =
            getString(R.string.features_memory_count, count)

        val body = card.findViewById<LinearLayout>(R.id.groupBody)
        val row = inflater.inflate(R.layout.item_feature_skill, body, false)
        row.findViewById<TextView>(R.id.tvSkillTitle).text =
            getString(R.string.features_memory_open)
        row.findViewById<TextView>(R.id.tvSkillDesc).text =
            getString(R.string.features_memory_hint)
        row.findViewById<View>(R.id.divider).visibility = View.GONE
        row.findViewById<View>(R.id.skillHeader).setOnClickListener { host.openMemory() }
        body.addView(row)

        bindToggle(card, body)
        return card
    }

    private fun buildSkillRow(
        inflater: LayoutInflater,
        parent: ViewGroup,
        skill: Skill
    ): View {
        val row = inflater.inflate(R.layout.item_feature_skill, parent, false)
        row.findViewById<TextView>(R.id.tvSkillTitle).text = skillTitle(skill)
        row.findViewById<TextView>(R.id.tvSkillDesc).text = skillDesc(skill)

        val exampleBox = row.findViewById<LinearLayout>(R.id.exampleBox)
        skill.examples.forEach { example ->
            exampleBox.addView(buildExampleRow(example, exampleBox))
        }

        // 没有示例的条目就不响应点击，避免点了没反应
        if (skill.examples.isNotEmpty()) {
            row.findViewById<View>(R.id.skillHeader).setOnClickListener {
                exampleBox.visibility =
                    if (exampleBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }
        return row
    }

    /** 一条示例：前面一个箭头，点一下就去对话页执行 */
    private fun buildExampleRow(example: String, parent: LinearLayout): TextView =
        TextView(requireContext()).apply {
            text = "›  $example"
            textSize = 13f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.brand_primary))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(
                requireContext(),
                android.R.drawable.list_selector_background
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { host.runFromSkill(example) }
        }

    /** 分组头点击：展开/收起，箭头跟着翻 */
    private fun bindToggle(card: View, body: LinearLayout) {
        val chevron = card.findViewById<ImageView>(R.id.ivChevron)
        card.findViewById<View>(R.id.groupHeader).setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate()
                .rotation(if (open) 180f else 0f)
                .setDuration(160)
                .start()
        }
    }

    /**
     * 技能标题 / 说明走四语言资源，资源缺失时回落到技能目录里的中文原文。
     *
     * 用 `when (id)` 显式列出来，而不是按名字拼资源名去 `getIdentifier`：
     * 后者拼错只会在运行时静默回落，编译期一点提示都没有。
     * 新增技能忘了加翻译时，这里会落到 `else` 分支，界面上直接看到中文原文。
     */
    private fun skillTitle(skill: Skill): String = pick(
        when (skill.id) {
            "time" -> R.string.skill_title_time
            "date" -> R.string.skill_title_date
            "weekday_calc" -> R.string.skill_title_weekday_calc
            "timezone" -> R.string.skill_title_timezone
            "arithmetic" -> R.string.skill_title_arithmetic
            "calc_continue" -> R.string.skill_title_calc_continue
            "unit_convert" -> R.string.skill_title_unit_convert
            "age" -> R.string.skill_title_age
            "random" -> R.string.skill_title_random
            "support" -> R.string.skill_title_support
            "reminder" -> R.string.skill_title_reminder
            "timer" -> R.string.skill_title_timer
            "torch" -> R.string.skill_title_torch
            "battery" -> R.string.skill_title_battery
            "open_app" -> R.string.skill_title_open_app
            "echo" -> R.string.skill_title_echo
            "guess" -> R.string.skill_title_guess
            "joke" -> R.string.skill_title_joke
            "quote" -> R.string.skill_title_quote
            "teach" -> R.string.skill_title_teach
            "chat" -> R.string.skill_title_chat
            "self_learn" -> R.string.skill_title_self_learn
            "continue_chat" -> R.string.skill_title_continue_chat
            "weather" -> R.string.skill_title_weather
            "outing" -> R.string.skill_title_outing
            "translate" -> R.string.skill_title_translate
            "knowledge" -> R.string.skill_title_knowledge
            "decide" -> R.string.skill_title_decide
            "online" -> R.string.skill_title_online
            "follow_up" -> R.string.skill_title_follow_up
            else -> 0
        },
        skill.title
    )

    private fun skillDesc(skill: Skill): String = pick(
        when (skill.id) {
            "time" -> R.string.skill_desc_time
            "date" -> R.string.skill_desc_date
            "weekday_calc" -> R.string.skill_desc_weekday_calc
            "timezone" -> R.string.skill_desc_timezone
            "arithmetic" -> R.string.skill_desc_arithmetic
            "calc_continue" -> R.string.skill_desc_calc_continue
            "unit_convert" -> R.string.skill_desc_unit_convert
            "age" -> R.string.skill_desc_age
            "random" -> R.string.skill_desc_random
            "support" -> R.string.skill_desc_support
            "reminder" -> R.string.skill_desc_reminder
            "timer" -> R.string.skill_desc_timer
            "torch" -> R.string.skill_desc_torch
            "battery" -> R.string.skill_desc_battery
            "open_app" -> R.string.skill_desc_open_app
            "echo" -> R.string.skill_desc_echo
            "guess" -> R.string.skill_desc_guess
            "joke" -> R.string.skill_desc_joke
            "quote" -> R.string.skill_desc_quote
            "teach" -> R.string.skill_desc_teach
            "chat" -> R.string.skill_desc_chat
            "self_learn" -> R.string.skill_desc_self_learn
            "continue_chat" -> R.string.skill_desc_continue_chat
            "weather" -> R.string.skill_desc_weather
            "outing" -> R.string.skill_desc_outing
            "translate" -> R.string.skill_desc_translate
            "knowledge" -> R.string.skill_desc_knowledge
            "decide" -> R.string.skill_desc_decide
            "online" -> R.string.skill_desc_online
            "follow_up" -> R.string.skill_desc_follow_up
            else -> 0
        },
        skill.description
    )

    private fun pick(res: Int, fallback: String): String =
        if (res == 0) fallback else getString(res)

    /** 中文分类名 → 当前语言的显示名 */
    private fun groupLabel(category: String): String = getString(
        when (category) {
            SkillCatalog.CATEGORY_TIME -> R.string.group_time
            SkillCatalog.CATEGORY_CALC -> R.string.group_calc
            SkillCatalog.CATEGORY_LIFE -> R.string.group_life
            SkillCatalog.CATEGORY_KNOWLEDGE -> R.string.group_knowledge
            SkillCatalog.CATEGORY_TOOL -> R.string.group_tool
            SkillCatalog.CATEGORY_FUN -> R.string.group_fun
            else -> R.string.group_dialog
        }
    )

    private fun messageView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        setPadding(dp(4), dp(24), dp(4), dp(24))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "FeaturesFragment"
    }
}
