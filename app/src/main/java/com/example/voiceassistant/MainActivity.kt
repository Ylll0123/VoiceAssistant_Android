package com.example.voiceassistant

import android.Manifest
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.example.voiceassistant.assistant.AssistantConfig
import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.CloudLlm
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.memory.SqliteMemoryRepository
import com.example.voiceassistant.speech.SpeechController
import com.example.voiceassistant.ui.ChatFragment
import com.example.voiceassistant.ui.MemoryFragment
import com.example.voiceassistant.ui.SettingsFragment
import com.example.voiceassistant.ui.SkillsFragment

/**
 * 应用外壳：底部导航 + 三个页面 + 全局语音控制。
 *
 * 分工：
 * - Activity：语音识别 / 播报、会话状态、页面切换
 * - Fragment：只负责本页的展示与交互
 * - [ChatSession]：消息列表 + 引擎上下文，跨页面共享，所以切页不丢对话
 */
class MainActivity : AppCompatActivity(), SpeechController.Listener {

    enum class Tab { CHAT, SKILLS, MEMORY, SETTINGS }

    /** 对话页对外暴露的回调，Activity 收到语音事件后转给它 */
    interface ChatUi {
        fun showStatus(text: String, highlight: Boolean = false)
        fun setMicActive(active: Boolean)
    }

    /** 设置持久化：改完立刻写盘，下次打开还是上次的样子 */
    private lateinit var settings: AppSettings

    /** 会话状态（消息 + 引擎 + 本地记忆库），整个应用共享一份 */
    val session: ChatSession by lazy {
        ChatSession(AssistantEngine(memory = SqliteMemoryRepository(applicationContext)))
    }

    lateinit var speech: SpeechController
        private set

    var speakEnabled: Boolean = true
        private set

    var continuousMode: Boolean = true
        private set

    var speechRate: Float = 1.0f
        private set

    var speechPitch: Float = 1.0f
        private set

    var ttsEngine: String = ""
        private set

    var replyStyle: Int = 1
        private set

    var proactiveFollowUp: Boolean = true
        private set

    var autoLearn: Boolean = true
        private set

    private lateinit var ivTabChat: ImageView
    private lateinit var ivTabSkills: ImageView
    private lateinit var ivTabMemory: ImageView
    private lateinit var ivTabSettings: ImageView
    private lateinit var tvTabChat: TextView
    private lateinit var tvTabSkills: TextView
    private lateinit var tvTabMemory: TextView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        session.ensureWelcome(getString(R.string.welcome))

        settings = AppSettings(this)

        speech = SpeechController(this)
        speech.listener = this

        // 先把持久化下来的设置整体灌回去，再创建界面，
        // 否则设置页会先按默认值画一遍，再被覆盖，看着像闪一下
        restoreSettings()

        // 答不上来的话 → 收进待学习列表（记忆页可以一键教会）
        session.engine.onUnanswered = { question -> settings.addPending(question) }

        // 重建（旋转 / 分屏 / 被系统回收后恢复）时回到原来那一页，
        // 否则每次重建都跳回对话页，看起来像是「页面丢了」
        val restored = savedInstanceState?.getString(KEY_TAB)
            ?.let { name -> runCatching { Tab.valueOf(name) }.getOrNull() }
            ?: Tab.CHAT
        currentTab = null
        selectTab(restored)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab?.name ?: Tab.CHAT.name)
    }

    // ------------------------------------------------------------------ 页面框架

    private fun applyInsets() {
        val root: LinearLayout = findViewById(R.id.root)

        // 统一交给根布局处理：顶部让出状态栏，底部让出导航栏。
        // 早先是把底部 inset 加到导航栏内部 padding 上，在部分手机上
        // 导航栏会被系统栏挤出去（技能 / 设置两个入口看不见），
        // 现在改成根布局留白，底部导航一定落在可用区域内。
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
    }

    private fun setupTabs() {
        ivTabChat = findViewById(R.id.ivTabChat)
        ivTabSkills = findViewById(R.id.ivTabSkills)
        ivTabMemory = findViewById(R.id.ivTabMemory)
        ivTabSettings = findViewById(R.id.ivTabSettings)
        tvTabChat = findViewById(R.id.tvTabChat)
        tvTabSkills = findViewById(R.id.tvTabSkills)
        tvTabMemory = findViewById(R.id.tvTabMemory)
        tvTabSettings = findViewById(R.id.tvTabSettings)

        findViewById<View>(R.id.tabChat).setOnClickListener { selectTab(Tab.CHAT) }
        findViewById<View>(R.id.tabSkills).setOnClickListener { selectTab(Tab.SKILLS) }
        findViewById<View>(R.id.tabMemory).setOnClickListener { selectTab(Tab.MEMORY) }
        findViewById<View>(R.id.tabSettings).setOnClickListener { selectTab(Tab.SETTINGS) }
    }

    fun selectTab(tab: Tab) {
        if (tab == currentTab) return

        // 离开对话页时，停掉麦克风和播报，避免在别的页面后台抢音频
        if (tab != Tab.CHAT) {
            speech.cancelListening()
            speech.stopSpeaking()
            chatUi?.setMicActive(false)
        }

        currentTab = tab
        val fragment: Fragment = when (tab) {
            Tab.CHAT -> ChatFragment()
            Tab.SKILLS -> SkillsFragment()
            Tab.MEMORY -> MemoryFragment()
            Tab.SETTINGS -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        updateTabUi()
    }

    private fun updateTabUi() {
        val active = ContextCompat.getColor(this, R.color.brand_primary)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)

        ivTabChat.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.CHAT) active else inactive)
        ivTabSkills.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.SKILLS) active else inactive)
        ivTabMemory.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.MEMORY) active else inactive)
        ivTabSettings.imageTintList = ColorStateList.valueOf(if (currentTab == Tab.SETTINGS) active else inactive)

        tvTabChat.setTextColor(if (currentTab == Tab.CHAT) active else inactive)
        tvTabSkills.setTextColor(if (currentTab == Tab.SKILLS) active else inactive)
        tvTabMemory.setTextColor(if (currentTab == Tab.MEMORY) active else inactive)
        tvTabSettings.setTextColor(if (currentTab == Tab.SETTINGS) active else inactive)
    }

    fun bindChatUi(ui: ChatUi) {
        chatUi = ui
    }

    fun unbindChatUi(ui: ChatUi) {
        if (chatUi === ui) chatUi = null
    }

    // ------------------------------------------------------------------ 设置

    /** 设置页要直接读写云端配置和待学习列表，把持久化对象给它 */
    fun appSettings(): AppSettings = settings

    /** 启动时把上次保存的全部设置灌回引擎和语音控制器 */
    private fun restoreSettings() {
        speakEnabled = settings.speakEnabled
        continuousMode = settings.continuousMode
        speech.speakEnabled = speakEnabled

        speechRate = settings.speechRate
        speechPitch = settings.speechPitch
        speech.setSpeechRate(speechRate)
        speech.setSpeechPitch(speechPitch)

        // 指定引擎必须在首次播报前切好，否则这一次还是用旧引擎的声音
        ttsEngine = settings.ttsEngine
        if (ttsEngine.isNotEmpty()) speech.useEngine(ttsEngine)

        replyStyle = settings.replyStyle
        proactiveFollowUp = settings.proactiveFollowUp
        autoLearn = settings.autoLearn
        syncAssistantConfig()
    }

    /** 把三个对话行为开关同步给纯逻辑层（规则 / 语料 / 引擎都读它） */
    private fun syncAssistantConfig() {
        AssistantConfig.replyStyle = when (replyStyle) {
            0 -> AssistantConfig.ReplyStyle.CONCISE
            2 -> AssistantConfig.ReplyStyle.DETAILED
            else -> AssistantConfig.ReplyStyle.STANDARD
        }
        AssistantConfig.proactiveFollowUp = proactiveFollowUp
        AssistantConfig.autoLearn = autoLearn
    }

    fun setSpeakEnabled(enabled: Boolean) {
        speakEnabled = enabled
        settings.speakEnabled = enabled
        speech.speakEnabled = enabled
        if (!enabled) speech.stopSpeaking()
    }

    fun setContinuousMode(enabled: Boolean) {
        continuousMode = enabled
        settings.continuousMode = enabled
        if (!enabled) {
            speech.cancelListening()
            chatUi?.setMicActive(false)
        }
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate
        settings.speechRate = rate
        speech.setSpeechRate(rate)
    }

    fun setSpeechPitch(pitch: Float) {
        speechPitch = pitch
        settings.speechPitch = pitch
        speech.setSpeechPitch(pitch)
    }

    /** 切换语音合成引擎；有些手机自带引擎不支持变速，换一个语速才生效 */
    fun setTtsEngine(packageName: String) {
        ttsEngine = packageName
        settings.ttsEngine = packageName
        speech.useEngine(packageName)
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

    fun setAutoLearn(enabled: Boolean) {
        autoLearn = enabled
        settings.autoLearn = enabled
        syncAssistantConfig()
    }

    /** 恢复默认设置（不动记忆库），并立刻生效 */
    fun resetSettings() {
        settings.reset()
        restoreSettings()
    }

    // ------------------------------------------------------------------ 对话页交互

    fun onMicClicked() {
        if (speech.isListening) {
            speech.stopListening()
        } else {
            ensurePermissionThenStart()
        }
    }

    fun onSendClicked(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            chatUi?.showStatus(getString(R.string.error_empty_input))
            return
        }
        sendText(trimmed)
    }

    /** 处理一条用户输入（文字或识别结果）：写入会话 → 播报 */
    fun sendText(text: String) = sendCandidates(listOf(text))

    /**
     * 处理一次识别（可能有多条候选）。
     *
     * 引擎会挑一条能理解的说法，如果挑中的不是首选，
     * 状态栏会显示「听到：xxx」，让用户知道助手是怎么理解的。
     */
    fun sendCandidates(candidates: List<String>) {
        val cleaned = candidates.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return

        val response = session.send(cleaned)

        val heard = response.heard
        if (heard != null && heard != cleaned.first()) {
            chatUi?.showStatus(getString(R.string.status_heard, heard), highlight = true)
            mainHandler.postDelayed({
                if (!speech.isSpeaking) chatUi?.showStatus(getString(R.string.status_idle))
            }, 2500)
        }

        // 本地实在答不上来、且用户开了云端增强，才去联网问一次
        if (response.intent == AssistantEngine.INTENT_FALLBACK && settings.isCloudUsable()) {
            askCloud(cleaned.first())
            return
        }

        if (speakEnabled) {
            if (heard == null || heard == cleaned.first()) {
                chatUi?.showStatus(getString(R.string.status_speaking))
            }
            speech.speak(response.text)
        } else {
            if (heard == null || heard == cleaned.first()) {
                chatUi?.showStatus(getString(R.string.status_idle))
            }
            maybeRestartListening(400)
        }
    }

    /**
     * 云端兜底：本地答不上来时联网问一次大模型。
     *
     * 全程异步：先把界面上那条兜底提示换成「让我想想」，
     * 拿到结果再替换成真正的回答，网络再慢也不会卡住对话。
     * 失败（无网 / Key 错 / 超时）就换回本地提示，用户感知不到这一层。
     */
    private fun askCloud(question: String) {
        chatUi?.showStatus(getString(R.string.status_cloud), highlight = true)
        session.updateLastAssistant(
            getString(R.string.cloud_thinking),
            AssistantEngine.INTENT_CLOUD_PENDING
        )

        val history = session.recentPairs(3)
        val endpoint = settings.cloudEndpoint
        val apiKey = settings.cloudApiKey
        val model = settings.cloudModel

        Thread {
            val answer = CloudLlm.ask(endpoint, apiKey, model, question, history)
            mainHandler.post {
                val text = answer?.takeIf { it.isNotBlank() }
                if (text == null) {
                    session.updateLastAssistant(
                        AssistantEngine.FALLBACK,
                        AssistantEngine.INTENT_FALLBACK
                    )
                    finishTurn(AssistantEngine.FALLBACK)
                } else {
                    session.updateLastAssistant(text, AssistantEngine.INTENT_CLOUD)
                    finishTurn(text)
                }
            }
        }.start()
    }

    /** 一轮结束：决定是直接出声，还是（关了播报时）立刻续听 */
    private fun finishTurn(text: String) {
        if (speakEnabled) {
            chatUi?.showStatus(getString(R.string.status_speaking))
            speech.speak(text)
        } else {
            chatUi?.showStatus(getString(R.string.status_idle))
            maybeRestartListening(400)
        }
    }

    /** 顶栏「技能」按钮：即使底部导航被系统栏遮住，也能进技能页 */
    fun openSkills() = selectTab(Tab.SKILLS)

    /** 技能页点击示例：切回对话页并直接执行 */
    fun runFromSkill(example: String) {
        selectTab(Tab.CHAT)
        supportFragmentManager.executePendingTransactions()
        sendText(example)
    }

    fun toggleSpeak(): Boolean {
        setSpeakEnabled(!speakEnabled)
        return speakEnabled
    }

    fun toggleContinuous(): Boolean {
        setContinuousMode(!continuousMode)
        return continuousMode
    }

    fun clearChat() {
        speech.cancelListening()
        speech.stopSpeaking()
        session.clear()
        session.addAssistant(getString(R.string.welcome))
        chatUi?.showStatus(getString(R.string.status_idle))
    }

    // ------------------------------------------------------------------ 语音

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
            chatUi?.showStatus(getString(R.string.error_no_recognizer))
            return
        }
        speech.stopSpeaking()
        speech.startListening()
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
        if (!speech.isRecognitionAvailable()) return
        if (!hasMicPermission()) {
            // 自动续听不能绕过权限申请（那是用户点麦克风时的路径），
            // 静默停在这里，等用户下一次手动点麦克风
            chatUi?.showStatus(getString(R.string.error_no_permission))
            return
        }
        mainHandler.postDelayed({
            if (continuousMode && isForeground && currentTab == Tab.CHAT &&
                !speech.isSpeaking && !speech.isListening
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
        // 退到后台就停掉识别和播报，避免连续对话在后台一直抢占麦克风
        mainHandler.removeCallbacksAndMessages(null)
        speech.cancelListening()
        speech.stopSpeaking()
        chatUi?.setMicActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        speech.release()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_TAB = "current_tab"
    }
}
