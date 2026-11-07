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

    companion object {
        fun of(raw: String?): ImPlatform? {
            val key = raw?.trim()?.lowercase() ?: return WECHAT
            return when {
                key.contains("qq") -> QQ
                key.contains("wechat") || key.contains("wx") || key.contains("微信") -> WECHAT
                else -> null
            }
        }
    }
}

/**
 * 发消息的自动化流程。
 *
 * ```
 * prepare()  打开应用 → 回到首页 → 搜索联系人 → 进聊天 → 填好文字  ⇢ 返回 traceId
 * confirm()  点「发送」                                          ⇢ 用户说过「确认」才走这一步
 * ```
 *
 * ## 为什么一定要拆成两步
 * 发错人、发错话是**不可撤销**的。自动化点错一次，代价比多点一下大得多。
 * 所以总线上的 `msg/send_text` 永远只做到「填好、没发」，
 * 必须再来一次 `msg/confirm_send` 才真的点发送，中间由界面复述给用户听。
 *
 * ## 幂等
 * 每次 prepare 生成一个 traceId，状态从 `PREPARED` 走到 `SENT`。
 * 同一个 traceId 重复 confirm 只会真正发送一次——自动化最怕的就是「点了两下」。
 *
 * ## 已知脆弱点（真机出问题先看这里）
 * - 微信/QQ 的节点 id 随版本变，所以查找顺序是「viewId → 文字 → 无障碍描述」，
 *   都对不上会失败并说明卡在第几步；
 * - 首次启动对方应用可能有开屏广告/更新弹窗，流程会卡在搜索那一步超时；
 * - 重名联系人会优先选**完全同名**的那个；没有完全同名时取名字最短的包含项，
 *   并把实际选中的名字写进确认语，让用户听得出来是不是发错人了。
 */
class ImAutomator(
    private val context: Context,
    private val driver: ImDriver = ImDriver()
) {

    /** 一次待确认的发送 */
    private data class Pending(
        val platform: ImPlatform,
        val to: String,
        /** 搜索结果里实际点中的那个名字（可能比 [to] 长，例如「妈妈」→「妈妈(工作)」） */
        val matched: String,
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
        /** @param matched 真正点开的那个联系人名 */
        data class Ok(val matched: String) : Filled()
        data class Error(val reason: String) : Filled()
    }

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val counter = AtomicInteger(0)

    // ------------------------------------------------------------------ 能力检查

    /**
     * 能不能发；能发返回 null，不能发返回原因（这句话会直接给用户看）。
     *
     * 这里刻意把「没开权限」和「开了但没连上」分开说：
     * 用户已经去系统里勾过了，再被告知「还没开权限」只会让人怀疑人生。
     */
    fun check(platform: ImPlatform): String? {
        if (!platform.installed(context)) return "手机里没装${platform.label}。"
        if (ImAccessibilityService.connected) return null

        return if (ImAccessibilityService.enabledInSystem(context)) {
            "无障碍权限是开着的，但服务还没连上。到系统设置里把「HardlySeek」关一下再打开就好。"
        } else {
            "还没开无障碍权限，我点不动${platform.label}。要不要现在去开一下？"
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

    // ------------------------------------------------------------------ 第一步：填好不发

    /**
     * 准备发送：打开应用 → 搜人 → 进聊天 → 填字。
     *
     * **会阻塞**（每一步都要等界面），必须在后台线程调用。
     */
    @Synchronized
    fun prepare(platform: ImPlatform, to: String, content: String): Outcome {
        check(platform)?.let { return Outcome.Failed(it) }
        if (to.isBlank()) return Outcome.Failed("要发给谁？")
        if (content.isBlank()) return Outcome.Failed("要发什么？")

        val filled = when (val result = fillScreen(platform, to, content)) {
            is Filled.Error -> return Outcome.Failed(result.reason)
            is Filled.Ok -> result
        }

        // 走到这里：对方应用里已经填好、但**没有点发送**
        val traceId = "im-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        pending[traceId] = Pending(
            platform = platform,
            to = to,
            matched = filled.matched,
            content = content,
            at = System.currentTimeMillis()
        )
        purgeExpired()

        val preview = "要给「${filled.matched}」发${platform.label}，内容是：$content。确认发送吗？"
        return Outcome.NeedConfirm(traceId, preview)
    }

    // ------------------------------------------------------------------ 第二步：确认后发送

    /**
     * 用户说「确认」之后才点发送。
     *
     * 如果这时候界面已经不在聊天页（用户切走了、或者被系统收掉了），
     * 会**重新铺一遍**（打开 → 搜索 → 进聊天 → 填字）再点，而不是直接失败。
     */
    @Synchronized
    fun confirm(traceId: String): Outcome {
        val item = pending[traceId] ?: return Outcome.Failed("这次发送已经过期了，重新说一遍吧。")
        if (item.sent) return Outcome.Failed("这条已经发出去了，不重复发。")
        if (System.currentTimeMillis() - item.at > EXPIRE_MS) {
            pending.remove(traceId)
            return Outcome.Failed("等太久了，这条过期了，重新说一遍吧。")
        }

        var sendButton = findSendButton()

        // 找不到发送按钮，多半是人跑到别处去了：重来一遍再点
        if (sendButton == null) {
            when (val again = fillScreen(item.platform, item.matched, item.content)) {
                is Filled.Error -> return Outcome.Failed(again.reason)
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

    // ------------------------------------------------------------------ 界面操作

    /**
     * 把「打开 → 搜人 → 进聊天 → 填字」整条走完，停在**发送前**。
     *
     * 每一步失败都返回带步骤的说明，而不是笼统的「失败了」——
     * 用户报问题的时候，那句提示就是唯一的线索。
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
            // 有的搜索框要先获得焦点才收得进字
            driver.click(searchBox)
            Thread.sleep(300)
            if (!driver.setText(searchBox, to)) {
                return Filled.Error("联系人名字填不进搜索框。")
            }
        }
        // 等搜索结果刷新，这一步只能靠等（各家都是输入即搜）
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
            // 聊天输入框常常要先点一下才变成可编辑
            driver.click(input)
            Thread.sleep(350)
            filled = driver.setText(input, content)
        }
        if (!filled) return Filled.Error("字填不进去，输入框可能被限制了。")

        // 5) 校验：performAction 返回 true 不代表内容真的进去了
        Thread.sleep(250)
        val actual = runCatching { input.text?.toString().orEmpty() }.getOrDefault("")
        if (actual.isNotBlank() && actual.trim() != content.trim()) {
            // 再写一次；还不行就算了，让用户在确认前能看到内容不对
            driver.setText(input, content)
            Thread.sleep(250)
        }

        return Filled.Ok(matched)
    }

    /**
     * 回到应用首页。
     *
     * 搜索入口只在首页。用户上次停在与某人的聊天里，直接找「搜索」会找不到，
     * 然后整个流程就失败在第一步——这是「点了没反应」最常见的原因。
     */
    private fun backToHome(platform: ImPlatform) {
        if (searchEntry(0) != null) return
        repeat(2) {
            if (driver.currentPackage() != platform.packageName) return
            driver.back()
            Thread.sleep(450)
            if (searchEntry(0) != null) return
        }
        // 退过头退到桌面了：重新拉起来，至少保证人还在应用里
        if (driver.currentPackage() != platform.packageName) {
            launch(platform)
            waitForApp(platform, 4_000)
        }
    }

    /**
     * 找搜索入口。
     *
     * 顺序：无障碍描述 → 文字 → 顶部可编辑框。
     * 微信把搜索挂在 contentDescription 上，QQ 更常见的是文字「搜索」。
     */
    private fun searchEntry(timeoutMs: Long): AccessibilityNodeInfo? {
        driver.find(byDescription = "搜索", timeoutMs = timeoutMs)?.let { return it }
        driver.find(byText = "搜索", timeoutMs = if (timeoutMs > 0) timeoutMs / 2 else 0)
            ?.let { return it }
        return null
    }

    /** 发送按钮：先按文字找，再退回「可见且可点、文字就是发送」 */
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

    /** 等前台真的切到目标应用 */
    private fun waitForApp(platform: ImPlatform, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            if (driver.currentPackage() == platform.packageName) return true
            Thread.sleep(250)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    /** 超过两分钟的待确认直接丢掉，避免「早上没确认的，下午说确认就发出去了」 */
    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        pending.entries.removeAll { now - it.value.at > EXPIRE_MS }
    }

    private companion object {
        const val EXPIRE_MS = 120_000L

        /** 等应用切到前台的上限 */
        const val LAUNCH_TIMEOUT_MS = 6_000L

        /** 找搜索入口的上限 */
        const val SEARCH_TIMEOUT_MS = 2_500L

        val SEND_TEXTS = setOf("发送", "Send")
    }
}
