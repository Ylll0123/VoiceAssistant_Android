package com.example.voiceassistant.data

import com.example.voiceassistant.assistant.CityClimate
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网查询：实时天气 + 百科搜索。
 *
 * ## 设计前提：**联网只是加分项，离线必须还能用**
 * 所有联网失败（没网 / 被墙 / 接口改版）都返回 null，
 * 调用方拿到 null 就用本地的常年气候数据或本地知识库兜底，
 * 所以断网时助手不会变傻，只是少了实时数据。
 *
 * ## 数据源（都是免费、不需要申请 Key 的公开接口）
 * | 用途 | 主源 | 降级源 |
 * |---|---|---|
 * | 实时天气 | Open-Meteo Forecast（经纬度） | Open-Meteo 地名反查 → wttr.in |
 * | 百科知识 | 中文维基 summary | 中文维基搜索 → 英文维基 summary |
 *
 * ## 线程约定
 * [httpGet] 会阻塞，只能在子线程调用；
 * 解析函数（[parseOpenMeteo] / [parseWikiSummary] …）是纯函数，可以直接在单测里断言。
 */
object WebSearch {

    /** 一次实时天气查询结果 */
    data class WeatherSnapshot(
        val city: String,
        val temp: Double,
        val feelsLike: Double? = null,
        val humidity: Int? = null,
        val wind: Double? = null,
        val desc: String = "天气状况未知",
        val high: Double? = null,
        val low: Double? = null,
        val rainChance: Int? = null,
        val source: String = "Open-Meteo"
    )

    /** 一次搜索结果 */
    data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String,
        val source: String
    )

    private const val TIMEOUT_MS = 7000
    private const val UA = "VoiceAssistant/2.6 (Android; offline-first)"

    // ------------------------------------------------------------------ 网络

    /** 阻塞式 GET，失败返回 null（不要在 UI 线程调用） */
    fun httpGet(url: String, timeoutMs: Int = TIMEOUT_MS): String? = runCatching {
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
            conn.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                }
                String(buffer.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun enc(text: String): String = URLEncoder.encode(text, "UTF-8")

    // ------------------------------------------------------------------ 天气

    fun forecastUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FShanghai&forecast_days=1"

    fun geocodeUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=${enc(name)}&count=1&language=zh&format=json"

    fun wttrUrl(name: String): String = "https://wttr.in/${enc(name)}?format=j1"

    /**
     * 查某城市的实时天气，多源降级。
     * @return 全部失败返回 null —— 调用方应回落到 [CityClimate.describe] 的常年数据
     */
    fun fetchWeather(city: CityClimate.CityClimate): WeatherSnapshot? {
        if (city.lat != 0.0 && city.lon != 0.0) {
            parseOpenMeteo(httpGet(forecastUrl(city.lat, city.lon)), city.city)?.let { return it }
        }
        val coords = parseGeocode(httpGet(geocodeUrl(city.city)))
        if (coords != null) {
            parseOpenMeteo(httpGet(forecastUrl(coords.first, coords.second)), city.city)?.let { return it }
        }
        parseWttr(httpGet(wttrUrl(city.city)), city.city)?.let { return it }
        return null
    }

    /** 解析 Open-Meteo forecast 响应 */
    fun parseOpenMeteo(raw: String?, city: String): WeatherSnapshot? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val current = JsonLite.obj(JsonLite.get(root, "current"))
        val temp = JsonLite.num(current["temperature_2m"]) ?: return null
        val daily = JsonLite.obj(JsonLite.get(root, "daily"))
        val firstMax = JsonLite.num(JsonLite.arr(daily["temperature_2m_max"]).firstOrNull())
        val firstMin = JsonLite.num(JsonLite.arr(daily["temperature_2m_min"]).firstOrNull())
        val rain = JsonLite.int(JsonLite.arr(daily["precipitation_probability_max"]).firstOrNull())
        return WeatherSnapshot(
            city = city,
            temp = temp,
            feelsLike = JsonLite.num(current["apparent_temperature"]),
            humidity = JsonLite.int(current["relative_humidity_2m"]),
            wind = JsonLite.num(current["wind_speed_10m"]),
            desc = weatherText(JsonLite.int(current["weather_code"])),
            high = firstMax,
            low = firstMin,
            rainChance = rain,
            source = "Open-Meteo"
        )
    }

    /** 解析 Open-Meteo 地名查询结果，取第一个结果的经纬度 */
    fun parseGeocode(raw: String?): Pair<Double, Double>? {
        if (raw.isNullOrBlank()) return null
        val results = JsonLite.arr(JsonLite.get(JsonLite.parseOrNull(raw), "results"))
        val first = JsonLite.obj(results.firstOrNull())
        val lat = JsonLite.num(first["latitude"]) ?: return null
        val lon = JsonLite.num(first["longitude"]) ?: return null
        return lat to lon
    }

    /** 解析 wttr.in 响应 */
    fun parseWttr(raw: String?, city: String): WeatherSnapshot? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val current = JsonLite.obj(JsonLite.arr(JsonLite.get(root, "current_condition")).firstOrNull())
        val temp = JsonLite.num(current["temp_C"]) ?: return null
        val desc = JsonLite.arr(current["weatherDesc"])
            .firstOrNull()
            ?.let { JsonLite.str(JsonLite.get(it, "value")) }
            ?.let { translateDesc(it) } ?: "天气状况未知"
        val today = JsonLite.obj(JsonLite.arr(JsonLite.get(root, "weather")).firstOrNull())
        return WeatherSnapshot(
            city = city,
            temp = temp,
            feelsLike = JsonLite.num(current["FeelsLikeC"]),
            humidity = JsonLite.int(current["humidity"]),
            wind = JsonLite.num(current["windspeedKmph"]),
            desc = desc,
            high = JsonLite.num(today["maxtempC"]),
            low = JsonLite.num(today["mintempC"]),
            rainChance = JsonLite.arr(today["hourly"])
                .mapNotNull { JsonLite.int(JsonLite.get(it, "chanceofrain")) }
                .maxOrNull(),
            source = "wttr.in"
        )
    }

    // ------------------------------------------------------------------ 搜索

    /**
     * 查某话题的资料，多源降级。
     *
     * 主源用**必应的 RSS 搜索接口**：国内可达、不用申请 Key、
     * 不限 User-Agent（App 自己的 UA 就能拿），返回的 `<description>`
     * 直接是一段成型的中文摘要（多来自百度百科），质量足够当好回答。
     * 维基百科排在后面——家里 / 校园网络出得去时才轮到它。
     *
     * @return 全部失败返回 null，调用方回落到本地推理框架
     */
    fun fetchSummary(topic: String): SearchResult? {
        val query = topic.trim()
        if (query.isEmpty()) return null

        searchRssUrl(query)?.let { parseBingResult(httpGet(it)) }?.let { return it }
        if (query.length in 2..20) {
            parseWikiSummary(httpGet("https://zh.wikipedia.org/api/rest_v1/page/summary/${enc(query)}"))
                ?.let { return it }
        }
        parseWikiSummary(httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(query)}"))
            ?.let { return it.copy(source = "英文维基百科") }
        return null
    }

    /** 必应 RSS 搜索：不用 Key，UA 也不挑，返回标准 XML */
    fun searchRssUrl(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return "https://cn.bing.com/search?q=${enc(q)}&format=rss"
    }

    /** 取必应 RSS 里的第一条结果 */
    fun parseBingResult(raw: String?): SearchResult? {
        if (raw.isNullOrBlank()) return null
        val first = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.get(1) ?: return null
        val snippet = tag(first, "description")
            ?.let { decodeEntities(stripTags(it)) }
            ?.trim()
            .orEmpty()
        if (snippet.isEmpty()) return null
        val rawTitle = tag(first, "title")?.let { decodeEntities(stripTags(it)) }.orEmpty()
        // 「光合作用（绿色植物……）_百度百科」太长了，截到「光合作用」才适合念出来
        val title = shortenTitle(rawTitle).ifEmpty { snippet.take(12) }
        val url = tag(first, "link")?.trim().orEmpty()
        return SearchResult(
            title = title,
            snippet = snippet,
            url = url,
            source = "必应搜索"
        )
    }

    /** 搜索结果标题裁剪：去掉来源后缀和长括号解释，最多 24 字 */
    fun shortenTitle(raw: String): String {
        val head = raw.substringBefore("_")
            .substringBefore(" - ")
            .substringBefore("（")
            .substringBefore("(")
            .trim()
        val text = head.ifEmpty { raw.trim() }
        return if (text.length > 24) text.substring(0, 24) else text
    }

    /** 取 XML 标签内容（纯文本的第一优先） */
    private fun tag(xml: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    private fun stripTags(text: String): String = text.replace(Regex("<[^>]*>"), "")

    /** 把 &nbsp; &#183; &quot; 这类实体还原成正常字符 */
    fun decodeEntities(text: String): String {
        val named = mapOf(
            "&nbsp;" to " ", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'",
            "&lt;" to "<", "&gt;" to ">", "&amp;" to "&", "&hellip;" to "…",
            "&mdash;" to "—", "&ldquo;" to "“", "&rdquo;" to "”"
        )
        var out = text
        named.forEach { (k, v) -> out = out.replace(k, v) }
        // 数字实体：&#183; &#x2014;
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        out = Regex("&#(\\d+);").replace(out) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        return out.trim()
    }

    fun searchUrl(query: String): String? {
        val q = query.trim()
        if (q.isEmpty()) return null
        return "https://zh.wikipedia.org/w/api.php?action=query&format=json&list=search" +
            "&srsearch=${enc(q)}&srlimit=1&utf8=1"
    }

    /** 从维基搜索响应里取第一个标题 */
    fun firstTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val results = JsonLite.arr(
            JsonLite.get(
                JsonLite.get(JsonLite.parseOrNull(raw), "query"),
                "search"
            )
        )
        return JsonLite.str(JsonLite.get(JsonLite.obj(results.firstOrNull()), "title"))
    }

    /** 解析维基 summary 响应 */
    fun parseWikiSummary(raw: String?): SearchResult? {
        if (raw.isNullOrBlank()) return null
        val obj = JsonLite.obj(JsonLite.parseOrNull(raw))
        val title = JsonLite.str(obj["title"]) ?: return null
        // 404 / 消歧义页没有摘要，或者摘要说"可以指"，当作没查到
        val extract = JsonLite.str(obj["extract"])?.trim().orEmpty()
        if (extract.isEmpty()) return null
        val url = JsonLite.str(
            JsonLite.get(
                JsonLite.get(JsonLite.get(obj, "content_urls"), "desktop"),
                "page"
            )
        ).orEmpty()
        return SearchResult(
            title = title,
            snippet = extract,
            url = url,
            source = "维基百科"
        )
    }

    // ------------------------------------------------------------------ 回答整理

    /** 把联网的实时天气整理成一段有结构的话：当前数据 → 穿衣建议 → 来源 */
    fun composeWeather(snapshot: WeatherSnapshot): String {
        val sb = StringBuilder()
        val feels = snapshot.feelsLike?.let { "（体感 ${fmt(it)}℃）" }.orEmpty()
        sb.append("${snapshot.city}现在 ${fmt(snapshot.temp)}℃$feels，${snapshot.desc}")
        snapshot.humidity?.let { sb.append("，湿度 $it%") }
        snapshot.wind?.let { sb.append("，风速 ${fmt(it)} km/h") }
        sb.append("。")

        if (snapshot.high != null && snapshot.low != null) {
            sb.append("今天最高 ${fmt(snapshot.high)}℃、最低 ${fmt(snapshot.low)}℃")
            snapshot.rainChance?.let { sb.append("，降水概率 $it%") }
            sb.append("。")
        }
        val reference = snapshot.high ?: snapshot.temp
        sb.append("按现在的温度看：${CityClimate.dressAdvice(reference.toInt())}。")
        sb.append("（这是刚联网查到的实时数据，来自 ${snapshot.source}）")
        return sb.toString()
    }

    /** 把搜索结果整理成一段有结构的话：结论 → 要点 → 来源 */
    fun composeSearch(result: SearchResult): String {
        val body = cleanSnippet(result.snippet)
        return "我上网查了「${result.title}」：${body}（来源：${result.source}）"
    }

    /** 摘要清洗：砍到第一个完整句号后两三句，去掉括号里的音标/注释 */
    fun cleanSnippet(raw: String, maxLength: Int = 160): String {
        var text = raw
            // 半角括号里的常是音标 / 外文名，全角括号里常是注释，都去掉再读才顺
            .replace(Regex("\\s*[(\\[（【][^)\\]）】]*[)\\]）】]"), "")
            .replace('\n', ' ')
            .trim()
        val sentences = text.split(Regex("(?<=[。！？])(?=[^。！？])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.isNotEmpty()) {
            text = buildString {
                for (sentence in sentences) {
                    if (length + sentence.length > maxLength && isNotEmpty()) break
                    append(sentence)
                }
            }
        }
        if (text.length > maxLength) text = text.substring(0, maxLength)
        return text.ifBlank { "没查到合适的说明。" }
    }

    private fun fmt(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.1f", value)

    /** WMO 天气代码 → 中文描述 */
    fun weatherText(code: Int?): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45 -> "有雾"
        48 -> "有雾凇"
        51 -> "毛毛雨"
        53 -> "细雨"
        55 -> "较大的毛毛雨"
        56, 57 -> "冻雨，地面会结冰"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨，路面很滑"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80 -> "阵雨"
        81 -> "较大的阵雨"
        82 -> "暴雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        null -> "天气状况未知"
        else -> "天气状况未知"
    }

    /** wttr.in 返回的是英文描述，翻成中文 */
    fun translateDesc(raw: String): String {
        val text = raw.lowercase()
        return when {
            text.contains("thunder") -> "雷阵雨"
            text.contains("sleet") -> "雨夹雪"
            text.contains("snow") -> "有雪"
            text.contains("drizzle") -> "毛毛雨"
            text.contains("rain") || text.contains("shower") -> "有雨"
            text.contains("fog") || text.contains("mist") -> "有雾"
            text.contains("overcast") -> "阴"
            text.contains("cloud") -> "多云"
            text.contains("clear") || text.contains("sunny") -> "晴"
            else -> raw
        }
    }
}
