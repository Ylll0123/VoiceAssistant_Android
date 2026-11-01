package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天气预报（明天 / 后天）+ 按定位查天气。
 *
 * 以前「明天天气怎么样」只能拿到常年平均值，还被说成"纯离线不联网"；
 * 现在取的是预报里对应那一天的数据，回答里也明确说是哪一天。
 */
class WeatherDaysTest {

    private val forecastJson = """
        {
          "current": {"temperature_2m": 21.5, "apparent_temperature": 20.1,
                      "relative_humidity_2m": 60, "weather_code": 1, "wind_speed_10m": 8.2},
          "daily": {
            "weather_code": [1, 61, 63],
            "temperature_2m_max": [24.0, 19.5, 17.0],
            "temperature_2m_min": [15.0, 12.0, 11.0],
            "precipitation_probability_max": [10, 80, 40]
          }
        }
    """.trimIndent()

    @Test
    fun `今天用实测值`() {
        val today = WebSearch.parseOpenMeteo(forecastJson, "上海")!!
        assertEquals(21.5, today.temp, 0.01)
        assertEquals("晴间多云", today.desc)
        assertEquals(60, today.humidity)
        assertEquals(0, today.dayOffset)
    }

    @Test
    fun `明天用预报里的第二天`() {
        val tomorrow = WebSearch.parseOpenMeteo(forecastJson, "上海", dayOffset = 1)!!
        assertEquals(19.5, tomorrow.temp, 0.01)
        assertEquals(12.0, tomorrow.low!!, 0.01)
        assertEquals("小雨", tomorrow.desc)
        assertEquals(80, tomorrow.rainChance)
        assertEquals(1, tomorrow.dayOffset)
    }

    @Test
    fun `后天也能取到`() {
        val day = WebSearch.parseOpenMeteo(forecastJson, "上海", dayOffset = 2)!!
        assertEquals(17.0, tomorrowHigh(day), 0.01)
        assertEquals("中雨", day.desc)
        assertEquals(2, day.dayOffset)
    }

    private fun tomorrowHigh(snapshot: WebSearch.WeatherSnapshot): Double = snapshot.temp

    @Test
    fun `预报天数写进请求地址`() {
        assertTrue(WebSearch.forecastUrl(31.2, 121.4).contains("forecast_days=1"))
        assertTrue(WebSearch.forecastUrl(31.2, 121.4, 2).contains("forecast_days=2"))
        // 天气代码也要一起要，否则预报日没有"小雨 / 阴"这种描述
        assertTrue(WebSearch.forecastUrl(31.2, 121.4, 2).contains("daily=weather_code"))
    }

    @Test
    fun `回答里要说清是哪一天`() {
        val snapshot = WebSearch.WeatherSnapshot(
            city = "上海", temp = 19.0, desc = "小雨", high = 19.5, low = 12.0,
            rainChance = 80, dayOffset = 1
        )
        val text = WebSearch.composeWeather(snapshot, "明天")
        assertTrue("实际：$text", text.contains("明天"))
        assertTrue("实际：$text", text.contains("12℃~19.5℃"))
        assertTrue("实际：$text", text.contains("带把伞"))
    }

    @Test
    fun `按定位查到的天气说「你那里」而不是城市名`() {
        val snapshot = WebSearch.WeatherSnapshot(
            city = "Shanghai", temp = 20.0, desc = "晴", fromLocation = true
        )
        val text = WebSearch.composeWeather(snapshot, "今天")
        assertTrue("实际：$text", text.contains("你那里"))
        assertTrue("实际：$text", !text.contains("Shanghai"))
    }

    @Test
    fun `wttr 定位响应里的地名能取出来`() {
        val raw = """
            {"nearest_area":[{"areaName":[{"value":"Shanghai"}],
             "country":[{"value":"China"}]}]}
        """.trimIndent()
        assertEquals("Shanghai（China）", WebSearch.parseWttrCity(raw))
        assertNull(WebSearch.parseWttrCity(""))
        assertNull(WebSearch.parseWttrCity("{"))
    }

    @Test
    fun `坏数据不会崩`() {
        assertNull(WebSearch.parseOpenMeteo("", "上海"))
        assertNull(WebSearch.parseOpenMeteo("{}", "上海"))
        assertNull(WebSearch.parseOpenMeteo("{", "上海", 1))
    }
}
