package com.example.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 发消息句式的专项测试。
 *
 * v5.0 收尾前只认「给X发微信说Y」一条正则，用户换个语序就漏给大模型；
 * 离线时大模型不可用，用户看到的只有「没联网，问答用不了」——消息根本没发。
 * 这组用例把常见语序全钉死，防止再退回去。
 */
class SendMessageTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)

    private fun hit(text: String) = LocalNlu.route(text, now)

    private fun service(text: String) = hit(text)?.service
    private fun arg(text: String, key: String) = hit(text)?.args?.str(key)

    @Test
    fun `标准语序仍能正确拆出收件人平台和内容`() {
        val text = "给妈妈发微信说我晚点到"
        assertEquals("msg/send_text", service(text))
        assertEquals("妈妈", arg(text, "to"))
        assertEquals("wechat", arg(text, "platform"))
        assertEquals("我晚点到", arg(text, "content"))
    }

    @Test
    fun `换语序也要能识别`() {
        val cases = listOf(
            "发微信给妈妈说我晚点到",
            "帮我发微信给妈妈说我晚点到",
            "帮我给妈妈发条微信说我晚点到",
            "给妈妈发条消息：我晚点到",
            "用微信给妈妈发消息说我晚点到",
            "给妈妈发微信，说我晚点到",
            "给妈妈发微信内容是明天见"
        )
        cases.forEach { text ->
            assertEquals("应该识别：$text", "msg/send_text", service(text))
            assertEquals("收件人：$text", "妈妈", arg(text, "to"))
            assertEquals("内容不能为空：$text", true, !arg(text, "content").isNullOrEmpty())
        }
    }

    @Test
    fun `没有引导词时也要从发字后面剥出内容`() {
        val text = "给妈妈发微信我晚点到"
        assertEquals("msg/send_text", service(text))
        assertEquals("我晚点到", arg(text, "content"))
    }

    @Test
    fun `QQ 与平台别名`() {
        val qq = "给小明发QQ说明天见"
        assertEquals("msg/send_text", service(qq))
        assertEquals("qq", arg(qq, "platform"))
        assertEquals("小明", arg(qq, "to"))

        val qq2 = "发qq给小明说明天见"
        assertEquals("qq", arg(qq2, "platform"))
    }

    @Test
    fun `发消息排在时间类规则前面`() {
        // 内容里的「十分钟后」不能被当成定闹钟
        val text = "给妈妈发微信说十分钟后到"
        assertEquals("msg/send_text", service(text))
        assertEquals("十分钟后到", arg(text, "content"))
    }

    @Test
    fun `长内容不该因为超过 40 字被丢给模型`() {
        val long = "给妈妈发微信说我今天加班到很晚大概九点半才能到家你们先吃饭别等我了我路上会买点水果回去"
        assertEquals("msg/send_text", service(long))
    }

    @Test
    fun `没有内容仍然不认领`() {
        assertNull(service("给妈妈发个微信"))
        assertNull(service("给妈妈发条消息"))
    }

    @Test
    fun `不是发消息的句子别误伤`() {
        assertNull(service("给我发一下文件"))
        // 「打开微信」是打开应用，不是发消息——原有的正确行为别被抢走
        assertEquals("device/open_app", service("打开微信"))
        assertNull(service("今天天气怎么样"))
        assertNull(service("解释一下傅里叶变换"))
        // 「发」但既没平台词也没消息字样，且没有收件人语境
        assertNull(service("发工资了吗"))
    }

    @Test
    fun `报障要能问出原因`() {
        assertEquals("msg/query_can_send", service("为什么发不出消息"))
        assertEquals("msg/query_can_send", service("能发微信吗"))
        assertEquals("msg/query_can_send", service("检查一下发消息"))
        assertEquals("qq", arg("检查一下发QQ消息", "platform"))
    }

    @Test
    fun `小心音量等本机指令不被抢走`() {
        assertEquals("audio/tts_stop", service("别念了"))
        assertEquals("device/flashlight", service("打开手电筒"))
    }
}
