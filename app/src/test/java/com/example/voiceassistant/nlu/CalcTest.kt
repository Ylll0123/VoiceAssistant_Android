package com.example.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地计算器测试。
 *
 * 这是「本地优先」里最不能出错的一块：算错了比不算更糟，
 * 因为用户会直接照着结果干活。所以宁可返回 null（交回大模型），也不能给出错的数。
 */
class CalcTest {

    private fun ev(expr: String): String? = Calc.evaluate(expr)?.let { Calc.format(it) }

    // ------------------------------------------------------------ 求值

    @Test
    fun `四则运算优先级正确`() {
        assertEquals("96", ev("12*8"))
        assertEquals("14", ev("2+3*4"))
        assertEquals("20", ev("(2+3)*4"))
        assertEquals("1", ev("7-2*3"))
        assertEquals("4", ev("10/2-1"))
    }

    @Test
    fun `小数和负数都对`() {
        assertEquals("2.5", ev("5/2"))
        assertEquals("4", ev("-6+10"))
        assertEquals("-6", ev("-2*3"))
        // 0.1+0.2 的浮点误差要被抹掉
        assertEquals("0.3", ev("0.1+0.2"))
    }

    @Test
    fun `乘方右结合`() {
        assertEquals("512", ev("2^3^2"))
        assertEquals("8", ev("2^3"))
    }

    @Test
    fun `除零算不出来`() {
        assertNull(Calc.evaluate("1/0"))
        assertNull(Calc.evaluate("5%0"))
    }

    @Test
    fun `语法错一律返回空，不猜`() {
        assertNull(Calc.evaluate("1+"))
        assertNull(Calc.evaluate("(1+2"))
        assertNull(Calc.evaluate("*5"))
        assertNull(Calc.evaluate(""))
        // 空格是允许的（「12 * 8」要说得出），去掉后照常求值
        assertEquals("96", ev("12 * 8"))
    }

    @Test
    fun `非法字符进不了求值`() {
        assertNull(Calc.evaluate("alert(1)"))
        assertNull(Calc.evaluate("12*a"))
    }

    // ------------------------------------------------------------ 中文说法

    @Test
    fun `中文运算符能翻译`() {
        assertEquals("12*8", Calc.normalize("12乘8"))
        assertEquals("12/4", Calc.normalize("12除以4"))
        assertEquals("3+5", Calc.normalize("3加5"))
        assertEquals("9-4", Calc.normalize("9减4"))
        assertEquals("2^3", Calc.normalize("2的3次方"))
    }

    @Test
    fun `除以要排在除前面`() {
        // 顺序写错会得到「12除/4」
        assertEquals("12/4", Calc.normalize("12除以4"))
    }

    // ------------------------------------------------------------ 该不该认领

    @Test
    fun `纯算式会被认领`() {
        assertTrue(Calc.looksLikeMath("12乘8"))
        assertTrue(Calc.looksLikeMath("帮我算12乘8"))
        assertTrue(Calc.looksLikeMath("12乘8等于几"))
        assertTrue(Calc.looksLikeMath("(3+5)/2是多少"))
    }

    @Test
    fun `不是算式就不认领`() {
        // 单个数字不是计算题
        assertFalse(Calc.looksLikeMath("12"))
        // 句子里的数字不是计算题
        assertFalse(Calc.looksLikeMath("今天作业写完了"))
        assertFalse(Calc.looksLikeMath("1个苹果多少钱"))
        // 有运算符但算不出来，也不能认领
        assertFalse(Calc.looksLikeMath("1+"))
    }

    // ------------------------------------------------------------ 格式化

    @Test
    fun `整数不带小数点`() {
        assertEquals("96", Calc.format(96.0))
        assertEquals("0", Calc.format(-0.0))
    }

    @Test
    fun `小数最多留四位且去掉末尾零`() {
        assertEquals("3.1416", Calc.format(3.14159))
        assertEquals("0.5", Calc.format(0.5))
    }
}
