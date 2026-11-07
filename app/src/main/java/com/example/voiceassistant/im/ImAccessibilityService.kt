package com.example.voiceassistant.im

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：**唯一能真正操作别的应用的那只手**。
 *
 * ## 它做的最小
 * 只把「读屏 + 点击 + 填字」三件事包成 [ImDriver]，业务判断全在 [ImAutomator] 里。
 * 这样以后要支持第三个 IM，只需要加一套查找规则，不用再碰权限和服务生命周期。
 *
 * ## 为什么不用 `onAccessibilityEvent` 驱动
 * 事件驱动听起来更优雅，但「点完搜索框等结果出来」这种时序，
 * 用事件接会很脆（ROM 不同，事件顺序和数量都不一样）。
 * 这里改成**主动轮询**：点完就 `waitFor` 目标节点，200ms 一次，超时就失败。
 * 逻辑直白，出问题也看得懂是哪一步没等到。
 *
 * ## 合规提示（写在代码里，别到上架才想起来）
 * 本服务用于「辅助语音操作」，用途必须在引导页和系统设置里如实说明。
 * 自动化操作微信/QQ 违反对方用户协议，可能被限制账号；
 * 所以调用方一律要求**用户确认后才点发送**，不做后台静默发送。
 */
class ImAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    companion object {
        /** 全局唯一实例；没连上时为 null */
        @Volatile
        var instance: ImAccessibilityService? = null
            private set

        /** 服务是否已连上（等价于「无障碍权限已开」） */
        @Volatile
        var connected: Boolean = false
            private set

        /**
         * 系统设置里到底有没有勾上我们。
         *
         * 和 [connected] 的区别很关键：用户明明开了权限、但服务还没被系统 bind 上时，
         * 光看 `connected` 会误报「还没开权限」——用户当然会觉得「我明明给了」。
         * 这里直接问系统的已启用列表，把两种情况分开说。
         */
        fun enabledInSystem(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    info.resolveInfo?.serviceInfo?.packageName == context.packageName
                }
        }.getOrDefault(false)
    }
}

/**
 * 读屏操作的薄封装。
 *
 * 所有方法都**不抛异常**：无障碍这套 API 失败是常态（节点失效、页面切走了），
 * 失败就返回 null/false，让上层决定重试还是放弃。
 */
class ImDriver {

    /**
     * 无障碍服务是否真的能读屏。
     *
     * 只认 `instance != null`：它是系统 bind 上服务时亲手设的，进程被杀后
     * 静态布尔会残留，但 instance 不会。至于当前窗口有没有节点，
     * 交给 [root] 去试，失败就返回 null，上层会降级到系统分享。
     */
    fun available(): Boolean = ImAccessibilityService.instance != null

    private fun root(): AccessibilityNodeInfo? = runCatching {
        ImAccessibilityService.instance?.rootInActiveWindow
    }.getOrNull()

    /** 当前前台窗口的包名（用来判断「现在到底在不在微信里」） */
    fun currentPackage(): String? = root()?.packageName?.toString()

    /**
     * 在整棵树里找节点。
     *
     * @param byViewId 按资源 id 精确找（最稳，但对方改版会失效）
     * @param byText 按显示文字找（「发送」这类按钮）
     * @param byDescription 按无障碍描述找（微信的搜索图标常年挂在 contentDescription 上）
     */
    fun find(
        byViewId: String? = null,
        byText: String? = null,
        byDescription: String? = null,
        timeoutMs: Long = 0
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            val node = findOnce(byViewId, byText, byDescription)
            if (node != null) return node
            if (timeoutMs <= 0) return null
            runCatching { Thread.sleep(STEP_MS) }
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    private fun findOnce(
        byViewId: String?,
        byText: String?,
        byDescription: String?
    ): AccessibilityNodeInfo? {
        val root = root() ?: return null

        if (byViewId != null) {
            // findAccessibilityNodeInfosByViewId 在部分 ROM 上会抛异常（没有 flagReportViewIds）
            runCatching { root.findAccessibilityNodeInfosByViewId(byViewId) }
                .getOrNull()?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        if (byText != null) {
            runCatching { root.findAccessibilityNodeInfosByText(byText) }
                .getOrNull()?.firstOrNull { node ->
                    node.isVisibleToUser && node.text?.toString()?.trim() == byText
                }?.let { return it }
            // 退一步：包含就算（「发送(1)」这种）
            runCatching { root.findAccessibilityNodeInfosByText(byText) }
                .getOrNull()?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        if (byDescription != null) {
            return walk(root).firstOrNull { node ->
                node.isVisibleToUser &&
                    node.contentDescription?.toString()?.contains(byDescription) == true
            }
        }
        return null
    }

    /** 找第一个可编辑的输入框（聊天页输入框的通用特征） */
    fun findEditable(timeoutMs: Long = 0): AccessibilityNodeInfo? =
        findCustom(timeoutMs) { node ->
            val editable = node.isEditable ||
                node.className?.toString()?.contains("EditText") == true
            editable && node.isVisibleToUser
        }

    /** 自定义条件查找（带重试） */
    fun findCustom(timeoutMs: Long, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            root()?.let { root ->
                walk(root).firstOrNull(predicate)?.let { return it }
            }
            if (timeoutMs <= 0) return null
            runCatching { Thread.sleep(STEP_MS) }
        } while (System.currentTimeMillis() < deadline)
        return null
    }

    /**
     * 点一下。
     *
     * 有些节点自己不可点（可点在父布局上），所以失败时沿着 parent 往上找一层；
     * 微信/QQ 的发送按钮、联系人条目经常整条链都 `isClickable=false`，
     * 这时最后一招是按节点在屏幕上的矩形派发一次点击手势。
     */
    fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        runCatching {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 3) {
                if (parent.isClickable &&
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) return true
                parent = parent.parent
                depth++
            }
        }
        return clickByBounds(node)
    }

    /** 兜底：按坐标派发一次轻点（需要 canPerformGestures=true） */
    private fun clickByBounds(node: AccessibilityNodeInfo): Boolean = runCatching {
        val service = ImAccessibilityService.instance ?: return false
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val path = android.graphics.Path().apply {
            moveTo(rect.exactCenterX(), rect.exactCenterY())
        }
        val stroke = android.accessibilityservice.GestureDescription
            .StrokeDescription(path, 0, 60L)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    /** 把文字直接写进输入框（比逐个字符点键盘可靠得多） */
    fun setText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        return runCatching {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /** 模拟返回键 */
    fun back(): Boolean = runCatching {
        ImAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }.getOrNull() == true

    /** 从根节点开始遍历整棵树 */
    private fun walk(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var guard = 0
        while (queue.isNotEmpty() && guard < MAX_NODES) {
            guard++
            val node = queue.removeFirst()
            result += node
            for (i in 0 until node.childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return result
    }

    private companion object {
        /** 轮询间隔：太短会把主线程之外的 CPU 拉满，太长用户要等 */
        const val STEP_MS = 200L

        /** 遍历上限：防止某些页面出现环状结构时死循环 */
        const val MAX_NODES = 2_000
    }
}
