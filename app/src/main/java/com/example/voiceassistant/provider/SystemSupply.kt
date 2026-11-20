package com.example.voiceassistant.provider

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.Settings
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.service.ScreenCapture
import com.example.voiceassistant.service.UiLaunch

/**
 * 系统能力域：`system/…`
 *
 * 四件「一句话就能办」的事：截屏、剪贴板、勿扰、拍照。
 *
 * ## 放进单独一域的理由
 * 它们都要么依赖一个**系统级授权**（截屏要读屏、勿扰要「勿扰权限」），
 * 要么**只能半自动**（拍照只能开相机，快门得人来按）。
 * 跟 `device/` 那些「调一下就生效」的开关不是一类东西，
 * 混在一起会让「哪里会失败」变得不可预测。
 *
 * ## 一条贯穿全文件的规矩
 * 做不到就说做不到，并且说清**卡在哪一步**。
 * 截屏没连读屏、勿扰没授权、相机被拦——三种原因不能都糊成一句「失败了」。
 */
class SystemSupply(
    context: Context,
    /**
     * 上一条回答的原文。
     *
     * 「复制刚才那段」是语音里最省事的说法。文本不在服务手里——它属于对话，
     * 交给界面注入；没有可复制的就如实说没有。
     */
    private val lastAnswer: () -> String? = { null }
) {

    private val app: Context = context.applicationContext

    private val clipboard: ClipboardManager?
        get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    val all: List<Supply> = listOf(screenshot(), clipboardSupply(), dnd(), camera())

    // ------------------------------------------------------------------ 截屏

    private fun screenshot(): Supply = object : Supply {
        override val name = "system/screenshot"
        override val summary = "截一张当前屏幕，存进相册。不需要任何额外授权。"

        override fun invoke(args: BusArgs): BusReply = when (val result = ScreenCapture.capture(app)) {
            is ScreenCapture.Result.Saved ->
                // 只报「在哪个相册」，不念那串带时间戳的文件名——念出来既长又没法听
                BusReply.ok("截好了，在相册的 HardlySeek 相册里。", "file" to result.path)

            ScreenCapture.Result.NoService -> BusReply.fail(
                "截屏用的是读屏能力，现在没连上，所以截不了。"
            )

            ScreenCapture.Result.Unsupported ->
                BusReply.fail("这台手机的系统版本太老，不支持直接截屏。")

            is ScreenCapture.Result.Failed -> BusReply.fail("没截成：${result.reason}。")
        }
    }

    // ------------------------------------------------------------------ 剪贴板

    private fun clipboardSupply(): Supply = object : Supply {
        override val name = "system/clipboard"
        override val summary =
            "剪贴板。参数 action=write 时把 text 复制进去；action=read 时读出当前剪贴板里的文字。"

        override fun invoke(args: BusArgs): BusReply {
            val manager = clipboard ?: return BusReply.fail("这台设备用不了剪贴板。")

            return when (args.str("action")) {
                "read", "get", "读", "读一下" -> read(manager)
                else -> {
                    // source=last_answer：要的是上一条回答，不是用户念出来的字
                    val text = if (args.str("source") == "last_answer") lastAnswer() else args.str("text")
                    write(manager, text)
                }
            }
        }

        private fun write(manager: ClipboardManager, text: String?): BusReply {
            val body = text?.trim().orEmpty()
            if (body.isEmpty()) return BusReply.fail("要复制什么？上一条我还没说过话。")
            if (body.length > MAX_CLIP) return BusReply.fail("太长了，剪贴板装不下。")

            val ok = runCatching {
                manager.setPrimaryClip(ClipData.newPlainText("HardlySeek", body))
            }.isSuccess
            if (!ok) return BusReply.fail("剪贴板写不进去，系统可能拦了。")

            return BusReply.ok("复制好了，去别处长按粘贴就行。", "text" to body)
        }

        /**
         * 读剪贴板。
         *
         * Android 10 起只有**当前有焦点的应用**能读，所以界面在后台时拿到的可能是空。
         * 这时不说「剪贴板是空的」（那是错话），而是把两种情况一起说清。
         */
        private fun read(manager: ClipboardManager): BusReply {
            val clip = runCatching { manager.primaryClip }.getOrNull()
                ?: return BusReply.fail(
                    "读不到剪贴板——可能在后台被系统挡住了，回到应用里再问一次试试。"
                )
            if (clip.itemCount == 0) return BusReply.fail("剪贴板里现在是空的。")

            val text = runCatching { clip.getItemAt(0)?.coerceToText(app)?.toString() }
                .getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) return BusReply.fail("剪贴板里没有文字。")

            val short = if (text.length > MAX_READ) text.take(MAX_READ) + "……" else text
            return BusReply.ok("剪贴板里是：$short", "text" to text)
        }
    }

    // ------------------------------------------------------------------ 勿扰

    private fun dnd(): Supply = object : Supply {
        override val name = "system/dnd"
        override val summary =
            "免打扰（勿扰模式）。参数 action：on 打开、off 关闭、query 查当前状态。"

        override fun invoke(args: BusArgs): BusReply {
            val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return BusReply.fail("这台设备用不了勿扰模式。")

            when (args.str("action")) {
                "query", "status", "查", "查一下" -> return describe(manager)
                "on", "开", "打开" -> return switchTo(manager, true)
                "off", "关", "关闭" -> return switchTo(manager, false)
                else -> return BusReply.fail("勿扰要打开还是关掉？")
            }
        }

        /**
         * 开 / 关勿扰。
         *
         * 改这个要「勿扰权限」（用户手动授予的特殊权限），
         * 没给就去授权页，并且**明说没开成**——绝不回一句「已开启」。
         */
        private fun switchTo(manager: NotificationManager, on: Boolean): BusReply {
            if (!manager.isNotificationPolicyAccessGranted) {
                val opened = UiLaunch.open(
                    app,
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                )
                val hint = if (opened) {
                    "已经打开了授权页，找到本应用打开开关就行。"
                } else {
                    "到系统设置的「勿扰权限」里给本应用打开开关。"
                }
                return BusReply.fail(
                    "改勿扰需要「勿扰权限」，现在还没给。$hint" +
                        "（开了之后说一句「开勿扰」我就能直接切。）",
                    "need_policy" to "true"
                )
            }

            val target = if (on) {
                NotificationManager.INTERRUPTION_FILTER_NONE
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }

            val ok = runCatching { manager.setInterruptionFilter(target) }.isSuccess
            if (!ok) return BusReply.fail("系统没让改勿扰，可能被别的设置锁住了。")

            // 写完读回：这个接口同样不返回结果，只能自己看
            return if (manager.currentInterruptionFilter == target) {
                BusReply.ok(if (on) "勿扰打开了，通知都不会响。" else "勿扰关了，通知正常提醒。")
            } else {
                BusReply.fail("试着改了，但系统没切过去，你到控制中心看一眼现在的状态。")
            }
        }

        private fun describe(manager: NotificationManager): BusReply {
            if (!manager.isNotificationPolicyAccessGranted) {
                return BusReply.fail("还查不了：改勿扰需要「勿扰权限」，现在没给。")
            }
            val label = when (manager.currentInterruptionFilter) {
                NotificationManager.INTERRUPTION_FILTER_NONE -> "勿扰开着，所有通知都不响"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "只允许优先联系人打扰"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "只允许闹钟"
                else -> "勿扰没开，通知正常提醒"
            }
            return BusReply.ok("现在$label。")
        }
    }

    // ------------------------------------------------------------------ 拍照

    private fun camera(): Supply = object : Supply {
        override val name = "system/camera"
        override val summary = "打开相机拍照。快门要人按，应用不代替你拍。"

        override fun invoke(args: BusArgs): BusReply {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Android 11+ 不声明 queries 就查不到相机应用，会误报「没装相机」
            if (intent.resolveActivity(app.packageManager) == null) {
                return BusReply.fail("没找到相机应用，可能真没装。")
            }

            return if (UiLaunch.open(app, intent)) {
                BusReply.ok("相机开好了，对准按一下快门。")
            } else {
                BusReply.fail("相机没打开，系统拦了这次跳转，你从桌面点一下吧。")
            }
        }
    }

    private companion object {
        /** 剪贴板别塞超大文本：超过这个量，粘贴进聊天框常常会卡 */
        const val MAX_CLIP = 20_000

        /** 「剪贴板里是：…」念出来的上限；完整内容在 data["text"] 里 */
        const val MAX_READ = 120
    }
}
