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
 * prepare()  ① 无障碍可用：打开应用 → 搜人 → 进聊天 → 填好文字  ⇢ NeedConfirm
 *            ② 否则 / ① 失败：只记下内容，什么都不打开            ⇢ NeedConfirm（分享模式）
 * confirm()  ① 点「发送」
 *            ② 拉起系统分享面板，文字已预填，用户自己选人并点发送
 * ```
 *
 * ## 为什么加了分享这条降级路
 * 微信 / QQ 会限制无障碍自动化：节点不暴露、`ACTION_SET_TEXT` 失效、搜索入口改版，
 * 甚至明确拦截自动化操作。只押注无障碍，用户看到的就是「明明开了权限还是发不出去」。
 * 系统 `ACTION_SEND` 是两家都公开支持的入口，不受改版影响，也不需要任何权限。
 * 代价是联系人要用户自己选——但**发错人比发不出更严重**，这一步本来就该由人把关。
 *
 * ## 红线没变
 * `msg/send_text` 永远不点发送。分享模式下连应用都不打开，
 * 用户说过「确认」才把内容带过去，最后那一下发送始终是人自己点的。
 *
 * ## 幂等
 * 每次 prepare 生成一个 traceId，同一个 traceId 重复 confirm 只会生效一次。
 * [of] 返回进程级单例：切去微信/分享面板再回来，Activity 重建也不会把待确认弄丢。
 *
 * ## 已知脆弱点（全自动那条路）
 * - 微信/QQ 的节点 id 随版本变，查找顺序是「viewId → 文字 → 无障碍描述」；
 * - 首次启动对方应用可能有开屏广告/更新弹窗，会卡在搜索那一步超时；
 * - 全自动失败不报错，直接降级到分享，并把原因写进确认语里。
 */
class ImAutomator(
    private val context: Context,
    private val driver: ImDriver = ImDriver()
) {

    /** 一次待确认的发送 */
    private data class Pending(
        val platform: ImPlatform,
        val to: String,
        val matched: String,
        val content: String,
        val mode: Mode,
        val at: Long,
        var sent: Boolean = false
    )

    private enum class Mode { AUTO, SHARE }

    sealed class Outcome {
        data class NeedConfirm(val traceId: String, val preview: String) : Outcome()
        data class Done(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** 「把界面铺好、字填进去」的结果 */
    private sealed class Filled {
        data class Ok(val matched: String) : Filled()
        data class Error(val reason: String) : Filled()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val counter = AtomicInteger(0)

    // ------------------------------------------------------------------ 能力检查

    /**
     * 为什么不能走全自动；能走返回 null。
     * 注意这里**不再阻断发送**——它只决定用哪条通路，而不是能不能发。
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
            "${platform.label}已安装，无障碍已连上，可以全自动发送；系统分享备用通路$share。"
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

    // ------------------------------------------------------------------ 第一步：准备（不发送）

    /**
     * 准备发送。全自动那条路会阻塞（要等界面），必须在后台线程调用。
     * 失败一律降级到分享，不把用户晾在「发不出去」上。
     */
    @Synchronized
    fun prepare(platform: ImPlatform, to: String, content: String): Outcome {
        if (!platform.installed(context)) return Outcome.Failed("手机里没装${platform.label}。")
        if (to.isBlank()) return Outcome.Failed("要发给谁？")
        if (content.isBlank()) return Outcome.Failed("要发什么？")

        val blocked = autoBlocked()
        val filled = if (blocked == null) fillScreen(platform, to, content) else null
        val auto = filled as? Filled.Ok
        val mode = if (auto != null) Mode.AUTO else Mode.SHARE

        // 全自动失败的原因要如实说出来，别让用户以为是权限没开
        val why = when {
            blocked != null -> blocked
            filled is Filled.Error -> "自动填字没走通（${filled.reason}）"
            else -> ""
        }

        val traceId = "im-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        pending[traceId] = Pending(
            platform = platform,
            to = to,
            matched = auto?.matched ?: to,
            content = content,
            mode = mode,
            at = System.currentTimeMillis()
        )
        purgeExpired()

        val preview = if (mode == Mode.AUTO) {
            "要给「${auto!!.matched}」发${platform.label}，内容是：$content。确认发送吗？"
        } else {
            "要给「$to」发${platform.label}，内容是：$content。" +
                if (why.isEmpty()) "我会把内容带到${platform.label}，你选一下联系人再点发送。确认吗？"
                else "$why，我会把内容带到${platform.label}，你选一下联系人再点发送。确认吗？"
        }
        return Outcome.NeedConfirm(traceId, preview)
    }

    // ------------------------------------------------------------------ 第二步：确认后执行

    @Synchronized
    fun confirm(traceId: String): Outcome {
        val item = pending[traceId] ?: return Outcome.Failed("这次发送已经过期了，重新说一遍吧。")
        if (item.sent) return Outcome.Failed("这条已经发出去了，不重复发。")
        if (System.currentTimeMillis() - item.at > EXPIRE_MS) {
            pending.remove(traceId)
            return Outcome.Failed("等太久了，这条过期了，重新说一遍吧。")
        }

        if (item.mode == Mode.SHARE) {
            return if (openShare(item.platform, item.content)) {
                item.sent = true
                pending.remove(traceId)
                Outcome.Done(
                    "已经把内容带到${item.platform.label}了，选一下「${item.to}」再点发送就好。"
                )
            } else {
                Outcome.Failed("打不开${item.platform.label}的分享页，可能版本不支持，得手动发了。")
            }
        }

        var sendButton = findSendButton()

        // 找不到发送按钮，多半是人跑到别处去了：重来一遍再点
        if (sendButton == null) {
            when (val again = fillScreen(item.platform, item.matched, item.content)) {
                is Filled.Error -> {
                    // 重铺也失败就别硬撑了，改走分享
                    return if (openShare(item.platform, item.content)) {
                        item.sent = true
                        pending.remove(traceId)
                        Outcome.Done("自动填字没走通，已经把内容带到${item.platform.label}了，选一下联系人再点发送就好。")
                    } else Outcome.Failed("${again.reason} 也打不开分享页，只能手动发了。")
                }
                is Filled.Ok -> sendButton = findSendButton()
            }
        }

        if (sendButton == null) {
            return Outcome.Failed("没找到发送按钮，界面可能变了。内容还在输入框里，你可以手动发。")
        }
        if (!driver.click(sendButton)) {
            return Outcome.Failed("发送按钮点不动，内容还在输入框里。")
        }

        item.sent = true
        pending.remove(traceId)
        return Outcome.Done("已经发给${item.matched}了。")
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

    // ------------------------------------------------------------------ 系统分享通路

    /**
     * 把文字交给微信 / QQ 的官方分享入口，联系人由用户自己选。
     *
     * 优先定向 `setPackage`，避免弹出一堆不相关的应用；
     * 万一对方这版没注册 `text/plain`，退回系统选择器，让用户挑一个能收的。
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

    // ------------------------------------------------------------------ 全自动通路

    /**
     * 把「打开 → 搜人 → 进聊天 → 填字」整条走完，停在**发送前**。
     * 每一步失败都返回带步骤的说明，上层据此降级到分享。
     */
    private fun fillScreen(platform: ImPlatform, to: String, content: String): Filled {
        if (!launch(platform)) return Filled.Error("打不开${platform.label}。")
        if (!waitForApp(platform, LAUNCH_TIMEOUT_MS)) {
            return Filled.Error("${platform.label}没打开，可能被系统拦了。")
        }

        // 1) 先回到首页：搜索入口只在首页。用户上次可能停在某个聊天里
        backToHome(platform)

        val searchEntry = searchEntry(SEARCH_TIMEOUT_MS)
            ?: return Filled.Error("没找到${platform.label}的搜索入口，界面可能改版了。")
        if (!driver.click(searchEntry)) return Filled.Error("搜索框点不开。")

        // 2) 在搜索框里填联系人名字
        val searchBox = driver.findEditable(timeoutMs = 3_000)
            ?: return Filled.Error("搜索框没弹出来，先退回去了，可以再试一次。")
        if (!driver.setText(searchBox, to)) {
            driver.click(searchBox)
            Thread.sleep(300)
            if (!driver.setText(searchBox, to)) {
                return Filled.Error("联系人名字填不进搜索框。")
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
        } ?: return Filled.Error("搜索结果里没有叫「$to」的联系人。")

        val matched = runCatching { contact.text?.toString()?.trim().orEmpty() }
            .getOrDefault("")
            .ifEmpty { to }

        driver.click(contact)
        Thread.sleep(700)
        if (driver.currentPackage() != platform.packageName) {
            return Filled.Error("点了「$matched」之后跳出了${platform.label}，可能点到别的地方了。")
        }

        // 4) 找到聊天输入框并填字
        val input = driver.findEditable(timeoutMs = 4_000)
            ?: return Filled.Error("没进到聊天页，可能点到别的地方了。")

        var filled = driver.setText(input, content)
        if (!filled) {
            driver.click(input)
            Thread.sleep(350)
            filled = driver.setText(input, content)
        }
        if (!filled) return Filled.Error("字填不进去，输入框可能被限制了。")

        Thread.sleep(250)
        val actual = runCatching { input.text?.toString().orEmpty() }.getOrDefault("")
        if (actual.isNotBlank() && actual.trim() != content.trim()) {
            driver.setText(input, content)
            Thread.sleep(250)
        }

        return Filled.Ok(matched)
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

    private fun findSendButton(): AccessibilityNodeInfo? {
        driver.find(byText = "发送", timeoutMs = 2_500)?.let { return it }
        driver.find(byText = "Send", timeoutMs = 600)?.let { return it }
        return driver.findCustom(timeoutMs = 800) { node ->
            val label = node.text?.toString()?.trim()
            node.isVisibleToUser && node.isClickable && label != null && label in SEND_TEXTS
        }
    }

    // ------------------------------------------------------------------ 内部

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
         * 进程级单例。待确认消息存在实例里，切到微信 / 分享面板再回来时
         * Activity 可能被重建，new 一个就会把 traceId 弄丢，用户说「确认」会白说。
         */
        @Volatile
        private var shared: ImAutomator? = null

        fun of(context: Context): ImAutomator = shared ?: synchronized(this) {
            shared ?: ImAutomator(context.applicationContext).also { shared = it }
        }

        const val EXPIRE_MS = 120_000L
        const val LAUNCH_TIMEOUT_MS = 6_000L
        const val SEARCH_TIMEOUT_MS = 2_500L
        val SEND_TEXTS = setOf("发送", "Send")
    }
}
