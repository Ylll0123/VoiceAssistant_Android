package com.example.voiceassistant.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络层：**当前有没有网**。
 *
 * 只有一个用途：没网时别让请求在那儿干等 7 秒超时，直接失败、
 * 立刻回落到本地内容——用户体感是「秒回」而不是「转圈半天再告诉你没网」。
 *
 * ## 为什么允许降级成"当作有网"
 * 判断不出状态时（还没 init、拿不到 ConnectivityManager）一律当**有网**：
 * 真没网的话请求自己会失败并回落，比误判成没网、白白不尝试要安全。
 */
object NetworkState {

    private var appContext: Context? = null

    /** 启动时调一次即可；之后各层都能查 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 有网返回 true；状态未知也返回 true（交给真实请求去试） */
    fun isOnline(): Boolean {
        val context = appContext ?: return true
        val manager = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return true
        val caps = runCatching {
            manager.getNetworkCapabilities(manager.activeNetwork)
        }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
