package com.example.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 「跳到别的界面」并**确认真的跳过去了**。
 *
 * ## 为什么不能直接 `context.startActivity`
 * Android 10 起后台启动 Activity 会被系统拦下，而 `startActivity` 在这种情况下
 * **不抛异常**：调用「成功」、界面一动不动。于是所有「面板已经打开了，在上面点一下」
 * 这类回话都可能是在骗人——用户抬头还在原地。
 *
 * 这跟 v5.8 修过的「分享页盲报成功」是同一个坑，只不过那次在 ImAutomator 里，
 * 这次散落在 WiFi / 蓝牙 / 相机这些入口上。
 *
 * ## 怎么确认
 * 借现成的信号：**我们自己的界面被盖住了**。
 * 设置面板、相机都是另一个 Activity，它们一上来本页就会 `onStart → onPause`。
 * 所以只要在短时间内观察到「我不在前台了」，就说明真的跳过去了。
 *
 * 界面启动时用 [bind] 把「启动」和「还在前台吗」两个探针接进来；
 * 没绑上（比如进程里没有 Activity）时退化成直接启动，但**如实返回 false**——
 * 宁可不打包票，也不说没发生的事。
 */
object UiLaunch {

    private const val TAG = "UiLaunch"

    /** 默认等这么久看界面有没有被盖住 */
    private const val VERIFY_MS = 1_500L

    private val main = Handler(Looper.getMainLooper())

    /** 由 Activity 注入：真正启动（走 Activity，才不受后台启动限制） */
    @Volatile
    private var starter: ((Intent) -> Boolean)? = null

    /** 由 Activity 注入：我们现在还在前台吗 */
    @Volatile
    private var foreground: () -> Boolean = { false }

    fun bind(start: (Intent) -> Boolean, isForeground: () -> Boolean) {
        starter = start
        foreground = isForeground
    }

    fun unbind() {
        starter = null
    }

    /**
     * 打开一个界面，并等它真的盖住我们。
     *
     * @return true 只在**观察到界面被盖住**时返回；无法确认一律 false
     */
    fun open(context: Context, intent: Intent, timeoutMs: Long = VERIFY_MS): Boolean {
        val wasForeground = foreground()
        val start = starter

        val launched = if (start != null) {
            /*
             * 启动必须回主线程做。
             * 但如果本来就在主线程上（某些同步入口会这样），再 post + 等锁
             * 就会等自己，直接卡死——所以分两条路走。
             */
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runCatching { start(intent) }.getOrDefault(false)
            } else {
                var ok = false
                val done = java.util.concurrent.CountDownLatch(1)
                main.post {
                    ok = runCatching { start(intent) }.getOrDefault(false)
                    done.countDown()
                }
                runCatching { done.await(1, java.util.concurrent.TimeUnit.SECONDS) }
                ok
            }
        } else {
            /*
             * 没有界面可借：只能退到应用上下文。
             * 这条路在后台大概率被拦，所以不管调没调通，结果都当作「没确认」。
             */
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            Log.i(TAG, "没有界面可借，无法确认是否真的跳转")
            return false
        }

        if (!launched) return false

        // 本来就不在前台：没有可观察的信号，同样不打包票
        if (!wasForeground) return false

        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (!foreground()) return true
            Thread.sleep(120)
        }
        Log.w(TAG, "界面没被盖住，跳转可能被系统拦了")
        return false
    }
}
