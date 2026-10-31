package com.example.voiceassistant.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.voiceassistant.MainActivity
import com.example.voiceassistant.R
import com.example.voiceassistant.assistant.SkillCatalog

/**
 * 技能页：把助手的全部能力按分类列出来。
 *
 * 每个示例都是一个可以点的「快捷指令」，点了就切回对话页执行。
 * 数据来自 [SkillCatalog]，和引擎共用同一份，所以不会出现
 * 「页面上写着能做、实际说却不行」的情况。
 *
 * 构建过程带兜底：万一某个机型上动态添加视图出错，
 * 页面会显示错误原因而不是一片空白，方便定位问题。
 */
class SkillsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_skills, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val container = view.findViewById<LinearLayout>(R.id.skillContainer)

        runCatching { buildSkills(container) }
            .onFailure { error ->
                Log.e(TAG, "技能列表构建失败", error)
                container.removeAllViews()
                container.addView(
                    messageView(
                        getString(
                            R.string.skills_load_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                )
            }

        if (container.childCount == 0) {
            container.addView(messageView(getString(R.string.skills_empty)))
        }
    }

    private fun buildSkills(container: LinearLayout) {
        val inflater = LayoutInflater.from(requireContext())

        SkillCatalog.categories.forEach { category ->
            val skills = SkillCatalog.byCategory(category)
            if (skills.isEmpty()) return@forEach

            container.addView(buildCategoryHeader(category))

            skills.forEach { skill ->
                val card = inflater.inflate(R.layout.item_skill, container, false)
                card.findViewById<TextView>(R.id.tvSkillTitle).text = skill.title
                card.findViewById<TextView>(R.id.tvSkillDesc).text = skill.description

                val examples = card.findViewById<LinearLayout>(R.id.exampleContainer)
                skill.examples.forEachIndexed { index, example ->
                    val chip = inflater.inflate(
                        R.layout.item_example_chip,
                        examples,
                        false
                    ) as TextView
                    chip.text = example
                    if (index > 0) {
                        (chip.layoutParams as ViewGroup.MarginLayoutParams).marginStart = dp(8)
                    }
                    chip.setOnClickListener {
                        (requireActivity() as MainActivity).runFromSkill(example)
                    }
                    examples.addView(chip)
                }
                container.addView(card)
            }
        }
    }

    private fun buildCategoryHeader(title: String): TextView = TextView(requireContext()).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        setPadding(0, dp(8), 0, dp(10))
    }

    /** 空态 / 错误提示，保证页面不会白屏 */
    private fun messageView(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        setPadding(0, dp(24), 0, dp(24))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "SkillsFragment"
    }
}
