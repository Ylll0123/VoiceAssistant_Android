package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 云端答案缓存：同一个问题问第二次不再计费。
 *
 * 语音助手里重复提问非常常见（没听清再问一遍、换个标点重发），
 * 每一遍都重新付费是纯粹的浪费。
 */
class CloudCacheTest {

    @Test
    fun `同一个问题第二次直接命中缓存`() {
        CloudCache.clear()
        assertNull(CloudCache.get("量子纠缠是什么"))
        CloudCache.put("量子纠缠是什么", "量子纠缠是两个粒子的关联状态。")
        assertEquals("量子纠缠是两个粒子的关联状态。", CloudCache.get("量子纠缠是什么"))
    }

    @Test
    fun `标点 空格 大小写不同算同一个问题`() {
        CloudCache.clear()
        CloudCache.put("上海天气怎么样？", "上海今天 22℃。")
        assertEquals("上海今天 22℃。", CloudCache.get("上海天气怎么样"))
        assertEquals("上海今天 22℃。", CloudCache.get(" 上海天气怎么样。 "))
    }

    @Test
    fun `容量有上限，不会无限涨`() {
        CloudCache.clear()
        repeat(40) { CloudCache.put("问题$it", "答案$it") }
        assertEquals(24, CloudCache.size())
        // 最早那条已经被挤掉
        assertNull(CloudCache.get("问题0"))
        assertEquals("答案39", CloudCache.get("问题39"))
    }

    @Test
    fun `空答案不入缓存`() {
        CloudCache.clear()
        CloudCache.put("空白", "")
        assertNull(CloudCache.get("空白"))
    }

    @Test
    fun `清空后不再命中`() {
        CloudCache.clear()
        CloudCache.put("你好吗", "挺好的。")
        CloudCache.clear()
        assertNull(CloudCache.get("你好吗"))
        assertEquals(0, CloudCache.size())
    }

    @Test
    fun `不同的问题互不干扰`() {
        CloudCache.clear()
        CloudCache.put("A", "答案A")
        CloudCache.put("B", "答案B")
        assertNotEquals(CloudCache.get("A"), CloudCache.get("B"))
    }
}
