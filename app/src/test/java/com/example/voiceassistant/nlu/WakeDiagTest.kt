package com.example.voiceassistant.nlu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 唤醒报障的口语入口。
 *
 * 唤醒只能由用户在设置里手动开，出问题时没别的入口能看状态，
 * 所以「检查一下唤醒」「小豆小豆没反应」必须能在本地就诊断为 `kws/status`——
 * 断网时也一样（本地指令本来就不依赖网络）。
 */
class WakeDiagTest {

    @Test
    fun `报障说法走诊断`() {
        assertEquals("kws/status", LocalNlu.route("检查一下语音唤醒")?.service)
        assertEquals("kws/status", LocalNlu.route("小豆小豆没反应")?.service)
        assertEquals("kws/status", LocalNlu.route("唤醒用不了")?.service)
        assertEquals("kws/status", LocalNlu.route("怎么开启唤醒")?.service)
        assertEquals("kws/status", LocalNlu.route("语音唤醒怎么不管用")?.service)
    }

    @Test
    fun `普通指令不会被唤醒诊断抢走`() {
        assertEquals("device/flashlight", LocalNlu.route("打开手电筒")?.service)
        assertEquals("device/brightness", LocalNlu.route("把亮度调暗一点")?.service)
        assertEquals("audio/volume_ctrl", LocalNlu.route("音量调到 30%")?.service)
        // 提到「小豆」但不是在报障：不该认领，交给模型理解
        assertNull(LocalNlu.route("小豆是谁"))
    }
}
