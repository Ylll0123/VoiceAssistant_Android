package com.example.voiceassistant.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.voiceassistant.R
import com.example.voiceassistant.memory.MemoryRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 教学相关的对话框，集中在一处。
 *
 * 对话页输入框上方那个「教学」按钮、记忆页右上角的「教一句」，
 * 走的都是这里的同一套流程——免得两处逻辑慢慢长歪。
 */
object TeachDialog {

    /** 「教学」菜单：教一句 / 看记忆 / 补答没答上来的 */
    fun showMenu(fragment: Fragment, onOpenMemory: () -> Unit = {}) {
        val host = fragment.requireActivity() as VoiceAssistantActivity
        val repository = repositoryOf(fragment) ?: return
        val pending = host.appSettings().pendingQuestions

        val items = arrayOf(
            fragment.getString(R.string.teach_action_new),
            fragment.getString(R.string.teach_action_memory, repository.entries().size),
            fragment.getString(R.string.teach_action_pending, pending.size)
        )

        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.teach_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> teachNew(fragment)
                    1 -> onOpenMemory()
                    else -> pickPending(fragment, pending)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 教一句全新的话：问法 + 回答 */
    fun teachNew(fragment: Fragment, onSaved: () -> Unit = {}) {
        val repository = repositoryOf(fragment) ?: return
        val context = fragment.requireContext()
        val padding = dp(fragment, 20)

        val etQuestion = EditText(context).apply {
            hint = fragment.getString(R.string.memory_teach_question_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val etAnswer = EditText(context).apply {
            hint = fragment.getString(R.string.memory_teach_answer_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, dp(fragment, 4), padding, 0)
            addView(etQuestion)
            addView(etAnswer)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.memory_teach_dialog_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val question = etQuestion.text.toString().trim()
                val answer = etAnswer.text.toString().trim()
                if (question.isEmpty() || answer.isEmpty()) {
                    toast(fragment, fragment.getString(R.string.memory_teach_incomplete))
                    return@setPositiveButton
                }
                repository.remember(question, answer)
                (fragment.requireActivity() as VoiceAssistantActivity)
                    .appSettings().removePending(question)
                toast(
                    fragment,
                    fragment.getString(R.string.memory_teach_saved, question, answer)
                )
                onSaved()
            }
            .show()
    }

    /** 从「没答上来的话」里挑一条补答案 */
    private fun pickPending(fragment: Fragment, pending: List<String>) {
        if (pending.isEmpty()) {
            toast(fragment, fragment.getString(R.string.memory_pending_empty))
            return
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.memory_pending_group)
            .setItems(pending.toTypedArray()) { _, which ->
                answerPending(fragment, pending[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 给某句没答上来的话补一个答案 */
    fun answerPending(fragment: Fragment, question: String, onSaved: () -> Unit = {}) {
        val repository = repositoryOf(fragment) ?: return
        val context = fragment.requireContext()
        val etAnswer = EditText(context).apply {
            hint = fragment.getString(R.string.memory_teach_answer_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(fragment, 20), dp(fragment, 4), dp(fragment, 20), 0)
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
                    toast(fragment, fragment.getString(R.string.memory_teach_incomplete))
                    return@setPositiveButton
                }
                repository.remember(question, answer)
                (fragment.requireActivity() as VoiceAssistantActivity)
                    .appSettings().removePending(question)
                toast(
                    fragment,
                    fragment.getString(R.string.memory_teach_pending_saved, question, answer)
                )
                onSaved()
            }
            .show()
    }

    private fun repositoryOf(fragment: Fragment): MemoryRepository? {
        val host = fragment.requireActivity() as VoiceAssistantActivity
        val repository = host.session.engine.memoryRepository()
        if (repository == null) {
            toast(fragment, fragment.getString(R.string.memory_unavailable))
        }
        return repository
    }

    private fun toast(fragment: Fragment, text: String) {
        Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(fragment: Fragment, value: Int): Int =
        (value * fragment.resources.displayMetrics.density).toInt()
}
