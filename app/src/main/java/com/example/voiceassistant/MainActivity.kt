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
import com.example.voiceassistant.assistant.CityClimate
import com.example.voiceassistant.assistant.DialogCorpus
import com.example.voiceassistant.assistant.KnowledgeBase
import com.example.voiceassistant.assistant.NgramModel
import com.example.voiceassistant.assistant.ReplyCrafter
import com.example.voiceassistant.assistant.ThoughtBank
import com.example.voiceassistant.assistant.OnlineQuery
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.data.ChatSession
import com.example.voiceassistant.data.CloudBrain
import com.example.voiceassistant.data.WebSearch
import com.example.voiceassistant.memory.MemorySeed
import com.example.voiceassistant.memory.SqliteMemoryRepository
import com.example.voiceassistant.speech.SpeechController
import com.example.voiceassistant.ui.ChatFragment
import com.example.voiceassistant.ui.MemoryFragment
import com.example.voiceassistant.ui.SettingsFragment
import com.example.voiceassistant.ui.SkillsFragment
import java.io.File

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

    /** 联网 runner：串行执行，避免同时打出去好几个请求 */
    private val netExecutor = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "web-search").apply { isDaemon = true }
    }

    /** 会话状态（消息 + 引擎 + 本地记忆库 + 小模型），整个应用共享一份 */
    val session: ChatSession by lazy {
        ChatSession(
            AssistantEngine(
                memory = SqliteMemoryRepository(applicationContext),
                model = localModel
            )
        )
    }

    lateinit var speech: SpeechController
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

    var autoLearn: Boolean = true
        private set

    var localModelEnabled: Boolean = true
        private set

    var onlineSearchEnabled: Boolean = true
        private set

    /**
     * 云端大模型（AI 大脑）配置。
     *
     * 默认全部关闭：没填 Key 就是一个纯粹的离线助手，行为与之前完全一致。
     * 用户在设置页填了接口和 Key 之后，本地答不好的问题会交给它，
     * 失败（超时 / Key 错 / 没网）时自动用本地答案兜底。
     */
    var cloudConfig: CloudBrain.CloudConfig = CloudBrain.CloudConfig()
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

        settings = AppSettings(this)

        // 小模型要先于会话创建（会话的引擎里要引用它）
        localModel = createLocalModel()

        session.ensureWelcome(getString(R.string.welcome))

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

        // 指定引擎必须在首次播报前切好，否则这一次还是用旧引擎的声音
        ttsEngine = settings.ttsEngine
        if (ttsEngine.isNotEmpty()) speech.useEngine(ttsEngine)

        replyStyle = settings.replyStyle
        proactiveFollowUp = settings.proactiveFollowUp
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
            // 思维模式语料：让模型学到这种「接住 + 给路径 + 抛回来」的说话方式，
            // 代价是模型文件变大（用户允许适当增加占用空间）
            model.observeAll(ThoughtBank.allSentences())
            model.observePairs(ReplyCrafter.trainingPairs())
            saveLocalModel()
        } else if (model.pairCount() == 0) {
            // 旧存档（v2.4 之前）没有问答对，补齐再存一次
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

    /** 切换语音合成引擎 */
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
    fun setCloudConfig(config: CloudBrain.CloudConfig) {
        cloudConfig = config
        settings.saveCloud(config)
    }

    /** 设置页「测试连接」：在子线程打一次最小请求，结果回调到主线程 */
    fun testCloud(config: CloudBrain.CloudConfig, onResult: (String) -> Unit) {
        netExecutor.execute {
            val result = runCatching { CloudBrain.ping(config) }
                .getOrElse { CloudBrain.CloudResult.Fail("请求失败：${it.message.orEmpty()}") }
            val text = when (result) {
                is CloudBrain.CloudResult.Ok ->
                    "连上了。模型 ${result.model} 回复：${result.text}"
                is CloudBrain.CloudResult.Fail -> "没连上：${result.reason}"
            }
            mainHandler.post { if (!isFinishing && !isDestroyed) onResult(text) }
        }
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

        // 云端大模型要带上下文，所以先在本轮写进会话之前把历史取好
        val history = session.recentTurns(CloudBrain.MAX_HISTORY_TURNS)
        val response = session.send(cleaned)

        val heard = response.heard
        if (heard != null && heard != cleaned.first()) {
            chatUi?.showStatus(getString(R.string.status_heard, heard), highlight = true)
            mainHandler.postDelayed({
                if (!speech.isSpeaking) chatUi?.showStatus(getString(R.string.status_idle))
            }, 2500)
        }

        // 本地答好了，但有些问题值得联网补一句实时 / 更准的内容
        val plan = if (AssistantConfig.onlineSearch) {
            OnlineQuery.plan(cleaned.first(), response.intent, lastWeatherCity)
        } else {
            null
        }
        if (plan != null) {
            searchOnline(plan, session.lastAssistantIndex(), response.text)
            return
        }

        // 本地答得不够好（或用户选了「AI 优先」）时，交给云端大模型
        if (CloudBrain.shouldAsk(response.intent, response.text, cloudConfig)) {
            askCloud(session.lastAssistantIndex(), response.text, cleaned.first(), history)
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
     * 异步联网查询。
     *
     * 本地那条回答先留在屏幕上（常年气候 / "我上网查一下"），
     * 子线程拿到结果后回主线程**替换同一条气泡**再播报，
     * 所以用户看到的是"下面在查 → 这条刷新了"，而不是突然多出一条。
     * 联网失败（没网 / 接口不可达）就保留本地回答补一句说明，离线可用性不受影响。
     */
    private fun searchOnline(plan: OnlineQuery.Plan, messageIndex: Int, localAnswer: String) {
        if (plan.kind == OnlineQuery.Kind.WEATHER && plan.city != null) {
            lastWeatherCity = plan.city
        }
        chatUi?.showStatus(getString(R.string.status_online), highlight = true)

        netExecutor.execute {
            val result: Pair<String, String>? = runCatching {
                when (plan.kind) {
                    OnlineQuery.Kind.WEATHER -> {
                        val city = plan.city ?: return@runCatching null
                        WebSearch.fetchWeather(city)?.let { AssistantEngine.INTENT_WEB to WebSearch.composeWeather(it) }
                    }
                    OnlineQuery.Kind.SEARCH -> {
                        WebSearch.fetchSummary(plan.query)
                            ?.let { AssistantEngine.INTENT_WEB to WebSearch.composeSearch(it) }
                    }
                }
            }.getOrNull()

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (result != null) {
                    session.replaceAssistant(messageIndex, result.second, result.first)
                    chatUi?.showStatus(getString(R.string.status_idle))
                    if (speakEnabled) {
                        chatUi?.showStatus(getString(R.string.status_speaking))
                        speech.speak(result.second)
                    }
                } else {
                    // 没查到：本地答案照旧，只是补一句说明，不让用户空等
                    session.replaceAssistant(
                        messageIndex,
                        "$localAnswer\n（我想联网核实一下，但这次没连上，以上是我本地内置的内容。）",
                        null
                    )
                    chatUi?.showStatus(getString(R.string.status_offline_fail))
                    if (speakEnabled) speech.speak(localAnswer)
                }
            }
        }
    }

    /**
     * 异步问云端大模型。
     *
     * 本地那条回答先留在屏幕上，子线程拿到结果后回主线程**替换同一条气泡**再播报，
     * 所以用户看到的是「先有一句 → 这条被更好的回答刷新了」。
     * 失败（没网 / Key 错 / 超时）保留本地答案，只在状态栏说明原因——
     * 这是设计底线：**配了云端也不能让离线可用性变差**。
     *
     * @param history 本轮之前的历史，用来保证模型接得上上下文
     */
    private fun askCloud(
        messageIndex: Int,
        localAnswer: String,
        input: String,
        history: List<CloudBrain.ChatTurn>
    ) {
        chatUi?.showStatus(getString(R.string.status_cloud_thinking), highlight = true)

        netExecutor.execute {
            val result: CloudBrain.CloudResult = runCatching {
                CloudBrain.call(cloudConfig, history, input, extraSystemForCloud())
            }.getOrElse { CloudBrain.CloudResult.Fail("请求失败：${it.message.orEmpty()}") }

            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                when (result) {
                    is CloudBrain.CloudResult.Ok -> {
                        session.replaceAssistant(messageIndex, result.text, AssistantEngine.INTENT_CLOUD)
                        // 云端的好答案也喂给本地小模型：聊得越多，本地兜底越接近这个水平
                        localModel.observePair(input, result.text)
                        chatUi?.showStatus(getString(R.string.status_idle))
                        if (speakEnabled) {
                            chatUi?.showStatus(getString(R.string.status_speaking))
                            speech.speak(result.text)
                        } else {
                            maybeRestartListening(400)
                        }
                    }
                    is CloudBrain.CloudResult.Fail -> {
                        session.replaceAssistant(messageIndex, localAnswer, null)
                        chatUi?.showStatus(getString(R.string.status_cloud_fail, result.reason))
                        if (speakEnabled) speech.speak(localAnswer) else maybeRestartListening(400)
                    }
                }
            }
        }
    }

    /**
     * 附加到 system 提示里的上下文：当前时间 + 用户教过的说法。
     *
     * 时间让模型答得出「今天」「明天」指的是哪一天；
     * 教过的说法保证云端不会和用户亲手教的内容唱反调。
     */
    private fun extraSystemForCloud(): String {
        val now = java.time.LocalDateTime.now()
        val sb = StringBuilder()
        sb.append("现在是 ${now.year} 年 ${now.monthValue} 月 ${now.dayOfMonth} 日，")
            .append("${now.hour} 点 ${String.format("%02d", now.minute)} 分左右。")

        val taught = session.engine.memoryRepository()?.entries()
            ?.filter { it.isTaught }
            ?.take(5)
            .orEmpty()
        if (taught.isNotEmpty()) {
            sb.append("\n用户亲手教过你这些说法，回答时必须照做：")
            for (entry in taught) {
                val answer = entry.answer.split('|', '｜').first().trim()
                sb.append("\n· 用户说「${entry.question}」时，要回答「$answer」")
            }
        }
        return sb.toString()
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
        if (::localModel.isInitialized) saveLocalModel()
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

        /** 本地小模型训练进度的存档文件名 */
        const val MODEL_FILE = "ngram_model.txt"
    }
}
