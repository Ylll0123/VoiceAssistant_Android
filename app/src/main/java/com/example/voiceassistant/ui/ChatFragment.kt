package com.example.voiceassistant.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.data.ChatMessage
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 对话页：消息列表 + 输入区。
 *
 * ## 输入方式（v4.0）
 * 没有麦克风按钮：**长按输入框说话，松手结束，上滑取消**。
 * 不按的时候不会自动收音——这是和以前的「连续对话」最大的区别，
 * 用户不用担心对着手机说别的也被录进去。
 *
 * 松手后识别结果只是**填进输入框**，还要点一下发送：说错了还有机会改。
 *
 * ## 气泡操作
 * 长按自己的气泡弹「修改 / 删除」，而且只对**最后一条**用户消息生效——
 * 改掉中间那句会让后面的回答对不上，不如不给改。
 * 修改直接在气泡里进行（[ChatAdapter] 的行内编辑）。
 */
class ChatFragment : Fragment(), ChatSessionView {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var root: View

    private lateinit var adapter: ChatAdapter
    private lateinit var host: VoiceAssistantActivity

    private var listener: com.example.voiceassistant.data.ChatSession.Listener? = null

    private var listening = false
    private var cancelArmed = false
    private var downY = 0f

    private val holdToTalk = Runnable {
        if (host.isOnline()) startListening() else toast(getString(R.string.status_offline_hint))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.chat_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        host = requireActivity() as VoiceAssistantActivity
        root = view

        rvMessages = view.findViewById(R.id.rvMessages)
        etInput = view.findViewById(R.id.etInput)
        btnSend = view.findViewById(R.id.btnSend)

        setupList()
        setupInput()
        setupIme()

        host.bindChatUi(this)
        listener = object : com.example.voiceassistant.data.ChatSession.Listener {
            override fun onMessagesChanged(messages: List<ChatMessage>) {
                adapter.submit(messages)
                scrollToBottom()
            }
        }
        host.session.attach(listener)
    }

    // ------------------------------------------------------------------ 列表

    private fun setupList() {
        adapter = ChatAdapter(
            onLongPress = { index -> onBubbleLongPress(index) },
            onSaveEdit = { index, text ->
                if (host.updateMessage(index, text)) {
                    // 改完就进入了「重新回答」，键盘留着只会挡住新答案
                    hideKeyboard()
                    toast(getString(R.string.bubble_edited))
                } else {
                    adapter.stopEdit()
                }
            }
        )
        // 从上方排：第一句贴着顶部，多了往下长；不 stackFromEnd 就不会出现「只有一句却蹲在底部」
        val manager = LinearLayoutManager(requireContext())
        rvMessages.layoutManager = manager
        rvMessages.adapter = adapter
        // 行内编辑时高度会跳，关掉动画免得像抽了一下
        rvMessages.itemAnimator = null
    }

    private fun scrollToBottom() {
        val last = adapter.itemCount - 1
        if (last >= 0) rvMessages.scrollToPosition(last)
    }

    // ------------------------------------------------------------------ 输入：长按说话

    private fun setupInput() {
        btnSend.setOnClickListener {
            val text = etInput.text.toString()
            if (text.isBlank()) return@setOnClickListener
            host.sendText(text)
        }

        etInput.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    cancelArmed = false
                    etInput.postDelayed(holdToTalk, HOLD_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!listening) return@setOnTouchListener true
                    // 上滑够了就进入「松手取消」，松回去还能救回来
                    val armed = downY - event.rawY > dp(CANCEL_DP)
                    if (armed != cancelArmed) {
                        cancelArmed = armed
                        etInput.hint = getString(
                            if (armed) R.string.status_listening_cancel
                            else R.string.status_listening_hold
                        )
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    etInput.removeCallbacks(holdToTalk)
                    if (listening) {
                        host.endVoice(cancelArmed)
                        resetListening()
                    } else {
                        // 没到长按时长：当成一次点击，聚焦并弹键盘
                        focusInput()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    etInput.removeCallbacks(holdToTalk)
                    if (listening) {
                        host.endVoice(true)
                        resetListening()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun startListening() {
        listening = true
        cancelArmed = false
        etInput.hint = getString(R.string.status_listening_hold)
        host.beginVoice()
    }

    /** Activity 通过 [setListening] 回调，这里只负责把状态改回普通输入框 */
    private fun resetListening() {
        listening = false
        cancelArmed = false
        etInput.hint = getString(R.string.hint_input_hold)
    }

    override fun setListening(active: Boolean, cancel: Boolean) {
        if (!active) {
            resetListening()
            return
        }
        listening = true
        cancelArmed = cancel
        etInput.hint = getString(
            if (cancel) R.string.status_listening_cancel else R.string.status_listening_hold
        )
    }

    // ------------------------------------------------------------------ 气泡

    private fun onBubbleLongPress(index: Int) {
        if (!host.isEditable(index)) {
            toast(getString(R.string.bubble_only_last))
            return
        }
        val items = arrayOf(getString(R.string.action_edit), getString(R.string.action_delete))
        MaterialAlertDialogBuilder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> host.startEdit(index)
                    else -> confirmDelete(index)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(index: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.bubble_delete_confirm_title)
            .setMessage(R.string.bubble_delete_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                if (host.deleteTurn(index)) toast(getString(R.string.bubble_delete_done))
            }
            .show()
    }

    // ------------------------------------------------------------------ 对外

    override fun refresh() {
        adapter.submit(host.session.messages)
        scrollToBottom()
    }

    override fun clearInput() {
        etInput.setText("")
        resetListening()
    }

    override fun setInput(text: String) {
        etInput.setText(text)
        etInput.setSelection(text.length)
        focusInput()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
        root.clearFocus()
    }

    override fun focusInput() {
        etInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun startEdit(index: Int): Boolean = adapter.startEdit(index)

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------ 键盘

    /** 键盘弹起时把最后一条滚上来（微信的做法），补偿统一在 Activity 根布局做 */
    private fun setupIme() {
        var lastIme = -1
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime != lastIme) {
                lastIme = ime
                if (ime > 0) root.post { scrollToBottom() }
            }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        if (isAdded) refresh()
    }

    override fun onDestroyView() {
        etInput.removeCallbacks(holdToTalk)
        host.session.attach(null)
        host.unbindChatUi(this)
        super.onDestroyView()
    }

    private companion object {
        /** 长按多久算「要说话」：380ms 接近系统长按手感 */
        const val HOLD_MS = 380L

        /** 上滑多少像素算取消 */
        // 用 dp 在代码里算，避免不同密度下手感差太多
        val CANCEL_DP = 56
    }
}

/** 对话页需要 Activity 提供的那几个动作，单独抽出来方便测试替身 */
interface ChatSessionView {
    fun refresh()
    fun clearInput()
    fun setInput(text: String)
    fun focusInput()
    fun startEdit(index: Int): Boolean
    fun setListening(active: Boolean, cancel: Boolean)
}
