package com.example.voiceassistant.im

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
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

        val blocked = autoBlocked()
        if (blocked == null) {
            when (val sent = sendAutomatically(item.platform, item.to, item.content)) {
                is Sent.Ok -> {
                    item.sent = true
                    pending.remove(traceId)
                    // 发送完成后自己跳回来，用户不用手动切
                    backToApp()
                    return Outcome.Done("已经发给「${sent.matched}」了。")
                }

                is Sent.Error -> {
                    // 全自动没成：能分享就把内容带过去，至少不让用户白说一遍
                    if (openShare(item.platform, item.content)) {
                        item.sent = true
                        pending.remove(traceId)
                        return Outcome.Done(
                            "自动发送没走通（${sent.reason}），已经把内容带到${item.platform.label}了，" +
                                "点一下发送就好。"
                        )
                    }
                    return Outcome.Failed("${sent.reason} 也打不开分享页，只能手动发了。")
                }
            }
        }

        return if (openShare(item.platform, item.content)) {
            item.sent = true
            pending.remove(traceId)
            Outcome.Done(
                "已经把内容带到${item.platform.label}了，选一下「${item.to}」再点发送就好。" +
                    "（$blocked，开了权限我就能自己发。）"
            )
        } else {
            Outcome.Failed("$blocked，${item.platform.label}的分享页也打不开，只能手动发了。")
        }
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

            val button = findSendButton(filled.input)
            if (button == null) {
                lastError = "没找到发送按钮，界面可能改版了"
                return@repeat
            }
            if (!driver.click(button)) {
                lastError = "发送按钮点不动"
                return@repeat
            }
            if (awaitSent(content)) return Sent.Ok(filled.matched)
            lastError = "点了发送但没看到消息出去"
        }
        return Sent.Error(lastError)
    }

    /**
     * 打开 → 搜人 → 进聊天 → 填字。停在**发送前**。
     * 每一步失败都返回带步骤的说明，上层据此决定降级还是报错。
     */
    private fun openChatAndFill(platform: ImPlatform, to: String, content: String): Filled {
        if (!launch(platform)) return Filled.Error("打不开${platform.label}")
        if (!waitForApp(platform, LAUNCH_TIMEOUT_MS)) {
            return Filled.Error("${platform.label}没打开，可能被系统拦了")
        }

        // 1) 先回到首页：搜索入口只在首页。用户上次可能停在某个聊天里
        backToHome(platform)

        val searchEntry = searchEntry(SEARCH_TIMEOUT_MS)
            ?: return Filled.Error("没找到${platform.label}的搜索入口")
        if (!driver.click(searchEntry)) return Filled.Error("搜索框点不开")

        // 2) 在搜索框里填联系人名字
        val searchBox = driver.findEditable(timeoutMs = 3_000)
            ?: return Filled.Error("搜索框没弹出来")
        if (!driver.setText(searchBox, to)) {
            driver.click(searchBox)
            Thread.sleep(300)
            if (!driver.setText(searchBox, to)) {
                return Filled.Error("联系人名字填不进搜索框")
            }
        }
        Thread.sleep(1_100)

        // 3) 点开联系人：完全同名优先
        val exact = driver.findCustom(timeoutMs = 2_500) { node ->
            node.isVisibleToUser && node.text?.toString()?.trim() == to
        }
        val contact = exact ?: driver.findCustom(timeoutMs = 1_200) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            node.isVisibleToUser && text.contains(to) &&
                text.length in to.length..(to.length + 8)
        } ?: return Filled.Error("搜索结果里没有叫「$to」的联系人")

        val matched = runCatching { contact.text?.toString()?.trim().orEmpty() }
            .getOrDefault("")
            .ifEmpty { to }

        driver.click(contact)
        Thread.sleep(700)
        if (driver.currentPackage() != platform.packageName) {
            return Filled.Error("点了「$matched」之后跳出了${platform.label}")
        }

        // 4) 找到聊天输入框并填字
        val input = driver.findEditable(timeoutMs = 4_000)
            ?: return Filled.Error("没进到聊天页")

        var filled = driver.setText(input, content)
        if (!filled) {
            driver.click(input)
            Thread.sleep(350)
            filled = driver.setText(input, content)
        }
        if (!filled) return Filled.Error("字填不进去")

        Thread.sleep(250)
        val actual = runCatching { input.text?.toString().orEmpty() }.getOrDefault("")
        if (actual.isNotBlank() && actual.trim() != content.trim()) {
            driver.setText(input, content)
            Thread.sleep(250)
        }

        return Filled.Ok(matched, input)
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

    private fun backToHome(platform: ImPlatform) {
        if (searchEntry(0) != null) return
        repeat(2) {
            if (driver.currentPackage() != platform.packageName) return
            driver.back()
            Thread.sleep(450)
            if (searchEntry(0) != null) return
        }
        if (driver.currentPackage() != platform.packageName) {
            launch(platform)
            waitForApp(platform, 4_000)
        }
    }

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
    }
}
