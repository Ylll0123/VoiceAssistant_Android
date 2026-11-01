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
        val source: String = "Open-Meteo",
        /** 查的是第几天（0 今天；>0 时 [temp] 是当天的最高气温） */
        val dayOffset: Int = 0,
        /** 城市名是定位得来的（用户没说城市），回答里要说清"你那里" */
        val fromLocation: Boolean = false
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

    /**
     * 天气预报地址。
     *
     * @param days 要几天（今天 = 1，问「明天」时要 2 天才能取到第 2 列数据）
     */
    fun forecastUrl(lat: Double, lon: Double, days: Int = 1): String =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=Asia%2FShanghai&forecast_days=${days.coerceIn(1, 7)}"

    fun geocodeUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=${enc(name)}&count=1&language=zh&format=json"

    fun wttrUrl(name: String): String = "https://wttr.in/${enc(name)}?format=j1"

    /**
     * 按**网络定位**查天气：用户没说城市时用（「天气怎么样」「要带伞吗」）。
     *
     * wttr.in 不带路径时按请求来源 IP 判断位置，返回 `nearest_area` 里的地名，
     * 这是唯一一个不用申请 Key、也不用 Android 定位权限就能拿到"我在哪"的办法。
     */
    fun locationUrl(): String = "https://wttr.in/?format=j1"

    /**
     * 查某城市的实时天气，多源降级。
     *
     * @param dayOffset 0 今天、1 明天、2 后天
     * @return 全部失败返回 null —— 调用方应回落到 [CityClimate.describe] 的常年数据
     */
    fun fetchWeather(city: CityClimate.CityClimate, dayOffset: Int = 0): WeatherSnapshot? {
        val days = (dayOffset + 1).coerceIn(1, 7)
        if (city.lat != 0.0 && city.lon != 0.0) {
            parseOpenMeteo(httpGet(forecastUrl(city.lat, city.lon, days)), city.city, dayOffset)
                ?.let { return it }
        }
        val coords = parseGeocode(httpGet(geocodeUrl(city.city)))
        if (coords != null) {
            parseOpenMeteo(httpGet(forecastUrl(coords.first, coords.second, days)), city.city, dayOffset)
                ?.let { return it }
        }
        parseWttr(httpGet(wttrUrl(city.city)), city.city, dayOffset)?.let { return it }
        return null
    }

    /** 按定位查「我这里」的天气；失败返回 null */
    fun fetchWeatherHere(dayOffset: Int = 0): WeatherSnapshot? {
        val raw = httpGet(locationUrl()) ?: return null
        val name = parseWttrCity(raw).orEmpty()
        return parseWttr(raw, name.ifBlank { "你那里" }, dayOffset, fromLocation = true)
    }

    /** 从 wttr.in 的响应里取定位到的地名 */
    fun parseWttrCity(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val area = JsonLite.obj(JsonLite.arr(JsonLite.get(root, "nearest_area")).firstOrNull())
        val name = JsonLite.str(JsonLite.get(JsonLite.obj(JsonLite.arr(area["areaName"]).firstOrNull()), "value"))
            ?.trim()
        val country = JsonLite.str(
            JsonLite.get(JsonLite.obj(JsonLite.arr(area["country"]).firstOrNull()), "value")
        )?.trim()
        // wttr 给的是拼音地名（Shanghai），配上国家名更好认
        return when {
            name.isNullOrEmpty() -> null
            country.isNullOrEmpty() -> name
            name.equals(country, ignoreCase = true) -> name
            else -> "$name（$country）"
        }
    }

    /**
     * 解析 Open-Meteo forecast 响应。
     *
     * 今天（[dayOffset] = 0）用 `current` 的实时观测值；
     * 明天及以后没有实测值，只能用 `daily` 那一天的预报：
     * 最高 / 最低气温 + 天气代码 + 降水概率。
     */
    fun parseOpenMeteo(raw: String?, city: String, dayOffset: Int = 0, fromLocation: Boolean = false): WeatherSnapshot? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val daily = JsonLite.obj(JsonLite.get(root, "daily"))
        val highs = JsonLite.arr(daily["temperature_2m_max"])
        val lows = JsonLite.arr(daily["temperature_2m_min"])
        val rains = JsonLite.arr(daily["precipitation_probability_max"])
        val codes = JsonLite.arr(daily["weather_code"])
        val index = dayOffset.coerceIn(0, 6)
        val high = JsonLite.num(highs.getOrNull(index))
        val low = JsonLite.num(lows.getOrNull(index))
        val rain = JsonLite.int(rains.getOrNull(index))

        val current = JsonLite.obj(JsonLite.get(root, "current"))
        val currentTemp = JsonLite.num(current["temperature_2m"])
        // 预报日没有体感、湿度这类实测项，只保留能拿到的
        val temp = (if (index == 0) (currentTemp ?: high) else (high ?: currentTemp)) ?: return null

        return WeatherSnapshot(
            city = city,
            temp = temp,
            feelsLike = if (index == 0) JsonLite.num(current["apparent_temperature"]) else null,
            humidity = if (index == 0) JsonLite.int(current["relative_humidity_2m"]) else null,
            wind = if (index == 0) JsonLite.num(current["wind_speed_10m"]) else null,
            desc = weatherText(
                if (index == 0) JsonLite.int(current["weather_code"]) ?: JsonLite.int(codes.getOrNull(index))
                else JsonLite.int(codes.getOrNull(index))
            ),
            high = high,
            low = low,
            rainChance = rain,
            source = "Open-Meteo",
            dayOffset = index,
            fromLocation = fromLocation
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

    /**
     * 解析 wttr.in 响应。
     *
     * wttr 一次给三天（`weather` 数组），所以「明天 / 后天」也能从这里取：
     * 当天用 `current_condition` 的实测值，往后几天用对应那天的 `maxtempC/mintempC`。
     */
    fun parseWttr(
        raw: String?,
        city: String,
        dayOffset: Int = 0,
        fromLocation: Boolean = false
    ): WeatherSnapshot? {
        if (raw.isNullOrBlank()) return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val index = dayOffset.coerceIn(0, 2)
        val current = JsonLite.obj(JsonLite.arr(JsonLite.get(root, "current_condition")).firstOrNull())
        val days = JsonLite.arr(JsonLite.get(root, "weather"))
        val day = JsonLite.obj(days.getOrNull(index))
        if (day.isEmpty() && index != 0) return null

        val dayMax = JsonLite.num(day["maxtempC"])
        val dayMin = JsonLite.num(day["mintempC"])
        val currentTemp = JsonLite.num(current["temp_C"])
        val temp = (if (index == 0) (currentTemp ?: dayMax) else (dayMax ?: currentTemp)) ?: return null

        val desc = if (index == 0) {
            JsonLite.arr(current["weatherDesc"])
                .firstOrNull()
                ?.let { JsonLite.str(JsonLite.get(it, "value")) }
                ?.let { translateDesc(it) } ?: "天气状况未知"
        } else {
            JsonLite.arr(day["hourly"])
                .mapNotNull { hour ->
                    JsonLite.arr(JsonLite.get(hour, "weatherDesc")).firstOrNull()
                        ?.let { JsonLite.str(JsonLite.get(it, "value")) }
                }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
                ?.let { translateDesc(it) } ?: "天气状况未知"
        }

        return WeatherSnapshot(
            city = city,
            temp = temp,
            feelsLike = if (index == 0) JsonLite.num(current["FeelsLikeC"]) else null,
            humidity = if (index == 0) JsonLite.int(current["humidity"]) else null,
            wind = if (index == 0) JsonLite.num(current["windspeedKmph"]) else null,
            desc = desc,
            high = dayMax,
            low = dayMin,
            rainChance = JsonLite.arr(day["hourly"])
                .mapNotNull { JsonLite.int(JsonLite.get(it, "chanceofrain")) }
                .maxOrNull(),
            source = "wttr.in",
            dayOffset = index,
            fromLocation = fromLocation
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

    /**
     * 把联网的实时天气整理成一段有结构的话：数据 → 穿衣建议 → 来源。
     *
     * @param dayLabel "今天 / 明天 / 后天"，由调用方（联网计划）给，
     *                 免得把「明天」的数据说成现在的天气。
     */
    fun composeWeather(snapshot: WeatherSnapshot, dayLabel: String = "今天"): String {
        val sb = StringBuilder()
        val place = if (snapshot.fromLocation) "你那里" else snapshot.city
        val whenWord = if (snapshot.dayOffset == 0) "现在" else dayLabel

        if (snapshot.dayOffset == 0) {
            val feels = snapshot.feelsLike?.let { "（体感 ${fmt(it)}℃）" }.orEmpty()
            sb.append("${place}现在 ${fmt(snapshot.temp)}℃$feels，${snapshot.desc}")
            snapshot.humidity?.let { sb.append("，湿度 $it%") }
            snapshot.wind?.let { sb.append("，风速 ${fmt(it)} km/h") }
            sb.append("。")
        } else {
            // 往后几天没有实测值，只能说预报区间
            val range = if (snapshot.high != null && snapshot.low != null) {
                " ${fmt(snapshot.low)}℃~${fmt(snapshot.high)}℃"
            } else {
                " ${fmt(snapshot.temp)}℃"
            }
            sb.append("${place}${whenWord}${range}，${snapshot.desc}")
            snapshot.rainChance?.let { sb.append("，降水概率 $it%") }
            sb.append("。")
        }

        if (snapshot.dayOffset == 0 && snapshot.high != null && snapshot.low != null) {
            sb.append("今天最高 ${fmt(snapshot.high)}℃、最低 ${fmt(snapshot.low)}℃")
            snapshot.rainChance?.let { sb.append("，降水概率 $it%") }
            sb.append("。")
        }
        val reference = snapshot.high ?: snapshot.temp
        sb.append("按这个温度：${CityClimate.dressAdvice(reference.toInt())}。")
        if (snapshot.rainChance != null && snapshot.rainChance >= 60) {
            sb.append("降水概率不低，带把伞更稳。")
        }
        sb.append("（${whenWord}的数据来自 ${snapshot.source}）")
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
