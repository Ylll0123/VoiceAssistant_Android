package com.example.voiceassistant.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager
import android.content.res.ColorStateList
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
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
import com.example.voiceassistant.bus.AssistantBus
import com.example.voiceassistant.bus.ServiceBus
import com.example.voiceassistant.core.AiChat
import com.example.voiceassistant.core.AssistantMode
import com.example.voiceassistant.core.CloudLlmBridge
import com.example.voiceassistant.core.Dispatcher
import com.example.voiceassistant.core.LlmSwitch
import com.example.voiceassistant.im.ImAutomator
import com.example.voiceassistant.provider.AudioSupply
import com.example.voiceassistant.provider.ContextSupply
import com.example.voiceassistant.provider.DeviceSupply
import com.example.voiceassistant.provider.GenSupply
import com.example.voiceassistant.provider.MsgSupply
import com.example.voiceassistant.provider.ScheduleSupply
import com.example.voiceassistant.provider.StudySupply
import com.example.voiceassistant.provider.ToolSupply
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
import com.example.voiceassistant.service.AppCatalog
import com.example.voiceassistant.service.AsrManager
import com.example.voiceassistant.service.MusicPlayer
import com.example.voiceassistant.service.TtsManager
import com.example.voiceassistant.tool.AiTools
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

    private lateinit var ai: AiChat
    private lateinit var aiTools: AiTools

    /** 本机全部可启动应用：用于「打开原神」这类常用表之外的应用 */
    private val appCatalog by lazy { AppCatalog(this) }

    /** 供需总线：本地 NLU 和大模型工具调用共用这一张表 */
    private lateinit var bus: ServiceBus

    /** 路由：这句话交给本地还是大模型 */
    private lateinit var dispatcher: Dispatcher

    private lateinit var msgSupply: MsgSupply

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
    private lateinit var btnEditChats: TextView
    private lateinit var contentRoot: View

    /** 抽屉打开时内容区被模糊；记一下当前状态，避免重复设置 */
    private var contentBlurred = false

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
        // 念不出来时先说一次原因，别让用户对着「没有声音」干猜
        tts.onProblem = { problem ->
            if (!isFinishing && !isDestroyed) toast(speechProblemText(problem))
        }

        installServices()

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
        if (conversations.list().isEmpty()) {
            startBlankConversation()
        } else {
            openConversation(conversations.current())
        }

        subscribeReminder()

        askNotificationPermission()
        main.postDelayed({ cleanInstallerOnce() }, 1200)

        if (savedInstanceState == null) {
            handleExternalQuery(intent)
        }
    }

    /**
     * 装配供需总线。
     *
     * 这是 v5.0 的核心改造：**每个能力只实现一次**，然后同时挂在两条入口上——
     * 本地 NLU 认得就直接调，大模型需要动手也调同一个。
     *
     * 装配顺序有意义：先有 [CloudLlmBridge]（翻译、解释、写文案要用它），
     * 再有总线，最后才是 [AiChat]（它要用 [AiTools] 读总线）。反过来会拧成循环依赖。
     */
    private fun installServices() {
        val device = AndroidDeviceController(applicationContext)
        val audioCtl = AndroidAudioController(applicationContext)
        val bridge = CloudLlmBridge { cloudConfig }
        // 进程级单例：待确认的发送不能随 Activity 重建丢失
        val automator = ImAutomator.of(applicationContext)

        // 「我上一条跟他说的什么」：读当前会话里助手说过的话
        msgSupply = MsgSupply(
            automator = automator,
            recentMessages = {
                session.messages.filter { !it.isUser }.map { it.text }.takeLast(5)
            }
        )

        bus = ServiceBus().apply {
            registerAll(*ScheduleSupply(this@VoiceAssistantActivity).all.toTypedArray())
            registerAll(
                *DeviceSupply(this@VoiceAssistantActivity, device) { setWindowBrightness(it) }
                    .all.toTypedArray()
            )
            registerAll(
                *AudioSupply(
                    tts = tts,
                    audio = audioCtl,
                    music = MusicPlayer(applicationContext)
                ) { beginVoiceFromBus() }.all.toTypedArray()
            )
            registerAll(*ToolSupply(bridge).all.toTypedArray())
            registerAll(*StudySupply(bridge).all.toTypedArray())
            registerAll(*GenSupply(bridge).all.toTypedArray())
            registerAll(
                *ContextSupply(
                    clearTurns = { clearCurrentTurns() },
                    onModeChanged = { mode -> settings.assistantMode = mode },
                    onLlmToggled = { enabled -> settings.cloudEnabled = enabled }
                ).all.toTypedArray()
            )
            registerAll(*msgSupply.all.toTypedArray())
        }

        aiTools = AiTools(bus)
        ai = AiChat(aiTools, config = { cloudConfig })
        dispatcher = Dispatcher(
            bus = bus,
            ai = ai,
            msg = msgSupply,
            // 常用表之外的应用（原神、学习通……）也走本地，不必绕一趟大模型
            appMatcher = { appCatalog.matches(it) }
        )
    }

    /** 亮度服务改不了系统亮度时的退路：只改本应用窗口 */
    private fun setWindowBrightness(value: Float) {
        runCatching {
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
    }

    /** `context/clear`：清掉这一轮的所有消息，只留欢迎语 */
    private fun clearCurrentTurns(): Int {
        val greeting = welcome()
        val count = session.messages.count { it.text != greeting.text }
        session.load(listOf(greeting))
        chatView?.refresh()
        return count
    }

    /**
     * 模型说「开始听」时开麦（`audio/asr_recognize`）。
     *
     * 返回是否真的开始——总线拿这个值决定回「我在听」还是「没法开麦」。
     */
    private fun beginVoiceFromBus(): Boolean {
        if (!hasMicPermission() || !asr.isAvailable()) return false
        main.post { if (!isFinishing && !isDestroyed) startVoiceInput() }
        return true
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
        btnEditChats = findViewById(R.id.btnEditChats)
        contentRoot = findViewById(R.id.contentRoot)
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
            onOpen = { openConversation(it) },
            onDelete = { confirmDeleteConversation(it) },
            onAdd = {
                closeDrawer()
                newConversation()
            },
            onEnterEdit = { setChatsEditing(true) }
        )
        rvConversations.layoutManager = LinearLayoutManager(this)
        rvConversations.adapter = drawerAdapter

        findViewById<TextView>(R.id.tvDrawerUser).text =
            AccountStore(this).currentUser.orEmpty()

        btnEditChats.setOnClickListener { setChatsEditing(!drawerAdapter.isEditing()) }
        scrim.setOnClickListener { closeDrawer() }
    }

    /**
     * 进出「编辑对话记录」。
     *
     * 编辑状态下每条的右侧露出删除按钮，右上角那颗白色小气泡的文案换成「完成」。
     */
    private fun setChatsEditing(editing: Boolean) {
        if (!::drawerAdapter.isInitialized) return
        drawerAdapter.setEditing(editing)
        btnEditChats.setText(if (editing) R.string.drawer_done else R.string.drawer_edit)
    }

    /**
     * 背景模糊。
     *
     * Android 12 起可以直接给内容区挂 [RenderEffect]——抽屉拉出来时，
     * 后面的对话是真的糊掉，而不是压一层灰。老系统没有这个能力，
     * 就退回「加深遮罩」，观感差点但至少不会显得没层次。
     */
    private fun setContentBlurred(blurred: Boolean) {
        if (!::contentRoot.isInitialized) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            scrim.setBackgroundColor(if (blurred) 0x8C000000.toInt() else 0x66000000)
            return
        }
        if (contentBlurred == blurred) return
        contentBlurred = blurred
        contentRoot.setRenderEffect(
            if (blurred) {
                RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
            } else {
                null
            }
        )
    }

    private fun refreshDrawer() {
        val list = conversations.list()
        drawerAdapter.submit(list, current?.id)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        currentFocus?.clearFocus()
    }

    private fun openDrawer() {
        drawerOpen = true
        // 键盘收起来：不然抽屉只有半屏高，列表被顶掉一截
        hideKeyboard()
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
        setContentBlurred(true)
    }

    /**
     * 收起抽屉。
     *
     * **顺手退出编辑模式**：抽屉一收，用户接下来就是要打字或说话。
     * 如果编辑状态留着，下次拉出来每条右边还是垃圾桶、右上角还写着「完成」，
     * 点一下标题也不切会话——看着像应用坏了。
     */
    private fun closeDrawer() {
        if (!drawerOpen) return
        drawerOpen = false
        setChatsEditing(false)
        val width = drawerPanel.width.toFloat().coerceAtLeast(1f)
        drawerPanel.animate().translationX(-width).setDuration(180)
            .withEndAction { drawerPanel.visibility = View.GONE }
            .start()
        scrim.animate().alpha(0f).setDuration(180)
            .withEndAction { scrim.visibility = View.GONE }
            .start()
        setContentBlurred(false)
    }

    // ------------------------------------------------------------------ 对话记录

    private fun openConversation(conversation: Conversation) {
        loadConversation(conversation)
        setChatsEditing(false)
        refreshDrawer()
        closeDrawer()
    }

    /**
     * 只换对话内容，**不关抽屉**。
     *
     * 删掉一条记录之后要接着留在列表里（用户可能还要删下一条），
     * 所以不能借用 [openConversation]——那样会被弹回对话框。
     */
    private fun loadConversation(conversation: Conversation) {
        turnSeq++
        current = conversation
        conversations.select(conversation.id)

        val saved = conversations.messages(conversation.id)
        session.load(if (saved.isEmpty()) listOf(welcome()) else saved)
        chatView?.refresh()
        setThinking(null)
    }

    private fun newConversation() {
        val conversation = conversations.create(getString(R.string.conv_new_title))
        openConversation(conversation)
        chatView?.focusInput()
    }

    private fun confirmDeleteConversation(conversation: Conversation) {
        this.appDialog()
            .setTitle(getString(R.string.bubble_delete_confirm_title))
            .setMessage(conversation.title)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val wasCurrent = current?.id == conversation.id
                conversations.delete(conversation.id)

                when {
                    // 删光了就停在空白态：不立刻补一条「新对话」，
                    // 等用户真的再问时才建——抽屉里空着比多一条假的干净
                    conversations.list().isEmpty() -> startBlankConversation()

                    // 删的是当前这条：后台换到最近的一段，抽屉照旧开着
                    wasCurrent -> loadConversation(conversations.current())

                    else -> Unit
                }
                refreshDrawer()
                toast(getString(R.string.conv_deleted))
            }
            .showWhite()
    }

    private fun welcome(): ChatMessage =
        ChatMessage(text = getString(R.string.welcome), isUser = false)

    /**
     * 空白态：一条记录都没有，界面上只有欢迎语。
     *
     * 这份欢迎语**不属于任何会话**，所以不落盘；用户真的开口了才建会话
     * （见 [ensureConversation]）。这样删完最后一条之后，抽屉里就是干净的，
     * 不会凭空空出一条「新对话」。
     */
    private fun startBlankConversation() {
        turnSeq++
        current = null
        setChatsEditing(false)
        session.load(listOf(welcome()))
        chatView?.refresh()
        setThinking(null)
    }

    /**
     * 有会话说就接着用，没有就现在建一条。
     *
     * 删光记录后的第一句话走这条路：抽屉里这时候才出现新的对话记录。
     */
    private fun ensureConversation(): Conversation {
        current?.let { return it }
        val created = conversations.create(getString(R.string.conv_new_title))
        current = created
        refreshDrawer()
        return created
    }

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

        // 删空之后的第一句：这时候才建会话，保证消息有地方落
        ensureConversation()

        // 上下文要在「这句进列表之前」取，否则它自己会被当成上一轮
        val history = session.recentTurns(HISTORY_TURNS)

        session.addUser(trimmed)
        chatView?.clearInput()
        askAi(history, trimmed)
    }

    /**
     * 真的去处理一次。
     *
     * 这里不再直接调大模型，而是交给 [Dispatcher] 判断：
     * 本地认得的指令当场办完（**不联网**），只有它办不了的才发请求。
     *
     * [turnSeq] 是「这一轮还算不算数」的凭据：用户改口、重发、切会话都会把它加一，
     * 于是迟到的结果会被丢掉，不会盖到别的对话上。
     */
    private fun askAi(history: List<LlmMessage>, question: String) {
        val seq = ++turnSeq
        setThinking(getString(R.string.status_thinking))
        executor.execute {
            val result = dispatcher.dispatch(question, history, styleHint(), NetworkState.isOnline())
            main.post { if (seq == turnSeq) onRouted(seq, result) }
        }
    }

    /** 路由结果上屏：本地办的、模型答的、没办成的，三种都写成一条助手消息 */
    private fun onRouted(seq: Int, result: Dispatcher.Result) {
        if (seq != turnSeq || isFinishing || isDestroyed) return

        when (result) {
            is Dispatcher.Result.Local -> {
                setThinking(null)
                session.addAssistant(result.reply.speech)
                if (speakEnabled) tts.speak(result.reply.speech)
                // 发消息因为没开无障碍而走了分享通路：用到时才问，比藏在设置里有效
                if (result.reply.data["need_access"] == "true") promptAccessibility()
                maybeNameConversation()
            }

            is Dispatcher.Result.Ask -> onAnswer(seq, result.answer)

            is Dispatcher.Result.Blocked -> {
                setThinking(null)
                // 没网 / AI 关掉这类「其实没办」，也留一条记录，用户回头看得见
                session.addAssistant(result.message)
            }
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

    /**
     * 回答风格固定成默认，不再让用户选。
     *
     * 以前这里按设置里的「精简 / 详细」拼一段提示词，现在一律不加额外要求——
     * 让人设和模型自己决定分寸，比一个藏在设置里的两档开关更稳。
     */
    private fun styleHint(): String = ""

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
        if (!hasMicPermission()) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceInput()
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

    /**
     * 松手后识别完就**直接发**，不用再点一次发送。
     *
     * 长按说话本来就是「说完就走」的动作，再让人回去点一下发送键，
     * 多出来的这一步只会在抬手瞬间丢焦点。说错了还能用气泡的长按改。
     */
    override fun onFinalResult(text: String, candidates: List<String>) {
        setThinking(null)
        chatView?.setListening(false, false)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            toast(getString(R.string.error_empty_input))
            return
        }
        sendText(trimmed)
    }

    override fun onError(message: String) {
        setThinking(null)
        chatView?.setListening(false, false)
        toast(message)
    }

    override fun onSpeakingFinished() {
        // 没有「连续对话」：说完就停，等用户下次开口
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

    fun setSpeakEnabled(enabled: Boolean) {
        speakEnabled = enabled
        settings.speakEnabled = enabled
        tts.speakEnabled = enabled
        if (!enabled) tts.stop()
    }

    /** 一个状态对应的人话，[TtsManager.onProblem] 和设置页共用 */
    fun speechProblemText(problem: TtsManager.Status): String = when (problem) {
        TtsManager.Status.MUTED -> getString(R.string.speech_muted)
        TtsManager.Status.NO_CHINESE -> getString(R.string.speech_no_chinese)
        TtsManager.Status.NO_ENGINE -> getString(R.string.speech_no_engine)
        TtsManager.Status.READY -> ""
    }

    /**
     * 「自动发消息权限」：跳到系统的无障碍设置页。
     *
     * 设置页里那一行删掉了，改成**用到时再提示**——第一次说「发微信给…」发现没权限，
     * 当场问一句要不要去开，比藏在设置里等人自己找有效得多。
     */
    fun openAccessibilitySettings() {
        if (!msgSupply.openAccessibilitySettings()) {
            toast(getString(R.string.settings_access_failed))
        }
    }

    /** 消息功能缺无障碍权限时的引导 */
    private fun promptAccessibility() {
        appDialog()
            .setTitle(R.string.access_prompt_title)
            .setMessage(R.string.access_prompt_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.access_prompt_go) { _, _ -> openAccessibilitySettings() }
            .showWhite()
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
        tts.speakEnabled = speakEnabled
        val engine = settings.ttsEngine
        if (engine.isNotEmpty()) tts.useEngine(engine)
        cloudConfig = settings.cloudConfig()

        // 模式和云端开关都是「上次怎么用，这次还怎么用」
        AssistantMode.switchTo(settings.assistantMode)
        LlmSwitch.set(settings.cloudEnabled)
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
        // 用户可能刚从系统设置里换过语音引擎 / 装了语音包，回来重探一次
        if (::tts.isInitialized) tts.refreshIfNeeded()
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
        /*
         * 这里**不能**再 main.removeCallbacksAndMessages(null)。
         *
         * 「打开微信」「打开设置」这类指令会立刻把别的应用拉到前台，本页随即 onPause；
         * 而「已经打开 XX」这句话正排在主线程队列里等着落盘。
         * 一刀清空的结果就是：应用确实打开了，但对话里永远少一句回复。
         */
        if (::asr.isInitialized) asr.cancel()
        /*
         * 也不在这里停 TTS。
         *
         * 「打开微信」是助手自己把别的应用拉到前台的，紧接着要念的那句
         * 「已经打开微信。」会被这一下掐掉——用户听到的就是「打开了但没吭声」。
         * 真正需要停的场景只有「用户要说话了」，那条路径在 startVoiceInput 里停。
         */
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

        /** 抽屉打开时内容区的模糊半径：够糊、又能看清大致轮廓 */
        const val BLUR_RADIUS = 22f
    }
}
