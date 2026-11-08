package com.example.voiceassistant.im

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.example.voiceassistant.overlay.FloatAssistant
import java.util.concurrent.atomic.AtomicInteger

/** 能自动发消息的平台 */
enum class ImPlatform(val key: String, val packageName: String, val label: String) {

    WECHAT("wechat", "com.tencent.mm", "微信"),
    QQ("qq", "com.tencent.mobileqq", "QQ");

    /** 这个应用装没装 */
    fun installed(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** 系统分享面板能不能把文字交给它 */
    fun shareable(context: Context): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(packageName)
        }
        context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }.getOrDefault(false)

    companion object {
        /**
         * 平台别名：本地 NLU、大模型、界面传过来的写法都得认。
         * 以前只认 wechat/qq/wx，模型写「微信」「腾讯QQ」就直接被判成「不支持」。
         */
        fun of(raw: String?): ImPlatform? {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return WECHAT
            if (QQ_KEYS.any { key.contains(it) }) return QQ
            if (WECHAT_KEYS.any { key.contains(it) }) return WECHAT
            return null
        }

        private val QQ_KEYS = listOf("qq", "企鹅", "腾讯qq", "mobileqq")
        private val WECHAT_KEYS = listOf(
            "wechat", "weixin", "wx", "微信", "微訊", "mm", "tencent.mm"
        )
    }
}

/**
 * 发消息的自动化流程。
 *
 * ```
 * prepare()  只校验 + 记住内容，**不打开任何应用**            ⇢ NeedConfirm
 * confirm()  打开应用 → 搜人 → 进聊天 → 填字 → 点发送 → 校验 → 回本应用
 * ```
 *
 * ## 为什么「准备」不再提前打开微信
 * 以前是 prepare 就把微信打开、把人找好、字填进去，然后等用户确认。
 * 问题是：那时候用户已经**被踢到微信里**了，还得自己切回本应用说「确认」，
 * 确认完又切回微信看结果——用户说的「不自动」就是指这个来回。
 *
 * 现在反过来：先在本应用里问一句「要给妈妈发微信，内容是……确认吗？」，
 * 用户说「确认」之后才开始动手，一口气做完再自己跳回来。
 * 全程只需要说一句话，不需要点任何按钮。
 *
 * ## 二次确认没取消
 * 这是唯一会**动到别人**的操作，发错收不回来。所以默认仍要一句「确认」；
 * 设置里可以打开「发送前免确认」，那就是说完直接发——由用户显式选择。
 *
 * ## 幂等
 * 每次 prepare 生成一个 traceId，同一个 traceId 重复 confirm 只会生效一次。
 * [of] 返回进程级单例：Activity 重建也不会把待确认弄丢。
 *
 * ## 已知脆弱点
 * - 微信/QQ 的节点 id 随版本变，查找顺序是「viewId → 文字 → 无障碍描述 → 坐标」；
 * - 首次启动对方应用可能有开屏广告/更新弹窗，会卡在搜索那一步超时；
 * - **搜索框和聊天输入框长得一样**（都是 EditText），靠「搜索在屏幕上半、聊天输入在底部」
 *   加提示文字/id 区分；两者都判不出来时宁可报「没进到聊天页」，也不往输入框里写字；
 * - 自动发送失败时不会硬撑，会如实说失败原因（能分享就把内容带过去）。
 */
class ImAutomator(
    private val context: Context,
    private val driver: ImDriver = ImDriver()
) {

    /** 一次待确认的发送 */
    private data class Pending(
        val platform: ImPlatform,
        val to: String,
        val content: String,
        val at: Long,
        var sent: Boolean = false
    )

    sealed class Outcome {
        data class NeedConfirm(val traceId: String, val preview: String) : Outcome()
        data class Done(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** 「把界面铺好、字填进去」的结果 */
    private sealed class Filled {
        data class Ok(val matched: String, val input: AccessibilityNodeInfo?) : Filled()
        data class Error(val reason: String) : Filled()
    }

    /** 一次自动发送的结果 */
    private sealed class Sent {
        data class Ok(val matched: String) : Sent()
        data class Error(val reason: String) : Sent()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val counter = AtomicInteger(0)

    // ------------------------------------------------------------------ 能力检查

    /**
     * 为什么走不了全自动；能走返回 null。
     * 注意这里**不阻断发送**——它只决定用哪条通路。
     */
    private fun autoBlocked(): String? {
        if (driver.available()) return null
        return if (ImAccessibilityService.enabledInSystem(context)) {
            "无障碍权限是开着的，但服务还没连上（到系统设置里把「HardlySeek」关一下再打开）"
        } else {
            "还没开无障碍权限"
        }
    }

    /**
     * 给用户看的分步自检：装没装、全自动通不通、分享通不通。
     * 「为什么发不出去」这类问题，靠这句话就能定位，不用猜。
     */
    fun diagnose(platform: ImPlatform): String {
        if (!platform.installed(context)) return "手机里没装${platform.label}，先装一个吧。"
        val share = if (platform.shareable(context)) "能用" else "用不了"
        val blocked = autoBlocked()
        return if (blocked == null) {
            "${platform.label}已安装，无障碍已连上，可以全自动发送：我自己进去找人、填字、点发送，发完回到这里。系统分享备用通路$share。"
        } else {
            "${platform.label}已安装，$blocked。系统分享通路$share，可以把内容带过去由你手动发送。"
        }
    }

    /** 打开系统的无障碍设置页，让用户自己勾选 */
    fun openAccessibilitySettings(): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 第一步：准备（不打开应用）

    /**
     * 准备发送：只校验参数、把内容记下来，**不碰任何别的应用**。
     *
     * 这样用户确认时人还留在本应用里，不会出现「说完话被弹到微信、还得切回来点确认」。
     */
    @Synchronized
    fun prepare(platform: ImPlatform, to: String, content: String): Outcome {
        if (!platform.installed(context)) return Outcome.Failed("手机里没装${platform.label}。")
        if (to.isBlank()) return Outcome.Failed("要发给谁？")
        if (content.isBlank()) return Outcome.Failed("要发什么？")

        val traceId = "im-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        pending[traceId] = Pending(
            platform = platform,
            to = to,
            content = content,
            at = System.currentTimeMillis()
        )
        purgeExpired()

        val blocked = autoBlocked()
        val preview = if (blocked == null) {
            "要给「$to」发${platform.label}，内容是：$content。确认吗？确认后我自己进去发送。"
        } else {
            "要给「$to」发${platform.label}，内容是：$content。" +
                "$blocked，确认后我只能把内容带到${platform.label}，需要你点一下发送。确认吗？"
        }
        return Outcome.NeedConfirm(traceId, preview)
    }

    // ------------------------------------------------------------------ 第二步：确认后全自动执行

    /**
     * 执行。**会阻塞**（打开应用、等界面、点发送），必须在后台线程调用。
     *
     * 无障碍可用时全自动：进页面 → 找人 → 填字 → 点发送 → 校验 → 跳回本应用。
     * 不可用时退回系统分享（唯一需要人点一下的情况），并如实说明。
     */
    @Synchronized
    fun confirm(traceId: String): Outcome {
        val item = pending[traceId] ?: return Outcome.Failed("这次发送已经过期了，重新说一遍吧。")
        if (item.sent) return Outcome.Failed("这条已经发出去了，不重复发。")
        if (System.currentTimeMillis() - item.at > EXPIRE_MS) {
            pending.remove(traceId)
            return Outcome.Failed("等太久了，这条过期了，重新说一遍吧。")
        }

        val result = runSend(item.platform, item.to, item.content)
        item.sent = true
        pending.remove(traceId)
        return result
    }

    /**
     * 直接发，不问「确认吗」。
     *
     * 只给**悬浮助手**用：那边已经把「发给谁、发什么」摆在卡片上、用户亲手点了「发送」，
     * 确认动作已经发生过一次，再问一遍就变成两次确认。
     *
     * **会阻塞**，必须在后台线程调用。
     */
    fun sendNow(platform: ImPlatform, to: String, content: String): Outcome {
        if (!platform.installed(context)) return Outcome.Failed("手机里没装${platform.label}。")
        if (to.isBlank()) return Outcome.Failed("要发给谁？")
        if (content.isBlank()) return Outcome.Failed("要发什么？")
        return runSend(platform, to, content)
    }

    /** 真正的执行：全自动 → 失败降级分享 */
    private fun runSend(platform: ImPlatform, to: String, content: String): Outcome {
        val blocked = autoBlocked()
        if (blocked == null) {
            when (val sent = sendAutomatically(platform, to, content)) {
                is Sent.Ok -> {
                    // 发送完成后自己跳回来，用户不用手动切（悬浮窗模式下不抢焦点）
                    if (!FloatAssistant.isRunning) backToApp()
                    return Outcome.Done("已经发给「${sent.matched}」了。")
                }

                is Sent.Error -> {
                    // 全自动没成：能分享就把内容带过去，至少不让用户白说一遍
                    if (openShare(platform, content)) {
                        return Outcome.Done(
                            "自动发送没走通（${sent.reason}），已经把内容带到${platform.label}了，" +
                                "点一下发送就好。"
                        )
                    }
                    return Outcome.Failed("${sent.reason} 也打不开分享页，只能手动发了。")
                }
            }
        }

        return if (openShare(platform, content)) {
            Outcome.Done(
                "已经把内容带到${platform.label}了，选一下「$to」再点发送就好。" +
                    "（$blocked，开了权限我就能自己发。）"
            )
        } else {
            Outcome.Failed("$blocked，${platform.label}的分享页也打不开，只能手动发了。")
        }
    }

    // ------------------------------------------------------------------ 会话列表（悬浮助手用）

    /**
     * 打开应用并停在首页。
     *
     * 悬浮助手要列「最近聊过谁」，就必须先把对方应用拉到前台——
     * 好在这时候用户看到的正是他想发消息的那个应用，不是被硬拽走的。
     */
    fun openHome(platform: ImPlatform): Boolean {
        if (!platform.installed(context)) return false
        if (driver.currentPackage() == platform.packageName) {
            backToHome(platform)
        } else {
            if (!launch(platform)) return false
            if (!waitForApp(platform, LAUNCH_TIMEOUT_MS)) return false
            backToHome(platform)
        }
        return driver.currentPackage() == platform.packageName
    }

    /**
     * 读当前屏幕上的会话名（要求已经停在首页）。读不到返回空表。
     *
     * 判据全在 [ImContacts.pick] 里，是纯函数，单测钉着。
     */
    fun conversationsOnScreen(limit: Int = 6): List<String> {
        if (driver.currentPackage() == null) return emptyList()
        val searchBottom = searchEntry(0)?.let { driver.bounds(it)?.bottom } ?: 0
        return ImContacts.pick(
            items = driver.textItems(),
            screenWidth = driver.screenWidth(),
            screenHeight = driver.screenHeight(),
            searchBottom = searchBottom,
            limit = limit
        )
    }

    /** 放弃这次待确认（用户说「算了」） */
    fun discard(traceId: String): Boolean = pending.remove(traceId) != null

    /** 有没有待确认的消息；界面据此决定要不要拦住下一句话 */
    fun hasPending(): Boolean {
        purgeExpired()
        return pending.isNotEmpty()
    }

    fun latestTraceId(): String? {
        purgeExpired()
        return pending.maxByOrNull { it.value.at }?.key
    }

    // ------------------------------------------------------------------ 全自动通路

    /**
     * 真正把消息发出去。
     *
     * 只重试一次：第一次多半是「界面还没渲染完」，第二次还不行就说明
     * 对方界面结构和我们认识的版本差太多，再试只是让用户干等。
     */
    private fun sendAutomatically(platform: ImPlatform, to: String, content: String): Sent {
        var lastError = "界面没准备好"
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(700)

            val filled = openChatAndFill(platform, to, content)
            if (filled is Filled.Error) {
                lastError = filled.reason
                return@repeat
            }
            filled as Filled.Ok

            // 三条路，按可靠性从高到低；前一条没看到气泡就试下一条
            if (pressSend(filled.input) && awaitSent(content)) return Sent.Ok(filled.matched)
            lastError = "点了发送但没看到消息出去"
        }
        return Sent.Error(lastError)
    }

    /**
     * 点发送：按钮 → 键盘回车 → 按坐标点输入框右边。
     *
     * 之所以要三条：微信/QQ 的发送键在不同版本里分别是
     * 「有文字的按钮」「没文字的圆圈」「键盘上的回车键」，光认一种必翻车。
     * 每条都只**点一次**，点完由调用方看气泡有没有出来，不会重复发。
     */
    private fun pressSend(input: AccessibilityNodeInfo?): Boolean {
        val button = findSendButton(input)
        if (button != null && driver.click(button)) return true
        if (driver.imeEnter(input)) return true

        // 最后按坐标点：输入框右侧 30dp，同一行
        val rect = driver.bounds(input)
        if (rect != null) {
            val x = rect.right + 30f * density()
            val y = rect.exactCenterY()
            if (x < driver.screenWidth() - 4) return driver.tap(x, y)
        }
        return false
    }

    private fun density(): Float =
        context.resources.displayMetrics.density.coerceAtLeast(1f)

    /**
     * 打开 → 搜人 → 进聊天 → 填字。停在**发送前**。
     * 每一步失败都返回带步骤的说明，上层据此决定降级还是报错。
     *
     * ## 为什么「进聊天页」要单独确认
     * 之前点完联系人只睡 700ms 就去找输入框。结果对方应用偶尔没响应这次点击，
     * 页面还停在搜索页——搜索框本身就是个 EditText，于是**消息内容被填进了搜索栏**，
     * 表现就是「搜了个联系人，却在搜索框里搜消息内容」。
     *
     * 现在把「进没进聊天页」当成一道硬门槛：
     * - 搜索框在屏幕**上半**，聊天输入框在**底部**，用几何位置区分；
     * - 没进聊天页就重试点击（含 QQ 的资料卡要先点「发消息」），
     * - 三次都不行就如实报失败，**绝不把内容写进搜索框**。
     */
    private fun openChatAndFill(platform: ImPlatform, to: String, content: String): Filled {
        if (!launch(platform)) return Filled.Error("打不开${platform.label}")
        if (!waitForApp(platform, LAUNCH_TIMEOUT_MS)) {
            return Filled.Error("${platform.label}没打开，可能被系统拦了")
        }

        // 1) 先回到首页：搜索入口只在首页。用户上次可能停在某个聊天里
        backToHome(platform)

        // 2) 首页上直接点会话进聊天。
        //    比「搜索 → 等联想 → 点第一条」少两次跳页，也避开搜索框那个坑：
        //    命中和用户说的名字一模一样的会话行才走这条快路，否则老老实实搜。
        val homeBottom = searchEntry(0)?.let { driver.bounds(it)?.bottom } ?: 0
        if (homeBottom > 0) {
            val exact = driver.findCustom(0) { node ->
                !node.isEditable && node.isVisibleToUser &&
                    node.text?.toString()?.trim() == to &&
                    (driver.bounds(node)?.top ?: 0) >= homeBottom - 8
            }
            if (exact != null && enterChat(platform, to, exact) != null) {
                val chatInput = waitChatInput(1_200) ?: return Filled.Error("没进到聊天页")
                return fillContent(chatInput, to, content)
            }
        }

        val searchEntry = searchEntry(SEARCH_TIMEOUT_MS)
            ?: return Filled.Error("没找到${platform.label}的搜索入口")
        if (!driver.click(searchEntry)) return Filled.Error("搜索框点不开")

        // 3) 在搜索框里填联系人名字（只认上半屏的输入框）
        val searchBox = findSearchBox(3_000)
            ?: return Filled.Error("搜索框没弹出来")
        if (!driver.fillText(searchBox, to)) {
            driver.click(searchBox)
            Thread.sleep(300)
            if (!driver.fillText(searchBox, to)) {
                return Filled.Error("联系人名字填不进搜索框")
            }
        }
        Thread.sleep(1_100)

        // 4) 点开联系人：完全同名优先，且排除「搜索 xxx」这类网络搜索行
        val searchBottom = driver.bounds(searchBox)?.bottom ?: 0
        val contact = findContact(to, searchBottom)
            ?: return Filled.Error("搜索结果里没有叫「$to」的联系人")
        val matched = runCatching { contact.text?.toString()?.trim().orEmpty() }
            .getOrDefault("")
            .ifEmpty { to }

        // 5) 必须真的进到聊天页，才允许写字
        val inputBox = enterChat(platform, to, contact)
            ?: return Filled.Error("点了「$matched」，但一直没进到聊天页（还停在搜索页）")

        return fillContent(inputBox, matched, content)
    }

    /**
     * 把内容写进聊天输入框。
     *
     * `ACTION_SET_TEXT` 被自定义输入框忽略时（微信部分版本会这样），
     * 改走剪贴板粘贴——两条路都失败才报错，而不是直接掉到分享页。
     */
    private fun fillContent(inputBox: AccessibilityNodeInfo, matched: String, content: String): Filled {
        var filled = driver.fillText(inputBox, content)
        if (!filled) {
            driver.click(inputBox)
            Thread.sleep(350)
            filled = driver.fillText(inputBox, content)
        }
        if (!filled) return Filled.Error("字填不进去")

        Thread.sleep(250)
        val actual = runCatching { inputBox.text?.toString().orEmpty() }.getOrDefault("")
        if (actual.isNotBlank() && actual.trim() != content.trim()) {
            driver.fillText(inputBox, content)
            Thread.sleep(250)
        }
        return Filled.Ok(matched, inputBox)
    }

    // ------------------------------------------------------------------ 页面判定

    /**
     * 从搜索结果进入聊天页。返回聊天输入框；**进不去就返回 null，绝不硬填**。
     *
     * 分四步递进，每一步都以「真的看到聊天输入框」为准：
     * 1. 节点点击——大多数情况一次就进；
     * 2. 还在搜索页 → 重新定位条目、按条目中心派发触摸（节点点击偶发不响应）；
     * 3. 进了资料卡（QQ 常见）→ 点「发消息」；
     * 4. 还不行 → 退回搜索页重搜一遍再点，最后仍失败就报错。
     *
     * 老版本只有第 1 步、睡 700ms 就往下走，于是「没点进去」时
     * 搜索框被当成聊天输入框，消息内容就填进了搜索栏。
     */
    private fun enterChat(
        platform: ImPlatform,
        to: String,
        firstContact: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        driver.click(firstContact)
        waitChatInput(CHAT_ENTER_TIMEOUT_MS)?.let { return it }

        // 还在搜索页：条目可能没响应这次点击，改用坐标再点一次
        if (findSearchBox(0) != null) {
            val below = driver.bounds(findSearchBox(0))?.bottom ?: 0
            tapOrClick(findContact(to, below) ?: firstContact)
            waitChatInput(2_500)?.let { return it }
        }

        // QQ 点搜索结果先进资料卡，要再点「发消息」
        driver.find(byText = "发消息", timeoutMs = 700)?.let { button ->
            driver.click(button)
            waitChatInput(2_500)?.let { chat -> return chat }
        }

        // 跑到别的应用了就别再折腾
        if (driver.currentPackage() != platform.packageName) return null

        // 退回搜索页，重新搜一遍再点
        driver.back()
        Thread.sleep(500)
        val box = findSearchBox(1_500) ?: return null
        driver.setText(box, to)
        Thread.sleep(1_000)
        tapOrClick(findContact(to, driver.bounds(box)?.bottom ?: 0) ?: return null)
        return waitChatInput(2_500)
    }

    /** 优先按矩形中心派发触摸，拿不到矩形再退回节点点击 */
    private fun tapOrClick(node: AccessibilityNodeInfo) {
        val rect = driver.bounds(node)
        if (rect != null) driver.tap(rect.exactCenterX(), rect.exactCenterY())
        else driver.click(node)
    }

    /**
     * 找搜索结果里的联系人行。
     *
     * 三个必须排除的对象：
     * - **搜索框本身**——我们刚把联系人名字填进去，它里面的文字和联系人一模一样，
     *   而且常常排在节点树更前面，点它就等于「又点了搜索框」，人根本没进去；
     * - 「搜索 xxx」「网络查找」这类行，点下去是网页搜索；
     * - 搜索框上方的任何东西（标题栏等）。
     *
     * @param belowY 只在搜索框下边缘以下的节点里找
     */
    private fun findContact(to: String, belowY: Int = 0): AccessibilityNodeInfo? {
        fun usable(node: AccessibilityNodeInfo): Boolean {
            if (node.isEditable || !node.isVisibleToUser) return false
            if (!ImPageRules.usableContact(node.text?.toString())) return false
            if (belowY > 0) {
                val rect = driver.bounds(node) ?: return false
                if (rect.top < belowY - 8) return false
            }
            return true
        }

        driver.findCustom(timeoutMs = 2_500) { node ->
            usable(node) && node.text?.toString()?.trim() == to
        }?.let { return it }

        return driver.findCustom(timeoutMs = 1_200) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            usable(node) && text.contains(to) && text.length in to.length..(to.length + 8)
        }
    }

    /** 搜索框：可编辑 + （看着像搜索框 或 在屏幕上半部分） */
    private fun findSearchBox(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            driver.findAll { node -> driver.isEditable(node) && looksLikeSearchBox(node) }
                .firstOrNull()?.let { return it }
            if (timeoutMs <= 0) return null
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    /**
     * 聊天输入框：可编辑、不是搜索框、明确落在屏幕下半部分。
     *
     * 必须拿**正向证据**（在底部）才返回：只凭「搜索框不见了」就认，
     * 万一这一版的搜索框没被认出来，消息内容照样会被填进搜索栏。
     * 唯一的例外是量不到屏幕高度（极老或特殊 ROM），那时没有几何可用。
     */
    private fun waitChatInput(timeoutMs: Long): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            val boxes = driver.findAll { node ->
                driver.isEditable(node) && !looksLikeSearchBox(node)
            }
            boxes.firstOrNull { looksLikeChatInput(it) }?.let { return it }
            if (driver.screenHeight() <= 0) boxes.firstOrNull()?.let { return it }
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun looksLikeSearchBox(node: AccessibilityNodeInfo): Boolean = ImPageRules.isSearchBox(
        centerY = nodeCenterY(node),
        screenHeight = driver.screenHeight(),
        hint = hintOf(node),
        text = runCatching { node.text?.toString() }.getOrNull(),
        ids = viewIdsOf(node)
    )

    private fun looksLikeChatInput(node: AccessibilityNodeInfo): Boolean = ImPageRules.isChatInput(
        centerY = nodeCenterY(node),
        screenHeight = driver.screenHeight(),
        hint = hintOf(node),
        text = runCatching { node.text?.toString() }.getOrNull(),
        ids = viewIdsOf(node)
    )

    /** 节点中心 Y；量不到返回 -1 */
    private fun nodeCenterY(node: AccessibilityNodeInfo): Int =
        driver.bounds(node)?.centerY() ?: -1

    private fun hintOf(node: AccessibilityNodeInfo): String =
        runCatching { node.hintText?.toString().orEmpty() }.getOrDefault("")

    /** 自身加近几层祖先的资源 id（判断是不是搜索框用） */
    private fun viewIdsOf(node: AccessibilityNodeInfo): List<String> {
        val ids = ArrayList<String>(4)
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 4) {
            runCatching { cur.viewIdResourceName }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { ids += it }
            cur = runCatching { cur.parent }.getOrNull()
            depth++
        }
        return ids
    }

    /**
     * 发送成功的判据：聊天里出现了这句话，或者输入框被清空。
     *
     * 只点一下按钮不算发出去——微信在断网、被限制、或者节点点错时会静默失败，
     * 那时候回一句「已经发了」就是骗人。
     */
    private fun awaitSent(content: String): Boolean {
        val key = content.trim().take(KEY_CHARS)
        if (key.isNotEmpty()) {
            val bubble = driver.findCustom(SENT_TIMEOUT_MS) { node ->
                !node.isEditable &&
                    node.isVisibleToUser &&
                    node.text?.toString()?.contains(key) == true
            }
            if (bubble != null) return true
        }
        // 有些版本发送后气泡文字被折叠，退一步看输入框是否已经清空
        val box = driver.findEditable(0)
        return box != null && box.text.isNullOrBlank()
    }

    private fun findSendButton(input: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        driver.find(byText = "发送", timeoutMs = 1_200)?.let { return it }
        driver.find(byDescription = "发送", timeoutMs = 600)?.let { return it }
        driver.find(byText = "Send", timeoutMs = 400)?.let { return it }
        // 兜底：输入框右侧同一行的可点击节点（不少版本发送键是个没文字的圆圈）
        return driver.findRightOf(input, timeoutMs = 800)
    }

    /**
     * 退到首页。
     *
     * 判据是「有搜索入口 **且** 页面上没有输入框」——搜索结果页同样有「搜索」两个字，
     * 光看搜索入口会把搜索页当首页，接着就点了页面上已有的搜索文字，
     * 于是搜索框里填的、后面填的都是上一轮的残留内容。
     */
    private fun backToHome(platform: ImPlatform) {
        if (atHome()) return
        repeat(2) {
            if (driver.currentPackage() != platform.packageName) return
            driver.back()
            Thread.sleep(500)
            if (atHome()) return
        }
        if (driver.currentPackage() != platform.packageName) {
            launch(platform)
            waitForApp(platform, 4_000)
        }
    }

    /** 首页：找得到搜索入口，且没有输入框 */
    private fun atHome(): Boolean =
        searchEntry(0) != null && driver.findEditable(0) == null

    private fun searchEntry(timeoutMs: Long): AccessibilityNodeInfo? {
        driver.find(byDescription = "搜索", timeoutMs = timeoutMs)?.let { return it }
        driver.find(byText = "搜索", timeoutMs = if (timeoutMs > 0) timeoutMs / 2 else 0)
            ?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ 系统分享通路

    /**
     * 把文字交给微信 / QQ 的官方分享入口，联系人由用户自己选。
     *
     * 只在**没开无障碍**或自动发送失败时用。不需要任何权限，也不怕对方改版，
     * 代价是最后一步要人点。
     */
    private fun openShare(platform: ImPlatform, content: String): Boolean = runCatching {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            // 部分 QQ 版本读 subject，两个都塞一份，保险
            putExtra(Intent.EXTRA_SUBJECT, content)
        }
        val targeted = Intent(base).apply {
            setPackage(platform.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val intent = if (targeted.resolveActivity(context.packageManager) != null) {
            targeted
        } else {
            Intent.createChooser(base, "发送给").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ 内部

    /** 发完把自己拉回前台，用户不需要手动切回来 */
    private fun backToApp() {
        runCatching {
            Thread.sleep(350)
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            context.startActivity(intent)
        }
    }

    private fun launch(platform: ImPlatform): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(platform.packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    private fun waitForApp(platform: ImPlatform, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (driver.currentPackage() == platform.packageName) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.at > EXPIRE_MS }
    }

    companion object {
        /**
         * 进程级单例。待确认消息存在实例里，Activity 重建也不会把 traceId 弄丢。
         */
        @Volatile
        private var shared: ImAutomator? = null

        fun of(context: Context): ImAutomator = shared ?: synchronized(this) {
            shared ?: ImAutomator(context.applicationContext).also { shared = it }
        }

        const val EXPIRE_MS = 120_000L
        const val LAUNCH_TIMEOUT_MS = 6_000L
        const val SEARCH_TIMEOUT_MS = 2_500L

        /** 判断「消息发出去了」时比对前几个字就够，太长了气泡里会被截断 */
        const val KEY_CHARS = 8

        /** 等消息出现在聊天里的时间 */
        const val SENT_TIMEOUT_MS = 2_200L

        /** 点完联系人后，等聊天页出现的时间 */
        const val CHAT_ENTER_TIMEOUT_MS = 3_000L
    }
}
