package com.example.voiceassistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多轮对话与新技能的引擎级测试。
 *
 * 这里的用例都走完整的「归一化 → 规则匹配 → 写回上下文」链路，
 * 能覆盖单个规则测试覆盖不到的顺序问题（比如谁先抢到这句话）。
 */
class MultiTurnTest {

    @Test
    fun `日期追问可以省略主语`() {
        val engine = AssistantEngine()
        engine.reply("今天几号")

        val reply = engine.reply("那后天呢")
        assertTrue("应该接着回答日期，实际：$reply", reply.contains("后天是"))
    }

    @Test
    fun `连续计算沿用上一次结果`() {
        val engine = AssistantEngine()
        engine.reply("帮我算 12*8")

        val reply = engine.reply("再加 5")
        assertTrue("应该在上次结果 96 上继续加，实际：$reply", reply.contains("101"))
    }

    @Test
    fun `笑话可以再来一个`() {
        val engine = AssistantEngine()
        engine.reply("讲个笑话")

        val reply = engine.reply("再来一个")
        assertNotEquals(AssistantEngine.FALLBACK, reply)
    }

    @Test
    fun `识别把猜数字听错一个字也能进入游戏`() {
        val engine = AssistantEngine()
        val reply = engine.reply("我们玩猜数子")
        assertTrue("容错后应该进入游戏，实际：$reply", reply.contains("1 到 100"))
    }

    @Test
    fun `猜数字可以多轮进行直到猜对`() {
        val engine = AssistantEngine()
        assertTrue(engine.reply("我们玩猜数字").contains("1 到 100"))

        var low = 1
        var high = 100
        var guess = 50
        var reply = ""

        repeat(8) {
            reply = engine.reply(guess.toString())
            when {
                reply.contains("猜对了") -> return
                reply.contains("小了") -> low = guess + 1
                else -> high = guess - 1
            }
            guess = (low + high) / 2
        }
        assertTrue("8 次二分内应该猜中，实际：$reply", reply.contains("猜对了"))
    }

    @Test
    fun `单位换算`() {
        val engine = AssistantEngine()
        val km = engine.reply("10 公里是多少英里")
        assertTrue("实际：$km", km.contains("英里"))
        assertTrue(engine.reply("30 摄氏度是多少华氏度").contains("86"))
    }

    @Test
    fun `世界时间`() {
        val engine = AssistantEngine()
        val reply = engine.reply("纽约现在几点")
        assertTrue("应该报出纽约时间，实际：$reply", reply.contains("纽约"))
        assertTrue(reply.contains("本地时间"))
    }

    @Test
    fun `日期推算算出下周几是几号`() {
        val engine = AssistantEngine()
        val reply = engine.reply("下周三几号")
        assertTrue("应该给出日期和星期，实际：$reply", reply.contains("月") && reply.contains("星期"))
    }

    @Test
    fun `距离某个日子还有几天`() {
        val engine = AssistantEngine()
        val reply = engine.reply("还有几天到10月31日")
        assertTrue("应该给出剩余天数，实际：$reply", reply.contains("还有") && reply.contains("天"))
    }

    @Test
    fun `年龄计算`() {
        val engine = AssistantEngine()
        assertTrue(engine.reply("2000年出生今年多大").contains("岁"))
    }

    @Test
    fun `上下文过期后追问退化到兜底而不是乱答`() {
        val engine = AssistantEngine()
        engine.reply("现在几点")
        engine.resetContext()

        assertEquals(AssistantEngine.FALLBACK, engine.reply("那后天呢"))
    }

    @Test
    fun `世界时间可以追问换城市`() {
        val engine = AssistantEngine()
        assertTrue(engine.reply("纽约现在几点").contains("纽约"))

        val reply = engine.reply("那东京呢")
        assertTrue("应该接着报东京时间，实际：$reply", reply.contains("东京"))
    }

    @Test
    fun `单位换算可以追问换数值`() {
        val engine = AssistantEngine()
        assertTrue(engine.reply("10 公里是多少英里").contains("英里"))

        val reply = engine.reply("那 20 公里呢")
        assertTrue("应该按上次的换算方向换算 20 公里，实际：$reply", reply.contains("12.4"))
    }

    @Test
    fun `完整的换算问句不会被追问规则劫持`() {
        val engine = AssistantEngine()
        engine.reply("10 公里是多少英里")

        val reply = engine.reply("30 摄氏度是多少华氏度")
        assertTrue("应该正常算华氏度，实际：$reply", reply.contains("86"))
    }

    @Test
    fun `同义词也能落到同一意图`() {
        val engine = AssistantEngine()
        assertNotEquals(AssistantEngine.FALLBACK, engine.reply("讲个段子"))

        val weekday = engine.reply("今天礼拜几")
        assertTrue("「礼拜几」应归一成「星期几」，实际：$weekday", weekday.contains("星期"))
    }

    @Test
    fun `纠错说法会按后半句重新理解`() {
        val engine = AssistantEngine()
        val reply = engine.reply("不对，我是说讲个笑话")
        assertTrue("应该讲笑话，实际：$reply", reply.length > 4 && reply != AssistantEngine.FALLBACK)
    }
}
