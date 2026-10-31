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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.MainActivity
import com.example.voiceassistant.R
import com.example.voiceassistant.memory.MemoryEntry
import com.example.voiceassistant.memory.MemoryRepository
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 本地记忆页：看得见的学习成果。
 *
 * 这一页存在的意义不只是「管理」：
 * - 用户能确认助手到底记住了什么（不是黑箱）
 * - 答得不好可以直接删掉重教
 * - 「没答上来的话」是自学习的入口：聊天时答不上来的说法会自动收进来，
 *   点一下就能教会它，教完立刻能用
 * - 答辩时可以直接展示「内置 / 我教的 / 它自学的」三个会增长的数字
 */
class MemoryFragment : Fragment() {

    private lateinit var adapter: MemoryAdapter
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var tvPendingTitle: TextView
    private lateinit var tvPendingEmpty: TextView
    private lateinit var pendingContainer: LinearLayout

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    private val repository: MemoryRepository?
        get() = host.session.engine.memoryRepository()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_memory, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvCount = view.findViewById(R.id.tvMemoryCount)
        tvEmpty = view.findViewById(R.id.tvMemoryEmpty)
        tvPendingTitle = view.findViewById(R.id.tvPendingTitle)
        tvPendingEmpty = view.findViewById(R.id.tvPendingEmpty)
        pendingContainer = view.findViewById(R.id.pendingContainer)

        adapter = MemoryAdapter { entry -> confirmDelete(entry) }
        view.findViewById<RecyclerView>(R.id.rvMemory).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MemoryFragment.adapter
            setHasFixedSize(false)
            // 整个页面已经是一层 ScrollView，列表不再单独滚动
            isNestedScrollingEnabled = false
        }

        view.findViewById<MaterialButton>(R.id.btnTeach).setOnClickListener { showTeachDialog() }
        view.findViewById<MaterialButton>(R.id.btnClearPending).setOnClickListener {
            host.appSettings().pendingQuestions = emptyList()
            refresh()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) refresh()
    }

    private fun refresh() {
        renderPending()

        val repo = repository
        if (repo == null) {
            tvCount.text = getString(R.string.memory_unavailable)
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.memory_unavailable)
            adapter.submitList(emptyList())
            return
        }

        val entries = repo.entries()
        val taught = entries.count { it.isTaught }
        val learned = entries.count { it.isLearned }
        val seed = entries.count { it.source == MemoryRepository.SOURCE_SEED }
        tvCount.text = getString(R.string.memory_count_full, entries.size, taught, learned, seed)

        tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = getString(R.string.memory_empty)
        adapter.submitList(entries)
    }

    /**
     * 渲染「没答上来的话」。
     *
     * 这是自学习闭环的另一半：引擎在对话里答不上来就把说法记下来，
     * 用户在这里补上答案，就变成了正式记忆。
     */
    private fun renderPending() {
        val pending = host.appSettings().pendingQuestions
        tvPendingTitle.text = getString(R.string.memory_pending_group, pending.size)
        tvPendingEmpty.visibility = if (pending.isEmpty()) View.VISIBLE else View.GONE

        pendingContainer.removeAllViews()
        // 最多显示 8 条，避免把下面的记忆列表挤没了
        pending.take(MAX_PENDING_ROWS).forEach { question ->
            pendingContainer.addView(buildPendingRow(question))
        }
    }

    private fun buildPendingRow(question: String): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val pad = dp(12)
            setPadding(pad, dp(10), pad, dp(10))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(8)
            layoutParams = params
        }

        val text = TextView(requireContext()).apply {
            this.text = question
            setTextColor(resources.getColor(R.color.text_primary, null))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val button = MaterialButton(
            requireContext(),
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            this.text = getString(R.string.memory_teach)
            textSize = 12f
            minHeight = dp(32)
            cornerRadius = dp(16)
            setOnClickListener { showTeachAnswerDialog(question) }
        }

        row.addView(text)
        row.addView(button)
        return row
    }

    /** 直接给某句没答上来的话补一个答案 */
    private fun showTeachAnswerDialog(question: String) {
        val repo = repository ?: return
        val context = requireContext()
        val etAnswer = EditText(context).apply {
            hint = getString(R.string.memory_teach_answer_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(etAnswer)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.memory_teach_pending_title)
            .setMessage(question)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val answer = etAnswer.text.toString().trim()
                if (answer.isEmpty()) {
                    toast(getString(R.string.memory_teach_incomplete))
                    return@setPositiveButton
                }
                repo.remember(question, answer)
                host.appSettings().removePending(question)
                toast(getString(R.string.memory_teach_pending_saved, question, answer))
                refresh()
            }
            .show()
    }

    private fun showTeachDialog() {
        val repo = repository ?: return
        val context = requireContext()
        val padding = dp(20)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(4), padding, 0)
        }
        val etQuestion = EditText(context).apply {
            hint = getString(R.string.memory_teach_question_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val etAnswer = EditText(context).apply {
            hint = getString(R.string.memory_teach_answer_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        container.addView(etQuestion)
        container.addView(etAnswer)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.memory_teach_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val question = etQuestion.text.toString().trim()
                val answer = etAnswer.text.toString().trim()
                if (question.isEmpty() || answer.isEmpty()) {
                    toast(getString(R.string.memory_teach_incomplete))
                    return@setPositiveButton
                }
                repo.remember(question, answer)
                host.appSettings().removePending(question)
                toast(getString(R.string.memory_teach_saved, question, answer))
                refresh()
            }
            .show()
    }

    private fun confirmDelete(entry: MemoryEntry) {
        val repo = repository ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.memory_delete_title)
            .setMessage(R.string.memory_delete_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                repo.delete(entry.id)
                refresh()
            }
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_PENDING_ROWS = 8
    }
}
