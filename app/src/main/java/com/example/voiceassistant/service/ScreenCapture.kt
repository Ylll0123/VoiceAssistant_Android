package com.example.voiceassistant.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import com.example.voiceassistant.im.ImAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 截屏并存进相册。
 *
 * ## 为什么走无障碍而不是 MediaProjection
 * `MediaProjection` 每次都要用户点一次「开始录制」的系统弹窗，还常驻一条投屏通知——
 * 对一个「说一句截屏」的指令来说太重了。
 * `AccessibilityService.takeScreenshot`（API 30+）读屏服务本来就有的权限，
 * **零额外授权、零弹窗**，代价是必须先连上读屏服务。
 *
 * ## 没连上就说没连上
 * 没有读屏时**不做任何假装**：不返回空图、不说「已截图」。
 * 上层会回一句「得先连上读屏能力才能截屏」，用户知道该去哪儿。
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"

    /** 截一张图最多等这么久；无障碍回调偶发不回来，不能把总线卡死 */
    private const val TIMEOUT_MS = 8_000L

    sealed class Result {
        data class Saved(val path: String) : Result()
        data object NoService : Result()
        data object Unsupported : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * 截屏并写入相册。**会阻塞**到出结果，必须在后台线程调用。
     *
     * 走 `Pictures/HardlySeek`，不用申请任何存储权限（MediaStore + IS_PENDING）。
     */
    fun capture(context: Context): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Result.Unsupported
        // 刚启动那几秒系统可能还没把读屏服务 bind 上，等一会儿再判「没有」
        val service = awaitService() ?: return Result.NoService
        return runCatching { captureWith(context, service) }
            .getOrElse {
                Log.w(TAG, "截屏异常: ${it.message}")
                Result.Failed(it.javaClass.simpleName)
            }
    }

    /**
     * 等读屏服务绑上来。
     *
     * 应用刚启动、或刚被系统从后台放回来时，`instance` 有一小段时间是 null：
     * 这时候直接回「没连上」会让用户以为功能坏了，其实再等半秒就好。
     */
    private fun awaitService(timeoutMs: Long = 2_500L): ImAccessibilityService? {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            ImAccessibilityService.instance?.let { return it }
            if (System.currentTimeMillis() >= until) return null
            Thread.sleep(120)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureWith(context: Context, service: ImAccessibilityService): Result {
        val latch = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()

        /*
         * 回调在另一个线程写、这里在主线程读，用原子引用而不是普通变量：
         * 局部变量加不了 @Volatile，靠 AtomicReference 才能保证看得见。
         */
        val shot = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val callback = object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                runCatching {
                    val buffer = result.hardwareBuffer
                    // wrapHardwareBuffer 给的是 HARDWARE 位图，compress 会抛；
                    // 先拷成软件位图再压缩
                    shot.set(
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    )
                    buffer.close()
                }.onFailure { failure.set("画面转换失败") }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                // 官方错误码转成一句人话，别把数字直接丢给用户
                failure.set(when (errorCode) {
                    android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                        "截屏出错"
                    android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                        "系统没给截屏权限"
                    android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                        "截得太频繁，歇一下再来"
                    android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                        "屏幕状态不对，解锁后再试"
                    else -> "系统没让截（错误 $errorCode）"
                })
                latch.countDown()
            }
        }

        return try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, pool, callback)
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Result.Failed("截屏没有响应")
            }
            val bitmap = shot.get() ?: return Result.Failed(failure.get() ?: "没拿到画面")

            val saved = save(context, bitmap)
            bitmap.recycle()
            saved
        } finally {
            pool.shutdownNow()
        }
    }

    /** 写进相册；返回给人看的结果 */
    private fun save(context: Context, bitmap: Bitmap): Result {
        val name = "HardlySeek_" + System.currentTimeMillis() + ".png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/HardlySeek"
            )
            // 写完再放出来，相册不会读到半张图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return Result.Failed("存不进相册")

        val written = runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: false
        }.getOrDefault(false)

        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return Result.Failed("图片没写进去")
        }

        runCatching {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null
            )
        }
        return Result.Saved("Pictures/HardlySeek/$name")
    }
}
