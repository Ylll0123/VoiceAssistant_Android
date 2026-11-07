package com.example.voiceassistant.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 网络层：**当前有没有网**，以及网络变了要通知谁。
 *
 * 两个用途：
 * 1. 没网时别让请求在那儿干等 7 秒超时，直接失败（[isOnline]）；
 * 2. 界面上的「绿点在线 / 红点离线」（[addListener]）。
 *
 * ## 为什么还要自己巡检
 * 系统的 `NetworkCallback` 在部分机型 / 场景下不一定及时回调：
 * 实测「开着 App 切飞行模式」时界面会一直停在「在线」。
 * 所以有界面在听的时候，每 [ONLINE_CHECK_MS] 自己问一次系统，
 * 离线时缩短到 [OFFLINE_RETRY_MS]，网络一回来立刻通知界面并自动恢复可用——
 * **这一步不显示在界面上**，用户只会看到红点自己变绿。
 *
 * ## 为什么允许降级成「当作有网」
 * 判断不出状态时（还没 init、拿不到 ConnectivityManager）一律当有网：
 * 真没网的话请求自己会失败，比误判成没网、白白不尝试要安全。
 */
object NetworkState {

    /** 网络状态变化；回调在主线程 */
    fun interface Listener {
        fun onNetworkChanged(online: Boolean)
    }

    /** 在线时的巡检间隔：够发现「Wi-Fi 连着但没外网」，又不至于让系统一直醒着 */
    private const val ONLINE_CHECK_MS = 5_000L

    /** 离线时多久复查一次。3 秒够快，网络一回来就能立刻自动恢复 */
    private const val OFFLINE_RETRY_MS = 3_000L

    private var appContext: Context? = null
    private var manager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 上一次对外公布的状态，用来去重（同一状态不重复通知） */
    @Volatile
    private var lastPublished: Boolean? = null

    /** 巡检是否在跑（有界面在听才跑） */
    private var ticking = false

    /**
     * 周期巡检。
     *
     * 为什么不只靠 `NetworkCallback`：系统回调在「飞行模式切换、Wi-Fi 连着但没外网」
     * 这些场景下经常不按预期来——实测从有网切到飞行模式时界面会一直停在「在线」。
     * 自己每 3~5 秒问一次系统，状态就一定跟得上，而且这一步完全不出现在界面上。
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking || listeners.isEmpty()) return
            val online = isOnline()
            publish(online)
            main.postDelayed(this, if (online) ONLINE_CHECK_MS else OFFLINE_RETRY_MS)
        }
    }

    /** 启动时调一次即可；重复调用无副作用 */
    fun init(context: Context) {
        val app = context.applicationContext
        if (appContext === app && callback != null) return
        appContext = app
        manager = runCatching { app.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        registerCallback()
        publish(isOnline())
    }

    /**
     * 有网返回 true；状态未知也返回 true（交给真实请求去试）。
     *
     * 判定标准是 `NET_CAPABILITY_INTERNET`：连着但压根没有外网能力
     * （比如只连着局域网）时当作没网，因为大模型一定连不上。
     */
    fun isOnline(): Boolean {
        val cm = manager ?: appContext?.let {
            runCatching { it.getSystemService(ConnectivityManager::class.java) }.getOrNull()
        } ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        listener.onNetworkChanged(isOnline())
        startTicking()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) stopTicking()
    }

    /** 界面出现：开始巡检（幂等） */
    private fun startTicking() {
        if (ticking) return
        ticking = true
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, ONLINE_CHECK_MS)
    }

    /** 界面退出：停掉巡检，后台不白耗电 */
    private fun stopTicking() {
        ticking = false
        main.removeCallbacks(ticker)
    }

    private fun registerCallback() {
        val cm = manager ?: return
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish(isOnline())

            override fun onLost(network: Network) = publish(isOnline())

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) = publish(isOnline())
        }
        callback = cb
        // 注册失败（权限 / 系统限制）不影响主流程：轮询那条路还在
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    /**
     * 公布一次状态：变了才通知，并用轮询兜底。
     *
     * 之所以「变了才通知」：`onCapabilitiesChanged` 可能一秒来好几次，
     * 每次都刷界面会闪。
     */
    private fun publish(online: Boolean) {
        val changed = lastPublished != online
        lastPublished = online
        if (changed) {
            main.post { listeners.forEach { runCatching { it.onNetworkChanged(online) } } }
        }
    }
}
