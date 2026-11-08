package com.example.voiceassistant.service

import android.content.Context
import android.media.AudioManager
import android.util.Log

/**
 * 服务层：系统音量控制。
 *
 * ## 为什么带 stream
 * 「把声音调小」在用户嘴里是一个动作，在系统里是好几条独立的音量流：
 * 媒体、铃声、闹钟。调错了条，用户看到的是「我说了没用」——
 * 之前一律只动媒体音量，「把铃声调小」自然毫无反应。
 *
 * ## 为什么每个方法都返回 Boolean
 * `AudioManager` 的写入接口几乎没有返回值：设了没生效、被系统策略挡住、
 * 音量档位是固定的（[AudioManager.isVolumeFixed]），都不会抛异常。
 * 所以这里统一「写一次再读一次」，把真实结果交给上层去说人话。
 */
interface AudioController {

    /** 静音 / 取消静音；返回系统是否真的处于目标状态 */
    fun setMuted(muted: Boolean, stream: Int = AudioManager.STREAM_MUSIC): Boolean

    fun isMuted(stream: Int = AudioManager.STREAM_MUSIC): Boolean

    /** 调高 / 调低一档 */
    fun adjustVolume(up: Boolean, stream: Int = AudioManager.STREAM_MUSIC): Boolean

    /** 当前百分比（0-100） */
    fun volumePercent(stream: Int = AudioManager.STREAM_MUSIC): Int

    /** 设到百分比；返回是否真的到了（±2% 以内算到） */
    fun setVolumePercent(percent: Int, stream: Int = AudioManager.STREAM_MUSIC): Boolean

    /** 这台设备的音量是不是固定的（平板 / 电视盒子上很常见） */
    fun isVolumeFixed(): Boolean
}

/**
 * [AudioController] 的安卓实现。
 *
 * 用 [AudioManager.ADJUST_RAISE] / [AudioManager.ADJUST_LOWER] 走系统分档逻辑
 * （音量一共有多少档由设备决定），比直接算百分比更稳。
 */
class AndroidAudioController(context: Context) : AudioController {

    private val audio: AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun setMuted(muted: Boolean, stream: Int): Boolean {
        val target = muted
        val ok = runCatching {
            audio.adjustStreamVolume(
                stream,
                if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
            true
        }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "静音失败: stream=$stream")
            return false
        }
        return isMuted(stream) == target
    }

    override fun isMuted(stream: Int): Boolean =
        runCatching { audio.isStreamMute(stream) }.getOrDefault(volumePercent(stream) == 0)

    override fun adjustVolume(up: Boolean, stream: Int): Boolean {
        val before = runCatching { audio.getStreamVolume(stream) }.getOrDefault(-1)
        val ok = runCatching {
            audio.adjustStreamVolume(
                stream,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_PLAY_SOUND
            )
            true
        }.getOrDefault(false)
        if (!ok) return false
        // 已经在顶/底了也算成功（用户听到的是「已经最大了」）
        val after = runCatching { audio.getStreamVolume(stream) }.getOrDefault(-1)
        return after >= 0 && (before < 0 || after != before || atBound(stream))
    }

    override fun volumePercent(stream: Int): Int = runCatching {
        val max = audio.getStreamMaxVolume(stream)
        val now = audio.getStreamVolume(stream)
        if (max <= 0) 0 else (now * 100 / max).coerceIn(0, 100)
    }.getOrDefault(0)

    override fun setVolumePercent(percent: Int, stream: Int): Boolean {
        val wanted = percent.coerceIn(0, 100)
        val applied = runCatching {
            val max = audio.getStreamMaxVolume(stream)
            if (max <= 0) return false
            /*
             * 按**档位**核对，不按百分比。
             *
             * 音量一共就十几档（这台机器 15 档）：30% 只能落到第 4 档，也就是 26%。
             * 以前拿「百分比差 2% 以内」当成功条件，等于永远判失败——
             * 于是界面上一句「没调成，现在是 26%」，而音量其实已经调好了。
             */
            val target = (max * wanted / 100).coerceIn(0, max)
            audio.setStreamVolume(stream, target, AudioManager.FLAG_PLAY_SOUND)
            val now = audio.getStreamVolume(stream)
            now == target || (target == 0 && now <= 1)
        }.getOrDefault(false)
        return applied
    }

    override fun isVolumeFixed(): Boolean = runCatching { audio.isVolumeFixed }.getOrDefault(false)

    private fun atBound(stream: Int): Boolean = runCatching {
        val max = audio.getStreamMaxVolume(stream)
        val now = audio.getStreamVolume(stream)
        now == 0 || now == max
    }.getOrDefault(false)

    private companion object {
        const val TAG = "AudioController"
    }
}
