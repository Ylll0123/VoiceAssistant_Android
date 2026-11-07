package com.example.voiceassistant.ui

import kotlin.math.abs

/**
 * 点击定位光标的纯计算部分。
 *
 * Android 的 [android.text.Layout] 拿不到 JVM 单测里，所以这里只做
 * 「一行里哪个插入点离手指最近」这一步：调用方把该行每个插入点的 x 坐标
 * 量好传进来，这里返回行内序号。几何与业务分开，规则才好测。
 *
 * 为什么不用 `Layout.getOffsetForHorizontal`：它在 RTL、换行符、行尾空白
 * 这几种情况下会把手势归到上一行/下一行，点文字末尾时容易跳位。
 * 直接对候选插入点取最近值，行为可预期，也和用户在屏幕上看到的一致。
 */
object CursorMath {

    /**
     * @param positions 该行每个插入点的 x 坐标，长度 = 本行字符数 + 1，
     *                  最后一个位置是「行尾」（点在行末空白处应该落到这儿）
     * @param tapX 手指相对于文字区域左上角的 x
     * @return 行内第几个插入点；空数组返回 0
     */
    fun nearestIndex(positions: FloatArray, tapX: Float): Int {
        if (positions.isEmpty()) return 0
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in positions.indices) {
            val distance = abs(positions[i] - tapX)
            // 严格小于：距离相同时偏左，符合「点在两个字正中间插到前面」的直觉
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /** 把行内序号夹进整段文字的合法范围 */
    fun clamp(offset: Int, textLength: Int): Int = offset.coerceIn(0, textLength)
}
