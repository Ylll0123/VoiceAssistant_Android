package com.example.voiceassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 点击定位光标的纯几何部分 */
class CursorMathTest {

    @Test
    fun `点在哪个插入点附近就落到哪个`() {
        // 三个字，四个插入点：0, 10, 20, 30
        val positions = floatArrayOf(0f, 10f, 20f, 30f)
        assertEquals(0, CursorMath.nearestIndex(positions, 1f))
        assertEquals(1, CursorMath.nearestIndex(positions, 9f))
        assertEquals(2, CursorMath.nearestIndex(positions, 21f))
        assertEquals(3, CursorMath.nearestIndex(positions, 100f))
        assertEquals(0, CursorMath.nearestIndex(positions, -50f))
    }

    @Test
    fun `距离相同时偏左`() {
        val positions = floatArrayOf(0f, 10f)
        assertEquals(0, CursorMath.nearestIndex(positions, 5f))
    }

    @Test
    fun `空数组与越界钳制`() {
        assertEquals(0, CursorMath.nearestIndex(floatArrayOf(), 12f))
        assertEquals(0, CursorMath.clamp(-3, 10))
        assertEquals(10, CursorMath.clamp(99, 10))
        assertEquals(4, CursorMath.clamp(4, 10))
    }

    @Test
    fun `行尾空白要落到最后一个插入点`() {
        // 行尾补的那个位置比最后一个字更靠右，点空白处应该选中它
        val positions = floatArrayOf(0f, 10f, 20f, 120f)
        assertEquals(3, CursorMath.nearestIndex(positions, 110f))
    }
}
