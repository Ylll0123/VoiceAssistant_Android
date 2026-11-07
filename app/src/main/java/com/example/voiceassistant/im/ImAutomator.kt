package com.example.voiceassistant.im

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
 * prepare()  打开应用 → 搜索联系人 → 进聊天 → 填好文字  ⇢ 停在这里，返回 traceId
 * confirm()  点「发送」                                    ⇢ 用户说过「确认」才走这一步
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
 * ## 已知脆弱点（上线前请看）
 * - 微信/QQ 的节点 id 随版本变，所以查找顺序是「viewId → 文字 → 无障碍描述」，
 *   全都对不上就会失败并提示「界面变了」；
 * - 首次启动对方应用可能有开屏广告/更新弹窗，流程会卡在搜索那一步超时；
 * - 重名联系人目前**不会**自动选（找不到完全同名就失败），这是刻意的。
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

    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()
    private val counter = AtomicInteger(0)

    // ------------------------------------------------------------------ 能力检查

    /**
     * 能不能发；能发返回 null，不能发返回原因（这句话会直接给用户看）。
     */
    fun check(platform: ImPlatform): String? {
        if (!platform.installed(context)) return "手机里没装${platform.label}。"
        if (!ImAccessibilityService.connected) {
            return "还没开无障碍权限，我点不动${platform.label}。要不要现在去开一下？"
        }
        return null
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

        // 别让对方应用的开屏动画把我们耍了：先等一下再找入口
        if (!launch(platform)) {
            return Outcome.Failed("打不开${platform.label}。")
        }
        if (!waitForApp(platform, 6_000)) {
            return Outcome.Failed("${platform.label}没打开，可能被系统拦了。")
        }

        // 1) 找搜索入口
        val searchEntry = driver.find(byDescription = "搜索", timeoutMs = 3_000)
            ?: driver.find(byText = "搜索", timeoutMs = 1_500)
        if (searchEntry == null) {
            driver.back()
            return Outcome.Failed("没找到${platform.label}的搜索框，界面可能改版了。")
        }
        driver.click(searchEntry)

        // 2) 在搜索框里填联系人名字
        val searchBox = driver.findEditable(timeoutMs = 3_000)
        if (searchBox == null) {
            driver.back()
            return Outcome.Failed("搜索框没弹出来，先退回去了，可以再试一次。")
        }
        driver.setText(searchBox, to)
        Thread.sleep(900) // 等搜索结果刷新，这一步只能靠等

        // 3) 点开与联系人**完全同名**的那一条。重名不猜，直接失败
        val contact = driver.findCustom(timeoutMs = 4_000) { node ->
            val text = node.text?.toString()?.trim()
            node.isVisibleToUser && text == to
        }
        if (contact == null) {
            driver.back()
            return Outcome.Failed("搜索结果里没有叫「$to」的联系人。")
        }
        driver.click(contact)
        Thread.sleep(700)

        // 4) 找到聊天输入框并填字
        val input = driver.findEditable(timeoutMs = 4_000)
        if (input == null) {
            return Outcome.Failed("没进到聊天页，可能点到别的地方了。")
        }
        if (!driver.setText(input, content)) {
            return Outcome.Failed("字填不进去，输入框可能被限制了。")
        }

        // 走到这里：对方应用里已经填好、但**没有点发送**
        val traceId = "im-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
        pending[traceId] = Pending(platform, to, content, System.currentTimeMillis())
        purgeExpired()

        val preview = "要给「$to」发${platform.label}，内容是：$content。确认发送吗？"
        return Outcome.NeedConfirm(traceId, preview)
    }

    // ------------------------------------------------------------------ 第二步：确认后发送

    /**
     * 用户说「确认」之后才点发送。
     */
    @Synchronized
    fun confirm(traceId: String): Outcome {
        val item = pending[traceId] ?: return Outcome.Failed("这次发送已经过期了，重新说一遍吧。")
        if (item.sent) return Outcome.Failed("这条已经发出去了，不重复发。")
        if (System.currentTimeMillis() - item.at > EXPIRE_MS) {
            pending.remove(traceId)
            return Outcome.Failed("等太久了，这条过期了，重新说一遍吧。")
        }

        // 发送按钮：微信/QQ 都是文字「发送」
        val sendButton = driver.find(byText = "发送", timeoutMs = 2_000)
        if (sendButton == null) {
            return Outcome.Failed("没找到发送按钮，界面可能变了。内容还在输入框里，你可以手动发。")
        }
        if (!driver.click(sendButton)) {
            return Outcome.Failed("发送按钮点不动，内容还在输入框里。")
        }

        item.sent = true
        pending.remove(traceId)
        return Outcome.Done("已经发给${item.to}了。")
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
    }
}
