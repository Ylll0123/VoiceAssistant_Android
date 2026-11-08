package com.example.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 本地意图识别测试：**每一句话应该去哪，都在这里钉死**。
 *
 * 这是「本地优先」的闸门，判错的代价是不对称的：
 * - 该本地却交给大模型 → 多花一次请求，慢一点，但结果仍然对；
 * - 不该本地却认领了 → 用户问天气却定了个闹钟，属于硬伤。
 *
 * 所以测试里两类都写：哪些该命中、哪些**必须**放行给大模型。
 */
class LocalNluTest {

    /** 2026-11-03 10:00 周二 */
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)

    private fun hit(text: String): LocalNlu.Hit? = LocalNlu.route(text, now)

    private fun service(text: String): String? = hit(text)?.service

    private fun args(text: String, key: String): String? = hit(text)?.args?.str(key)

    /** 假装本机装了这些应用，用来测「常用表之外也能打开」 */
    private fun hitWithApps(text: String, installed: Set<String>): LocalNlu.Hit? =
        LocalNlu.route(text, now, appMatcher = { it in installed })

    // ------------------------------------------------------------ 设备

    @Test
    fun `手电筒开关`() {
        assertEquals("device/flashlight", service("打开手电筒"))
        assertEquals("on", args("打开手电筒", "action"))
        assertEquals("off", args("把手电筒关掉", "action"))
    }

    @Test
    fun `打开应用要认得出名字`() {
        assertEquals("device/open_app", service("打开微信"))
        assertEquals("微信", args("打开微信", "name"))
        // 「打开设置页面」要归一成「设置」，否则设备层找不到应用
        assertEquals("设置", args("打开设置页面", "name"))
        // 「打开这个思路」不是打开应用，必须放给大模型
        assertNull(service("打开这个思路"))
    }

    @Test
    fun `常用表以外的应用靠本机安装列表认领`() {
        // 没有安装列表时保守放弃：「原神」不在内置表里
        assertNull(service("打开原神"))

        val installed = setOf("原神", "学习通", "微信")
        val hit = hitWithApps("打开原神", installed)
        assertEquals("device/open_app", hit?.service)
        assertEquals("原神", hit?.args?.str("name"))

        // 「打开学习通」里的名字短，也要能认出来
        assertEquals("学习通", hitWithApps("打开学习通吧", installed)?.args?.str("name"))

        // 装了微信：内置表优先，走归一后的「微信」
        assertEquals("微信", hitWithApps("打开微信", installed)?.args?.str("name"))

        // 名字不像应用，就算有安装列表也不能认领
        assertNull(hitWithApps("打开这个思路", installed))
    }

    @Test
    fun `WiFi 和蓝牙开关`() {
        assertEquals("device/wifi_ctrl", service("打开wifi"))
        assertEquals("on", args("打开wifi", "action"))
        assertEquals("off", args("关闭wifi", "action"))
        assertEquals("device/bluetooth_ctrl", service("打开蓝牙耳机"))
        assertEquals("off", args("把蓝牙关了", "action"))
    }

    @Test
    fun `亮度调节`() {
        assertEquals("device/brightness", service("亮度调到60%"))
        assertEquals("60", args("亮度调到60%", "percent"))
        assertEquals("up", args("屏幕太暗了", "action"))
        assertEquals("down", args("屏幕太亮了", "action"))
    }

    // ------------------------------------------------------------ 音频

    @Test
    fun `音量与静音`() {
        assertEquals("audio/volume_ctrl", service("音量调到30%"))
        assertEquals("30", args("音量调到30%", "percent"))
        assertEquals("mute", args("静音", "action"))
        // 「取消静音」不能被「静音」抢走
        assertEquals("unmute", args("取消静音", "action"))
        assertEquals("up", args("大声点", "action"))
        assertEquals("down", args("小声一点", "action"))
    }

    @Test
    fun `别念了是停止播报，不是改音量`() {
        assertEquals("audio/tts_stop", service("别念了"))
        assertEquals("audio/tts_stop", service("闭嘴"))
    }

    @Test
    fun `音量数字不带百分号也要认`() {
        // 「调到 30」这种说法以前会掉给大模型，离线时等于没反应
        assertEquals("audio/volume_ctrl", service("音量调到30"))
        assertEquals("30", args("音量调到30", "percent"))
        assertEquals("50", args("声音调到五十", "percent"))
        assertEquals("80", args("把音量调到80", "percent"))
    }

    @Test
    fun `音量要分清媒体铃声闹钟`() {
        assertEquals("music", args("音量调到30", "stream"))
        assertEquals("ring", args("把铃声调小", "stream"))
        assertEquals("ring", args("铃声静音", "stream"))
        assertEquals("alarm", args("闹钟音量调到80", "stream"))
    }

    @Test
    fun `放歌和切歌`() {
        assertEquals("audio/music_ctrl", service("播放音乐"))
        assertEquals("play", args("播放音乐", "action"))
        assertEquals("play", args("放首歌来听", "action"))
        assertEquals("pause", args("暂停音乐", "action"))
        assertEquals("next", args("下一首", "action"))
        assertEquals("previous", args("上一首", "action"))
        // 单独一个「暂停」不算：更可能是在说计时器
        assertNull(hit("暂停"))
    }

    // ------------------------------------------------------------ 时间

    @Test
    fun `问时间问日期`() {
        assertEquals("tool/time", service("现在几点"))
        assertEquals("time", args("现在几点", "kind"))
        assertEquals("date", args("今天几号", "kind"))
        assertEquals("weekday", args("今天星期几", "kind"))
        // 「八点提醒我」是定闹钟，不是问时间
        assertEquals("schedule/add_alarm", service("八点提醒我"))
    }

    // ------------------------------------------------------------ 日程

    @Test
    fun `提醒要能算出绝对时间并从内容里剥掉时间`() {
        assertEquals("schedule/add_alarm", service("提醒我八点交作业"))
        // 上午十点说「八点」，落地是明早八点
        assertEquals("2026-11-04 08:00", args("提醒我八点交作业", "at"))
        assertEquals("交作业", args("提醒我八点交作业", "content"))
        // 时段词也要从内容里去掉，不然提醒会叫「早上」
        assertEquals("提醒", args("明天早上七点叫我", "content"))
        assertEquals("2026-11-04 07:00", args("明天早上七点叫我", "at"))
    }

    @Test
    fun `相对时长走 minutes，不算绝对时间`() {
        assertEquals("schedule/add_alarm", service("10分钟后叫我"))
        assertEquals("10", args("10分钟后叫我", "minutes"))
        assertEquals("30", args("计时半小时", "minutes"))
    }

    @Test
    fun `没说时间的提醒不认领，交给大模型追问`() {
        assertNull(service("记得提醒我买菜"))
    }

    // ------------------------------------------------------------ 计算

    @Test
    fun `纯算式本地算`() {
        assertEquals("tool/calc", service("帮我算12乘8"))
        // 参数原样带过去，由 tool/calc 自己剥壳（本地和模型两条路共用一份剥壳逻辑）
        assertEquals("帮我算12乘8", args("帮我算12乘8", "expression"))
        assertEquals("tool/calc", service("(3+5)/2是多少"))
    }

    // ------------------------------------------------------------ 消息

    @Test
    fun `发消息要同时有收件人和内容`() {
        assertEquals("msg/send_text", service("给妈妈发微信说我晚点到"))
        assertEquals("妈妈", args("给妈妈发微信说我晚点到", "to"))
        assertEquals("我晚点到", args("给妈妈发微信说我晚点到", "content"))
        assertEquals("wechat", args("给妈妈发微信说我晚点到", "platform"))
        // 没有内容 = 需要先让模型写一句，本地不认领
        assertNull(service("给妈妈发个微信"))
    }

    // ------------------------------------------------------------ 该放行的

    @Test
    fun `问答、解释、翻译这类一律交给大模型`() {
        assertNull(service("解释一下傅里叶变换"))
        assertNull(service("背单词的诀窍是什么"))
        assertNull(service("把这句话翻译成英文"))
        assertNull(service("今天天气怎么样"))
        assertNull(service("我有点累"))
    }

    @Test
    fun `空话和超长句都不猜`() {
        assertNull(service("   "))
        assertNull(service("我今天下午想去图书馆借一本关于信号与系统的书然后回宿舍慢慢看顺便把作业也写了这样明天就不用早起去教室"))
    }
}
