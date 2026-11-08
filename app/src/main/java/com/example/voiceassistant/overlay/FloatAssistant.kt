package com.example.voiceassistant.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.example.voiceassistant.R
import com.example.voiceassistant.core.AiChat
import com.example.voiceassistant.core.CloudLlmBridge
import com.example.voiceassistant.core.Dispatcher
import com.example.voiceassistant.data.AppSettings
import com.example.voiceassistant.handler.llm.CloudLlmHandler
import com.example.voiceassistant.im.ImAutomator
import com.example.voiceassistant.im.ImContacts
import com.example.voiceassistant.im.ImPlatform
import com.example.voiceassistant.kws.WakeWordService
import com.example.voiceassistant.network.NetworkState
import com.example.voiceassistant.provider.ContextSupply
import com.example.voiceassistant.provider.DeviceSupply
import com.example.voiceassistant.provider.GenSupply
import com.example.voiceassistant.provider.MsgSupply
import com.example.voiceassistant.provider.ScheduleSupply
import com.example.voiceassistant.provider.StudySupply
import com.example.voiceassistant.provider.ToolSupply
import com.example.voiceassistant.service.AndroidDeviceController
import com.example.voiceassistant.service.AppCatalog
import com.example.voiceassistant.service.AsrManager
import com.example.voiceassistant.tool.AiTools
import com.example.voiceassistant.bus.ServiceBus

/**
 * 悬浮助手：在任何应用上面都能叫出来的那只「我在听」。
 *
 * ## 它解决的是「为什么要退出正在做的事」
 * 以前发微信必须：切回本应用 → 长按说话 → 等确认 → 被弹到微信 → 发完再切回来。
 * 用户抱怨的「不自动」，本质是这一串来回。小爱同学那套的做法反过来：
 * **用的时候人就在微信里**，助手浮在上面问一句「你要发给谁」，
 * 读的是当前这个应用的会话列表，选完人直接发。
 *
 * ## 三层
 * ```
 * 悬浮球（常驻，白底小圆）  ← 点一下展开
 *   「我在听」面板           ← 识别 + 追问 + 确认，全在这张卡里
 *     联系人编号列表        ← 从微信/QQ 首页现读，说「第 3 个」也行
 *     发送确认卡（取消/发送）
 * ```
 *
 * ## 为什么状态机放在这一层而不是 NLU
 * 多轮追问（问人 → 问内容 → 问确认）是**界面状态**，不是语句意图。
 * 塞进 NLU 会让「给妈妈发微信说我晚点到」这种一句话说完的情况也被追问。
 * 这里只接「一句话里没说清收件人」的入口，其余照旧交给 [Dispatcher]。
 *
 * ## 权限
 * - 悬浮窗（SYSTEM_ALERT_WINDOW）：设置页里申请；
 * - 录音：应用启动时已经有；
 * - 无障碍：接手微信/QQ 用的，和主界面同一条通路。
 */
class FloatAssistant : Service(), AsrManager.Listener {

    /**
     * 服务也要套一层语言。
     *
     * Activity 是在自己的 `attachBaseContext` 里换 Configuration 的，
     * 这条路对 Service 不生效——不加这一层，界面是中文、悬浮卡上的字是英文。
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(
            com.example.voiceassistant.data.LocaleHelper.wrap(
                newBase,
                com.example.voiceassistant.data.AppSettings.readLanguage(newBase)
            )
        )
    }

    private enum class Step {
        /** 待命：说什么都交给 [Dispatcher] */
        IDLE,

        /** 等用户选人（列表已铺好，或让他直接说名字） */
        CHOOSING,

        /** 等用户说内容 */
        ASKING,

        /** 等用户确认 */
        CONFIRMING,

        /** 正在进微信/QQ 发送 */
        RUNNING
    }

    private lateinit var wm: WindowManager
    private lateinit var asr: AsrManager
    private lateinit var settings: AppSettings
    private lateinit var automator: ImAutomator
    private lateinit var dispatcher: Dispatcher
    private lateinit var appCatalog: AppCatalog

    private var bubble: View? = null
    private var panel: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null

    private var tvUser: TextView? = null
    private var tvReply: TextView? = null
    private var listBox: LinearLayout? = null
    private var confirmCard: LinearLayout? = null
    private var tvTo: TextView? = null
    private var tvContent: TextView? = null
    private var dots: DotsView? = null

    private val ui = Handler(Looper.getMainLooper())

    private var step = Step.IDLE
    private var platform = ImPlatform.WECHAT
    private var names: List<String> = emptyList()
    private var to = ""
    private var content = ""

    /**
     * 走 [Dispatcher] 那条路时，[MsgSupply] 已经 prepare 过、生成了 traceId。
     *
     * 这时候点「发送」必须用 confirm(traceId) 而不是 sendNow——
     * 否则等于绕过服务层又发了一遍，而服务层那条还挂着待确认，用户下一句「确认」会重复发。
     */
    private var pendingTrace: String? = null

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        alive = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = AppSettings(this)
        automator = ImAutomator.of(applicationContext)
        appCatalog = AppCatalog(applicationContext)
        asr = AsrManager(this).also { it.listener = this }
        dispatcher = buildDispatcher()
        startForegroundCompat()
        showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LISTEN -> {
                expand()
                beginListening()
            }
            else -> showBubble()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        instance = null
        isRunning = false
        ui.removeCallbacksAndMessages(null)
        releaseMic()
        runCatching { asr.cancel(); asr.release() }
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { panel?.let { wm.removeView(it) } }
        bubble = null
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 组装

    /**
     * 悬浮窗里的服务是**另建一份**，不复用界面的那份。
     *
     * 两份 [ServiceBus] 互不干扰，但 [ImAutomator] 是进程级单例，
     * 待确认的发送仍然共用——用户在界面里说「确认」，在这里也能接着发。
     */
    private fun buildDispatcher(): Dispatcher {
        val cfg: () -> CloudLlmHandler.CloudConfig = { settings.cloudConfig() }
        val bridge = CloudLlmBridge(cfg)
        val device = AndroidDeviceController(applicationContext)
        val msg = MsgSupply(
            context = applicationContext,
            automator = automator,
            recentMessages = { emptyList() },
            autoSend = { settings.msgAutoSend }
        )
        val bus = ServiceBus().apply {
            registerAll(*ScheduleSupply(this@FloatAssistant).all.toTypedArray())
            registerAll(*DeviceSupply(this@FloatAssistant, device) { }.all.toTypedArray())
            registerAll(*ToolSupply(bridge).all.toTypedArray())
            registerAll(*StudySupply(bridge).all.toTypedArray())
            registerAll(*GenSupply(bridge).all.toTypedArray())
            registerAll(*ContextSupply().all.toTypedArray())
            registerAll(*msg.all.toTypedArray())
        }
        val ai = AiChat(AiTools(bus), config = cfg)
        return Dispatcher(
            bus = bus,
            ai = ai,
            msg = msg,
            appMatcher = { appCatalog.matches(it) }
        )
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL, getString(R.string.float_notification_channel),
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            manager.createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent().setClassName(packageName, "com.example.voiceassistant.ui.VoiceAssistantActivity"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.float_notification_title))
            .setContentText(getString(R.string.float_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------------ 悬浮球

    private fun showBubble() {
        if (bubble != null) return
        hidePanel()
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = round(Color.WHITE, 22)
            elevation = dp(8).toFloat()
            setPadding(dp(12), 0, dp(12), 0)
            /*
             * 一定要给 DotsView 明确的宽高。
             *
             * 不传 LayoutParams 时 getDefaultSize 会把 AT_MOST 的量法直接当成可用宽度，
             * 一个 WRAP_CONTENT 的 View 就撑满了整行——小圆球变成盖住半屏的白条。
             */
            addView(
                DotsView(this@FloatAssistant, Color.WHITE).also { it.tint = ACCENT },
                LinearLayout.LayoutParams(dp(26), dp(16))
            )
            setOnClickListener { expand(); beginListening() }
            enableDrag(this)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            dp(44),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            // 让开顶栏：压在标题上会挡住应用自己的按钮
            y = dp(96)
        }
        runCatching { wm.addView(view, params) }
        bubble = view
        bubbleParams = params
    }

    /** 小圆可以拖着挪位置：挡着字的时候用户得能推开 */
    private fun enableDrag(view: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, event ->
            val params = bubbleParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) v.performClick()
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ 面板

    private fun expand() {
        hideBubble()
        if (panel != null) return
        val card = buildCard()
        val params = WindowManager.LayoutParams(
            screenWidth() - dp(32),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(56)
        }
        runCatching { wm.addView(card, params) }
        panel = card
        panelParams = params
        step = Step.IDLE
        showPanel()
        resetContent()
        setReply(getString(R.string.float_idle_hint))
    }

    private fun collapse() {
        runCatching { asr.cancel() }
        hidePanel()
        step = Step.IDLE
        names = emptyList()
        to = ""
        content = ""
        pendingTrace = null
        showBubble()
    }

    private fun hideBubble() {
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        dots?.let { it.animate()?.cancel() }
        dots = null
        tvUser = null
        tvReply = null
        listBox = null
        confirmCard = null
        tvTo = null
        tvContent = null
    }

    private fun buildCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.WHITE, 26)
            elevation = dp(16).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(18))
        }

        // 头部：五个点 + 我在听 + 关闭
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dots = DotsView(this, Color.WHITE).also { it.tint = ACCENT }
        header.addView(dots, LinearLayout.LayoutParams(dp(40), dp(24)))
        header.addView(
            TextView(this).apply {
                text = getString(R.string.float_listen)
                setTextColor(TEXT)
                textSize = 17f
                setPadding(dp(10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            TextView(this).apply {
                text = "×"
                setTextColor(HINT)
                textSize = 24f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(4), dp(6))
                setOnClickListener { collapse() }
            }
        )
        card.addView(header, LinearLayout.LayoutParams(-1, dp(40)))

        // 用户说的话（右侧灰气泡，跟着识别结果实时变）
        tvUser = TextView(this).apply {
            textSize = 15f
            setTextColor(TEXT)
            gravity = Gravity.END
            background = round(Color.rgb(0xF2, 0xF2, 0xF2), 16)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            visibility = View.GONE
        }
        card.addView(
            tvUser,
            LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END
                topMargin = dp(6)
            }
        )

        // 助手说的话
        tvReply = TextView(this).apply {
            textSize = 17f
            setTextColor(TEXT)
            setPadding(0, dp(12), 0, dp(2))
        }
        card.addView(tvReply, LinearLayout.LayoutParams(-1, -2))

        // 联系人列表
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(
            listBox,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        )

        // 发送确认卡
        confirmCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(Color.rgb(0xF7, 0xF7, 0xF7), 18)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            visibility = View.GONE
        }
        confirmCard!!.addView(label(getString(R.string.float_send_to)))
        tvTo = TextView(this).apply {
            textSize = 17f
            setTextColor(TEXT)
            setPadding(0, dp(6), 0, dp(10))
        }
        confirmCard!!.addView(tvTo)
        tvContent = TextView(this).apply {
            textSize = 18f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(14))
        }
        confirmCard!!.addView(tvContent)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(
            actionButton(getString(R.string.action_cancel), Color.rgb(0xEF, 0xEF, 0xEF), TEXT) {
                resetContent()
                setReply(getString(R.string.action_cancel))
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(12) }
        )
        buttons.addView(
            actionButton(getString(R.string.float_send), SEND_GREEN, Color.WHITE) { runSend() },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )
        confirmCard!!.addView(buttons)
        card.addView(
            confirmCard,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        )

        return card
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(HINT)
    }

    private fun actionButton(
        text: String,
        bg: Int,
        fg: Int,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 17f
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = round(bg, 23)
        setOnClickListener { onClick() }
    }

    /** 面板内容复位（不动 step） */
    private fun resetContent() {
        tvUser?.text = ""
        tvUser?.visibility = View.GONE
        listBox?.removeAllViews()
        listBox?.visibility = View.VISIBLE
        confirmCard?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ 识别

    private fun beginListening() {
        showPanel()
        dots?.startPulse()
        if (!hasMic()) {
            setReply(getString(R.string.float_no_mic))
            return
        }
        /*
         * 麦克风是独占的：唤醒服务还在循环里的话，这里 start 会立刻
         * ERROR_AUDIO / ERROR_RECOGNIZER_BUSY，表现就是「点了小圆球没反应」。
         * 先让它退出，等系统把麦克风还回来（和主界面同一条路数）。
         */
        WakeWordService.instance?.pause()
        ui.removeCallbacks(startAsr)
        ui.postDelayed(startAsr, MIC_HANDOVER_MS)
    }

    private val startAsr = Runnable {
        if (!alive || !hasMic()) return@Runnable
        asr.start()
    }

    /** 服务已经被销毁时，排队里的 [startAsr] 不能再动识别器 */
    private var alive = false

    /** 说完 / 出错之后把唤醒循环还回去，不然用户回到主界面就叫不醒了 */
    private fun releaseMic() {
        runCatching { WakeWordService.instance?.resume() }
    }

    private fun hasMic(): Boolean = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onReady() {
        dots?.startPulse()
    }

    override fun onPartialResult(text: String) {
        ui.post {
            if (text.isBlank()) return@post
            tvUser?.let {
                it.visibility = View.VISIBLE
                it.text = text
            }
        }
    }

    override fun onEndOfSpeech() = Unit

    override fun onFinalResult(text: String, candidates: List<String>) {
        ui.post {
            dots?.stopPulse()
            releaseMic()
            val said = text.trim()
            if (said.isNotEmpty()) {
                tvUser?.let {
                    it.visibility = View.VISIBLE
                    it.text = said
                }
            }
            handle(said)
        }
    }

    override fun onError(message: String) {
        ui.post {
            dots?.stopPulse()
            releaseMic()
            setReply(message)
        }
    }

    // ------------------------------------------------------------------ 多轮

    private fun handle(text: String) {
        if (text.isEmpty()) return
        when (step) {
            Step.IDLE -> handleIdle(text)
            Step.CHOOSING -> handleChoosing(text)
            Step.ASKING -> {
                content = text
                step = Step.CONFIRMING
                showConfirm()
                beginListening()
            }
            Step.CONFIRMING -> when (dispatcher.confirmKind(text)) {
                Dispatcher.ConfirmKind.YES -> runSend()
                Dispatcher.ConfirmKind.NO -> {
                    resetContent()
                    setReply(getString(R.string.action_cancel))
                }
                // 既没说确认也没说取消：当成改内容（用户可能觉得说错了）
                Dispatcher.ConfirmKind.OTHER -> {
                    content = text
                    showConfirm()
                }
            }
            Step.RUNNING -> setReply(getString(R.string.float_busy))
        }
    }

    /**
     * 一句话进来，先看是不是「要发消息但没说发给谁」；
     * 其余照旧交给 [Dispatcher]（本地指令 / 大模型），结果原样显示在卡上。
     */
    private fun handleIdle(text: String) {
        val bare = SendIntent.parse(text)
        if (bare != null) {
            beginChoose(ImPlatform.of(bare.platform) ?: ImPlatform.WECHAT)
            return
        }
        setReply(getString(R.string.float_thinking))
        Thread {
            val online = NetworkState.isOnline()
            val result = runCatching {
                dispatcher.dispatch(text, emptyList(), "", online)
            }.getOrNull()
            ui.post { showDispatch(result) }
        }.start()
    }

    private fun showDispatch(result: Dispatcher.Result?) {
        when (result) {
            null -> setReply(getString(R.string.float_failed))
            is Dispatcher.Result.Blocked -> setReply(result.message)
            is Dispatcher.Result.Local -> {
                val reply = result.reply
                setReply(reply.speech)
                // 本地服务准备了一条待发送：把确认卡直接铺出来，省得再问一遍
                val trace = reply.data["trace_id"]
                if (trace != null) {
                    step = Step.CONFIRMING
                    to = reply.data["to"] ?: to
                    content = reply.data["content"] ?: content
                    pendingTrace = trace
                    showConfirm()
                }
            }
            is Dispatcher.Result.Ask -> setReply(result.answer.text)
        }
    }

    /** 进「选人」：把当前应用的会话列表读出来铺在卡上 */
    private fun beginChoose(target: ImPlatform) {
        platform = target
        step = Step.CHOOSING
        resetContent()
        setReply(getString(R.string.float_reading_contacts, target.label))
        Thread {
            val ok = runCatching { automator.openHome(target) }.getOrDefault(false)
            val list = if (ok) runCatching {
                automator.conversationsOnScreen(CHOICE_COUNT)
            }.getOrDefault(emptyList()) else emptyList()
            ui.post {
                names = list
                if (list.isEmpty()) {
                    setReply(getString(R.string.float_ask_recipient))
                } else {
                    renderNames(list)
                    setReply(getString(R.string.float_ask_recipient))
                }
                beginListening()
            }
        }.start()
    }

    private fun renderNames(list: List<String>) {
        val box = listBox ?: return
        box.removeAllViews()
        list.forEachIndexed { index, name ->
            val row = TextView(this).apply {
                text = "${index + 1}   $name"
                textSize = 17f
                setTextColor(TEXT)
                setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { askContent(name) }
            }
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun handleChoosing(text: String) {
        if (SendIntent.cancel(text)) {
            resetContent()
            setReply(getString(R.string.action_cancel))
            return
        }
        val picked = ImContacts.choose(text, names)
        if (picked != null) {
            askContent(picked)
            return
        }
        // 列表没读到、用户直接说了个名字
        val name = cleanName(text)
        if (name.isNotEmpty()) askContent(name)
        else setReply(getString(R.string.float_ask_recipient))
    }

    /** 把「发给李思旭」剥成「李思旭」 */
    private fun cleanName(text: String): String {
        var name = text.trim()
        for (prefix in listOf("发给", "给", "和", "跟", "选", "第", "就")) name = name.removePrefix(prefix)
        name = name.trim().trim('，', ',', '。', '！', '!', ' ')
        name = name.removeSuffix("吧").removeSuffix("呀").removeSuffix("呗").trim()
        return if (name.length in 1..12) name else ""
    }

    private fun askContent(name: String) {
        to = name
        pendingTrace = null
        resetContent()
        // 列表收起来，只留确认卡的位置
        listBox?.visibility = View.GONE
        step = Step.ASKING
        setReply(getString(R.string.float_ask_content, name))
        beginListening()
    }

    private fun showConfirm() {
        listBox?.visibility = View.GONE
        confirmCard?.visibility = View.VISIBLE
        tvTo?.text = to
        tvContent?.text = content
        setReply(getString(R.string.float_confirm, to))
    }

    private fun runSend() {
        if (to.isBlank() || content.isBlank()) return
        step = Step.RUNNING
        confirmCard?.visibility = View.GONE
        setReply(getString(R.string.float_running, platform.label))
        val target = platform
        val who = to
        val body = content
        val trace = pendingTrace
        pendingTrace = null
        Thread {
            val outcome = runCatching {
                if (trace != null) automator.confirm(trace) else automator.sendNow(target, who, body)
            }.getOrNull()
            ui.post {
                step = Step.IDLE
                to = ""
                content = ""
                when (outcome) {
                    null -> setReply(getString(R.string.float_failed))
                    is ImAutomator.Outcome.Done -> setReply(outcome.message)
                    is ImAutomator.Outcome.Failed -> setReply(outcome.message)
                    is ImAutomator.Outcome.NeedConfirm -> setReply(outcome.preview)
                }
                ui.postDelayed({ if (step == Step.IDLE) collapse() }, DONE_COLLAPSE_MS)
            }
        }.start()
    }

    private fun setReply(text: String) {
        showPanel()
        tvReply?.text = text
    }

    private fun showPanel() {
        panel?.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------ 尺寸/样式

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun screenWidth(): Int =
        resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: 1080

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun round(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** 五个跳动的点：比静态图标更像「在听」 */
    private inner class DotsView(context: Context, bg: Int) : View(context) {
        var tint: Int = ACCENT
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }
        private val heights = floatArrayOf(0.45f, 0.8f, 1f, 0.8f, 0.45f)

        /**
         * 用一个 0→1 循环下去的值驱动相位。
         *
         * 用 [android.animation.ValueAnimator] 而不是 `ObjectAnimator.ofFloat(this, "phase", …)`：
         * 后者靠反射找 setter，属性名写错要到运行时才炸，Lint 也会报
         * `ObjectAnimatorBinding`。这里直接拿值改字段，没有反射这一步。
         */
        private var animator: ValueAnimator? = null
        private var phase = 0f

        override fun onDraw(canvas: Canvas) {
            val n = 5
            val gap = width.toFloat() / (n + 1)
            val r = height * 0.11f
            for (i in 0 until n) {
                val wave = if (animator == null) 1f else {
                    val t = (phase + i * 0.18f) % 1f
                    0.55f + 0.45f * kotlin.math.abs(1f - 2f * t)
                }
                val h = height * heights[i] * wave
                canvas.drawCircle(gap * (i + 1), height / 2f, r * (0.8f + 0.4f * h / height), paint)
            }
        }

        fun startPulse() {
            if (animator != null) return
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopPulse() {
            animator?.cancel()
            animator = null
            invalidate()
        }
    }

    companion object {
        /** 服务开着没有：界面/自动化据此决定发完要不要把自己拉回前台 */
        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        private var instance: FloatAssistant? = null

        const val ACTION_LISTEN = "com.example.voiceassistant.overlay.LISTEN"
        const val ACTION_STOP = "com.example.voiceassistant.overlay.STOP"

        private const val CHANNEL = "float_assistant"
        private const val NOTIFICATION_ID = 0x4A11
        private const val CHOICE_COUNT = 6
        private const val DONE_COLLAPSE_MS = 4_000L

        /** 让唤醒循环先放开麦克风、再开自己的识别（和主界面同一个节奏） */
        private const val MIC_HANDOVER_MS = 450L

        private val ACCENT = Color.rgb(0x4F, 0x7B, 0xFF)
        private val TEXT = Color.rgb(0x1A, 0x1A, 0x1A)
        private val HINT = Color.rgb(0x99, 0x99, 0x99)
        private val SEND_GREEN = Color.rgb(0x07, 0xC1, 0x60)

        /** 能不能画悬浮窗（系统设置里给过权限才行） */
        fun canDraw(context: Context): Boolean = android.provider.Settings.canDrawOverlays(context)

        fun start(context: Context, listen: Boolean = false) {
            val intent = Intent(context, FloatAssistant::class.java).apply {
                action = if (listen) ACTION_LISTEN else null
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatAssistant::class.java))
        }

        /** 唤醒词命中时用：已经在跑就直接开麦，没跑就拉起来再开麦 */
        fun listen(context: Context) {
            instance?.let {
                it.expand()
                it.beginListening()
                return
            }
            start(context, listen = true)
        }
    }
}
