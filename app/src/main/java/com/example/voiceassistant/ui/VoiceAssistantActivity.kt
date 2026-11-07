package com.example.voiceassistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voiceassistant.R
import com.example.voiceassistant.core.AiChat
import com.example.voiceassistant.data.AccountStore
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.ChatMessage
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.data.Conversation
import com.example.voiceassistant.data.ConversationStore
import com.example.voiceassistant.data.LocaleHelper
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.installer.ApkCleaner
import com.example.voiceassistant.network.LlmMessage
import com.example.voiceassistant.network.NetworkState
import com.example.voiceassistant.reminder.ReminderAlarm
import com.example.voiceassistant.reminder.ReminderFired
import com.example.voiceassistant.reminder.Reminders
import com.example.voiceassistant.service.AndroidAudioController
import com.example.voiceassistant.service.AndroidDeviceController
import com.example.voiceassistant.service.AsrManager
import com.example.voiceassistant.service.TtsManager
import com.example.voiceassistant.tool.AiTools
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

/**
 * 应用外壳：**登录门禁 + 顶栏 + 抽屉 + 一轮对话的调度**。
 *
 * 界面结构（v4.0）：
 * ```
 * ┌──────────────────────────────┐
 * │ ☰        HardlySeek        ⚙ │   ← 标题下方：● 在线 / 离线，思考时被替换
 * ├──────────────────────────────┤
 * │                              │
 * │        消息列表               │
 * │                              │
 * ├──────────────────────────────┤
 * │ [ 说点什么… 长按可以说话 ]  ➤ │   ← 没有麦克风键，长按输入框说话
 * └──────────────────────────────┘
 * 左滑出来的抽屉（占屏宽 4/5）：对话记录列表 + 新建
 * ```
 *
 * 这一层只做四件事：收输入、调 [AiChat]、上屏、落盘。所有「怎么回答」都在 core 里。
 */
class VoiceAssistantActivity : AppCompatActivity(), AsrManager.Listener, TtsManager.Listener {

    /** 当前这次对话（抽屉里的一条记录） */
    private lateinit var conversations: ConversationStore
    private var current: Conversation? = null

    /** 消息 + 多轮上下文 */
    lateinit var session: ChatSession
        private set

    private lateinit var settings: AppSettings

    lateinit var asr: AsrManager
        private set

    lateinit var tts: TtsManager
        private set

    var cloudConfig: CloudLlmHandler.CloudConfig = CloudLlmHandler.CloudConfig()
        private set

    var speakEnabled: Boolean = true
        private set

    var replyStyle: Int = 2
        private set

    private lateinit var ai: AiChat
    private lateinit var aiTools: AiTools

    /** 单线程：同一时刻只有一个请求在飞，回来按顺序上屏 */
    private val executor = Executors.newSingleThreadExecutor {
        Thread(it, "assistant-net").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** 轮次号：新的一句 / 切换对话都会 +1，旧结果回来直接丢掉 */
    private var turnSeq = 0

    // ------------------------------------------------------------------ 视图

    private lateinit var btnMenu: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var statusRow: LinearLayout
    private lateinit var dotStatus: View
    private lateinit var tvConn: TextView
    private lateinit var tvThinking: TextView
    private lateinit var drawerPanel: LinearLayout
    private lateinit var scrim: View
    private lateinit var rvConversations: RecyclerView
    private lateinit var drawerAdapter: ConversationAdapter

    private var chatView: ChatFragment? = null
    private var settingsOpen = false
    private var online = true
    private var drawerOpen = false
    private var isForeground = false

    private val networkListener = NetworkState.Listener { isOnline ->
        online = isOnline
        updateConnUi()
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                toast(getString(R.string.error_no_permission))
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(getString(R.string.status_notify_denied))
        }

    private val apkCleanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val deleted = result.resultCode == Activity.RESULT_OK
            Toast.makeText(
                this,
                if (deleted) R.string.apk_clean_done else R.string.apk_clean_kept,
                Toast.LENGTH_SHORT
            ).show()
        }

    // ------------------------------------------------------------------ 启动

    /** 界面语言在 Context 构造阶段就换好，必须早于 setContentView */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, AppSettings.readLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 强制登录：没登录一律去登录页（v4.0 起没有游客）
        if (!AccountStore(this).isSignedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)
        conversations = ConversationStore(this)

        Reminders.install(this)
        NetworkState.init(this)

        asr = AsrManager(this).also { it.listener = this }
        tts = TtsManager(this).also { it.listener = this }
        restoreSettings()

        aiTools = AiTools(
            context = this,
            device = AndroidDeviceController(applicationContext),
            audio = AndroidAudioController(applicationContext),
            stopSpeaking = { tts.stop() }
        )
        ai = AiChat(aiTools, config = { cloudConfig })

        session = ChatSession()
        session.onChanged = { saveCurrent() }

        bindViews()
        applyInsets()
        online = NetworkState.isOnline()
        updateConnUi()
        bindToolbar()
        bindDrawer()
        setupBack()

        // 先挂上对话页：session 装载完要有人接住回调，否则列表永远是空的
        showChatFragment()
        openConversation(conversations.current())

        subscribeReminder()

        askNotificationPermission()
        main.postDelayed({ cleanInstallerOnce() }, 1200)

        if (savedInstanceState == null) handleExternalQuery(intent)
    }

    /** 外部传入的一句话（「发送到 HardlySeek」/ 自动化测试） */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalQuery(intent)
    }

    private fun handleExternalQuery(intent: Intent?) {
        val query = intent?.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty()) return
        intent?.removeExtra(EXTRA_QUERY)
        main.postDelayed({ sendText(query) }, 350)
    }

    // ------------------------------------------------------------------ 视图绑定

    private fun bindViews() {
        btnMenu = findViewById(R.id.btnMenu)
        btnSettings = findViewById(R.id.btnSettings)
        statusRow = findViewById(R.id.statusRow)
        dotStatus = findViewById(R.id.dotStatus)
        tvConn = findViewById(R.id.tvConn)
        tvThinking = findViewById(R.id.tvThinking)
        drawerPanel = findViewById(R.id.drawerPanel)
        scrim = findViewById(R.id.scrim)
        rvConversations = findViewById(R.id.rvConversations)
    }

    private fun applyInsets() {
        val root: View = findViewById(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun bindToolbar() {
        btnMenu.setOnClickListener {
            if (settingsOpen) closeSettings() else openDrawer()
        }
        btnSettings.setOnClickListener { openSettings() }
    }

    private fun updateConnUi() {
        val color = ContextCompat.getColor(
            this,
            if (online) R.color.online_green else R.color.offline_red
        )
        dotStatus.backgroundTintList = ColorStateList.valueOf(color)
        tvConn.setTextColor(color)
        tvConn.text = getString(if (online) R.string.net_online else R.string.net_offline)
        // 从离线恢复：把状态行那句提示收回去
        if (online && tvThinking.visibility != View.VISIBLE) statusRow.visibility = View.VISIBLE
    }

    /**
     * 顶栏第二行：正常情况下是在线状态，思考时被一句话顶掉。
     *
     * 这两者共用同一个位置——标题正下方只留一行，
     * 既满足「思考在标题下面」，也不会把顶栏撑成三行。
     */
    private fun setThinking(text: String?) {
        if (text.isNullOrBlank()) {
            tvThinking.visibility = View.GONE
            statusRow.visibility = View.VISIBLE
        } else {
            tvThinking.text = text
            tvThinking.visibility = View.VISIBLE
            statusRow.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ 抽屉

    private fun bindDrawer() {
        drawerAdapter = ConversationAdapter(
            onClick = { openConversation(it) },
            onLongClick = { confirmDeleteConversation(it) }
        )
        rvConversations.layoutManager = LinearLayoutManager(this)
        rvConversations.adapter = drawerAdapter

        findViewById<TextView>(R.id.tvDrawerUser).text =
            AccountStore(this).currentUser.orEmpty()

        findViewById<View>(R.id.btnNewChat).setOnClickListener {
            closeDrawer()
            newConversation()
        }
        scrim.setOnClickListener { closeDrawer() }
    }

    private fun refreshDrawer() {
        val list = conversations.list()
        drawerAdapter.submit(list, current?.id)
    }

    private fun openDrawer() {
        drawerOpen = true
        refreshDrawer()
        // 抽屉占屏宽 4/5，比侧栏宽、比全屏窄，露出后面的对话
        val width = (resources.displayMetrics.widthPixels * 0.8f).toInt()
        drawerPanel.layoutParams = drawerPanel.layoutParams.apply { this.width = width }
        drawerPanel.translationX = -width.toFloat()
        drawerPanel.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.visibility = View.VISIBLE
        drawerPanel.animate().translationX(0f).setDuration(220).start()
        scrim.animate().alpha(1f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        val width = drawerPanel.width.toFloat().coerceAtLeast(1f)
        drawerPanel.animate().translationX(-width).setDuration(180)
            .withEndAction { drawerPanel.visibility = View.GONE }
            .start()
        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }
            .start()
    }

    // ------------------------------------------------------------------ 对话记录

    private fun openConversation(conversation: Conversation) {
        turnSeq++
        current = conversation
        conversations.select(conversation.id)

        val saved = conversations.messages(conversation.id)
        if (saved.isEmpty()) {
            session.load(listOf(welcome()))
        } else {
            session.load(saved)
        }
        chatView?.refresh()
        refreshDrawer()
        closeDrawer()
        setThinking(null)
    }

    private fun newConversation() {
        val conversation = conversations.create(getString(R.string.conv_new_title))
        openConversation(conversation)
        chatView?.focusInput()
    }

    private fun confirmDeleteConversation(conversation: Conversation) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.bubble_delete_confirm_title))
            .setMessage(conversation.title)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val wasCurrent = current?.id == conversation.id
                conversations.delete(conversation.id)
                refreshDrawer()
                if (wasCurrent) openConversation(conversations.current()) else toast(
                    getString(R.string.conv_deleted)
                )
            }
            .show()
    }

    private fun welcome(): ChatMessage =
        ChatMessage(text = getString(R.string.welcome), isUser = false)

    private fun saveCurrent() {
        val conversation = current ?: return
        conversations.saveMessages(conversation.id, session.messages)
        conversations.touch(conversation.id)
        refreshDrawer()
    }

    // ------------------------------------------------------------------ 发送

    fun bindChatUi(ui: ChatFragment) {
        chatView = ui
    }

    fun unbindChatUi(ui: ChatFragment) {
        if (chatView === ui) chatView = null
    }

    /** 输入框回车 / 点发送 */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            toast(getString(R.string.error_empty_input))
            return
        }
        if (!NetworkState.isOnline()) {
            toast(getString(R.string.status_offline_hint))
            setThinking(null)
            return
        }

        // 上下文要在「这句进列表之前」取，否则它自己会被当成上一轮
        val history = session.recentTurns(HISTORY_TURNS)

        session.addUser(trimmed)
        chatView?.clearInput()
        askAi(history, trimmed)
    }

    /**
     * 真的去问一次。
     *
     * [turnSeq] 是「这一轮还算不算数」的凭据：用户改口、重发、切会话都会把它加一，
     * 于是迟到的回答会被丢掉，不会盖到别的对话上。
     */
    private fun askAi(history: List<LlmMessage>, question: String) {
        val seq = ++turnSeq
        setThinking(getString(R.string.status_thinking))
        executor.execute {
            val answer = ai.ask(history, question, styleHint())
            main.post { if (seq == turnSeq) onAnswer(seq, answer) }
        }
    }

    private fun onAnswer(seq: Int, answer: AiChat.Answer) {
        if (seq != turnSeq || isFinishing || isDestroyed) return
        setThinking(null)
        session.addAssistant(answer.text)

        if (answer.error != null && !online) updateConnUi()
        if (speakEnabled) tts.speak(answer.text)
        maybeNameConversation()
    }

    /**
     * 第一次得到回答后，让大模型给这次对话起个名字。
     *
     * 起名是额外的请求，失败就用首句截断（[AiChat.fallbackTitle]）——
     * 抽屉里不会出现一排「新对话」。
     */
    private fun maybeNameConversation() {
        val conversation = current ?: return
        if (conversation.named) return
        val firstUser = session.messages.firstOrNull { it.isUser }?.text ?: return
        val firstAnswer = session.messages.firstOrNull { !it.isUser && it.text != getString(R.string.welcome) }
            ?.text.orEmpty()
        val id = conversation.id

        executor.execute {
            val title = ai.title(firstUser, firstAnswer)
            main.post {
                conversations.rename(id, title)
                if (current?.id == id) current = current?.copy(title = title, named = true)
                refreshDrawer()
            }
        }
    }

    private fun styleHint(): String {
        val base = if (replyStyle == 0) {
            "这次回答要短：一两句说到点上，不解释、不列点。"
        } else {
            "这次可以多说一点：先给结论，再补一两条理由或做法。"
        }
        val followUp = if (settings.proactiveFollowUp) "" else "这次不要反问，说完就停。"
        return listOf(base, followUp).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** 离线 / 联网切换时，让界面立刻反映出「现在能不能用」 */
    fun isOnline(): Boolean = online

    // ------------------------------------------------------------------ 气泡操作

    /** 只有最后一条用户消息能改能删——这是需求，也是防止改乱上下文 */
    fun isEditable(index: Int): Boolean {
        val message = session.messageAt(index) ?: return false
        return message.isUser && index == session.lastUserIndex()
    }

    fun startEdit(index: Int): Boolean {
        if (!isEditable(index)) return false
        return chatView?.startEdit(index) == true
    }

    /**
     * 改掉最后一条用户消息，并**让助手重新回答**。
     *
     * 旧答案必须作废：用户改的是问题，留着原来那句就变成「问 A 答 B」。
     * 改完立刻重问一次，界面上先看到新问题、再填进新答案，和重新发一句一样。
     */
    fun updateMessage(index: Int, text: String): Boolean {
        if (!isEditable(index)) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // 历史只取这条之前的：这句已经被改写，不能再当作上下文
        val history = session.recentTurnsBefore(index, HISTORY_TURNS)
        session.updateUserText(index, trimmed)
        session.dropAnswerAfter(index)
        chatView?.refresh()

        if (!NetworkState.isOnline()) {
            // 没网就别假装在答：把状态说清楚，联网后重发即可
            session.addAssistant(getString(R.string.offline_after_edit))
            toast(getString(R.string.status_offline_hint))
            return true
        }
        askAi(history, trimmed)
        return true
    }

    fun deleteTurn(index: Int): Boolean {
        if (!isEditable(index)) return false
        val ok = session.deleteTurn(index)
        if (ok) chatView?.refresh()
        return ok
    }

    // ------------------------------------------------------------------ 语音

    /** 长按输入框：开始收音 */
    fun beginVoice() {
        if (!NetworkState.isOnline()) {
            toast(getString(R.string.status_offline_hint))
            return
        }
        if (hasMicPermission()) {
            startVoiceInput()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /** 松手：结束收音。cancel=true 表示上滑取消 */
    fun endVoice(cancel: Boolean) {
        if (!asr.isListening) return
        if (cancel) {
            asr.cancel()
            setThinking(null)
        } else {
            asr.stop()
        }
    }

    private fun startVoiceInput() {
        if (!asr.isAvailable()) {
            toast(getString(R.string.error_no_recognizer))
            return
        }
        tts.stop()
        asr.start()
    }

    override fun onReady() {
        setThinking(getString(R.string.status_listening_hold))
        chatView?.setListening(true, false)
    }

    override fun onPartialResult(text: String) {
        setThinking(getString(R.string.status_recognizing) + " " + text)
    }

    override fun onEndOfSpeech() {
        setThinking(getString(R.string.status_recognizing))
        chatView?.setListening(false, false)
    }

    override fun onFinalResult(text: String, candidates: List<String>) {
        setThinking(null)
        chatView?.setListening(false, false)
        // 识别结果放进输入框，让用户看一眼再发——长按说话不等于立刻发送
        chatView?.setInput(text)
    }

    override fun onError(message: String) {
        setThinking(null)
        chatView?.setListening(false, false)
        toast(message)
    }

    override fun onSpeakingFinished() {
        // 没有「连续对话」了：说完就停，等用户下次开口
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ 设置页

    fun appSettings(): AppSettings = settings

    fun openSettings() {
        if (settingsOpen) return
        closeDrawer()
        settingsOpen = true
        btnMenu.setImageResource(R.drawable.ic_arrow_back)
        btnSettings.visibility = View.INVISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SettingsFragment())
            .commit()
    }

    private fun closeSettings() {
        if (!settingsOpen) return
        settingsOpen = false
        btnMenu.setImageResource(R.drawable.ic_menu)
        btnSettings.visibility = View.VISIBLE
        showChatFragment()
    }

    /**
     * 把对话页放进容器里（幂等）。
     *
     * 已经挂着就不动它：重建 Fragment 会丢掉当前滚动位置，
     * 而且从设置页返回时也没必要把整段对话重新渲染一遍。
     */
    private fun showChatFragment() {
        val current = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (current is ChatFragment) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, ChatFragment())
            .commit()
    }

    fun rotateBackIconToMenu() = closeSettings()

    /**
     * 设置页「恢复默认设置」。
     *
     * 只清偏好：对话记录、提醒、账号都由各自的存储管，不该被一次点按抹掉。
     * 清完立刻回读一遍，界面上的开关才不会和实际状态对不上。
     */
    fun resetSettings() {
        settings.reset()
        restoreSettings()
    }

    fun setCloudConfig(config: CloudLlmHandler.CloudConfig) {
        cloudConfig = config
        settings.saveCloud(config)
    }

    fun testCloud(config: CloudLlmHandler.CloudConfig, onResult: (String) -> Unit) {
        executor.execute {
            val text = runCatching { CloudLlmHandler.ping(config) }
                .getOrElse { "没连上：${it.message.orEmpty()}" }
            main.post { if (!isFinishing && !isDestroyed) onResult(text) }
        }
    }

    fun setReplyStyle(style: Int) {
        replyStyle = style
        settings.replyStyle = style
    }

    fun setSpeakEnabled(enabled: Boolean) {
        speakEnabled = enabled
        settings.speakEnabled = enabled
        tts.speakEnabled = enabled
        if (!enabled) tts.stop()
    }

    fun toggleSpeak(): Boolean {
        setSpeakEnabled(!speakEnabled)
        return speakEnabled
    }

    /** 清空**当前这条**对话记录（其它记录不受影响） */
    fun clearCurrentConversation() {
        turnSeq++
        setThinking(null)
        session.clear()
        session.load(listOf(welcome()))
        saveCurrent()
        chatView?.refresh()
    }

    fun gotoLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        )
        finish()
    }

    private fun restoreSettings() {
        speakEnabled = settings.speakEnabled
        replyStyle = settings.replyStyle
        tts.speakEnabled = speakEnabled
        val engine = settings.ttsEngine
        if (engine.isNotEmpty()) tts.useEngine(engine)
        cloudConfig = settings.cloudConfig()
    }

    // ------------------------------------------------------------------ 其它

    private fun subscribeReminder() {
        com.example.voiceassistant.bus.AssistantBus.on<ReminderFired>(owner = this) { event ->
            if (isFinishing || isDestroyed) return@on
            if (speakEnabled && isForeground && !tts.isSpeaking) {
                tts.speak(getString(R.string.speak_reminder, event.content))
            }
        }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                drawerOpen -> closeDrawer()
                settingsOpen -> closeSettings()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (ReminderAlarm.canNotify(this)) return
        val prefs = getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
        runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /** 装完清安装包：每个版本只跑一次 */
    private fun cleanInstallerOnce() {
        if (isFinishing || isDestroyed) return
        val prefs = getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
        val key = KEY_APK_CLEAN + versionName()
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()

        val found = runCatching { ApkCleaner.findInstallers(this) }.getOrNull().orEmpty()
        if (found.isEmpty()) return
        val remaining = found.filterNot { ApkCleaner.deleteSilently(this, it) }
        if (remaining.isEmpty()) {
            toast(getString(R.string.apk_clean_done))
            return
        }
        val sender = ApkCleaner.deleteRequest(this, remaining) ?: return
        runCatching { apkCleanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        isForeground = true
        if (::session.isInitialized) chatView?.refresh()
    }

    override fun onStart() {
        super.onStart()
        // 只有界面在前台才巡检网络：后台没必要每几秒醒一次
        NetworkState.addListener(networkListener)
    }

    override fun onStop() {
        NetworkState.removeListener(networkListener)
        super.onStop()
    }

    override fun onPause() {
        isForeground = false
        main.removeCallbacksAndMessages(null)
        if (::asr.isInitialized) asr.cancel()
        if (::tts.isInitialized) tts.stop()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        com.example.voiceassistant.bus.AssistantBus.off(this)
        if (::asr.isInitialized) asr.release()
        if (::tts.isInitialized) tts.release()
        super.onDestroy()
    }

    private companion object {
        const val PREFS_ONBOARDING = "onboarding"
        const val KEY_NOTIFY_ASKED = "notify_asked"
        const val KEY_APK_CLEAN = "apk_clean_"
        const val EXTRA_QUERY = "query"

        /** 带几轮上下文：3 轮够接住「它 / 那明天呢」，再多只是烧 token */
        const val HISTORY_TURNS = 3
    }
}
