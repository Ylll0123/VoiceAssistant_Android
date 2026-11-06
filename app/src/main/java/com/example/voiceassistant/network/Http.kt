package com.example.voiceassistant.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 网络层：最底下的一层 HTTP。
 *
 * ## 为什么不用 Retrofit / OkHttp
 * 语音助手只有两个接口要打（`/chat/completions` 和几个公开数据源），
 * 引一套 HTTP 栈 + 序列化库只为两三个请求不划算，
 * 而且会让 APK 变大、构建变慢。这里是原生 [HttpURLConnection] +
 * 项目自带的极简 JSON 解析器（[com.example.voiceassistant.data.JsonLite]）。
 *
 * ## 约定
 * - 所有方法**阻塞**，只能在子线程调用（UI 线程调用会直接被系统判死）；
 * - 失败**返回 null / 抛异常**，不弹 Toast、不写界面——那是上层的职责。
 */
object Http {

    const val DEFAULT_TIMEOUT_MS = 7_000

    private const val UA = "VoiceAssistant/3.1 (Android; offline-first)"

    /** 阻塞式 GET，失败返回 null */
    fun get(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String? = runCatching {
        // 飞行模式 / 没网：直接失败，别让用户干等 7 秒超时
        if (!NetworkState.isOnline()) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh")
            if (conn.responseCode !in 200..299) return@runCatching null
            readBody(conn)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 阻塞式 POST（JSON）。
     *
     * @return 「响应体 to HTTP 状态码」；连接直接失败时响应体为 null。
     */
    fun postJson(
        url: String,
        apiKey: String,
        body: String,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Pair<String?, Int> {
        // 没网就别连了：返回 (null, 0)，上层按「连不上」处理并回落本地
        if (!NetworkState.isOnline()) return null to 0
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            readBody(conn, code) to code
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int = conn.responseCode): String? {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
            }
            String(buffer.toByteArray(), Charsets.UTF_8)
        }
    }

    /** 把网络异常翻成用户看得懂的中文 */
    fun describe(e: Throwable, timeoutMs: Int): String = when (e) {
        is SocketTimeoutException -> "请求超过 ${timeoutMs / 1000} 秒没回来（可能网络不通）"
        is UnknownHostException -> "域名解析不了，检查一下网络或接口地址"
        is ConnectException -> "连不上服务器，检查接口地址或网络"
        is SSLException -> "HTTPS 握手失败，检查接口地址"
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "网络读写出错了"
        else -> e.message?.takeIf { it.isNotBlank() } ?: "请求失败"
    }
}
