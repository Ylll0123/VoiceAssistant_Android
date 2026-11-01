package com.example.voiceassistant.handler.qa

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.AssistantResponse
import com.example.voiceassistant.assistant.ReplyCrafter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地问答处理器的测试。
 *
 * 核心是验证「可编辑问答表（assets/qa_lib.json）优先级最高」：
 * 用户亲手维护的内容，必须盖过任何内置逻辑。
 */
class QaLocalHandlerTest {

    @Test
    fun `JSON 问答表能解析出来`() {
        val lib = QaLib.fromJson(
            """{"qa":[{"q":"实验室wifi密码","a":"问辅导员"},{"q":"门禁","a":"刷校园卡"}]}"""
        )
        assertEquals(2, lib.size())
        assertEquals("问辅导员", lib.match("实验室wifi密码"))
        assertEquals("刷校园卡", lib.match("门禁"))
    }

    @Test
    fun `坏数据只跳过不崩溃`() {
        val lib = QaLib.fromJson(
            """{"qa":[{"q":"只有问题"},{"a":"只有答案"},null,{"q":"  ","a":"空"},{"q":"正常","a":"答案"}]}"""
        )
        assertEquals(1, lib.size())
        assertEquals("答案", lib.match("正常"))
    }

    @Test
    fun `格式不对时退化成空表而不是抛异常`() {
        assertEquals(0, QaLib.fromJson("这不是 JSON").size())
        assertEquals(0, QaLib.fromJson("{}").size())
        assertEquals(0, QaLib.empty().size())
        assertNull(QaLib.empty().match("随便"))
    }

    @Test
    fun `标点空格不影响命中`() {
        val lib = QaLib.fromJson("""{"qa":[{"q":"上海天气","a":"晴"}]}""")
        assertEquals("晴", lib.match("上海  天气！"))
    }

    @Test
    fun `问答表命中时优先于引擎`() {
        val lib = QaLib.fromJson("""{"qa":[{"q":"苹果","a":"这是你自己写的答案"}]}""")
        val handler = QaLocalHandler(AssistantEngine(), lib)
        val reply = handler.answer(listOf("苹果"))
        assertEquals("这是你自己写的答案", reply.text)
        assertEquals(QaLocalHandler.INTENT_QA_LIB, reply.intent)
    }

    @Test
    fun `多种答法用竖线分隔时逐条轮换`() {
        val lib = QaLib.fromJson("""{"qa":[{"q":"苹果","a":"红的|青的|不告诉你"}]}""")
        val seen = (1..3).map { lib.match("苹果") }
        assertEquals(listOf("红的", "青的", "不告诉你"), seen)
        // 转一圈回到第一句，而不是卡在最后一条
        assertEquals("红的", lib.match("苹果"))
    }

    @Test
    fun `全角竖线和多余空格也能拆开`() {
        val lib = QaLib.fromJson("""{"qa":[{"q":"门禁","a":"刷校园卡 ｜ 找保安"}]}""")
        assertEquals("刷校园卡", lib.match("门禁"))
        assertEquals("找保安", lib.match("门禁"))
    }

    @Test
    fun `只有一种答法时不会多出竖线`() {
        val lib = QaLib.fromJson("""{"qa":[{"q":"门禁","a":"刷校园卡"}]}""")
        assertEquals("刷校园卡", lib.match("门禁"))
        assertEquals("刷校园卡", lib.match("门禁"))
    }
}
