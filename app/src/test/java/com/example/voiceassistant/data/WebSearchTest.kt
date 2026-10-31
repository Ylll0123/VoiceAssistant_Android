package com.example.voiceassistant.data

import com.example.voiceassistant.assistant.CityClimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网结果的解析与整理（不发请求，只验证「接口返回 → 一句话回答」这段）。
 *
 * 之所以把这些拆成纯函数：联网部分在单元测试里跑不动，
 * 单独把解析和模板拆出来之后，即使不联网也能验证逻辑是对的。
 */
class WebSearchTest {

    private val OPEN_METEO =
        "{\"current\":{\"temperature_2m\":12.4,\"relative_humidity_2m\":55," +
            "\"apparent_temperature\":10.2,\"weather_code\":61,\"wind_speed_10m\":9.3}," +
            "\"daily\":{\"temperature_2m_max\":[22.1],\"temperature_2m_min\":[8.4]," +
            "\"precipitation_probability_max\":[30]}}"

    private val WTTR =
        "{\"current_condition\":[{\"temp_C\":\"12\",\"FeelsLikeC\":\"10\",\"humidity\":\"55\"," +
            "\"weatherDesc\":[{\"value\":\"Light rain\"}],\"windspeedKmph\":\"9\"}]," +
            "\"weather\":[{\"maxtempC\":\"22\",\"mintempC\":\"8\"," +
            "\"hourly\":[{\"chanceofrain\":\"30\"}]}]}"

    @Test
    fun `解析 Open-Meteo 的实时天气`() {
        val snap = WebSearch.parseOpenMeteo(OPEN_METEO, "北京")!!
        assertEquals("北京", snap.city)
        assertEquals(12.4, snap.temp, 0.001)
        assertEquals(10.2, snap.feelsLike!!, 0.001)
        assertEquals(55, snap.humidity)
        assertEquals("小雨", snap.desc)
        assertEquals(22.1, snap.high!!, 0.001)
        assertEquals(8.4, snap.low!!, 0.001)
        assertEquals(30, snap.rainChance)
    }

    @Test
    fun `解析 wttr 的降级数据并把英文描述翻成中文`() {
        val snap = WebSearch.parseWttr(WTTR, "上海")!!
        assertEquals(12.0, snap.temp, 0.001)
        assertEquals("有雨", snap.desc)
        assertEquals(22.0, snap.high!!, 0.001)
        assertEquals("wttr.in", snap.source)
    }

    @Test
    fun `坏数据返回 null 而不是崩溃`() {
        assertNull(WebSearch.parseOpenMeteo(null, "北京"))
        assertNull(WebSearch.parseOpenMeteo("", "北京"))
        assertNull(WebSearch.parseOpenMeteo("不是 json", "北京"))
        assertNull(WebSearch.parseOpenMeteo("{\"daily\":{}}", "北京"))
        assertNull(WebSearch.parseWttr(" broken", "北京"))
    }

    @Test
    fun `地名反查能拿到经纬度`() {
        val raw = "{\"results\":[{\"name\":\"Beijing\",\"latitude\":39.9,\"longitude\":116.4}]}"
        assertEquals(39.9 to 116.4, WebSearch.parseGeocode(raw))
        assertNull(WebSearch.parseGeocode("{\"results\":[]}"))
    }

    @Test
    fun `解析维基摘要并取到正文链接`() {
        val raw = "{\"title\":\"人工智能\",\"extract\":\"人工智能是计算机科学的一个分支。\"," +
            "\"content_urls\":{\"desktop\":{\"page\":\"https://zh.wikipedia.org/wiki/ai\"}}}"
        val result = WebSearch.parseWikiSummary(raw)!!
        assertEquals("人工智能", result.title)
        assertEquals("维基百科", result.source)
        assertTrue(result.url.startsWith("https://"))
    }

    @Test
    fun `解析必应 RSS 的第一条结果（联网主源）`() {
        val raw = "<rss><channel><item>" +
            "<title>量子纠缠（物理学概念）_百度百科</title>" +
            "<link>https://baike.baidu.com/item/x/1714985</link>" +
            "<description>在量子力学里，当几个粒子在彼此相互作用后，由于各个粒子所拥有的特性" +
            "已综合成为整体性质，则称这现象为量子纠缠。</description>" +
            "</item><item><title>别的</title><link>https://x</link><description>略</description></item>" +
            "</channel></rss>"
        val result = WebSearch.parseBingResult(raw)!!
        // 来源后缀和括号解释都去掉，念出来才顺
        assertEquals("量子纠缠", result.title)
        assertTrue("实际：${result.snippet}", result.snippet.startsWith("在量子力学里"))
        assertEquals("必应搜索", result.source)
        assertTrue(result.url.startsWith("https://"))
    }

    @Test
    fun `RSS 里的 HTML 实体要还原`() {
        assertEquals("巴黎 & London", WebSearch.decodeEntities("巴黎 &amp; London"))
        assertEquals("温度18°C", WebSearch.decodeEntities("温度18&#176;C"))
        assertTrue(WebSearch.decodeEntities("a&nbsp;b") == "a b")
    }

    @Test
    fun `RSS 为空或没结果时不认领`() {
        assertNull(WebSearch.parseBingResult(null))
        assertNull(WebSearch.parseBingResult(""))
        assertNull(WebSearch.parseBingResult("<rss><channel></channel></rss>"))
    }

    @Test
    fun `搜索 URL 用必应 RSS 并且做了编码`() {
        val url = WebSearch.searchRssUrl("量子纠缠")!!
        assertTrue("实际：$url", url.startsWith("https://cn.bing.com/search?q="))
        assertTrue("实际：$url", url.endsWith("&format=rss"))
        assertNull(WebSearch.searchRssUrl("  "))
    }

    @Test
    fun `搜索结果里第一条候选标题要能取出来`() {
        val raw = "{\"query\":{\"search\":[{\"title\":\"量子纠缠\"},{\"title\":\"量子力学\"}]}}"
        assertEquals("量子纠缠", WebSearch.firstTitle(raw))
        assertNull(WebSearch.firstTitle("{\"query\":{\"search\":[]}}"))
    }

    @Test
    fun `摘要清洗：去括号音标并截断到句子边界`() {
        val cleaned = WebSearch.cleanSnippet(
            "人工智能（英语：AI）是计算机科学的分支。它研究智能行为。第三句不应该出现在这么短的结果里。",
            maxLength = 40
        )
        assertTrue("实际：$cleaned", cleaned.startsWith("人工智能是计算机科学的分支"))
        assertTrue("实际：$cleaned", cleaned.length <= 40)
    }

    @Test
    fun `实时天气整理成有结构的一句话 包含数据和来源`() {
        val text = WebSearch.composeWeather(
            WebSearch.WeatherSnapshot(
                city = "北京", temp = 12.4, feelsLike = 10.2, humidity = 55,
                wind = 9.3, desc = "小雨", high = 22.1, low = 8.4, rainChance = 30
            )
        )
        assertTrue("实际：$text", text.contains("北京现在 12.4℃"))
        assertTrue("实际：$text", text.contains("体感 10.2℃"))
        assertTrue("实际：$text", text.contains("小雨"))
        assertTrue("实际：$text", text.contains("最高 22.1℃"))
        assertTrue("实际：$text", text.contains("Open-Meteo"))
    }

    @Test
    fun `搜索结果整理成「标题 + 摘要 + 来源」`() {
        val text = WebSearch.composeSearch(
            WebSearch.SearchResult("人工智能", "是计算机科学的分支。", "https://x", "维基百科")
        )
        assertTrue("实际：$text", text.contains("我上网查了「人工智能」"))
        assertTrue("实际：$text", text.contains("来源：维基百科"))
    }

    @Test
    fun `内置城市都带了联网用的经纬度`() {
        val beijing = CityClimate.find("北京天气")!!
        assertTrue(beijing.lat > 30 && beijing.lon > 100)
        assertTrue(WebSearch.forecastUrl(beijing.lat, beijing.lon).contains("latitude=39.9"))
    }

    @Test
    fun `WMO 天气代码认得全`() {
        assertEquals("晴", WebSearch.weatherText(0))
        assertEquals("雷阵雨伴冰雹", WebSearch.weatherText(96))
        assertEquals("天气状况未知", WebSearch.weatherText(null))
    }
}
