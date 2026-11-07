package com.example.voiceassistant.tool

import com.example.voiceassistant.data.JsonLite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 工具层的纯函数测试：**不发请求、不碰手机**。
 *
 * 这里守两件事：
 * 1. 工具清单必须是合法 JSON —— 拼错一个括号，接口直接 400，整轮对话都断；
 * 2. 模型给的时间字符串要能正确落地 —— 解析错就会把提醒定到明年去。
 */
class AiToolsTest {

    /** 固定基准时间，避免单测跟真实时钟赛跑：2026-11-03 10:00 周二 */
    private val reference: LocalDateTime = LocalDateTime.of(2026, 11, 3, 10, 0)

    private fun toLocal(millis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())

    // ------------------------------------------------------------ 工具清单

    @Test
    fun `工具清单是合法 JSON 数组`() {
        val root = JsonLite.parseOrNull(AiTools.SCHEMA)
        assertTrue("工具清单解析失败：${AiTools.SCHEMA.take(80)}", root != null)
        assertTrue("顶层必须是数组", root is List<*>)
        assertEquals(6, (root as List<*>).size)
    }

    @Test
    fun `六个工具都在，名字不能拼错`() {
        val names = toolNames()
        assertEquals(
            listOf(
                "create_reminder", "list_reminders", "cancel_reminder",
                "set_timer", "device", "stop_speaking"
            ),
            names
        )
    }

    @Test
    fun `每个工具都有描述和参数结构`() {
        val root = JsonLite.parseOrNull(AiTools.SCHEMA) as List<*>
        root.forEach { element ->
            val function = JsonLite.get(JsonLite.obj(element), "function")
            val name = JsonLite.str(JsonLite.get(function, "name")).orEmpty()
            // 描述是模型判断「该不该调」的唯一依据，空描述等于没有这个工具
            assertTrue("「$name」缺描述", JsonLite.str(JsonLite.get(function, "description")).orEmpty().length > 10)
            val parameters = JsonLite.get(function, "parameters")
            assertEquals("「$name」的参数必须是 object", "object", JsonLite.str(JsonLite.get(parameters, "type")))
        }
    }

    @Test
    fun `建提醒必须同时要内容和时间`() {
        val create = (JsonLite.parseOrNull(AiTools.SCHEMA) as List<*>)
            .map { JsonLite.get(JsonLite.obj(it), "function") }
            .first { JsonLite.str(JsonLite.get(it, "name")) == "create_reminder" }
        val required = JsonLite.arr(JsonLite.get(JsonLite.get(create, "parameters"), "required"))
            .mapNotNull { JsonLite.str(it) }
        assertEquals(listOf("content", "time"), required)
    }

    private fun toolNames(): List<String> =
        (JsonLite.parseOrNull(AiTools.SCHEMA) as List<*>).mapNotNull {
            JsonLite.str(JsonLite.get(JsonLite.get(JsonLite.obj(it), "function"), "name"))
        }

    // ------------------------------------------------------------ 时间解析

    @Test
    fun `完整日期时间直接采用`() {
        val at = AiTools.parseTime("2026-11-05 08:30", reference)!!
        assertEquals(LocalDateTime.of(2026, 11, 5, 8, 30), toLocal(at))
    }

    @Test
    fun `带秒的写法也认`() {
        val at = AiTools.parseTime("2026-11-05 08:30:15", reference)!!
        assertEquals(LocalDateTime.of(2026, 11, 5, 8, 30, 15), toLocal(at))
    }

    @Test
    fun `只有日期时按当天九点`() {
        val at = AiTools.parseTime("2026-11-05", reference)!!
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), toLocal(at))
    }

    @Test
    fun `只有时分且还没到点就是今天`() {
        val at = AiTools.parseTime("18:00", reference)!!
        assertEquals(LocalDateTime.of(2026, 11, 3, 18, 0), toLocal(at))
    }

    @Test
    fun `只有时分但已经过了就顺延到明天`() {
        // 用户下午两点说「九点提醒我」，指的是明天早上九点
        val at = AiTools.parseTime("09:00", reference)!!
        assertEquals(LocalDateTime.of(2026, 11, 4, 9, 0), toLocal(at))
    }

    @Test
    fun `看不懂的时间返回空，由模型再问一遍`() {
        assertNull(AiTools.parseTime("明天早上", reference))
        assertNull(AiTools.parseTime("八点", reference))
        assertNull(AiTools.parseTime("", reference))
        assertNull(AiTools.parseTime(null, reference))
    }

    // ------------------------------------------------------------ 参数读取

    @Test
    fun `能取到字符串和数字参数`() {
        val args = """{"content":"交作业","minutes":15,"repeat":"daily"}"""
        assertEquals("交作业", JsonArgs.str(args, "content"))
        assertEquals(15.0, JsonArgs.num(args, "minutes")!!, 0.001)
        assertEquals("daily", JsonArgs.str(args, "repeat"))
        assertNull(JsonArgs.str(args, "time"))
    }

    @Test
    fun `参数是坏 JSON 时不抛异常`() {
        assertNull(JsonArgs.str("{content:", "content"))
        assertNull(JsonArgs.str(null, "content"))
        assertNull(JsonArgs.num("[]", "minutes"))
    }
}
