package com.example.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.data.ChatMessage
import com.example.voiceassistant.speech.SpeechController
import com.example.voiceassistant.ui.ChatAdapter
import kotlin.math.max

class MainActivity : AppCompatActivity(), SpeechController.Listener {

    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnMic: ImageButton
    private lateinit var btnSend: ImageButton
    private lateinit var btnMute: ImageButton
    private lateinit var btnContinuous: ImageButton
    private lateinit var btnClear: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var headerBar: LinearLayout
    private lateinit var inputBar: LinearLayout

    private lateinit var adapter: ChatAdapter
    private lateinit var speech: SpeechController
    private val engine = AssistantEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speakEnabled = true

    /** 连续对话：一次点击后，每轮回复播完自动重新打开麦克风 */
    private var continuousMode = true

    /** 界面是否在前台，避免退到后台还在自动开麦克风 */
    private var isForeground = false

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                setStatus(getString(R.string.error_no_permission))
                Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        bindViews()
        applyWindowInsets()
        setupRecyclerView()
        setupSpeech()
        setupClicks()
        updateContinuousButton()

        adapter.add(ChatMessage(getString(R.string.welcome), isUser = false))
    }

    // ------------------------------------------------------------------ 初始化

    private fun bindViews() {
        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)
        btnSend = findViewById(R.id.btnSend)
        btnMute = findViewById(R.id.btnMute)
        btnContinuous = findViewById(R.id.btnContinuous)
        btnClear = findViewById(R.id.btnClear)
        tvStatus = findViewById(R.id.tvStatus)
        headerBar = findViewById(R.id.headerBar)
        inputBar = findViewById(R.id.inputBar)
    }

    /** 顶部栏避开状态栏，底部输入区避开导航栏与键盘 */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            headerBar.updatePadding(top = dp(14) + bars.top)
            inputBar.updatePadding(bottom = dp(10) + max(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter()
        rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = false
        }
        rvMessages.adapter = adapter
        rvMessages.itemAnimator?.changeDuration = 150
    }

    private fun setupSpeech() {
        speech = SpeechController(this)
        speech.listener = this
        if (!speech.isRecognitionAvailable()) {
            setStatus(getString(R.string.error_no_recognizer))
        }
    }

    private fun setupClicks() {
        btnMic.setOnClickListener {
            if (speech.isListening) {
                speech.stopListening()
            } else {
                ensurePermissionThenStart()
            }
        }

        btnSend.setOnClickListener {
            submitTypedText()
        }

        btnMute.setOnClickListener {
            speakEnabled = !speakEnabled
            speech.speakEnabled = speakEnabled
            btnMute.setImageResource(
                if (speakEnabled) R.drawable.ic_volume_on else R.drawable.ic_volume_off
            )
            if (!speakEnabled) {
                speech.stopSpeaking()
                setStatus(getString(R.string.status_muted))
            } else {
                setStatus(getString(R.string.status_idle))
            }
        }

        btnContinuous.setOnClickListener {
            continuousMode = !continuousMode
            updateContinuousButton()
            if (!continuousMode) {
                speech.cancelListening()
                resetMicButton()
            }
            setStatus(
                getString(
                    if (continuousMode) R.string.status_continuous_on
                    else R.string.status_continuous_off
                )
            )
        }

        btnClear.setOnClickListener {
            speech.stopSpeaking()
            adapter.clear()
            adapter.add(ChatMessage(getString(R.string.welcome), isUser = false))
            setStatus(getString(R.string.status_idle))
        }
    }

    // ------------------------------------------------------------------ 语音输入

    private fun ensurePermissionThenStart() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startVoiceInput()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceInput() {
        if (!speech.isRecognitionAvailable()) {
            setStatus(getString(R.string.error_no_recognizer))
            return
        }
        speech.stopSpeaking()
        speech.startListening()
    }

    override fun onReady() {
        setStatus(getString(R.string.status_listening), highlight = true)
        btnMic.animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).start()
    }

    override fun onPartialResult(text: String) {
        setStatus("识别中：$text", highlight = true)
    }

    override fun onEndOfSpeech() {
        setStatus(getString(R.string.status_recognizing))
        resetMicButton()
    }

    override fun onFinalResult(text: String) {
        resetMicButton()
        setStatus(getString(R.string.status_idle))
        handleUserInput(text)
    }

    override fun onError(message: String) {
        resetMicButton()
        setStatus(message)
        // 识别失败只在状态栏提示，不写进对话列表：
        // 连续对话时 "没听清" 会反复触发，写进列表会把对话刷屏
    }

    // ------------------------------------------------------------------ 对话

    private fun submitTypedText() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) {
            setStatus(getString(R.string.error_empty_input))
            return
        }
        etInput.setText("")
        handleUserInput(text)
    }

    private fun handleUserInput(text: String) {
        adapter.add(ChatMessage(text, isUser = true))
        scrollToBottom()
        setStatus(getString(R.string.status_thinking))

        // 稍微延迟一点，让"思考中"有个过渡，也避免和语音识别的回调抢时序
        mainHandler.postDelayed({
            val reply = engine.reply(text)
            adapter.add(ChatMessage(reply, isUser = false))
            scrollToBottom()
            if (speakEnabled) {
                setStatus(getString(R.string.status_speaking))
                // 播报结束后会回调 onSpeakingFinished，由它决定是否继续听
                speech.speak(reply)
            } else {
                setStatus(getString(R.string.status_idle))
                maybeRestartListening(400)
            }
        }, 350)
    }

    /** 一轮播报结束：连续对话模式下自动进入下一轮 */
    override fun onSpeakingFinished() {
        setStatus(getString(R.string.status_idle))
        maybeRestartListening(250)
    }

    /**
     * 重新打开麦克风。
     * 延迟一小段时间是为了让上一轮的 UI 动画和状态栏更新完，
     * 同时用 isSpeaking / isListening 双重判断防止重复启动。
     */
    private fun maybeRestartListening(delayMs: Long) {
        if (!continuousMode || !isForeground) return
        if (!speech.isRecognitionAvailable()) return
        if (!hasMicPermission()) {
            // 自动续听不能绕过权限申请（那是用户点麦克风时的路径），
            // 静默停在这里，等用户下一次手动点麦克风
            setStatus(getString(R.string.error_no_permission))
            return
        }
        mainHandler.postDelayed({
            if (continuousMode && isForeground && !speech.isSpeaking && !speech.isListening) {
                startVoiceInput()
            }
        }, delayMs)
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ 小工具

    private fun updateContinuousButton() {
        btnContinuous.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (continuousMode) R.color.brand_primary else R.color.text_secondary
            )
        )
        btnContinuous.alpha = if (continuousMode) 1f else 0.45f
        btnContinuous.contentDescription = getString(
            if (continuousMode) R.string.desc_continuous_on else R.string.desc_continuous_off
        )
    }

    private fun setStatus(text: String, highlight: Boolean = false) {
        tvStatus.text = text
        tvStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (highlight) R.color.brand_primary else R.color.text_secondary
            )
        )
    }

    private fun resetMicButton() {
        btnMic.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
    }

    private fun scrollToBottom() {
        val lastIndex = adapter.itemCount - 1
        if (lastIndex >= 0) rvMessages.smoothScrollToPosition(lastIndex)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        isForeground = false
        // 退到后台就停掉识别和播报，避免连续对话在后台一直抢占麦克风
        mainHandler.removeCallbacksAndMessages(null)
        speech.cancelListening()
        speech.stopSpeaking()
        resetMicButton()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        speech.release()
        super.onDestroy()
    }
}
