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
 * - 答辩时可以直接展示「预置 26 条 + 我教的 N 条」这个会增长的数字
 */
class MemoryFragment : Fragment() {

    private lateinit var adapter: MemoryAdapter
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView

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

        adapter = MemoryAdapter { entry -> confirmDelete(entry) }
        view.findViewById<RecyclerView>(R.id.rvMemory).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@MemoryFragment.adapter
            setHasFixedSize(false)
        }

        view.findViewById<MaterialButton>(R.id.btnTeach).setOnClickListener { showTeachDialog() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) refresh()
    }

    private fun refresh() {
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
        tvCount.text = getString(R.string.memory_count, entries.size, taught)

        tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = getString(R.string.memory_empty)
        adapter.submitList(entries)
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
}
