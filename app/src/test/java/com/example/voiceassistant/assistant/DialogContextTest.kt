package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多轮上下文的状态机：写入、读取、过期 */
class DialogContextTest {

    private var now = 0L

    private fun context(timeoutMs: Long = 1000L) = DialogContext(timeoutMs) { now }

    @Test
    fun `记住之后可以读到意图和槽位`() {
        val context = context()
        context.remember("arithmetic", mapOf("value" to "96"))

        assertTrue(context.isAlive())
        assertTrue(context.hasIntent("arithmetic"))
        assertEquals("96", context.slot("value"))
    }

    @Test
    fun `超过有效期后上下文失效`() {
        val context = context()
        context.remember("date")
        now = 1500L

        assertFalse(context.isAlive())
        assertNull(context.slot("value"))
    }

    @Test
    fun `过期后重新记住会清掉旧槽位`() {
        val context = context()
        context.remember("arithmetic", mapOf("value" to "96"))
        now = 5000L
        context.remember("joke")

        assertTrue(context.hasIntent("joke"))
        assertNull("旧槽位不应残留", context.slot("value"))
    }

    @Test
    fun `剩余时间会随时间递减`() {
        val context = context()
        context.remember("date")
        now = 400L

        assertEquals(600L, context.remainingMs())
    }

    @Test
    fun `清空后不再命中任何意图`() {
        val context = context()
        context.remember("guess", mapOf("target" to "42"))
        context.clear()

        assertFalse(context.isAlive())
        assertFalse(context.hasIntent("guess"))
    }
}
