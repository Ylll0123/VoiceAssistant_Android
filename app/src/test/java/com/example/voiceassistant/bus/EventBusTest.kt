package com.example.voiceassistant.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件总线的测试：**只测分发语义**，不测业务。
 *
 * 一根总线要守住四条底线：
 * ① 订阅什么就收什么（类型不串台）；
 * ② 取消订阅、按页面注销之后不再收到；
 * ③ 一个订阅者崩了不能连累别人；
 * ④ 没人订阅时发布不能报错。
 */
class EventBusTest {

    private data class Ping(val n: Int) : Event
    private data class Pong(val n: Int) : Event
    private data class Bang(val n: Int) : Event

    @Test
    fun `订阅者能收到同类事件`() {
        val bus = EventBus()
        val got = mutableListOf<Int>()
        bus.on<Ping> { got += it.n }

        bus.publish(Ping(1))
        bus.publish(Ping(2))

        assertEquals(listOf(1, 2), got)
    }

    @Test
    fun `不同类型的事件不会串台`() {
        val bus = EventBus()
        var pings = 0
        var pongs = 0
        bus.on<Ping> { pings++ }
        bus.on<Pong> { pongs++ }

        bus.publish(Ping(1))

        assertEquals(1, pings)
        assertEquals(0, pongs)
    }

    @Test
    fun `取消订阅之后不再收到`() {
        val bus = EventBus()
        var count = 0
        val subscription = bus.on<Ping> { count++ }

        bus.publish(Ping(1))
        subscription.cancel()
        bus.publish(Ping(2))

        assertEquals(1, count)
        assertFalse(subscription.active)
    }

    @Test
    fun `按 owner 注销能一次清干净`() {
        val bus = EventBus()
        val page = Any()
        var count = 0
        bus.on<Ping>(page) { count++ }
        bus.on<Pong>(page) { count++ }
        bus.on<Ping>(Any()) { count += 100 }

        bus.off(page)
        bus.publish(Ping(1))
        bus.publish(Pong(1))

        assertEquals("只剩另一个 owner 的那条订阅", 100, count)
        assertEquals(1, bus.size)
    }

    @Test
    fun `一个订阅者抛异常不影响其它订阅者`() {
        val bus = EventBus()
        val errors = mutableListOf<Throwable>()
        bus.onError = { errors += it }
        var delivered = 0
        bus.on<Ping> { throw IllegalStateException("炸了") }
        bus.on<Ping> { delivered++ }

        val count = bus.publish(Ping(1))

        assertEquals(2, count)
        assertEquals("后一个订阅者照常收到", 1, delivered)
        assertEquals(1, errors.size)
    }

    @Test
    fun `没有订阅者时发布不报错`() {
        val bus = EventBus()
        assertEquals(0, bus.publish(Bang(1)))
    }

    @Test
    fun `发布过程中取消订阅不会崩`() {
        val bus = EventBus()
        var late = 0
        lateinit var subscription: EventBus.Subscription
        subscription = bus.on<Ping> { subscription.cancel() }
        bus.on<Ping> { late++ }

        bus.publish(Ping(1))

        assertEquals(1, late)
        assertTrue("自己把自己注销掉之后总线仍然可用", bus.size == 1)
    }
}
