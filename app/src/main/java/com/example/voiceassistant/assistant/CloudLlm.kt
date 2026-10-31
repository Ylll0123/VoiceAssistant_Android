package com.example.voiceassistant.assistant

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * 可选的云端大模型兜底（OpenAI 兼容接口，默认关闭）。
 *
 * ## 定位
 * 本应用的核心是**本地**：规则 + 语料 + 记忆库全部跑在手机上，断网也能聊。
 * 云端只做**最后一道兜底**：本地确实答不上来时才发一次请求；
 * 没开开关、没填 Key、或者没网，就立刻失败，回到本地的提示语，不做任何等待。
 *
 * ## 为什么不引入 OkHttp / Retrofit / org.json
 * 工程当前依赖缓存里没有这些库；而且 org.json 在单元测试里是只有方法签名的桩，
 * 一调用就抛 "not mocked"。一次 POST + 一段 JSON 手写起来不难，
 * 还能让构造请求和解析响应变成可单测的纯函数。
 *
 * 请求体格式与 OpenAI `/v1/chat/completions` 一致，
 * 所以 DeepSeek、通义、Moonshot、本地 Ollama（/v1 兼容地址）都能直接填进去用。
 */
object CloudLlm {

    /** 本地答不上来时，交给云端的提示词 */
    private const val SYSTEM_PROMPT =
        "你是一个语音助手，用中文口语回答。要求：1）一到两句话，不超过 60 个字；" +
            "2）不要使用 emoji、markdown 和换行；3）直接给结论，不要复述问题。"

    private const val CONNECT_TIMEOUT_MS = 6_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 构造请求体（纯函数，可单测） */
    fun buildRequest(model: String, question: String, history: List<Pair<String, String>> = emptyList()): String {
        val messages = StringBuilder()
        messages.append(message("system", SYSTEM_PROMPT))
        // 只带最近几轮：控制长度，也避免把整段历史都发出去
        for ((user, assistant) in history.takeLast(HISTORY_TURNS)) {
            messages.append(',').append(message("user", user))
            messages.append(',').append(message("assistant", assistant))
        }
        messages.append(',').append(message("user", question))

        return buildString {
            append('{')
            append("\"model\":").append(quote(model))
            append(",\"messages\":[").append(messages).append(']')
            append(",\"temperature\":0.8")
            append(",\"max_tokens\":220")
            append(",\"stream\":false")
            append('}')
        }
    }

    /** 解析响应；拿不到内容返回 null（纯函数，可单测） */
    fun parseReply(body: String): String? {
        if (body.isBlank()) return null
        val content = CONTENT_PATTERN.find(body)?.groupValues?.get(1)
        if (!content.isNullOrBlank()) return tidy(unescape(content))
        // 部分兼容接口只返回旧式的 text 字段
        val text = TEXT_PATTERN.find(body)?.groupValues?.get(1)
        if (!text.isNullOrBlank()) return tidy(unescape(text))
        return null
    }

    /**
     * 发起一次请求。**必须在后台线程调用**。
     *
     * @return 模型给出的回答；失败（无网 / 超时 / 鉴权失败 / 解析失败）返回 null，
     *         调用方据此回到本地答案，用户感知不到网络环节。
     */
    fun ask(
        endpoint: String,
        apiKey: String,
        model: String,
        question: String,
        history: List<Pair<String, String>> = emptyList()
    ): String? {
        if (apiKey.isBlank() || endpoint.isBlank() || model.isBlank()) return null
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(endpoint)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(buildRequest(model, question, history))
                writer.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val response = connection.inputStream
                .bufferedReader(StandardCharsets.UTF_8)
                .use { it.readText() }
            parseReply(response)
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 把回答收拾成适合朗读的一段话：去掉标记、压成一行、超长截断 */
    fun tidy(raw: String): String {
        val flat = raw
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("[*`#>_]+"), "")
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (flat.length > MAX_LENGTH) flat.take(MAX_LENGTH) + "…" else flat
    }

    private fun message(role: String, content: String): String =
        "{\"role\":${quote(role)},\"content\":${quote(content)}}"

    /** JSON 字符串转义：中文原样保留，只处理必须转义的那几个字符 */
    private fun quote(value: String): String = buildString {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char == '\r' -> append("\\r")
                char == '\t' -> append("\\t")
                char < ' ' -> append("\\u").append(String.format("%04x", char.code))
                else -> append(char)
            }
        }
        append('"')
    }

    /** 反转义 JSON 字符串里的 \n / \uXXXX 等 */
    private fun unescape(raw: String): String = buildString {
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\' || index + 1 >= raw.length) {
                append(char)
                index++
                continue
            }
            index++
            when (val next = raw[index]) {
                'n' -> append('\n')
                't' -> append('\t')
                'r' -> append('\r')
                'u' -> {
                    val hex = raw.substring(index + 1, (index + 5).coerceAtMost(raw.length))
                    hex.toIntOrNull(16)?.let { append(it.toChar()) }
                    index += 4
                }
                else -> append(next)
            }
            index++
        }
    }

    private const val MAX_LENGTH = 160
    private const val HISTORY_TURNS = 3

    private val CONTENT_PATTERN = Regex("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val TEXT_PATTERN = Regex("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
}
