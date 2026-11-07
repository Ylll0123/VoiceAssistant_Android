package com.example.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.MediaStore
import android.provider.Settings

/**
 * 服务层：**手机本身**的能力（电量、手电筒、打开应用）。
 *
 * 抽成接口是为了让工具层的判定能单测——真机上才有 CameraManager，
 * 单测里给个假实现就能验证「说『打开手电筒』会不会被认领」。
 */
interface DeviceController {

    /** 电量描述，取不到返回 null */
    fun battery(): String?

    /** 开 / 关手电筒；设备没有闪光灯返回 false */
    fun setTorch(on: Boolean): Boolean

    /**
     * 打开一个应用。
     *
     * @return 打开成功返回应用显示名；找不到返回 null
     */
    fun openApp(name: String): String?
}

/**
 * [DeviceController] 的安卓实现。
 *
 * ## 打开应用为什么用「名字 → 包名」表
 * Android 11 起，应用列表默认是**过滤**的（要 QUERY_ALL_PACKAGES 才拿得到全量）。
 * 为了打开一个微信去申请「读取所有应用」的权限不划算，也容易让用户不安。
 * 所以维护一张常用应用表，命中就直接拉起；表里没有的如实说找不到。
 */
class AndroidDeviceController(context: Context) : DeviceController {

    private val app: Context = context.applicationContext

    /** 本机全部可启动应用；常用表里没有的，都靠它 */
    private val catalog = AppCatalog(app)

    override fun battery(): String? = runCatching {
        val sticky = app.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null

        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100 / scale)

        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        when {
            status == BatteryManager.BATTERY_STATUS_FULL -> "电量已经满了（100%）。"
            charging -> "现在电量 $percent%，正在充电。"
            percent <= 15 -> "现在电量 $percent%，快没电了，记得充电。"
            else -> "现在电量 $percent%。"
        }
    }.getOrNull()

    override fun setTorch(on: Boolean): Boolean = runCatching {
        val manager = app.getSystemService(CameraManager::class.java) ?: return false
        val id = manager.cameraIdList.firstOrNull { cameraId ->
            val traits = manager.getCameraCharacteristics(cameraId)
            traits.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return false
        manager.setTorchMode(id, on)
        true
    }.getOrDefault(false)

    override fun openApp(name: String): String? {
        val key = name.trim()
        if (key.isEmpty()) return null
        val lower = key.lowercase()

        // 1) 系统入口：完全同名才认，避免「微信读书」被「微信」截胡
        SYSTEM_INTENTS.entries.firstOrNull { it.key == lower }?.let { (label, intent) ->
            if (launch(intent)) return label
        }

        // 2) 常用第三方：完全同名
        PACKAGES.entries.firstOrNull { it.key == lower }?.let { (label, pkg) ->
            app.packageManager.getLaunchIntentForPackage(pkg)?.let { if (launch(it)) return label }
        }

        /*
         * 3) 全量目录：任意已安装应用。
         *
         * 这一步才是「打开所有应用」的关键——上面两张表只是常用项的快速路径，
         * 原神、学习通、企业微信这些没进表的，都从这里认。
         */
        catalog.open(key)?.let { return it }

        // 4) 老表兜底：包含即可，取最长的那个键（「哔哩哔哩」优先于「哔哩」）
        SYSTEM_INTENTS.entries.filter { lower.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.let { (label, intent) -> if (launch(intent)) return label }
        PACKAGES.entries.filter { lower.contains(it.key) }
            .maxByOrNull { it.key.length }
            ?.let { (label, pkg) ->
                app.packageManager.getLaunchIntentForPackage(pkg)
                    ?.let { if (launch(it)) return label }
            }

        // 5) 传进来的本来就是包名
        if (lower.contains('.')) {
            app.packageManager.getLaunchIntentForPackage(lower)
                ?.let { if (launch(it)) return lower }
        }
        return null
    }

    private fun launch(intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    }.getOrDefault(false)

    private companion object {

        /** 系统自带入口：标准 Intent 比包名稳（各家 ROM 包名都不一样） */
        val SYSTEM_INTENTS: Map<String, Intent> = mapOf(
            "相机" to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            "拍照" to Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            "设置" to Intent(Settings.ACTION_SETTINGS),
            "浏览器" to Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://cn.bing.com")
            ),
            "网页" to Intent(Intent.ACTION_VIEW, Uri.parse("https://cn.bing.com")),
            "拨号" to Intent(Intent.ACTION_DIAL),
            "电话" to Intent(Intent.ACTION_DIAL),
            "短信" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            "信息" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            "计算器" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALCULATOR),
            "日历" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
            "相册" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
            "图库" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
            "音乐" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            "地图" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        )

        /** 常用第三方应用 */
        val PACKAGES: Map<String, String> = mapOf(
            "微信" to "com.tencent.mm",
            "qq" to "com.tencent.mobileqq",
            "支付宝" to "com.eg.android.AlipayGphone",
            "抖音" to "com.ss.android.ugc.aweme",
            "快手" to "com.smile.gifmaker",
            "b站" to "tv.danmaku.bili",
            "哔哩" to "tv.danmaku.bili",
            "淘宝" to "com.taobao.taobao",
            "京东" to "com.jingdong.app.mall",
            "拼多多" to "com.xunmeng.pinduoduo",
            "微博" to "com.sina.weibo",
            "知乎" to "com.zhihu.android",
            "小红书" to "com.xingin.xhs",
            "网易云" to "com.netease.cloudmusic",
            "高德" to "com.autonavi.minimap",
            "百度地图" to "com.baidu.BaiduMap",
            "钉钉" to "com.alibaba.android.rimet",
            "腾讯会议" to "com.tencent.wemeet.app",
            "王者" to "com.tencent.tmgp.sgame"
        )
    }
}
