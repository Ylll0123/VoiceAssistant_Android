package com.example.voiceassistant.provider

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.core.LlmBridge
import com.example.voiceassistant.data.JsonLite
import com.example.voiceassistant.nlu.Calc
import java.net.URLEncoder
import java.time.LocalDateTime

/**
 * 工具查询域：`tool/…`
 *
 * 四个服务按「能不能本地算」分成两档，这是这一域最重要的一条线：
 *
 * | 服务 | 怎么答 | 为什么 |
 * |---|---|---|
 * | `tool/calc` | 本地求值 | 算术必须准确，模型有时候会算错，本地算完就没这个问题 |
 * | `tool/time` | 本地系统时间 | 问「现在几点」还要花一次请求，用户能感觉到卡 |
 * | `tool/translate` | 大模型 | 翻译是生成任务，本地做不了 |
 * | `tool/weather` | 免费天气 API，失败退大模型 | 天气是实时数据，模型不知道今天的雨 |
 */
class ToolSupply(
    private val llm: LlmBridge,
    private val now: () -> LocalDateTime = { LocalDateTime.now() }
) {

    val all: List<Supply> = listOf(calc(), time(), translate(), weather())

    // ------------------------------------------------------------------ 计算

    private fun calc(): Supply = object : Supply {
        override val name = "tool/calc"
        override val summary =
            "算四则运算。参数 expression 是算式，例如「12*8」「(3+5)/2」。支持加减乘除、括号、百分号。"

        override fun invoke(args: BusArgs): BusReply {
            val raw = args.str("expression") ?: return BusReply.fail("没听清要算什么。")
            val body = Calc.stripWrapper(raw)
            val value = Calc.evaluate(body)
                ?: return BusReply.fail("这个算式没看懂：「$raw」。")
            return BusReply.ok("${body} = ${Calc.format(value)}", "value" to Calc.format(value))
        }
    }

    // ------------------------------------------------------------------ 时间

    private fun time(): Supply = object : Supply {
        override val name = "tool/time"
        override val summary =
            "查当前时间 / 日期 / 星期。参数 kind：time（默认）/ date / weekday / datetime。"

        override fun invoke(args: BusArgs): BusReply {
            val moment = now()
            val text = when (args.str("kind")) {
                "date" -> "今天是 ${moment.year} 年 ${moment.monthValue} 月 ${moment.dayOfMonth} 日。"
                "weekday" -> "今天是${weekday(moment)}。"
                "datetime" -> "现在是 ${moment.year} 年 ${moment.monthValue} 月 ${moment.dayOfMonth} 日 " +
                    "${weekday(moment)} ${hm(moment)}。"
                else -> "现在是 ${hm(moment)}。"
            }
            return BusReply.ok(text)
        }
    }

    // ------------------------------------------------------------------ 翻译

    private fun translate(): Supply = object : Supply {
        override val name = "tool/translate"
        override val summary =
            "翻译。参数 text 是要翻译的内容，to 是目标语言（中文 / 英文 / 日文，默认自动中英互译）。"

        override fun invoke(args: BusArgs): BusReply {
            val text = args.str("text") ?: return BusReply.fail("要翻译什么？")
            if (text.length > 800) return BusReply.fail("这段太长了，分几段发给我吧。")

            val to = args.str("to")
            val target = to?.takeIf { it.isNotBlank() }
                ?: if (text.any { it.code in 0x4E00..0x9FFF }) "英文" else "中文"

            val answer = llm.complete(
                prompt = "把下面这段翻译成$target。只输出译文，不要解释、不要加引号。\n$text",
                maxTokens = 600
            ) ?: return BusReply.fail("翻译需要联网，现在没连上。")

            return BusReply.ok(answer)
        }
    }

    // ------------------------------------------------------------------ 天气

    private fun weather(): Supply = object : Supply {
        override val name = "tool/weather"
        override val summary =
            "查天气。参数 city 是城市名，例如「北京」「杭州」。不说城市时默认用上次说的那个。"

        override fun invoke(args: BusArgs): BusReply {
            val city = args.str("city")?.trim()?.takeIf { it.isNotEmpty() }
                ?: LAST_CITY
                ?: return BusReply.fail("要查哪个城市的天气？")

            val live = fetchWeather(city)
            if (live != null) {
                LAST_CITY = city
                return BusReply.ok(live)
            }

            // 天气接口不通（没网 / 被挡），退给大模型；它至少知道个大概，还会说清是概略
            val fallback = llm.complete(
                prompt = "用户问$city 现在的天气。你拿不到实时数据的话，就说清楚这是大致情况、" +
                    "建议看天气应用，不要编具体数字。两三句话说清。",
                maxTokens = 200
            ) ?: return BusReply.fail("查天气要联网，现在没连上。")

            LAST_CITY = city
            return BusReply.ok(fallback)
        }
    }

    /** 走 open-meteo（免 key），成功返回一句人话，任何一步失败返回 null */
    private fun fetchWeather(city: String): String? = runCatching {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val geoRaw = com.example.voiceassistant.network.Http.get(
            "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh&format=json",
            timeoutMs = 6_000
        ) ?: return null

        val geo = JsonLite.parseOrNull(geoRaw) ?: return null
        val first = JsonLite.arr(JsonLite.get(geo, "results")).firstOrNull() ?: return null
        val latitude = JsonLite.num(JsonLite.get(first, "latitude")) ?: return null
        val longitude = JsonLite.num(JsonLite.get(first, "longitude")) ?: return null
        val name = JsonLite.str(JsonLite.get(first, "name")) ?: city

        val url = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        val raw = com.example.voiceassistant.network.Http.get(url, timeoutMs = 6_000) ?: return null
        val root = JsonLite.parseOrNull(raw) ?: return null
        val current = JsonLite.get(root, "current") ?: return null

        val temperature = JsonLite.num(JsonLite.get(current, "temperature_2m"))
            ?: return null
        val feels = JsonLite.num(JsonLite.get(current, "apparent_temperature"))
        val humidity = JsonLite.num(JsonLite.get(current, "relative_humidity_2m"))
        val code = JsonLite.num(JsonLite.get(current, "weather_code"))?.toInt() ?: -1

        val daily = JsonLite.get(root, "daily")
        val high = JsonLite.num(JsonLite.arr(JsonLite.get(daily, "temperature_2m_max")).firstOrNull())
        val low = JsonLite.num(JsonLite.arr(JsonLite.get(daily, "temperature_2m_min")).firstOrNull())

        buildString {
            append("$name 现在${describeCode(code)}，${round(temperature)}°C")
            if (high != null && low != null) append("，今天 ${round(low)}~${round(high)}°C")
            if (feels != null && kotlin.math.abs(feels - temperature) >= 2) {
                append("，体感 ${round(feels)}°C")
            }
            if (humidity != null) append("，湿度 ${round(humidity)}%")
            append("。")
        }
    }.getOrNull()

    private fun describeCode(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大致晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "有雾"
        in 51..57 -> "毛毛雨"
        in 61..67 -> "下雨"
        in 71..77 -> "下雪"
        in 80..82 -> "阵雨"
        85, 86 -> "阵雪"
        in 95..99 -> "雷阵雨"
        else -> "天气不明"
    }

    private fun round(value: Double): String = Math.round(value).toString()

    private fun weekday(moment: LocalDateTime): String = when (moment.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    private fun hm(moment: LocalDateTime): String =
        String.format("%d 点 %02d 分", moment.hour, moment.minute)

    private companion object {
        /** 上次查过的城市：用户接着问「那明天呢」时不用再报一遍地名 */
        var LAST_CITY: String? = null
    }
}
