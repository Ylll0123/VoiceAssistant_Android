package com.example.voiceassistant.tool

import com.example.voiceassistant.service.DeviceController

/**
 * 工具层：**手机本身**（电量 / 手电筒 / 打开应用）。
 *
 * 这类指令是市面语音助手的标配，共同点是「本机能做、0 请求」，
 * 所以放在工具层最前面，一句话就能办完，不用联网也不用问大模型。
 *
 * ## 判定顺序（重要）
 * 1. 手电筒：「打开手电筒」含「打开」，必须先判，
 *    否则会被当成「打开某个叫手电筒的应用」；
 * 2. 电量：「还有多少电」「电量」；
 * 3. 打开应用：「打开微信」「启动相机」。
 *
 * ## 找不到就直说
 * 「打开某某」如果不在已知名单里，如实回「没找到这个应用」，
 * 而不是硬拉一个浏览器搜索——后者在语音场景里很像故障。
 */
object DeviceTool {

    const val NAME = "device"

    private val TORCH_CUES = listOf("手电筒", "手电", "打光", "照明")
    private val TORCH_OFF = listOf("关", "关闭", "关掉", "别开", "不要")

    private val BATTERY_CUES = listOf(
        "多少电", "电量", "电池", "还有几格电", "电还够", "需要充电吗"
    )

    private val OPEN_PREFIX = listOf("帮我打开", "打开一下", "打开", "启动", "进入")

    fun matches(raw: String, device: DeviceController?): Boolean {
        if (device == null) return false
        val text = raw.trim()
        if (text.isEmpty()) return false
        if (TeachGuard.isTeaching(text)) return false

        if (TORCH_CUES.any { text.contains(it) }) return true
        if (BATTERY_CUES.any { text.contains(it) }) return true
        return appName(text) != null
    }

    fun handle(raw: String, device: DeviceController?): ToolReply? {
        if (!matches(raw, device)) return null
        val text = raw.trim()

        if (TORCH_CUES.any { text.contains(it) }) {
            // 「关掉手电筒」里也有「手电筒」，先判关再判开
            val on = !TORCH_OFF.any { text.contains(it) }
            val ok = device!!.setTorch(on)
            return when {
                !ok -> reply("这台设备没有可用的闪光灯。", ToolIntent.DEVICE)
                on -> reply("手电筒打开了。", ToolIntent.TORCH)
                else -> reply("手电筒关了。", ToolIntent.TORCH)
            }
        }

        if (BATTERY_CUES.any { text.contains(it) }) {
            val battery = device!!.battery()
            return reply(battery ?: "读不到电量，可能是系统限制。", ToolIntent.BATTERY)
        }

        val name = appName(text) ?: return null
        val opened = device!!.openApp(name)
        return if (opened != null) {
            reply("打开了$opened。", ToolIntent.DEVICE)
        } else {
            reply("没找到「$name」这个应用，可能没装，或者名字不在这台手机的常用名单里。", ToolIntent.DEVICE)
        }
    }

    /** 从「打开微信」里取出「微信」；不是打开类指令返回 null */
    private fun appName(text: String): String? {
        val prefix = OPEN_PREFIX.firstOrNull { text.startsWith(it) } ?: return null
        // 「打开一下手电筒」这种要在前缀之外再剥掉一个「一下」
        val rest = text.removePrefix(prefix)
            .removePrefix("一下")
            .trim('，', ',', '。', '.', '!', '！', ' ', '：')
        return rest.takeIf { it.length in 1..12 }
    }

    private fun reply(text: String, intent: String) = ToolReply(text, intent, NAME)
}
