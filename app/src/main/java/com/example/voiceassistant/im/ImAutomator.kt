package com.example.voiceassistant.im

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

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
         *
         * **空值返回 null，不返回微信**：以前这里默认 WECHAT，结果「用户没说平台」
         * 和「用户说了微信」变成同一件事，常用 QQ 的人每次都被带到微信。
         * 现在「没说」交给调用方决定（`AppSettings.lastImPlatform` 记着上次用的那个）。
         */
        fun of(raw: String?): ImPlatform? {
            val key = raw?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return null
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
 * 「把消息送到聊天框」的自动化流程。
 *
 * ```
 * deliver()  打开应用 → 回首页 → 找人 → 进聊天页 → 填字 → **停在这里**
 * ```
 *
 * ## 为什么不再自动点发送
 * 自动提交有封号风险：微信/QQ 的用户协议明确禁止自动化操作，而且它们是靠
 * 行为特征判定的——从进页面到点发送只隔几百毫秒，正常人做不到。
 * 一旦被判，损失的是账号，不是一个功能。
 *
 * 换成「填好、等你点」之后：
 * - 风险几乎为零：最后一个动作是**人**做的，和复制粘贴没区别；
 * - 体验几乎一样：不用自己找人、不用打字，抬手点一下就行；
 * - 还多了一层保险：发之前能看见内容，说错了当场改。
 *
 * ## 填不进去的时候
 * 无障碍没开、或对方界面改版到认不出来时，退回系统 `ACTION_SEND`：
 * 内容预填好，选联系人后点发送。同样不替用户提交。
 *
 * ## 已知脆弱点
 * - 微信/QQ 的节点 id 随版本变，查找顺序是「viewId → 文字 → 无障碍描述 → 坐标」；
 * - 首次启动对方应用可能有开屏广告/更新弹窗，会卡在搜索那一步超时；
 * - **搜索框和聊天输入框长得一样**（都是 EditText），靠「搜索在屏幕上半、聊天输入在底部」
 *   加提示文字/id 区分；两者都判不出来时宁可报「没进到聊天页」，也不往输入框里写字。
 */
class ImAutomator(
    private val context: Context,
    private val driver: ImDriver = ImDriver(),
    /**
     * 把内容放进剪贴板。
     *
     * 这是**唯一一定成功**的通道：不管无障碍在不在、系统让不让跳转，
     * 文字都能先到用户手边。分享页没起来时，回话就靠它兜住。
     */
    private val clipboard: (String) -> Boolean = { text ->
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager ?: return@runCatching false
            manager.setPrimaryClip(android.content.ClipData.newPlainText("hardlyseek", text))
            true
        }.getOrDefault(false)
    },
    /**
     * 我们自己的界面还在不在前台。
     *
     * 没有读屏能力时，这是**唯一能反证「分享页真的起来了」**的信号：
     * 分享页是另一个 Activity，它一出现我们就会 onPause。
     * 由界面在启动时注入（[bindUiProbe]），因为这里拿不到 Activity 的状态。
     */
    private var uiForeground: () -> Boolean = { false }
) {

    /** 界面启动时把「我还在前台吗」这个探针接进来 */
    fun bindUiProbe(probe: () -> Boolean) {
        uiForeground = probe
    }

    /**
     * 一次发送的结果。
     *
     * 刻意不带「要不要去开权限」这种字段：无障碍是内部实现细节，
     * 用户既没有入口也管不了它，把这件事抛给界面只会变成一句让人困惑的提示。
     */
    sealed class Outcome {
        data class Done(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /**
     * 「把界面铺好、字填进去」的结果。
     *
     * [verified] 指**真的从输入框里读回了这段文字**。以前只要能调用成功就算填好了，
     * 遇到读不到内容的控件就会「其实没填上，却回一句已填好」——用户看到的是
     * 「说已经发了，实际什么都没发生」。现在读不回来就如实说读不到。
     */
    private sealed class Filled {
        data class Ok(
            val matched: String,
            val input: AccessibilityNodeInfo?,
            val verified: Boolean = true
        ) : Filled()

        data class Error(val reason: String, val kind: Fail = Fail.PAGE) : Filled()
    }

    /**
     * 失败发生在哪一层。
     *
     * 这一层决定「还能不能降级到系统分享」：
     * - 找不到人：分享也没用，而且很容易发错人，只能如实说没找到；
     * - 界面没走通 / 字没填进去：内容还有价值，可以丢进分享页让用户自己选人。
     */
    private enum class Fail { CONTACT, PAGE, FILL }

    /**
     * 并发控制：**后来的请求赢**。
     *
     * 用户连着说两遍「发消息给老王」，第一遍可能正卡在等微信启动。
     * 以前两条会在同一个线程池里排队，第二遍要等第一遍十几秒——用户看到的就是「卡住」。
     * 现在后一条把 `generation` 加一，正在跑的那条在下一个检查点自己退出，
     * 锁立刻让出来，新的一条按当时的真实界面重新走一遍。
     */
    private val lock = Any()

    @Volatile
    private var generation = 0

    @Volatile
    private var activeGen = 0

    /** 这一次的总预算；超了就当作失败，不再无限等 */
    @Volatile
    private var budgetEnd = 0L

    private fun aborted(): Boolean =
        activeGen != generation || System.currentTimeMillis() > budgetEnd

    // ------------------------------------------------------------------ 能力检查

    /**
     * 为什么走不了自动填字；能走返回 null。
     * 注意这里**不阻断**，它只决定用哪条通路。
     */
    private fun autoBlocked(): String? {
        if (driver.available()) return null
        return if (ImAccessibilityService.enabledInSystem(context)) {
            "读屏服务还没连上（系统设置里关掉再打开一次就好）"
        } else {
            "这台机器没有开启读屏能力"
        }
    }

    /**
     * 给用户看的分步自检：装没装、能不能自己填字、内容送到哪儿。
     *
     * 措辞里不出现「无障碍」「权限」这些词——用户改不了，也不该被要求去改。
     */
    fun diagnose(platform: ImPlatform): String {
        if (!platform.installed(context)) return "手机里没装${platform.label}，先装一个吧。"
        val auto = autoBlocked()
        return if (auto == null) {
            "${platform.label}已安装：我能自己进聊天页、找到人、把话填进输入框，发送键留给你点。" +
                "内容也会同步复制到剪贴板，万一没送过去可以直接粘贴。"
        } else {
            "${platform.label}已安装：这次会把内容带进${platform.label}的分享页，你选人发送；" +
                "同时内容已经复制到剪贴板，没跳过去也能粘贴。"
        }
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 把消息送到聊天输入框。**会阻塞**（打开应用、等界面、填字），必须在后台线程调用。
     *
     * 成功时用户人就停在那个聊天页上，输入框里是待发送的内容，抬手点一下发送即可。
     */
    fun deliver(platform: ImPlatform, to: String, content: String): Outcome {
        if (!platform.installed(context)) return Outcome.Failed("手机里没装${platform.label}。")
        if (to.isBlank()) return Outcome.Failed("要发给谁？")
        if (content.isBlank()) return Outcome.Failed("要发什么？")

        // 先占住「最新」的位置：正在跑的上一条会尽快退出
        val mine = ++generation
        return synchronized(lock) {
            if (mine != generation) {
                return@synchronized Outcome.Failed("已经换成你新说的那条了。")
            }
            activeGen = mine
            budgetEnd = System.currentTimeMillis() + BUDGET_MS
            runCatching { deliverInner(platform, to, content) }
                .getOrElse { error ->
                    Log.w(TAG, "deliver 异常: ${error.message}")
                    Outcome.Failed("这次没做成：${error.javaClass.simpleName}")
                }
        }
    }

    private fun deliverInner(platform: ImPlatform, to: String, content: String): Outcome {
        // 内容先落剪贴板：无论后面走哪条路，用户手上都有一份
        val copied = runCatching { clipboard(content) }.getOrDefault(false)

        val blocked = autoBlocked()
        if (blocked != null) {
            Log.i(TAG, "deliver: 自动填字不可用（$blocked），改走系统分享")
            val share = openShare(platform, content)
            return if (share.verified) {
                Outcome.Done(
                    "已经把内容带到${platform.label}的分享页了，选一下「$to」再点发送就好。"
                )
            } else if (copied) {
                // 跳没跳过去无法确认：不装作成功了，但内容确实到用户手上了
                Outcome.Done(
                    "内容已经复制到剪贴板了。打开${platform.label}，进「$to」的聊天，长按输入框粘贴就能发。"
                )
            } else {
                Outcome.Failed("没能把内容送进${platform.label}，你再说一次试试。")
            }
        }

        val filled = openChatAndFill(platform, to, content)
        Log.i(
            TAG,
            "deliver: " + when (filled) {
                is Filled.Ok -> "填好了 matched=${filled.matched} verified=${filled.verified}"
                is Filled.Error -> "失败(${filled.kind}) ${filled.reason}"
            }
        )
        return when (filled) {
            is Filled.Ok -> if (filled.verified) {
                Outcome.Done(
                    "已经在${platform.label}里给「${filled.matched}」填好了，点一下发送就行。"
                )
            } else {
                // 读不到输入框内容：不能替用户拍板说填好了，更不能说发出去了
                Outcome.Done(
                    "已经打开${platform.label}里「${filled.matched}」的聊天页，也把内容填进去了，" +
                        "但读不到输入框里的字，你低头看一眼再点发送（内容也在剪贴板里）。"
                )
            }

            is Filled.Error -> {
                /*
                 * 找不到人时**不走分享**。
                 *
                 * 那时候分享页里根本选不出这个人，用户很可能顺手点了别人——
                 * 一条消息就发错了。这种「办不到」必须直说。
                 */
                if (filled.kind == Fail.CONTACT) {
                    Outcome.Failed(filled.reason)
                } else {
                    val share = openShare(platform, content)
                    when {
                        share.verified -> Outcome.Done(
                            "没能自己填进去（${filled.reason}），已经把内容带到${platform.label}了，" +
                                "选「$to」再点发送就好。"
                        )
                        copied -> Outcome.Done(
                            "没能自己填进去（${filled.reason}），内容已经复制到剪贴板。" +
                                "打开${platform.label}，进「$to」的聊天，长按输入框粘贴。"
                        )
                        else -> Outcome.Failed("${filled.reason}，内容也没能送到${platform.label}。")
                    }
                }
            }
        }
    }

    /**
     * 打开 → 找人 → 进聊天 → 填字。停在**发送前**，不点任何发送键。
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
            // 有些 ROM 第一次拉起只是把任务提到后台，再试一次通常就上来了
            launch(platform)
            if (!waitForApp(platform, LAUNCH_RETRY_MS)) {
                return Filled.Error("${platform.label}没跳出来，系统可能拦了后台跳转")
            }
        }
        if (aborted()) return Filled.Error("这一条被新的请求顶掉了")

        /*
         * 0) 已经停在这个人的聊天页（上一轮填完、用户没退出）。
         *
         * 这一步是「第二次发送不跳转」的正面解法：不再依赖返回键把用户
         * 从聊天页一路退回首页——那条路上任何一次返回没人接，就会把应用退出，
         * 后面自然「没跳转、也没填字」。停在对的地方就直接填。
         */
        if (currentChatMatches(to)) {
            Log.i(TAG, "already in chat with $to")
            val input = waitChatInput(1_200) ?: return Filled.Error("没找到聊天输入框")
            return fillContent(input, to, content)
        }

        // 1) 先回到首页：搜索入口只在首页。用户上次可能停在某个聊天里
        backToHome(platform)
        if (driver.currentPackage() != platform.packageName) {
            return Filled.Error("从${platform.label}里被弹出来了")
        }
        if (aborted()) return Filled.Error("这一条被新的请求顶掉了")

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
            if (exact != null) {
                val entered = enterChat(platform, to, exact)
                if (entered != null) return fillContent(entered, to, content)
                Log.i(TAG, "首页同名会话点不进去，改走搜索")
            }
        }

        // 3) 搜索：首页 → 点搜索入口 → 填名字
        val searchEntry = searchEntry(SEARCH_TIMEOUT_MS)
            ?: return Filled.Error("没找到${platform.label}的搜索入口")
        if (!driver.click(searchEntry)) {
            val rect = driver.bounds(searchEntry)
            if (rect == null) return Filled.Error("搜索入口点不开")
            driver.tap(rect.exactCenterX(), rect.exactCenterY())
        }

        val searchBox = findSearchBox(SEARCH_BOX_TIMEOUT_MS)
            ?: return Filled.Error("搜索框没弹出来")
        if (!driver.fillText(searchBox, to)) {
            driver.click(searchBox)
            Thread.sleep(300)
            if (!driver.fillText(searchBox, to)) {
                return Filled.Error("联系人名字填不进搜索框")
            }
        }
        Thread.sleep(900)

        // 4) 点开联系人：完全同名优先，且排除「搜索 xxx」这类网络搜索行
        val searchBottom = driver.bounds(searchBox)?.bottom ?: 0
        val contact = findContact(to, searchBottom)
            ?: return Filled.Error("搜索结果里没有叫「$to」的联系人", Fail.CONTACT)
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
     *
     * **最后一定要读回来**：写进去和真写进去是两件事。读不到就不敢说「填好了」，
     * 更不能说「已发送」——那正是用户反馈的「没发出去还说发了」。
     */
    private fun fillContent(inputBox: AccessibilityNodeInfo, matched: String, content: String): Filled {
        // 上一轮填过、用户没发的话，这里会累加成「我晚点到我晚点到」，先清空
        if (driver.actualText(inputBox).trim().isNotEmpty()) {
            driver.fillText(inputBox, "")
            Thread.sleep(150)
        }

        var filled = driver.fillText(inputBox, content)
        if (!filled) {
            driver.click(inputBox)
            Thread.sleep(350)
            filled = driver.fillText(inputBox, content)
        }
        if (!filled) return Filled.Error("字填不进去", Fail.FILL)

        // 读回来核对：读到就核对，读不到就不敢说自己填好了
        repeat(4) { attempt ->
            Thread.sleep(200)
            val now = driver.actualText(inputBox).trim()
            if (now == content.trim()) return Filled.Ok(matched, inputBox, verified = true)
            if (now.isNotEmpty() && now != content.trim() && attempt >= 1) {
                // 内容不对：再写一遍，别把上一次的残留留在框里
                driver.fillText(inputBox, content)
            }
        }

        val now = driver.actualText(inputBox).trim()
        return if (now.isEmpty()) {
            Filled.Ok(matched, inputBox, verified = false)
        } else if (now == content.trim()) {
            Filled.Ok(matched, inputBox, verified = true)
        } else {
            Filled.Error("输入框里是「$now」，和要发的不一样", Fail.FILL)
        }
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
        if (aborted()) return null

        // 还在搜索页：条目可能没响应这次点击，改用坐标再点一次
        if (findSearchBox(0) != null) {
            val below = driver.bounds(findSearchBox(0))?.bottom ?: 0
            tapOrClick(findContact(to, below) ?: firstContact)
            waitChatInput(2_500)?.let { return it }
            if (aborted()) return null
        }

        // QQ 点搜索结果先进资料卡，要再点「发消息」
        driver.find(byText = "发消息", timeoutMs = 700)?.let { button ->
            driver.click(button)
            waitChatInput(2_500)?.let { chat -> return chat }
            if (aborted()) return null
        }

        // 跑到别的应用了就别再折腾
        if (driver.currentPackage() != platform.packageName) return null

        // 退回搜索页重搜。
        // 注意：**首页上不能按返回**——那一下会把整个应用退掉，后面就成「没跳转」了。
        if (!atHome()) {
            driver.back()
            Thread.sleep(500)
            if (driver.currentPackage() != platform.packageName) return null
        }
        val entry = searchEntry(1_200) ?: return null
        if (!driver.click(entry)) tapOrClick(entry)
        val box = findSearchBox(2_000) ?: return null
        driver.fillText(box, to)
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
     * 现在是不是已经停在「和 [to] 的聊天页」。
     *
     * 判据：底部有聊天输入框、顶上有一条文字刚好是这个名字的标题。
     * 两个都要有——群里也可能出现同名，光看标题会误判；只有输入框又不知道是谁。
     *
     * 有了这条，第二次发消息就不用「返回 → 首页 → 再点一次」：
     * 那条路上任何一步没人接，用户看到的都是「没跳转」。
     */
    private fun currentChatMatches(to: String): Boolean {
        if (driver.currentPackage().isNullOrEmpty()) return false
        waitChatInput(800) ?: return false
        val screen = driver.screenHeight()
        if (screen <= 0) return false
        val titleLimit = screen * TITLE_FRACTION
        return driver.findAll { node ->
            node.isVisibleToUser &&
                node.text?.toString()?.trim() == to &&
                (driver.bounds(node)?.centerY() ?: Int.MAX_VALUE) < titleLimit
        }.isNotEmpty()
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

    /** 搜索框：可编辑 + 看着像搜索框（提示语 / id / 屏幕上半三判据） */
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
        val end = System.currentTimeMillis() + timeoutMs
        do {
            val boxes = driver.findAll { node ->
                driver.isEditable(node) && !looksLikeSearchBox(node)
            }
            boxes.firstOrNull { looksLikeChatInput(it) }?.let { return it }
            if (driver.screenHeight() <= 0) boxes.firstOrNull()?.let { return it }
            if (aborted()) return null
            Thread.sleep(250)
        } while (System.currentTimeMillis() < end)
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
     * 退到首页。
     *
     * 判据是「有搜索入口 **且** 页面上没有输入框」——搜索结果页同样有「搜索」两个字，
     * 光看搜索入口会把搜索页当首页，接着就点了页面上已有的搜索文字，
     * 于是搜索框里填的、后面填的都是上一轮的残留内容。
     */
    private fun backToHome(platform: ImPlatform) {
        if (atHome()) return

        /*
         * 先按底部 tab（微信 / 消息），不要一上来就按返回。
         *
         * 返回键在「我」这种 tab 页上不是回列表，而是**把整个应用退掉**——
         * 用户看到的就是「第二次没跳转」。tab 一直在底部，点它比猜返回栈稳。
         */
        val screen = driver.screenHeight()
        if (screen > 0) {
            val labels = if (platform == ImPlatform.QQ) listOf("消息") else listOf("微信")
            for (label in labels) {
                val tab = driver.findAll { node ->
                    node.isVisibleToUser &&
                        node.text?.toString()?.trim() == label &&
                        (driver.bounds(node)?.centerY() ?: 0) > screen * TAB_FRACTION
                }.firstOrNull()
                if (tab != null) {
                    tapOrClick(tab)
                    Thread.sleep(600)
                    if (atHome()) return
                }
            }
        }

        // 再按返回：只在「页面上确实有输入框」（聊天页/搜索页）时按，避免退出应用
        repeat(2) {
            if (driver.currentPackage() != platform.packageName) return
            if (driver.findEditable(0) == null) return
            driver.back()
            Thread.sleep(500)
            if (atHome()) return
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

    /** 一次分享的结果：只认「真的跳过去了」 */
    private data class Share(val verified: Boolean)

    /**
     * 把文字交给微信 / QQ 的官方分享入口，联系人由用户自己选。
     *
     * ## 为什么一定要「验证」
     * `startActivity` 在后台启动被系统拦住时**不会抛异常**：调用成功、界面没动。
     * 以前的代码在没有读屏能力时直接 `return true`，于是回一句「已经把内容带到微信了」，
     * 用户抬头一看还在原地——这就是「他说发了，其实没跳转」的来源。
     *
     * 现在两条验证路径，都必须看见变化才算成功：
     * - 有读屏：前台包名从我们变成别人；
     * - 没有读屏：我们自己的 Activity 被盖住（分享页是另一个 Activity，一定会让我们 onPause）。
     * 都看不见变化就返回 false，由调用方改说「已复制到剪贴板」——至少那句话是真的。
     */
    private fun openShare(platform: ImPlatform, content: String): Share {
        val wasForeground = uiForeground()

        val started = runCatching {
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

        if (!started) return Share(false)

        val until = System.currentTimeMillis() + SHARE_TIMEOUT_MS

        // 有读屏：前台包名变了就是真跳过去了
        if (driver.available()) {
            while (System.currentTimeMillis() < until) {
                val pkg = driver.currentPackage()
                if (pkg != null && pkg != context.packageName) return Share(true)
                Thread.sleep(200)
            }
            Log.w(TAG, "openShare: 包名没变，分享页可能没起来")
            return Share(false)
        }

        /*
         * 没有读屏：只能反证。
         * 本来就不在前台时（比如用户在别的应用里触发），我们没有可观察的信号，
         * 于是如实返回「没验证」——宁可说「已复制到剪贴板」，也不谎报跳转成功。
         */
        if (!wasForeground) return Share(false)
        while (System.currentTimeMillis() < until) {
            if (!uiForeground()) return Share(true)
            Thread.sleep(150)
        }
        Log.w(TAG, "openShare: 界面没被盖住，分享页可能没起来")
        return Share(false)
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
        val end = System.currentTimeMillis() + timeoutMs
        do {
            if (driver.currentPackage() == platform.packageName) return true
            if (aborted()) return false
            Thread.sleep(250)
        } while (System.currentTimeMillis() < end)
        return false
    }

    companion object {
        /**
         * 进程级单例。
         *
         * 界面和大模型工具共用同一只「手」：无障碍服务只有一个，
         * 两条入口各建一个实例只会互相抢节点。
         */
        @Volatile
        private var shared: ImAutomator? = null

        fun of(context: Context): ImAutomator = shared ?: synchronized(this) {
            shared ?: ImAutomator(context.applicationContext).also { shared = it }
        }

        const val LAUNCH_TIMEOUT_MS = 4_000L

        /** 第一次没拉起来时再等这么久（有些 ROM 第一次只把任务提到后台） */
        const val LAUNCH_RETRY_MS = 2_000L

        const val SEARCH_TIMEOUT_MS = 2_000L

        /** 点完搜索入口，等搜索框出现 */
        const val SEARCH_BOX_TIMEOUT_MS = 2_500L

        /**
         * 一次发送的总预算。
         *
         * 每步都有超时，但叠起来仍可能十几秒；超过这个上限就当作失败收场，
         * 不能让一条消息把整个助手卡在那里。
         */
        const val BUDGET_MS = 28_000L

        /** 点完联系人后，等聊天页出现的时间 */
        const val CHAT_ENTER_TIMEOUT_MS = 3_000L

        /** 分享页起来的等待时间：超过了就当没跳过去 */
        const val SHARE_TIMEOUT_MS = 3_000L

        /** 聊天页标题只在屏幕最上面这一条里找（占全屏高度的比例） */
        const val TITLE_FRACTION = 0.16f

        /** 底部 tab 栏的位置（占全屏高度的比例） */
        const val TAB_FRACTION = 0.78f

        private const val TAG = "ImFlow"
    }
}
