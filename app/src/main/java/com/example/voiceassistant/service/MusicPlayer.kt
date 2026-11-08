package com.example.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/**
 * 服务层：**放歌**。
 *
 * 我们的应用不是播放器，也拿不到别人的播放队列，所以只能做两件事：
 * 1. 把本机的音乐应用拉到前台（这是「打开某个音乐 App」）；
 * 2. 给它派发一个媒体键（播放 / 暂停 / 上一首 / 下一首）。
 *
 * 媒体键是系统级的：只要有应用持有音频焦点，派发过去就生效，
 * 不需要任何权限，也不用管对方是哪一家。
 *
 * ## 为什么还要等一会儿
 * 刚 launch 的应用还没建好媒体会话，这时候发 KEYCODE_MEDIA_PLAY 会被丢掉。
 * 所以打开后等一小会儿再发，发完再问一句 [AudioManager.isMusicActive]——
 * 真的在响了才说「正在放」，否则如实说「打开了，你点一下播放」。
 */
class MusicPlayer(context: Context) {

    enum class Action {
        PLAY, PAUSE, NEXT, PREVIOUS;

        companion object {
            fun of(raw: String?): Action? = when (raw?.trim()?.lowercase()) {
                "play", "start", "播放" -> PLAY
                "pause", "stop", "暂停" -> PAUSE
                "next", "下一首" -> NEXT
                "previous", "prev", "上一首" -> PREVIOUS
                else -> null
            }
        }
    }

    data class Outcome(val ok: Boolean, val message: String)

    private val app: Context = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun handle(action: Action): Outcome = when (action) {
        Action.PLAY -> play()
        Action.PAUSE -> pause()
        Action.NEXT -> skip(KeyEvent.KEYCODE_MEDIA_NEXT, "下一首")
        Action.PREVIOUS -> skip(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "上一首")
    }

    // ------------------------------------------------------------------ 播放

    private fun play(): Outcome {
        // 已经在放：不用再开一遍应用，直接返回，免得每句「播放音乐」都跳一次界面
        if (isPlaying()) {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            return Outcome(true, "正在放歌呢。")
        }

        val target = pickApp()
            ?: return Outcome(false, "手机里没找到音乐应用，先装一个（网易云、QQ 音乐都行）。")

        if (!launch(target.packageName)) {
            return Outcome(false, "打不开${target.label}，系统可能拦了后台跳转。")
        }

        // 等它把媒体会话建起来，再按播放
        Thread.sleep(OPEN_WAIT_MS)
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        Thread.sleep(PLAY_WAIT_MS)
        if (!isPlaying()) sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        Thread.sleep(600)

        return if (isPlaying()) {
            Outcome(true, "在${target.label}里放上了，想停就说「暂停音乐」。")
        } else {
            // 真的没响就别说放了：可能是曲库为空、要登录、或者这一版不认媒体键
            Outcome(true, "已经把${target.label}打开了，你点一下播放键就行。")
        }
    }

    private fun pause(): Outcome {
        if (!isPlaying()) return Outcome(true, "现在没在放歌。")
        sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        return Outcome(true, "好，暂停了。")
    }

    private fun skip(keyCode: Int, name: String): Outcome {
        if (!isPlaying()) return Outcome(false, "现在没在放歌，先跟我说「播放音乐」。")
        sendKey(keyCode)
        return Outcome(true, "切到$name 了。")
    }

    // ------------------------------------------------------------------ 内部

    private fun isPlaying(): Boolean = runCatching { audio?.isMusicActive == true }
        .getOrDefault(false)

    private fun sendKey(keyCode: Int) {
        val am = audio ?: return
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.onFailure { Log.w(TAG, "派发媒体键失败: $keyCode", it) }
    }

    private fun launch(pkg: String): Boolean = runCatching {
        val intent = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        app.startActivity(intent)
        true
    }.getOrDefault(false)

    private data class Candidate(val packageName: String, val label: String)

    /**
     * 挑一个音乐应用：先看常见的那几家，再退到「名字里带音乐 / Music / 播放器」的任意应用。
     *
     * 不能只认包名：各家 ROM 自带的播放器包名千奇百怪，但名字总归写得出。
     */
    private fun pickApp(): Candidate? {
        KNOWN.forEach { candidate ->
            if (installed(candidate.packageName)) {
                val label = labelOf(candidate.packageName) ?: candidate.label
                return Candidate(candidate.packageName, label)
            }
        }

        @Suppress("DEPRECATION")
        val launchers = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                app.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
            }
        }.getOrDefault(emptyList())

        return launchers.asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(app.packageManager)?.toString().orEmpty()
                val hit = MUSIC_WORDS.any { label.contains(it, ignoreCase = true) }
                if (hit) Candidate(pkg, label.ifBlank { pkg }) else null
            }
            .firstOrNull()
    }

    private fun installed(pkg: String): Boolean = runCatching {
        app.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    private fun labelOf(pkg: String): String? = runCatching {
        val info = app.packageManager.getApplicationInfo(pkg, 0)
        app.packageManager.getApplicationLabel(info).toString()
    }.getOrNull()

    private companion object {
        const val TAG = "MusicPlayer"

        /** 打开后等媒体会话建好 */
        const val OPEN_WAIT_MS = 1_400L

        /** 按了播放再等它真正开始出声 */
        const val PLAY_WAIT_MS = 1_200L

        val KNOWN = listOf(
            Candidate("com.netease.cloudmusic", "网易云音乐"),
            Candidate("com.tencent.qqmusic", "QQ音乐"),
            Candidate("com.kugou.android", "酷狗音乐"),
            Candidate("cn.kuwo.player", "酷我音乐"),
            Candidate("com.google.android.apps.youtube.music", "YouTube Music"),
            Candidate("com.spotify.music", "Spotify"),
            Candidate("com.luna.music", "汽水音乐"),
            Candidate("com.ximalaya.ting.android", "喜马拉雅"),
            Candidate("com.miui.player", "小米音乐"),
            Candidate("com.heytap.music", "音乐"),
            Candidate("com.oppo.music", "音乐"),
            Candidate("com.huawei.music", "音乐"),
            Candidate("com.android.mediacenter", "音乐"),
            Candidate("com.sec.android.app.music", "三星音乐"),
            Candidate("com.android.music", "音乐")
        )

        val MUSIC_WORDS = listOf("音乐", "Music", "播放器", "Player", "网易云", "酷狗", "酷我")
    }
}
