package com.example.voiceassistant.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 供需总线测试。
 *
 * 总线是「本地」和「大模型」两条入口的公共出口，它一旦出错，
 * 表现是「两边都以为没事、其实谁也没动」。所以这里重点守**失败路径**。
 */
class ServiceBusTest {

    private lateinit var bus: ServiceBus

    @Before
    fun setUp() {
        bus = ServiceBus()
    }

    private fun supply(name: String, reply: BusReply = BusReply.ok("好了")): Supply =
        object : Supply {
            override val name = name
            override val summary = "测试用"
            override fun invoke(args: BusArgs): BusReply = reply
        }

    // ------------------------------------------------------------ 注册与查找

    @Test
    fun `注册后能查到，顺序保持登记顺序`() {
        bus.register(supply("device/flashlight"))
        bus.register(supply("audio/volume_ctrl"))

        assertTrue(bus.has("device/flashlight"))
        assertEquals(listOf("device/flashlight", "audio/volume_ctrl"), bus.names)
        assertNull(bus.get("device/teleport"))
    }

    @Test
    fun `同名注册会覆盖，方便热替换`() {
        bus.register(supply("tool/calc", BusReply.ok("旧")))
        bus.register(supply("tool/calc", BusReply.ok("新")))

        assertEquals(1, bus.names.size)
        assertEquals("新", bus.call("tool/calc").speech)
    }

    @Test
    fun `批量注册`() {
        bus.registerAll(supply("a/b"), supply("a/c"), supply("d/e"))
        assertEquals(3, bus.names.size)
    }

    // ------------------------------------------------------------ 调用

    @Test
    fun `调用会把参数原样交给供给方`() {
        var seen: BusArgs? = null
        bus.register(object : Supply {
            override val name = "device/brightness"
            override val summary = "亮度"
            override fun invoke(args: BusArgs): BusReply {
                seen = args
                return BusReply.ok("调好了")
            }
        })

        val reply = bus.call("device/brightness", BusArgs.of("percent" to "60"))

        assertTrue(reply.ok)
        assertEquals("60", seen?.int("percent")?.toString())
    }

    @Test
    fun `没有这个服务时返回失败而不是抛异常`() {
        val reply = bus.call("device/teleport")
        assertFalse(reply.ok)
        assertTrue(reply.speech.contains("没有"))
    }

    @Test
    fun `供给方抛异常会被包成失败回执`() {
        bus.register(object : Supply {
            override val name = "device/flashlight"
            override val summary = "会炸"
            override fun invoke(args: BusArgs): BusReply = throw IllegalStateException("没有闪光灯")
        })

        val reply = bus.call("device/flashlight")
        assertFalse(reply.ok)
        // 原因要带出来，否则界面上只能显示「失败了」，用户不知道该怎么办
        assertTrue(reply.speech.contains("没有闪光灯"))
    }

    @Test
    fun `清空注册表`() {
        bus.register(supply("a/b"))
        bus.clear()
        assertTrue(bus.names.isEmpty())
    }

    // ------------------------------------------------------------ 名字约定

    @Test
    fun `服务名格式判断`() {
        assertTrue(ServiceBus.isServiceName("schedule/add_alarm"))
        assertFalse(ServiceBus.isServiceName("add_alarm"))
        assertFalse(ServiceBus.isServiceName("schedule/add/alarm"))
        assertFalse(ServiceBus.isServiceName("/add"))
        assertFalse(ServiceBus.isServiceName("schedule/"))
    }

    @Test
    fun `两个方向的名字转换互逆`() {
        assertEquals("schedule_add_alarm", ServiceBus.toToolName("schedule/add_alarm"))
        assertEquals("schedule/add_alarm", ServiceBus.toServiceName("schedule_add_alarm"))
        // 动作名自带的第二、第三个下划线要留住
        assertEquals("kws/set_wake_word", ServiceBus.toServiceName("kws_set_wake_word"))
    }

    // ------------------------------------------------------------ 参数与回执

    @Test
    fun `参数的类型转换都容忍脏值`() {
        val args = BusArgs.of(
            "percent" to "60", "on" to "开", "empty" to "  ", "bad" to "六十"
        )
        assertEquals(60, args.int("percent"))
        assertEquals(60.0, args.num("percent")!!, 0.001)
        assertTrue(args.bool("on"))
        // 空串按「没给」处理
        assertNull(args.str("empty"))
        // 给了但不是数字也算没给
        assertNull(args.num("bad"))
        // 取不到时用默认值
        assertFalse(args.bool("missing"))
        assertTrue(args.bool("missing", default = true))
    }

    @Test
    fun `确认类回执带上 trace_id`() {
        val reply = BusReply.confirm("给「妈妈」发：我晚点到", "t-1", "to" to "妈妈")
        assertTrue(reply.ok)
        assertEquals("true", reply.data["need_confirm"])
        assertEquals("t-1", reply.data["trace_id"])
        assertEquals("妈妈", reply.data["to"])
    }
}
