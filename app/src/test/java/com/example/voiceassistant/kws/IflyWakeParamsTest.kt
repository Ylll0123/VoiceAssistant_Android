package com.example.voiceassistant.kws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 讯飞唤醒的**纯逻辑**测试。
 *
 * 真引擎要 appid、要 arm 手机、要控制台训好的资源，这三样都不在 CI 里，
 * 能测的就是「参数拼得对不对」和「错误码翻译成人话后，用户知不知道该干嘛」。
 * 这两件事恰恰是最容易写错的：路径少一个转义、阈值忘了夹紧，
 * 到了手机上就是一句「引擎没起来」，谁也看不出为什么。
 */
class IflyWakeParamsTest {

    private fun params(
        appId: String = "5f1a2b3c",
        key: String = "key-123",
        secret: String = "sec-456",
        resPath: String = "/data/user/0/com.example.voiceassistant/files/ifly/ivw/vtn/res.bin",
        threshold: Int = 1450
    ) = IflyWakeEngine.buildParams(appId, key, secret, resPath, threshold)

    @Test
    fun `登录三件套原样进参数`() {
        val json = params()
        assertTrue(json.contains("\"appid\": \"5f1a2b3c\""))
        assertTrue(json.contains("\"key\": \"key-123\""))
        assertTrue(json.contains("\"api_secret\": \"sec-456\""))
    }

    @Test
    fun `唤醒资源走 path 且是绝对路径`() {
        val json = params()
        assertTrue(json.contains("\"res_type\": \"path\""))
        assertTrue(json.contains("/data/user/0/com.example.voiceassistant/files/ifly/ivw/vtn/res.bin"))
    }

    @Test
    fun `持续唤醒开着`() {
        // 关掉 wakeup_mode 就只剩「点一下才听」，这不是唤醒词该有的样子
        assertTrue(params().contains("\"wakeup_mode\": \"on\""))
    }

    @Test
    fun `阈值写进 ivw 节点`() {
        assertTrue(params(threshold = 1100).contains("\"ivw_threshold\": \"1100\""))
    }

    @Test
    fun `阈值超出范围会被夹紧`() {
        assertEquals(true, params(threshold = 9999).contains("\"ivw_threshold\": \"3000\""))
        assertEquals(true, params(threshold = -20).contains("\"ivw_threshold\": \"0\""))
    }

    @Test
    fun `路径里的引号与反斜杠会被转义`() {
        val json = params(resPath = "/tmp/a\"b\\c/res.bin")
        // 原样塞进去会生成非法 JSON，引擎只会回一句「参数错误」
        assertFalse(json.contains("/tmp/a\"b"))
        assertTrue(json.contains("\\\""))
    }

    @Test
    fun `没开通离线唤醒的错误要被当成致命`() {
        // 11209 = 没开通；11210 = 资源和 appid 不是一套。这两种只能去控制台解决
        assertTrue(IflyWakeEngine.isFatal(11209))
        assertTrue(IflyWakeEngine.isFatal(11210))
        assertTrue(IflyWakeEngine.isFatal(IflyWakeEngine.ERR_UNSUPPORTED))
    }

    @Test
    fun `麦克风被占这种偶发错误不该直接放弃`() {
        assertFalse(IflyWakeEngine.isFatal(20006))
        assertFalse(IflyWakeEngine.isFatal(0))
    }

    @Test
    fun `错误码要翻成能照着做的建议`() {
        assertTrue(IflyWakeEngine.describeError(11209).contains("离线唤醒"))
        assertTrue(IflyWakeEngine.describeError(11210).contains("appid"))
        assertTrue(IflyWakeEngine.describeError(20001).contains("联网"))
        assertTrue(IflyWakeEngine.describeError(99999).contains("99999"))
    }
}
