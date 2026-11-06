package com.example.voiceassistant.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
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
import androidx.fragment.app.Fragment
import com.example.voiceassistant.R
import com.example.voiceassistant.bus.AssistantBus
import com.example.voiceassistant.dialog.DialogueManager
import com.example.voiceassistant.fuzzy.FuzzyEngine
import com.example.voiceassistant.handler.llm.CloudCache
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.tool.ToolIntent
import com.example.voiceassistant.assistant.AssistantConfig
import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.CityClimate
import com.example.voiceassistant.assistant.DialogCorpus
import com.example.voiceassistant.assistant.KnowledgeBase
import com.example.voiceassistant.assistant.NgramModel
import com.example.voiceassistant.assistant.QueryUnderstanding
import com.example.voiceassistant.assistant.ReplyCrafter
import com.example.voiceassistant.assistant.ThoughtBank
import com.example.voiceassistant.core.AssistantPipeline
import com.example.voiceassistant.core.AssistantRouter
import com.example.voiceassistant.core.HistoryManager
import com.example.voiceassistant.core.RawText
import com.example.voiceassistant.core.ReplyReady
import com.example.voiceassistant.core.RoutePlanner
import com.example.voiceassistant.core.TurnPlanned
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.AccountStore
import com.example.voiceassistant.data.LocaleHelper
import com.example.voiceassistant.data.ChatHistoryStore
import com.example.voiceassistant.data.ChatMessage
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.handler.qa.QaLib
import com.example.voiceassistant.handler.qa.QaLocalHandler
import com.example.voiceassistant.handler.web.WebHandler
import com.example.voiceassistant.installer.ApkCleaner
import com.example.voiceassistant.memory.MemorySeed
import com.example.voiceassistant.network.LlmResult
import com.example.voiceassistant.network.NetworkState
import com.example.voiceassistant.memory.SqliteMemoryRepository
import com.example.voiceassistant.reminder.ReminderAlarm
import com.example.voiceassistant.reminder.ReminderFired
import com.example.voiceassistant.reminder.Reminders
import com.example.voiceassistant.service.AndroidAudioController
import com.example.voiceassistant.service.AndroidDeviceController
import com.example.voiceassistant.service.AsrManager
import com.example.voiceassistant.service.TtsManager
import com.example.voiceassistant.tool.ToolHandler
import java.io.File

/**
 * UI 层：**应用外壳 + 语音入口**。
 *
 * 这一层只做四件事，不写任何业务判断：
 * 1. 唤起录音（[AsrManager]）/ 播放语音（[TtsManager]）/ 申请麦克风权限；
 * 2. 把识别出来的一串候选文本交给调度核心（[AssistantRouter]）；
 * 3. 把调度结果交给 [HistoryManager] 落盘，并显示在界面上；
 * 4. 底部导航切换页面。
 *
 * 「这句话该本地答还是联网还是问云端」不在这一层——全在 core 与 handler 里。
 * 所以这一层很薄，改业务逻辑时基本不用碰它。
 */
class VoiceAssistantActivity : AppCompatActivity(), AsrManager.Listener, TtsManager.Listener {

    enum class Tab { CHAT, FEATURES, SETTINGS }

    /** 对话页对外暴露的回调，Activity 收到语音事件后转给它 */
    interface ChatUi {
        fun showStatus(text: String, highlight: Boolean = false)
        fun setMicActive(active: Boolean)
    }

    /** 设置持久化：改完立刻写盘，下次打开还是上次的样子 */
    private lateinit var settings: AppSettings

    /**
     * 内置本地小模型（字符级 n-gram，纯 Kotlin）。
     *
     * 启动时先读上次的训练进度（filesDir/ngram_model.txt）；
     * 第一次没有存档，就用内置语料打底训练并存档。
     * 之后每轮对话都会增量学习，退到后台时把新学的写盘。
     */
    private lateinit var localModel: NgramModel

    /**
     * 最近一次查过天气的城市。
     * 用户接着说「那上海呢 / 那里呢」时用它兜底，
     * 否则 OnlineQuery 拿不到城市就不联网。
     */
    private var lastWeatherCity: CityClimate.CityClimate? = null

    /** 联网 runner：单线程串行执行，保证同一时刻只有一个请求在飞 */
    private val netExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "assistant-net").apply { isDaemon = true }
    }

    lateinit var asr: AsrManager
        private set

    lateinit var tts: TtsManager
        private set

    var speakEnabled: Boolean = true
        private set

    var continuousMode: Boolean = true
        private set

    var ttsEngine: String = ""
        private set

    var replyStyle: Int = 1
        private set

    var proactiveFollowUp: Boolean = true
        private set

    /** 是否展示本地「理解过程」（淡色小字，答案出现即移除） */
    var showThinking: Boolean = true
        private set

    var autoLearn: Boolean = true
        private set

    var localModelEnabled: Boolean = true
        private set

    var onlineSearchEnabled: Boolean = true
        private set

    /**
     * 云端大模型（AI 大脑）配置。
     *
     * 默认全部关闭：没填 Key 就是一个纯粹的离线助手。
     * 用户在设置页填了接口和 Key 之后，本地答不好的问题会交给它，
     * 失败（超时 / Key 错 / 没网）时自动用本地答案兜底。
     */
    var cloudConfig: CloudLlmHandler.CloudConfig = CloudLlmHandler.CloudConfig()
        private set

    private lateinit var ivTabChat: ImageView
    private lateinit var ivTabFeatures: ImageView
    private lateinit var ivTabSettings: ImageView
    private lateinit var tvTabChat: TextView
    private lateinit var tvTabFeatures: TextView
    private lateinit var tvTabSettings: TextView

    private var chatUi: ChatUi? = null
    private var currentTab: Tab? = null
    private var isForeground = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoiceInput()
            } else {
                chatUi?.showStatus(getString(R.string.error_no_permission))
                Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
            }
        }

    /**
     * 通知权限（Android 13+）。
     *
     * 拒绝了也不影响其它功能：提醒照样记进清单，
     * 只是到点弹不出通知——所以这里不追着要，也不弹解释框。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                chatUi?.showStatus(getString(R.string.status_notify_denied))
            }
        }

    /**
     * 系统「允许删除此文件？」的回执。
     *
     * 装机后清理安装包要用它：别人的文件（浏览器下载的）只能由系统弹框、
     * 用户点一下才删得掉，这是分区存储的安全约束。
     * 我们自己的确认框已经去掉了——装完就清，不再多问一句。
     */
    private val apkCleanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val deleted = result.resultCode == Activity.RESULT_OK
            Toast.makeText(
                this,
                if (deleted) R.string.apk_clean_done else R.string.apk_clean_kept,
                Toast.LENGTH_SHORT
            ).show()
        }

    /** 会话状态（消息 + 引擎），整个应用共享一份 */
    val session: ChatSession by lazy {
        ChatSession(
            AssistantEngine(
                memory = SqliteMemoryRepository(applicationContext),
                model = localModel
            )
        )
    }

    /** 对话历史：只负责"记几轮、给云端时截多长" */
    private val history: HistoryManager by lazy { HistoryManager(session) }

    /** 对话记录的落盘位置：重启后把最近几十条恢复出来 */
    private val chatHistory: ChatHistoryStore by lazy { ChatHistoryStore(applicationContext) }

    /**
     * 调度核心：本地工具 → 本地问答 → 按需联网 → 云端大模型 → 本地兜底。
     *
     * Activity 只管把输入丢进去、把结果拿出来，中间怎么走完全由它决定。
     */
    private val router: AssistantRouter by lazy {
        AssistantRouter(
            history = history,
            toolHandler = ToolHandler(
                AndroidAudioController(applicationContext),
                AndroidDeviceController(applicationContext)
            ),
            qaHandler = QaLocalHandler(session.engine, QaLib.load(applicationContext)),
            webHandler = WebHandler,
            llmHandler = CloudLlmHandler
        )
    }

    /** 事件总线（进程级）：四个模块之间只通过它说话 */
    private val bus = AssistantBus

    /**
     * 一轮输入的完整流水线：
     * 语音链路 → 模糊容错引擎 → 对话管理器 →（必要时）问答链路。
     *
     * 界面只调用 [AssistantPipeline.submit]，然后听总线上的
     * [TurnPlanned] / [ReplyReady]——不关心中间走了哪条路。
     */
    private val pipeline: AssistantPipeline by lazy {
        AssistantPipeline(
            bus = bus,
            fuzzy = FuzzyEngine(),
            dialog = DialogueManager(Reminders.center(applicationContext)),
            router = router,
            history = history,
            executor = netExecutor,
            main = mainHandler,
            config = { config() }
        )
    }

    /**
     * 界面语言：在 Context 构造阶段就把 Configuration 换成选定语言。
     *
     * 必须早于 setContentView，否则资源已经按系统语言解析过了，
     * 设置页里切完语言会「文字没变、只有重建之后才变」。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, AppSettings.readLanguage(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 没登录（也没选游客）就先去看登录页。
        // 外部 query 入口放行：自动化测试和「发送到 HardlySeek」不该被门禁挡住。
        if (!ensureSignedIn()) return

        // 显式指定浅色系统栏样式。
        // enableEdgeToEdge() 不带参数时会按系统深色模式自动选择图标颜色，
        // 手机开深色模式 -> 状态栏用浅色图标，而本应用界面是浅色底，
        // 结果状态栏图标几乎看不见，看起来就和模拟器「完全不一样」。
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

        applyInsets()
        setupTabs()

        settings = AppSettings(this)

        // 提醒模块：建通知渠道、把工具层的落点换成持久化实现、
        // 把重启 / 覆盖安装后丢掉的闹钟重新排上
        Reminders.install(this)

        // 联网状态：没网时网络层直接失败回落本地，不再干等超时
        NetworkState.init(this)

        // 小模型要先于会话创建（会话的引擎里要引用它）
        localModel = createLocalModel()

        // 上次的对话先恢复出来，再补欢迎语：
        // 否则重启后屏幕上只剩一句自我介绍，追问「那明天呢」就接不上了。
        //
        // 历史的第一条永远是欢迎语，而且它的文案会随界面语言 / 应用名变化，
        // 所以这里按「位置」而不是按文字认：先丢掉旧的那条，再用当前语言的文案重放一条。
        // （以前按中文前缀判断，一切到英文就会出现两条欢迎语）
        val saved = chatHistory.load()
        val history = if (saved.firstOrNull()?.isOpeningLine() == true) saved.drop(1) else saved
        session.restore(listOf(newWelcome()) + history)
        session.onChanged = { chatHistory.save(it) }
        session.ensureWelcome(getString(R.string.welcome))

        asr = AsrManager(this)
        asr.listener = this
        tts = TtsManager(this)
        tts.listener = this

        // 先把持久化下来的设置整体灌回去，再创建界面，
        // 否则设置页会先按默认值画一遍，再被覆盖，看着像闪一下
        restoreSettings()

        // 答不上来的话 → 收进待学习列表（记忆页可以一键教会）
        session.engine.onUnanswered = { question -> settings.addPending(question) }

        // 总线订阅放在界面起来之后：事件回调里要碰 chatUi / tts
        subscribeEvents()

        // 重建（旋转 / 分屏 / 被系统回收后恢复）时回到原来那一页，
        // 否则每次重建都跳回对话页，看起来像是「页面丢了」
        val restored = savedInstanceState?.getString(KEY_TAB)
            ?.let { name -> runCatching { Tab.valueOf(name) }.getOrNull() }
            ?: Tab.CHAT
        currentTab = null
        selectTab(restored)
        setupBackBehavior()

        askNotificationPermission()

        // 装完顺手把安装包清掉：等界面起来再干，别和首帧抢时间
        mainHandler.postDelayed({ cleanInstallerOnce() }, 1200)

        // 外部调用入口：别的 App / 快捷方式可以把一句话直接送进来
        // （自动化测试也走这条路，否则 adb 没法输入中文）
        if (savedInstanceState == null) handleExternalQuery(intent)
    }

    /**
     * 外部传入的一句话：「发送到 HardlySeek 说 xxx」。
     *
     * 用 extra 而不是自定义 scheme，是因为不想多开一个对外的入口面；
     * 配合 `launchMode="singleTop"`，第二句话会回到同一个界面实例，
     * 多轮上下文（「改成四点」指代上一条）才不会断。
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalQuery(intent)
    }

    private fun handleExternalQuery(intent: android.content.Intent?) {
        val query = intent?.getStringExtra(EXTRA_QUERY)?.trim().orEmpty()
        if (query.isEmpty()) return
        // 用掉就清掉：否则旋转屏、从后台回来都会被重放一遍
        intent?.removeExtra(EXTRA_QUERY)

        selectTab(Tab.CHAT)
        supportFragmentManager.executePendingTransactions()
        mainHandler.postDelayed({ sendText(query) }, 350)
    }

    /**
     * 装完自动清理安装包：不问，直接删（每个版本只跑一次）。
     *
     * 分两档，都不弹自建对话框：
     * 1. 自己的文件（应用内升级下载的）→ 静默删除，用户完全无感；
     * 2. 浏览器下载的 → 直接发起系统删除请求。系统那一下授权是分区存储的硬约束，
     *    删别人的文件必须过系统这道门，但它不是我们多问一句。
     *
     * 两档都不成立就安静退出，绝不拿弹窗打扰用户。
     */
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
            Toast.makeText(this, R.string.apk_clean_done, Toast.LENGTH_SHORT).show()
            return
        }

        // 传空列表会让 createDeleteRequest 抛异常，所以这里必须先判空
        val sender = ApkCleaner.deleteRequest(this, remaining) ?: return
        runCatching { apkCleanLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
    }

    /** 当前版本号，用来做「每个版本只清理一次」的键 */
    @Suppress("DEPRECATION")
    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 第一次进来问一次通知权限；拒绝了就不再打扰 */
    private fun askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (ReminderAlarm.canNotify(this)) return

        // 只问一次：反复弹系统权限框很烦，用户想开自己去系统设置里开
        val prefs = getSharedPreferences(PREFS_ONBOARDING, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFY_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()

        runCatching {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab?.name ?: Tab.CHAT.name)
    }

    // ------------------------------------------------------------------ 页面框架

    private fun applyInsets() {
        val root: LinearLayout = findViewById(R.id.root)
        val bottomNav: LinearLayout = findViewById(R.id.bottomNav)

        // 统一交给根布局处理：顶部让出状态栏，底部让出「导航栏或键盘」中更高的那个。
        //
        // 键盘相关的历史坑：
        // - 早先把 ime inset 加在输入栏自己的 padding 上，而系统在部分机型上
        //   又会同时把窗口 resize 一部分，两边叠加 → 输入栏和键盘之间空出一条；
        // - 现在只在这里（根布局）处理一次：bottom = max(导航栏, 键盘)，
        //   输入栏保持普通 padding，不再额外补偿。
        // - 键盘弹起时隐藏底部导航（微信的做法）：导航藏到键盘后面没有意义，
        //   省出的空间全部给消息列表，最后一条消息正好贴在输入栏上方。
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = ime.bottom > bars.bottom

            bottomNav.visibility = if (imeVisible) View.GONE else View.VISIBLE
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = maxOf(bars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun setupTabs() {
        ivTabChat = findViewById(R.id.ivTabChat)
        ivTabFeatures = findViewById(R.id.ivTabFeatures)
        ivTabSettings = findViewById(R.id.ivTabSettings)
        tvTabChat = findViewById(R.id.tvTabChat)
        tvTabFeatures = findViewById(R.id.tvTabFeatures)
        tvTabSettings = findViewById(R.id.tvTabSettings)

        findViewById<View>(R.id.tabChat).setOnClickListener { selectTab(Tab.CHAT) }
        findViewById<View>(R.id.tabFeatures).setOnClickListener { selectTab(Tab.FEATURES) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { selectTab(Tab.SETTINGS) }
    }

    fun selectTab(tab: Tab) {
        if (tab == currentTab) return

        // 离开对话页时，停掉麦克风和播报，避免在别的页面后台抢音频
        if (tab != Tab.CHAT) {
            asr.cancel()
            tts.stop()
            chatUi?.setMicActive(false)
        }

        currentTab = tab
        val fragment: Fragment = when (tab) {
            Tab.CHAT -> ChatFragment()
            Tab.FEATURES -> FeaturesFragment()
            Tab.SETTINGS -> SettingsFragment()
        }
        // 切底部导航时把二级页面的返回栈清空，
        // 否则从设置页按返回会先退回「记忆」这种上一轮的中间页
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        updateTabUi()
    }

    /**
     * 打开本地记忆页。
     *
     * 底部导航只有三项，记忆不是独立 tab，而是功能页里的二级页面——
     * 所以用 addToBackStack，系统返回时回到功能页，底栏高亮仍是「功能」。
     */
    fun openMemory() {
        asr.cancel()
        tts.stop()
        chatUi?.setMicActive(false)
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, MemoryFragment())
            .addToBackStack(BACK_MEMORY)
            .commit()
    }

    /** 退出登录：清掉身份，回到登录页（任务栈整体重置） */
    fun gotoLogin() {
        startActivity(
            android.content.Intent(this, LoginActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            }
        )
        finish()
    }

    /**
     * 登录门禁。
     *
     * @return true 表示可以继续启动；false 表示已经跳去登录页并结束了本页
     */
    private fun ensureSignedIn(): Boolean {
        if (AccountStore(this).isSignedIn) return true
        // 外部送进来的那句话（自动化 / 分享）不挡
        if (intent?.getStringExtra(EXTRA_QUERY) != null) return true
        startActivity(android.content.Intent(this, LoginActivity::class.java))
        finish()
        return false
    }

    private fun updateTabUi() {
        val active = ContextCompat.getColor(this, R.color.brand_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)

        ivTabChat.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.CHAT) active else inactive)
        ivTabFeatures.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.FEATURES) active else inactive)
        ivTabSettings.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.SETTINGS) active else inactive)

        tvTabChat.setTextColor(if (currentTab == Tab.CHAT) active else inactive)
        tvTabFeatures.setTextColor(if (currentTab == Tab.FEATURES) active else inactive)
        tvTabSettings.setTextColor(if (currentTab == Tab.SETTINGS) active else inactive)
    }

    fun bindChatUi(ui: ChatUi) {
        chatUi = ui
    }

    fun unbindChatUi(ui: ChatUi) {
        if (chatUi === ui) chatUi = null
    }

    // ------------------------------------------------------------------ 返回键

    /**
     * 返回键分三层，符合「二级页面 → 主页面 → 退出」的直觉：
     * 1. 有二级页面（记忆）→ 先退回功能页；
     * 2. 不在对话页 → 回对话页；
     * 3. 已经在对话页 → 把控制权交回系统（退出应用）。
     */
    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                supportFragmentManager.backStackEntryCount > 0 ->
                    supportFragmentManager.popBackStack()
                currentTab != Tab.CHAT -> selectTab(Tab.CHAT)
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 设置

    /** 设置页要直接读写云端配置和待学习列表，把持久化对象给它 */
    fun appSettings(): AppSettings = settings

    /** 启动时把上次保存的全部设置灌回引擎和语音控制器 */
    private fun restoreSettings() {
        speakEnabled = settings.speakEnabled
        continuousMode = settings.continuousMode
        tts.speakEnabled = speakEnabled

        // 指定引擎必须在首次播报前切好，否则这一次还是用旧引擎的声音
        ttsEngine = settings.ttsEngine
        if (ttsEngine.isNotEmpty()) tts.useEngine(ttsEngine)

        replyStyle = settings.replyStyle
        proactiveFollowUp = settings.proactiveFollowUp
        showThinking = settings.showThinking
        autoLearn = settings.autoLearn
        localModelEnabled = settings.localModel
        onlineSearchEnabled = settings.onlineSearch
        cloudConfig = settings.cloudConfig()
        syncAssistantConfig()
    }

    /** 把对话行为开关同步给纯逻辑层（规则 / 语料 / 引擎都读它） */
    private fun syncAssistantConfig() {
        AssistantConfig.replyStyle = when (replyStyle) {
            0 -> AssistantConfig.ReplyStyle.CONCISE
            2 -> AssistantConfig.ReplyStyle.DETAILED
            else -> AssistantConfig.ReplyStyle.STANDARD
        }
        AssistantConfig.proactiveFollowUp = proactiveFollowUp
        AssistantConfig.autoLearn = autoLearn
        AssistantConfig.localModel = localModelEnabled
        AssistantConfig.onlineSearch = onlineSearchEnabled
    }

    /**
     * 加载本地小模型：有存档读存档，没有就用内置语料打底并存档。
     * 打底语料 = 闲聊话题的全部应答 + 预置常识的答案。
     */
    private fun createLocalModel(): NgramModel {
        val file = File(filesDir, MODEL_FILE)
        val model = NgramModel()
        val loaded = runCatching {
            if (file.exists()) model.loadInto(file.readText()) else false
        }.getOrDefault(false)
        if (!loaded) {
            model.observeAll(
                DialogCorpus.topics.flatMap { it.replies + it.more + it.advice + it.followUps + it.samples }
            )
            model.observeAll(MemorySeed.pairs.map { it.second })
            // 常识知识库的问答对是模型的"主课"：问句和答案配对训练，
            // 模型才学得到「问句的尾巴接答案的开头」，续写才有因果
            model.observePairs(MemorySeed.pairs)
            model.observePairs(KnowledgeBase.trainingPairs())
            // 思维模式语料：让模型学到这种「接住 + 给路径 + 抛回来」的说话方式
            model.observeAll(ThoughtBank.allSentences())
            model.observePairs(ReplyCrafter.trainingPairs())
            saveLocalModel()
        } else if (model.pairCount() == 0) {
            // 旧存档没有问答对，补齐再存一次
            model.observePairs(MemorySeed.pairs)
            model.observePairs(KnowledgeBase.trainingPairs())
            model.observeAll(ThoughtBank.allSentences())
            model.observePairs(ReplyCrafter.trainingPairs())
            saveLocalModel()
        }
        return model
    }

    /** 把小模型当前的训练进度写盘（退后台 / 退出时调用） */
    private fun saveLocalModel() {
        runCatching {
            File(filesDir, MODEL_FILE).writeText(localModel.serialize())
            localModel.markSaved()
        }
    }

    fun setSpeakEnabled(enabled: Boolean) {
        speakEnabled = enabled
        settings.speakEnabled = enabled
        tts.speakEnabled = enabled
        if (!enabled) tts.stop()
    }

    fun setContinuousMode(enabled: Boolean) {
        continuousMode = enabled
        settings.continuousMode = enabled
        if (!enabled) {
            asr.cancel()
            chatUi?.setMicActive(false)
        }
    }

    /** 切换语音合成引擎 */
    fun setTtsEngine(packageName: String) {
        ttsEngine = packageName
        settings.ttsEngine = packageName
        tts.useEngine(packageName)
    }

    fun setReplyStyle(style: Int) {
        replyStyle = style
        settings.replyStyle = style
        syncAssistantConfig()
    }

    fun setProactiveFollowUp(enabled: Boolean) {
        proactiveFollowUp = enabled
        settings.proactiveFollowUp = enabled
        syncAssistantConfig()
    }

    /** 是否展示「理解过程」（淡色小字，答案出现即移除） */
    fun setShowThinking(enabled: Boolean) {
        showThinking = enabled
        settings.showThinking = enabled
    }

    fun setAutoLearn(enabled: Boolean) {
        autoLearn = enabled
        settings.autoLearn = enabled
        syncAssistantConfig()
    }

    fun setLocalModelEnabled(enabled: Boolean) {
        localModelEnabled = enabled
        settings.localModel = enabled
        syncAssistantConfig()
    }

    fun setOnlineSearchEnabled(enabled: Boolean) {
        onlineSearchEnabled = enabled
        settings.onlineSearch = enabled
        syncAssistantConfig()
    }

    /** 设置页保存云端配置：立刻写盘并生效 */
    fun setCloudConfig(config: CloudLlmHandler.CloudConfig) {
        cloudConfig = config
        settings.saveCloud(config)
        // 换了服务商 / 模型，之前缓存的答案不能再复用
        CloudCache.clear()
    }

    /** 设置页「测试连接」：在子线程打一次最小请求，结果回调到主线程 */
    fun testCloud(config: CloudLlmHandler.CloudConfig, onResult: (String) -> Unit) {
        netExecutor.execute {
            val result = runCatching { CloudLlmHandler.ping(config) }
                .getOrElse { LlmResult.Fail("请求失败：${it.message.orEmpty()}") }
            val text = when (result) {
                is LlmResult.Ok -> "连上了。模型 ${result.model} 回复：${result.text}"
                is LlmResult.Fail -> "没连上：${result.reason}"
            }
            mainHandler.post { if (!isFinishing && !isDestroyed) onResult(text) }
        }
    }

    /** 恢复默认设置（不动记忆库），并立刻生效 */
    fun resetSettings() {
        settings.reset()
        restoreSettings()
    }

    // ------------------------------------------------------------------ 对话

    /**
     * 轮次序号。
     *
     * 后台那条串行链（联网 → 云端）回来时，如果序号已经不是最新，
     * 说明用户又说了新的一句——旧结果直接丢弃，绝不上屏。
     */
    private var turnSeq = 0

    /** 思考过程小字是否还在显示（答案已上屏后不再追加，免得又冒出一行） */
    @Volatile
    private var thinkingActive = false

    /** 麦克风按钮 */
    fun onMicClicked() {
        if (asr.isListening) {
            asr.stop()
        } else {
            ensurePermissionThenStart()
        }
    }

    /** 输入框发送 */
    fun onSendClicked(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            chatUi?.showStatus(getString(R.string.error_empty_input))
            return
        }
        sendText(trimmed)
    }

    /** 处理一条用户输入（文字或识别结果） */
    fun sendText(text: String) = sendCandidates(listOf(text), RawText.SOURCE_TEXT)

    /**
     * 处理一次识别（可能有多条候选）。
     *
     * 界面在这里只做两件事：轮次号 +1（用来丢弃过期结果），
     * 然后把输入丢进流水线。**中间怎么走一概不管**——
     * 提醒域、本机工具、本地问答、联网、云端，都由流水线和各模块自己决定；
     * 结果通过事件总线上的 [ReplyReady] 回来。
     */
    fun sendCandidates(candidates: List<String>, source: String = RawText.SOURCE_ASR) {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return

        turnSeq++
        pipeline.submit(turnSeq, cleaned, source)
    }

    /**
     * 订阅事件总线。
     *
     * 三个事件的职责分得很清：
     * - [TurnPlanned]：本地阶段算完了，界面据此决定要不要显示思考过程；
     * - [ReplyReady]：最终答案，**只上屏一次**（不会再出现「先答一遍再改一遍」）；
     * - [ReminderFired]：提醒到点，闪一下状态栏，前台时顺带念一句。
     */
    private fun subscribeEvents() {
        bus.on<TurnPlanned>(owner = this) { event ->
            if (event.turn == turnSeq) showThinking(event.turn, event.plan)
        }
        bus.on<ReplyReady>(owner = this) { event ->
            if (event.turn == turnSeq) finishTurn(event.turn, event.reply, event.primary)
        }
        bus.on<ReminderFired>(owner = this) { onReminderFired(it) }
    }

    /** 提醒到点：状态栏提示 +（前台且开着播报时）念一句 */
    private fun onReminderFired(event: ReminderFired) {
        if (isFinishing || isDestroyed) return
        chatUi?.showStatus(getString(R.string.status_reminder_fired, event.content), highlight = true)
        if (speakEnabled && isForeground && currentTab == Tab.CHAT && !tts.isSpeaking) {
            tts.speak(getString(R.string.speak_reminder, event.content))
        }
    }

    /** 当前文案的欢迎语气泡 */
    private fun newWelcome() = ChatMessage(text = getString(R.string.welcome), isUser = false)

    /**
     * 是不是「开场那一条」。
     *
     * 落盘的历史里第一条固定是欢迎语；改名、换语言之后它的文字会变，
     * 所以只认「位置 + 是助手发的普通消息」，不去比对具体文案。
     */
    private fun ChatMessage.isOpeningLine(): Boolean =
        !isUser && kind == ChatMessage.KIND_NORMAL

    /** 当前生效的运行期配置 */
    private fun config(): AssistantRouter.Config = AssistantRouter.Config(
        onlineEnabled = onlineSearchEnabled,
        cloud = cloudConfig,
        fallbackCity = lastWeatherCity
    )

    /**
     * 把「理解过程 + 接下来要做什么」以淡色小字逐行显示出来。
     *
     * 每一步间隔一点点，用户能看到它在想什么、正在联网还是正在问 AI，
     * 而不是一闪而过——这也是豆包 / DeepSeek 那种思考过程给人的信任感来源。
     */
    private fun showThinking(seq: Int, plan: AssistantRouter.TurnPlan) {
        thinkingActive = showThinking
        if (!showThinking) {
            val waitingText = if (plan.decision.route == RoutePlanner.Route.WEB) {
                getString(R.string.status_online)
            } else {
                getString(R.string.status_cloud_thinking)
            }
            chatUi?.showStatus(waitingText, highlight = true)
            return
        }

        session.startThinking("正在理解…")
        val post = { index: Int, line: String ->
            mainHandler.postDelayed(
                { if (seq == turnSeq && thinkingActive) session.appendThinking("· $line") },
                QueryUnderstanding.STEP_MS * index
            )
        }
        plan.understanding.steps.forEachIndexed { index, step -> post(index + 1, step) }
        post(plan.understanding.steps.size + 1, "${plan.decision.note}…")
    }

    /**
     * 结果落地（主线程）：清掉思考过程 → 上屏一次 → 播报。
     *
     * @param seq 这一轮的序号；不是最新就丢弃，防止旧答案插回来
     */
    private fun finishTurn(seq: Int, reply: AssistantRouter.Reply, primary: String) {
        if (seq != turnSeq) return
        thinkingActive = false
        if (isFinishing || isDestroyed) return

        history.recordAssistant(reply.text, reply.intent)

        val heard = reply.heard
        if (heard != null && heard != primary) {
            chatUi?.showStatus(getString(R.string.status_heard, heard), highlight = true)
            mainHandler.postDelayed({
                if (!tts.isSpeaking) chatUi?.showStatus(getString(R.string.status_idle))
            }, 2500)
            return
        }

        // 工具让助手闭嘴（静音 / 停止播报）时不再念出来
        if (reply.intent == ToolIntent.TTS_STOP) {
            tts.stop()
        }

        if (reply.error != null) {
            chatUi?.showStatus(getString(R.string.status_cloud_fail, reply.error))
        }

        if (speakEnabled) {
            chatUi?.showStatus(getString(R.string.status_speaking))
            // 没念出声（引擎坏了 / 全是不认识的符号）就当场收尾，
            // 否则状态栏会一直停在「正在回答…」，也不会重开麦克风
            if (!tts.speak(reply.speakText)) {
                chatUi?.showStatus(reply.error?.let { getString(R.string.status_cloud_fail, it) }
                    ?: getString(R.string.status_idle))
                maybeRestartListening(400)
            }
        } else {
            chatUi?.showStatus(reply.error?.let { getString(R.string.status_cloud_fail, it) }
                ?: getString(R.string.status_idle))
            maybeRestartListening(400)
        }
    }

    /** 功能页点击示例：切回对话页并直接执行 */
    fun runFromSkill(example: String) {
        selectTab(Tab.CHAT)
        supportFragmentManager.executePendingTransactions()
        sendText(example)
    }

    /** 改一条用户消息的文字（对话页点气泡 → 修改） */
    fun updateUserMessage(index: Int, text: String): Boolean =
        session.updateUserText(index, text)

    /** 删掉一轮对话：提问 + 紧跟着的回答（对话页点气泡 → 删除） */
    fun deleteTurn(index: Int): Boolean = session.deleteTurn(index)

    fun toggleSpeak(): Boolean {
        setSpeakEnabled(!speakEnabled)
        return speakEnabled
    }

    fun toggleContinuous(): Boolean {
        setContinuousMode(!continuousMode)
        return continuousMode
    }

    fun clearChat() {
        asr.cancel()
        tts.stop()
        // 作废还在后台跑的那一条：序号一变，它的结果就不会再上屏
        turnSeq++
        thinkingActive = false
        history.clear()
        session.ensureWelcome(getString(R.string.welcome))
        chatUi?.showStatus(getString(R.string.status_idle))
    }

    // ------------------------------------------------------------------ 语音事件

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
        if (!asr.isAvailable()) {
            chatUi?.showStatus(getString(R.string.error_no_recognizer))
            return
        }
        tts.stop()
        asr.start()
    }

    override fun onReady() {
        chatUi?.showStatus(getString(R.string.status_listening), highlight = true)
        chatUi?.setMicActive(true)
    }

    override fun onPartialResult(text: String) {
        chatUi?.showStatus("识别中：$text", highlight = true)
    }

    override fun onEndOfSpeech() {
        chatUi?.showStatus(getString(R.string.status_recognizing))
        chatUi?.setMicActive(false)
    }

    override fun onFinalResult(text: String, candidates: List<String>) {
        chatUi?.setMicActive(false)
        chatUi?.showStatus(getString(R.string.status_idle))
        sendCandidates(candidates)
    }

    override fun onError(message: String) {
        chatUi?.setMicActive(false)
        chatUi?.showStatus(message)
        // 识别失败只在状态栏提示，不写进对话列表：
        // 连续对话时 "没听清" 会反复触发，写进列表会把对话刷屏
    }

    override fun onSpeakingFinished() {
        chatUi?.showStatus(getString(R.string.status_idle))
        maybeRestartListening(250)
    }

    /**
     * 重新打开麦克风。
     * 延迟一小段时间是为了让上一轮的 UI 动画和状态栏更新完，
     * 同时用 isSpeaking / isListening 双重判断防止重复启动。
     */
    private fun maybeRestartListening(delayMs: Long) {
        if (!continuousMode || !isForeground || currentTab != Tab.CHAT) return
        if (!asr.isAvailable()) return
        if (!hasMicPermission()) {
            // 自动续听不能绕过权限申请（那是用户点麦克风时的路径），
            // 静默停在这里，等用户下一次手动点麦克风
            chatUi?.showStatus(getString(R.string.error_no_permission))
            return
        }
        mainHandler.postDelayed({
            if (continuousMode && isForeground && currentTab == Tab.CHAT &&
                !tts.isSpeaking && !asr.isListening
            ) {
                startVoiceInput()
            }
        }, delayMs)
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        isForeground = true
    }

    override fun onPause() {
        isForeground = false
        // 退到后台就停掉识别和播报，避免连续对话在后台一直抢占麦克风。
        // 这里必须判 initialized：登录门禁会在 onCreate 里就 finish 掉本页，
        // 那时 asr / tts 还没来得及创建，直接调用会崩在 onDestroy（已被真机撞到过）
        mainHandler.removeCallbacksAndMessages(null)
        if (::asr.isInitialized) asr.cancel()
        if (::tts.isInitialized) tts.stop()
        chatUi?.setMicActive(false)
        if (::localModel.isInitialized) saveLocalModel()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        // 一次注销全部订阅：只注销单个的话，旋转屏几次就会收到一堆重复回调
        bus.off(this)
        if (::asr.isInitialized) asr.release()
        if (::tts.isInitialized) tts.release()
        super.onDestroy()
    }

    private companion object {
        const val KEY_TAB = "current_tab"

        /** 首次引导相关的偏好都放这一个文件 */
        const val PREFS_ONBOARDING = "onboarding"

        /** 通知权限只问一次，问过就记下来 */
        const val KEY_NOTIFY_ASKED = "notify_asked"

        /** 装完清理安装包的标记（后面拼版本号，每个版本各问一次） */
        const val KEY_APK_CLEAN = "apk_clean_"

        /** 外部传入一句话用的 extra 名 */
        const val EXTRA_QUERY = "query"

        /** 记忆页在返回栈里的名字 */
        const val BACK_MEMORY = "memory"

        /** 本地小模型训练进度的存档文件名 */
        const val MODEL_FILE = "ngram_model.txt"
    }
}
