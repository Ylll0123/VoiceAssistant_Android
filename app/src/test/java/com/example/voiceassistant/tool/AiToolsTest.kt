package com.example.voiceassistant.tool

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.ServiceBus
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.data.JsonLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层测试：**不发请求、不碰手机**。
 *
 * 这里守三件事：
 * 1. 工具清单必须是合法 JSON —— 拼错一个括号，接口直接 400，整轮对话都断；
 * 2. 名字必须能翻成总线服务名 —— 翻错就是「模型调了、本机没动」；
 * 3. 参数的摊平与结果转换 —— 模型给的是 JSON 字符串，总线要的是扁平 Map。
 */
class AiToolsTest {

    // ------------------------------------------------------------ 工具清单

    private fun functions(): List<Any?> =
        (JsonLite.parseOrNull(AiTools.SCHEMA) as List<*>).map { JsonLite.get(JsonLite.obj(it), "function") }

    private fun toolNames(): List<String> =
        functions().mapNotNull { JsonLite.str(JsonLite.get(it, "name")) }

    @Test
    fun `工具清单是合法 JSON 数组`() {
        val root = JsonLite.parseOrNull(AiTools.SCHEMA)
        assertTrue("工具清单解析失败：${AiTools.SCHEMA.take(80)}", root != null)
        assertTrue("顶层必须是数组", root is List<*>)
        assertEquals(toolNames().size, (root as List<*>).size)
    }

    @Test
    fun `工具清单和总线服务一一对得上`() {
        assertEquals(
            listOf(
                "schedule_add_alarm", "schedule_add_todo", "schedule_query", "schedule_cancel",
                "device_flashlight", "device_brightness", "device_open_app", "device_battery",
                "device_wifi_ctrl", "device_bluetooth_ctrl",
                "audio_volume_ctrl", "audio_tts_stop",
                "tool_calc", "tool_time", "tool_translate", "tool_weather",
                "msg_send_text", "msg_confirm_send", "msg_query_can_send"
            ),
            toolNames()
        )
    }

    @Test
    fun `每个工具都有够长的描述和 object 参数`() {
        functions().forEach { function ->
            val name = JsonLite.str(JsonLite.get(function, "name")).orEmpty()
            // 描述是模型判断「该不该调」的唯一依据，空描述等于没有这个工具
            val desc = JsonLite.str(JsonLite.get(function, "description")).orEmpty()
            assertTrue("「$name」描述太短：$desc", desc.length >= 4)
            val parameters = JsonLite.get(function, "parameters")
            assertEquals("「$name」的参数必须是 object", "object", JsonLite.str(JsonLite.get(parameters, "type")))
        }
    }

    @Test
    fun `建提醒至少要内容，时间可以后补`() {
        val create = functions().first { JsonLite.str(JsonLite.get(it, "name")) == "schedule_add_alarm" }
        val required = JsonLite.arr(JsonLite.get(JsonLite.get(create, "parameters"), "required"))
            .mapNotNull { JsonLite.str(it) }
        // 「记一下要交作业」也得能落库，所以只强制 content
        assertEquals(listOf("content"), required)
    }

    @Test
    fun `发消息必须同时给收件人和内容`() {
        val send = functions().first { JsonLite.str(JsonLite.get(it, "name")) == "msg_send_text" }
        val required = JsonLite.arr(JsonLite.get(JsonLite.get(send, "parameters"), "required"))
            .mapNotNull { JsonLite.str(it) }
        assertEquals(listOf("to", "content"), required)
    }

    // ------------------------------------------------------------ 名字转换

    @Test
    fun `名字只换第一个下划线`() {
        assertEquals("device/flashlight", ServiceBus.toServiceName("device_flashlight"))
        // 动作名自带下划线，剩下的不能被切开
        assertEquals("kws/set_wake_word", ServiceBus.toServiceName("kws_set_wake_word"))
        assertEquals("schedule/add_alarm", ServiceBus.toServiceName("schedule_add_alarm"))
        // 没有下划线就原样返回
        assertEquals("nonsense", ServiceBus.toServiceName("nonsense"))
    }

    @Test
    fun `服务名转工具名把所有斜杠换掉`() {
        assertEquals("schedule_add_alarm", ServiceBus.toToolName("schedule/add_alarm"))
    }

    @Test
    fun `清单里的名字翻过来必须是总线格式`() {
        toolNames().forEach { name ->
            val service = ServiceBus.toServiceName(name)
            assertTrue("「$name」翻成了「$service」，不是 域/动作", ServiceBus.isServiceName(service))
        }
    }

    // ------------------------------------------------------------ 执行

    /** 记下最后一次调用参数的假供给方 */
    private class Recorder(private val reply: BusReply) : Supply {
        override val name = "device/flashlight"
        override val summary = "假的手电筒"
        var lastArgs: BusArgs? = null

        override fun invoke(args: BusArgs): BusReply {
            lastArgs = args
            return reply
        }
    }

    @Test
    fun `执行时把 JSON 参数摊平后交给总线`() {
        val supply = Recorder(BusReply.ok("手电筒打开了。"))
        val bus = ServiceBus().register(supply)
        val speech = AiTools(bus).execute(
            AiTools.Call("call-1", "device_flashlight", """{"action":"on","percent":50}""")
        )

        assertEquals("手电筒打开了。", speech)
        assertEquals("on", supply.lastArgs?.str("action"))
        // 50 不能变成 "50.0"，否则总线里的 toIntOrNull 还得额外兜
        assertEquals("50", supply.lastArgs?.str("percent"))
    }

    @Test
    fun `模型编了个不存在的工具时不崩`() {
        val speech = AiTools(ServiceBus()).execute(AiTools.Call("x", "device_teleport", "{}"))
        assertTrue(speech.contains("没有这个工具"))
    }

    @Test
    fun `供给方抛异常也转成一句人话`() {
        val bus = ServiceBus().register(object : Supply {
            override val name = "device/flashlight"
            override val summary = "会炸的手电筒"
            override fun invoke(args: BusArgs): BusReply = throw IllegalStateException("没有闪光灯")
        })
        val speech = AiTools(bus).execute(AiTools.Call("x", "device_flashlight", "{}"))
        assertTrue("异常没被兜住：$speech", speech.contains("没做成"))
    }

    @Test
    fun `认不认得看总线里有没有这个服务`() {
        val bus = ServiceBus().register(Recorder(BusReply.ok("ok")))
        val tools = AiTools(bus)
        assertTrue(tools.knows("device_flashlight"))
        assertFalse(tools.knows("device_flashlight_color"))
    }

    // ------------------------------------------------------------ 参数读取

    @Test
    fun `能取到字符串和数字参数`() {
        val args = """{"content":"交作业","minutes":15,"repeat":"daily"}"""
        assertEquals("交作业", JsonArgs.str(args, "content"))
        assertEquals(15.0, JsonArgs.num(args, "minutes")!!, 0.001)
        assertEquals("daily", JsonArgs.str(args, "repeat"))
        assertNull(JsonArgs.str(args, "at"))
    }

    @Test
    fun `参数是坏 JSON 时不抛异常`() {
        assertNull(JsonArgs.str("{content:", "content"))
        assertNull(JsonArgs.str(null, "content"))
        assertNull(JsonArgs.num("[]", "minutes"))
    }

    @Test
    fun `摊平时丢掉空值和嵌套结构`() {
        val flat = JsonArgs.flatMap("""{"a":"x","b":"  ","c":null,"d":{"k":1},"e":[1],"f":true}""")
        assertEquals(mapOf("a" to "x", "f" to "true"), flat)
    }
}
