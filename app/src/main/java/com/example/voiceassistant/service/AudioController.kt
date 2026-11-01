package com.example.voiceassistant.service

import android.content.Context
import android.media.AudioManager

/** 服务层：系统音量控制 */
interface AudioController {
    /** 静音 / 取消静音 */
    fun setMuted(muted: Boolean)

    /** 是否处于静音 */
    fun isMuted(): Boolean

    /** 调高 / 调低一档音量 */
    fun adjustVolume(up: Boolean)
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

    override fun setMuted(muted: Boolean) {
        runCatching {
            if (muted) {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_MUTE,
                    0
                )
            } else {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
                )
            }
        }
    }

    override fun isMuted(): Boolean = runCatching {
        audio.isStreamMute(AudioManager.STREAM_MUSIC)
    }.getOrDefault(false)

    override fun adjustVolume(up: Boolean) {
        runCatching {
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_PLAY_SOUND
            )
        }
    }
}
