package com.example.voiceassistant.service

/**
 * 「打开 XX」里 XX 可以是哪些应用。
 *
 * ## 为什么单独列一份
 * 本地 NLU 要在**不联网、不碰 Android** 的前提下判断「打开这个思路」不是「打开某个应用」。
 * 判断依据只能是「XX 是不是一个真实存在的应用名」，所以这张表必须能被纯函数读到
 * （[com.example.voiceassistant.service.AndroidDeviceController] 里那些 Intent 是 Android 对象，
 * 单测里一碰就炸）。
 *
 * ⚠️ 改这张表时记得同步 `AndroidDeviceController.SYSTEM_INTENTS` / `PACKAGES`，
 * 不然会出现「NLU 认领了、设备层打不开」的裂缝。
 */
object KnownApps {

    /** 系统入口（用标准 Intent 打开，不依赖包名） */
    val SYSTEM: Set<String> = setOf(
        "相机", "拍照", "设置", "浏览器", "网页", "拨号", "电话",
        "短信", "信息", "计算器", "日历", "相册", "图库", "音乐", "地图"
    )

    /** 第三方应用（包名表） */
    val THIRD_PARTY: Set<String> = setOf(
        "微信", "qq", "支付宝", "抖音", "快手", "b站", "哔哩", "哔哩哔哩",
        "淘宝", "京东", "拼多多", "微博", "知乎", "小红书", "网易云",
        "网易云音乐", "高德", "高德地图", "百度地图", "钉钉", "腾讯会议", "王者", "王者荣耀"
    )

    /**
     * 把用户说的名字归一到表里的写法；不认识返回 null。
     *
     * 为什么要归一：用户会说「打开微信吧」「打开设置页面」「打开哔哩哔哩」，
     * 直接把原话丢给 [com.example.voiceassistant.service.AndroidDeviceController] 会找不到应用。
     * 这里统一吐出表里那个名字（「微信」「设置」「哔哩哔哩」），下游就不必再猜。
     */
    fun canonical(raw: String): String? {
        val original = raw.trim()
        val name = original.lowercase()
            .removeSuffix("app")
            .removeSuffix("应用")
            .removeSuffix("软件")
            .removeSuffix("页面")
            .trim()
        if (name.isEmpty()) return null

        val table = SYSTEM + THIRD_PARTY
        // 完全同名优先，避免「哔哩」抢在「哔哩哔哩」前面
        table.firstOrNull { it == name }?.let { return it }
        // 其次取最长的那个命中项：「哔哩哔哩」> 「哔哩」
        table.filter { name.contains(it) }.maxByOrNull { it.length }?.let { return it }
        // 直接报包名的也算（「打开 com.tencent.mm」），原样返回
        if (name.contains('.') && name.length > 5) return original
        return null
    }

    /** 名字像不像一个应用（用于 NLU 决定要不要认领「打开 XX」） */
    fun isKnown(raw: String): Boolean = canonical(raw) != null

    /** 给模型看的应用清单（拼进工具描述太长，只在提示词里提一句就够） */
    fun sample(): String = "微信、QQ、B站、淘宝、支付宝、浏览器、相机、设置、日历"
}
