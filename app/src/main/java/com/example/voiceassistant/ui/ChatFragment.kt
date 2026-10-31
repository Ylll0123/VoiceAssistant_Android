package com.example.voiceassistant.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.MainActivity
import com.example.voiceassistant.R
import com.example.voiceassistant.data.ChatMessage
import com.example.voiceassistant.data.ChatSession
import android.content.res.ColorStateList

/**
 * 对话页：消息列表 + 输入区 + 语音按钮。
 *
 * 不持有语音资源（那在 Activity 里），只把用户操作转发给 Activity，
 * 再接收 Activity 的状态回调更新顶栏。
 */
class ChatFragment : Fragment(), MainActivity.ChatUi, ChatSession.Listener {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnMic: ImageButton
    private lateinit var btnSend: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnContinuous: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var btnSkills: TextView
    private lateinit var tvStatus: TextView
    private lateinit var root: LinearLayout

    private lateinit var adapter: ChatAdapter

    private val host: MainActivity
        get() = requireActivity() as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.chat_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        applyImeInsets()
        setupList()
        setupClicks()

        host.bindChatUi(this)
        host.session.attach(this)   // 立即回调一次，重建视图后也能恢复全部消息
        refreshToolbar()
        status(getString(R.string.status_idle))
    }

    private fun bindViews(view: View) {
        root = view.findViewById(R.id.chatRoot)
        rvMessages = view.findViewById(R.id.rvMessages)
        etInput = view.findViewById(R.id.etInput)
        btnMic = view.findViewById(R.id.btnMic)
        btnSend = view.findViewById(R.id.btnSend)
        btnMute = view.findViewById(R.id.btnMute)
        btnContinuous = view.findViewById(R.id.btnContinuous)
        btnClear = view.findViewById(R.id.btnClear)
        btnSkills = view.findViewById(R.id.btnSkills)
        tvStatus = view.findViewById(R.id.tvStatus)
    }

    /**
     * 键盘 inset 的补偿统一由 Activity 的根布局做（见 MainActivity.applyInsets），
     * 这里只负责一件补充的事：键盘弹起时把消息列表滚到最后一条，
     * 保证「最后一条聊天记录显示在输入行上方」（微信 / QQ 的行为）。
     */
    private fun applyImeInsets() {
        var lastImeBottom = -1
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (ime.bottom != lastImeBottom) {
                lastImeBottom = ime.bottom
                if (ime.bottom > 0) root.post { scrollToBottom() }
            }
            insets
        }
    }

    private fun setupList() {
        adapter = ChatAdapter()
        val manager = LinearLayoutManager(requireContext())
        // 从底部开始排：消息少的时候也贴着输入栏，而不是顶在列表上方
        manager.stackFromEnd = true
        rvMessages.layoutManager = manager
        rvMessages.adapter = adapter
        rvMessages.itemAnimator?.changeDuration = 150
    }

    private fun setupClicks() {
        btnMic.setOnClickListener { host.onMicClicked() }

        btnSend.setOnClickListener {
            val text = etInput.text.toString()
            etInput.setText("")
            host.onSendClicked(text)
        }

        btnMute.setOnClickListener {
            val enabled = host.toggleSpeak()
            refreshToolbar()
            if (enabled) {
                status(getString(R.string.status_idle))
            } else {
                status(getString(R.string.status_muted))
            }
        }

        btnContinuous.setOnClickListener {
            val enabled = host.toggleContinuous()
            refreshToolbar()
            status(
                getString(
                    if (enabled) R.string.status_continuous_on
                    else R.string.status_continuous_off
                )
            )
        }

        btnClear.setOnClickListener { host.clearChat() }

        btnSkills.setOnClickListener { host.openSkills() }
    }

    // ---------------------------------------------------------------- 状态回调

    override fun onMessagesChanged(messages: List<ChatMessage>) {
        adapter.submit(messages)
        scrollToBottom()
    }

    override fun showStatus(text: String, highlight: Boolean) {
        if (!isAdded) return
        tvStatus.text = text
        tvStatus.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (highlight) R.color.brand_primary else R.color.text_secondary
            )
        )
    }

    /** 不带高亮的常用写法，免得每个调用点都写 false */
    private fun status(text: String) = showStatus(text, false)

    override fun setMicActive(active: Boolean) {
        if (!isAdded) return
        btnMic.animate()
            .scaleX(if (active) 1.12f else 1f)
            .scaleY(if (active) 1.12f else 1f)
            .setDuration(160)
            .start()
    }

    // ---------------------------------------------------------------- 小工具

    /** 顶栏图标跟随 Activity 里的开关状态 */
    private fun refreshToolbar() {
        val active = ContextCompat.getColor(requireContext(), R.color.brand_primary)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_secondary)

        btnMute.setImageResource(
            if (host.speakEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
        )
        btnMute.imageTintList = ColorStateList.valueOf(muted)

        btnContinuous.imageTintList =
            ColorStateList.valueOf(if (host.continuousMode) active else muted)
        btnContinuous.alpha = if (host.continuousMode) 1f else 0.45f
        btnContinuous.contentDescription = getString(
            if (host.continuousMode) R.string.desc_continuous_on else R.string.desc_continuous_off
        )
    }

    private fun scrollToBottom() {
        val lastIndex = adapter.itemCount - 1
        if (lastIndex >= 0) rvMessages.smoothScrollToPosition(lastIndex)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // 从设置页改了开关再回来，图标要跟着更新
        if (isAdded) {
            refreshToolbar()
            host.session.attach(this)
        }
    }

    override fun onDestroyView() {
        host.session.attach(null)
        host.unbindChatUi(this)
        super.onDestroyView()
    }
}
