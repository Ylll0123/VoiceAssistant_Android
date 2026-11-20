package com.example.voiceassistant.provider

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.service.DeviceController
import com.example.voiceassistant.service.UiLaunch

/**
 * 设备控制域：`device/…`
 *
 * ## 这里有一半的服务「做不到全自动」，必须说实话
 * Android 10 起第三方应用**不能**直接开关 WiFi（`setWifiEnabled` 直接返回 false），
 * Android 13 起开关蓝牙需要系统级权限。所以这两个服务的实现是
 * **拉起系统面板 + 如实告诉用户「在这儿切」**，而不是假装成功。
 *
 * 一个「说成功了但其实没动」的助手，比一个「说得清楚」的助手危险得多。
 *
 * 能真正全自动的只有：手电筒、打开应用、读电量、本应用内亮度。
 */
class DeviceSupply(
    context: Context,
    private val device: DeviceController,
    /**
     * 改不了系统亮度时的退路：只调当前窗口的亮度。
     *
     * 由 Activity 注入（`window.attributes.screenBrightness`）。
     * 传 null 表示当前没有界面，那就只能提示权限。
     */
    private val windowBrightness: ((Float) -> Unit)? = null
) {

    private val app: Context = context.applicationContext

    /**
     * 手电筒现在开着没有。
     *
     * 以前它是伴生对象里的普通 `var`：后台有 3 条线程，"切换手电筒" 并发进来时
     * 两条会读到同一个旧值、朝同一个方向翻，结果就是按了两次还是一次。
     * 现在用原子量，翻转方向由它保证。
     *
     * 局限也说清楚：应用重启后这里是 false，而手电筒可能还亮着。
     * 所以 `toggle` 只在用户明说「切换」时用，说「打开/关闭」永远是对的。
     */
    private val torchOn = java.util.concurrent.atomic.AtomicBoolean(false)

    val all: List<Supply> = listOf(
        flashlight(), brightness(), openApp(), battery(), wifi(), bluetooth()
    )

    // ------------------------------------------------------------------ 手电筒

    private fun flashlight(): Supply = object : Supply {
        override val name = "device/flashlight"
        override val summary = "开 / 关手电筒。参数 action：on 或 off。"

        override fun invoke(args: BusArgs): BusReply {
            val on = when (args.str("action")) {
                "on", "开", "打开" -> true
                "off", "关", "关闭" -> false
                "toggle" -> !torchOn.get()
                else -> return BusReply.fail("要开还是关？")
            }
            val done = device.setTorch(on)
            if (!done) return BusReply.fail("这台设备没有可用的闪光灯。")
            torchOn.set(on)
            return BusReply.ok(if (on) "手电筒已打开。" else "手电筒已关闭。")
        }
    }

    // ------------------------------------------------------------------ 亮度

    private fun brightness(): Supply = object : Supply {
        override val name = "device/brightness"
        override val summary =
            "调屏幕亮度。参数 percent（1-100），或 action：up / down / max / min。"

        override fun invoke(args: BusArgs): BusReply {
            val current = currentPercent()
            val target = when {
                args.int("percent") != null -> args.int("percent")!!.coerceIn(1, 100)
                args.str("action") == "up" -> (current + 15).coerceAtMost(100)
                args.str("action") == "down" -> (current - 15).coerceAtLeast(1)
                args.str("action") == "max" -> 100
                args.str("action") == "min" -> 1
                else -> return BusReply.fail("要调到多少？")
            }

            // 首选改系统亮度（需要「修改系统设置」权限）
            if (Settings.System.canWrite(app)) {
                val value = (target * 255 / 100).coerceIn(1, 255)
                runCatching {
                    // 关掉自动亮度，否则刚写进去就被系统改回去
                    Settings.System.putInt(
                        app.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(
                        app.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        value
                    )
                }
                // 写完读回：Settings 写失败也不抛异常，只能自己再看一眼
                val actual = currentPercent()
                return if (kotlin.math.abs(actual - target) <= 2) {
                    BusReply.ok("亮度调到 $target% 了。")
                } else {
                    BusReply.fail("试着调到 $target%，但系统只到 $actual%，可能被自动亮度改回去了。")
                }
            }

            // 退路：只调当前窗口（不需要任何权限，但只在本应用里有效）
            windowBrightness?.let { apply ->
                apply(target / 100f)
                return BusReply.ok(
                    "亮度调到 $target%（只在这个应用里生效）。" +
                        "想改整机亮度，得给我「修改系统设置」的权限。"
                )
            }

            return BusReply.fail(
                "改整机亮度需要「修改系统设置」权限，现在还没给。要不要去系统设置里开一下？"
            )
        }
    }

    // ------------------------------------------------------------------ 打开应用

    private fun openApp(): Supply = object : Supply {
        override val name = "device/open_app"
        override val summary =
            "打开手机里装好的任意应用。参数 name 是应用名，例如「微信」「B站」「原神」「学习通」。" +
                "常用应用走内置表，其余按安装列表模糊匹配。"

        override fun invoke(args: BusArgs): BusReply {
            val name = args.str("name")?.trim() ?: return BusReply.fail("要打开哪个应用？")
            if (name.isEmpty()) return BusReply.fail("要打开哪个应用？")
            val opened = device.openApp(name) ?: return BusReply.fail(
                "手机里没找到「$name」，也许真没装，或者叫别的名字。"
            )
            return BusReply.ok("已经打开$opened。")
        }
    }

    // ------------------------------------------------------------------ 电量

    private fun battery(): Supply = object : Supply {
        override val name = "device/battery"
        override val summary = "看手机电量。"

        override fun invoke(args: BusArgs): BusReply {
            val text = device.battery() ?: return BusReply.fail("电量读不出来。")
            return BusReply.ok(text)
        }
    }

    // ------------------------------------------------------------------ 无线开关

    private fun wifi(): Supply = object : Supply {
        override val name = "device/wifi_ctrl"
        override val summary = "开 / 关 WiFi。参数 action：on 或 off。"

        @SuppressLint("MissingPermission")
        override fun invoke(args: BusArgs): BusReply {
            val on = when (args.str("action")) {
                "on", "开", "打开" -> true
                "off", "关", "关闭" -> false
                else -> return BusReply.fail("WiFi 要开还是关？")
            }

            // Android 10 之前还能直接切；之后接口被禁用，只能请用户到面板里点
            val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val done = runCatching { manager?.isWifiEnabled = on }.isSuccess && manager?.isWifiEnabled == on
                if (done) return BusReply.ok(if (on) "WiFi 已打开。" else "WiFi 已关闭。")
            }

            // 面板动作直接写字符串：常量在 API 29 才有，内联字符串能避开版本检查
            val opened = openPanel(PANEL_WIFI)
            return if (opened) {
                BusReply.ok(
                    "系统不让应用直接开关 WiFi，已经帮你把面板打开了，" +
                        "在上面点一下就行。"
                )
            } else {
                BusReply.fail("打不开 WiFi 设置面板，手动到系统设置里看一下吧。")
            }
        }
    }

    private fun bluetooth(): Supply = object : Supply {
        override val name = "device/bluetooth_ctrl"
        override val summary =
            "开 / 关蓝牙。参数 action：on 或 off；也可以传 action=connect 表示去配对耳机。"

        @SuppressLint("MissingPermission")
        override fun invoke(args: BusArgs): BusReply {
            val action = args.str("action") ?: "on"

            if (action == "connect" || action == "pair") {
                val opened = openPanel(Settings.ACTION_BLUETOOTH_SETTINGS)
                return if (opened) {
                    BusReply.ok("蓝牙配对面板开好了，在这里选耳机连上就行。")
                } else {
                    BusReply.fail("打不开蓝牙设置。")
                }
            }

            val on = action == "on" || action == "开" || action == "打开"
            val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return BusReply.fail("这台设备没有蓝牙。")

            // Android 13 起 enable() 需要系统级权限，普通应用调用会被静默拒绝
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val done = runCatching {
                    if (on) adapter.enable() else adapter.disable()
                }.isSuccess && adapter.isEnabled == on
                if (done) return BusReply.ok(if (on) "蓝牙已打开。" else "蓝牙已关闭。")
            }

            val opened = openPanel(Settings.ACTION_BLUETOOTH_SETTINGS)
            return if (opened) {
                BusReply.ok("系统不允许应用直接开关蓝牙，蓝牙面板已经打开了，在上面切一下。")
            } else {
                BusReply.fail("打不开蓝牙设置面板。")
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 打开某个系统设置页，并**确认界面真的被盖住了**。
     *
     * 以前这里是 `startActivity` + `true`：Android 10 起后台启动会被拦，
     * 而 `startActivity` 被拦下时**不抛异常**，于是这个函数恒返回 true——
     * WiFi / 蓝牙就会回「面板已经开好了」，用户抬头还在原地。
     * 现在交给 [UiLaunch]，只有看见界面变化才算成功。
     * 面板动作本身不被支持时，退回总设置页再试一次。
     */
    private fun openPanel(action: String): Boolean {
        if (UiLaunch.open(app, Intent(action))) return true
        return UiLaunch.open(app, Intent(Settings.ACTION_SETTINGS))
    }

    private fun currentPercent(): Int {
        val value = runCatching {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return (value * 100 / 255).coerceIn(1, 100)
    }

    private companion object {
        /** Settings.Panel.ACTION_WIFI 的字面值（常量本身 API 29 才有） */
        const val PANEL_WIFI = "android.settings.panel.action.WIFI"
    }
}
