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
import kotlin.math.abs

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
    private var downX = 0f
    private var movedWhileDown = false

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

        etInput.setOnClickListener {
            // 点一下就聚焦并弹键盘；光标位置由 EditText 自己算，这里只负责把输入法叫出来
            focusInput()
        }

        /*
         * 长按说话用系统的长按回调，而不是自己按时间算。
         *
         * 两个原因：
         * 1. 返回 true 就把长按**消费掉**了，EditText 不会再去弹「全选 / 复制」那套菜单，
         *    否则一边录音一边弹菜单，界面会乱；
         * 2. 长按已经消费，抬手时 TextView 不会 performClick，光标也不会被拽到手指位置。
         */
        etInput.setOnLongClickListener {
            if (host.isOnline()) startListening() else toast(getString(R.string.status_offline_hint))
            true
        }

        /*
         * 触摸只干两件事：记下起点、上滑判断取消、**短按时精确定位光标**。
         *
         * ⚠️ ACTION_DOWN / 短按的 ACTION_UP 一律返回 false，事件要完整交回 EditText，
         * 否则系统不会自己弹键盘、也不会亮起光标手柄。
         *
         * 但只靠系统默认定位是不够的：
         * 1) 输入框还没拿到焦点时，第一次点击 Android 只做「聚焦」，光标仍留在原处；
         * 2) 多行文字里点行末、点换行附近，系统会把光标归到上一行/下一行。
         * 所以短按抬手时按手指坐标自己算一次插入点，补上这两种情况。
         * 长按说话和滑动取消的语义完全不变。
         */
        etInput.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    downX = event.x
                    movedWhileDown = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawY - downY) > dp(8) || abs(event.x - downX) > dp(8)) {
                        movedWhileDown = true
                    }
                    if (!listening) {
                        // 不录音时连移动也放行：系统要靠它拖光标手柄、选文字
                        false
                    } else {
                        // 上滑够了就进入「松手取消」，松回去还能救回来
                        val armed = downY - event.rawY > dp(CANCEL_DP)
                        if (armed != cancelArmed) {
                            cancelArmed = armed
                            etInput.hint = getString(
                                if (armed) R.string.status_listening_cancel
                                else R.string.status_listening_hold
                            )
                        }
                        // 录音时吃掉移动事件，否则输入框会跟着手指滚
                        true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!listening) {
                        // 短按（没拖动、没触发长按）：把光标精确放到手指点中的字符间。
                        // 必须 post 到队尾——performClick → focusInput → 弹键盘排在后面，
                        // 输入法重启有概率把 selection 拉回末尾，抢在它前面会被覆盖。
                        if (!movedWhileDown) {
                            val x = event.x
                            val y = event.y
                            etInput.post {
                                if (!listening && !movedWhileDown) placeCursorAt(x, y)
                            }
                        }
                        false
                    } else {
                        host.endVoice(cancelArmed)
                        resetListening()
                        true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (listening) {
                        host.endVoice(true)
                        resetListening()
                    }
                    false
                }

                else -> false
            }
        }
    }

    /**
     * 把光标精确放到手指点中的位置。
     *
     * 关键是**换算到文字坐标系**：EditText 有内边距、多行时还能纵向滚动，
     * 直接拿 `event.x/y` 去问 Layout 会整体偏一段。哪一行用 vertical 反查，
     * 行内第几个字交给 [CursorMath] 取最近的插入点。
     *
     * 只在「短按且没拖动」时调用：拖动是系统在拖光标手柄 / 选文字，
     * 这时候插一脚会把手柄抢走。
     */
    private fun placeCursorAt(x: Float, y: Float) {
        val layout = etInput.layout ?: return
        val text = etInput.text ?: return
        if (text.isEmpty()) return

        val textX = x - etInput.totalPaddingLeft + etInput.scrollX
        val textY = y - etInput.totalPaddingTop + etInput.scrollY
        val lastY = (layout.height - 1).coerceAtLeast(0)
        val line = layout.getLineForVertical(textY.toInt().coerceIn(0, lastY))
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)

        // 行内每个插入点的 x。
        // 最后那个位置必须用「文末那个字符的右边界」，不能用 getLineRight：
        // EditText 是撑满整行的，getLineRight 是整行右边界（近 800px），
        // 点行尾空白时它反而离手指最远，光标会停在倒数第二位。
        val positions = FloatArray(end - start + 1) { i ->
            layout.getPrimaryHorizontal(start + i)
        }
        val offset = CursorMath.clamp(start + CursorMath.nearestIndex(positions, textX), text.length)
        if (offset != etInput.selectionStart || offset != etInput.selectionEnd) {
            etInput.setSelection(offset)
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
        requireContext().appDialog()
            .setItems(items) { _, which ->
                when (which) {
                    0 -> host.startEdit(index)
                    else -> confirmDelete(index)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showWhite()
    }

    private fun confirmDelete(index: Int) {
        requireContext().appDialog()
            .setTitle(R.string.bubble_delete_confirm_title)
            .setMessage(R.string.bubble_delete_confirm_msg)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                if (host.deleteTurn(index)) toast(getString(R.string.bubble_delete_done))
            }
            .showWhite()
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
        host.session.attach(null)
        host.unbindChatUi(this)
        super.onDestroyView()
    }

    private companion object {
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
