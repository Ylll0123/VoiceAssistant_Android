package com.example.voiceassistant.tool

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.ServiceBus
import com.example.voiceassistant.data.JsonLite

/**
 * 大模型与本机之间的**唯一接口**：把 `tools` 里声明的函数名翻译成总线服务名。
 *
 * ```
 * 模型返回 "device_flashlight"  ──▶  device/flashlight  ──▶  ServiceBus  ──▶ 真实现
 * ```
 *
 * ## 为什么这层现在这么薄
 * v4.0 时这里是「工具清单 + 每个工具的实现」，本地 NLU 想用同一个能力就得再写一遍。
 * 改成总线之后，实现全在 `provider/…`，这层只剩**协议适配**：
 * - 名字转换：模型不认 `/`，只认 `_`；
 * - 参数转换：模型给的是 JSON 字符串，总线要的是扁平 Map；
 * - 结果转换：总线返回 [com.example.voiceassistant.bus.BusReply]，模型只要一句人话。
 *
 * 加一个新能力时，只需要写一个 [com.example.voiceassistant.bus.Supply] 并在
 * [SCHEMA] 里补一段描述——本地和模型两条路同时就有了。
 *
 * ## 声明为什么要手写
 * 描述写得准不准，直接决定模型会不会调错、参数填得对不对。
 * 自动从代码生成描述虽然省事，但会退化成「content: 内容」这种没有信息量的句子。
 * 这三十来行 JSON 换模型少犯一次错，很划算。
 */
class AiTools(private val bus: ServiceBus) {

    /** 一次工具调用（从模型响应里解析出来的） */
    data class Call(val id: String, val name: String, val arguments: String)

    /** 工具清单（OpenAI function calling 的 `tools` 字段） */
    val schema: String get() = SCHEMA

    /** 这个工具名认不认得（单测和调试用） */
    fun knows(toolName: String): Boolean = bus.has(serviceOf(toolName))

    /**
     * 执行一次调用，返回**给模型看的结果**（一句中文）。
     *
     * 不抛异常：任何失败都转成「失败了，原因是…」，让模型自己决定怎么向用户解释。
     */
    fun execute(call: Call): String {
        val service = serviceOf(call.name)
        if (!bus.has(service)) return "没有这个工具：${call.name}"

        val args = BusArgs.from(JsonArgs.flatMap(call.arguments))
        val reply = bus.call(service, args)
        return reply.speech
    }

    /**
     * 工具名 → 服务名。
     *
     * 只换**第一个**下划线：`kws_set_wake_word` → `kws/set_wake_word`，
     * 保留动作名里的下划线，不然 `set_wake_word` 会被切成三段。
     * 具体实现统一放在 [ServiceBus.toServiceName]，两边不会各转一套。
     */
    private fun serviceOf(toolName: String): String = ServiceBus.toServiceName(toolName)

    companion object {

        /**
         * 暴露给模型的工具清单。
         *
         * 只放「模型自己做不到」的事：动手机、（真正）联网、算准数。
         * 写文案、解释概念、翻译句子这些它张嘴就来，不必包成工具——
         * 工具越多，每次请求的 token 越多，模型选错的概率也越大。
         */
        val SCHEMA: String = """
[
  {"type":"function","function":{
    "name":"schedule_add_alarm",
    "description":"在手机上新建提醒/闹钟。用户说要提醒什么并且给了时间时调用。没说时间就别调，先问清几点。",
    "parameters":{"type":"object","properties":{
      "content":{"type":"string","description":"提醒的内容，去掉时间词，例如「交作业」"},
      "at":{"type":"string","description":"触发时间，格式必须是 yyyy-MM-dd HH:mm（24 小时制，本地时间）"},
      "minutes":{"type":"number","description":"如果用户说的是「X 分钟后」，用这个字段代替 at"},
      "repeat":{"type":"string","enum":["none","daily","weekly"],"description":"重复规则，默认 none"}
    },"required":["content"]}}},
  {"type":"function","function":{
    "name":"schedule_add_todo",
    "description":"记一条没有具体时间的待办。用户说「记一下要做 X」但没说几点时用这个。",
    "parameters":{"type":"object","properties":{
      "content":{"type":"string","description":"待办内容"}
    },"required":["content"]}}},
  {"type":"function","function":{
    "name":"schedule_query",
    "description":"查看待办和闹钟。用户问「我有什么安排」「今天要干什么」时调用。",
    "parameters":{"type":"object","properties":{
      "scope":{"type":"string","enum":["all","today","timed"],"description":"范围，默认 all"}
    }}}},
  {"type":"function","function":{
    "name":"schedule_cancel",
    "description":"取消待办或闹钟。按关键词匹配；匹配到多条时不要自己选，把候选告诉用户。",
    "parameters":{"type":"object","properties":{
      "keyword":{"type":"string","description":"内容里的关键词，例如「交作业」"}
    },"required":["keyword"]}}},
  {"type":"function","function":{
    "name":"device_flashlight",
    "description":"开关手电筒。",
    "parameters":{"type":"object","properties":{
      "action":{"type":"string","enum":["on","off"],"description":"开还是关"}
    },"required":["action"]}}},
  {"type":"function","function":{
    "name":"device_brightness",
    "description":"调屏幕亮度。用户说「太亮了」「调暗点」「亮度 50%」时调用。",
    "parameters":{"type":"object","properties":{
      "percent":{"type":"number","description":"目标亮度 1-100"},
      "action":{"type":"string","enum":["up","down","max","min"],"description":"按档调时用"}
    }}}},
  {"type":"function","function":{
    "name":"device_open_app",
    "description":"打开手机上的应用。用户说「打开微信」「打开 B 站」时调用。",
    "parameters":{"type":"object","properties":{
      "name":{"type":"string","description":"应用名，例如「微信」「浏览器」"}
    },"required":["name"]}}},
  {"type":"function","function":{
    "name":"device_battery",
    "description":"看手机电量。",
    "parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{
    "name":"device_wifi_ctrl",
    "description":"开 / 关 WiFi。注意：新系统上应用不能直接切，会打开系统面板让用户点。",
    "parameters":{"type":"object","properties":{
      "action":{"type":"string","enum":["on","off"],"description":"开还是关"}
    },"required":["action"]}}},
  {"type":"function","function":{
    "name":"device_bluetooth_ctrl",
    "description":"开 / 关蓝牙，或去配对耳机。",
    "parameters":{"type":"object","properties":{
      "action":{"type":"string","enum":["on","off","connect"],"description":"开、关，或去配对"}
    },"required":["action"]}}},
  {"type":"function","function":{
    "name":"audio_volume_ctrl",
    "description":"调音量、静音。用户说「大声点」「静音」「音量 30%」时调用。",
    "parameters":{"type":"object","properties":{
      "action":{"type":"string","enum":["up","down","mute","unmute","current"],"description":"按档调或静音"},
      "percent":{"type":"number","description":"目标音量 0-100"}
    }}}},
  {"type":"function","function":{
    "name":"audio_tts_stop",
    "description":"立刻停止朗读。用户说「别念了」时调用。",
    "parameters":{"type":"object","properties":{}}}},
  {"type":"function","function":{
    "name":"tool_calc",
    "description":"算四则运算。需要精确结果的计算一律调这个，不要心算。",
    "parameters":{"type":"object","properties":{
      "expression":{"type":"string","description":"算式，例如 12*8 或 (3+5)/2"}
    },"required":["expression"]}}},
  {"type":"function","function":{
    "name":"tool_time",
    "description":"查当前时间、日期、星期。用户问「几点了」「今天几号」时调用，不要自己编。",
    "parameters":{"type":"object","properties":{
      "kind":{"type":"string","enum":["time","date","weekday","datetime"],"description":"默认 time"}
    }}}},
  {"type":"function","function":{
    "name":"tool_translate",
    "description":"翻译单词或句子。",
    "parameters":{"type":"object","properties":{
      "text":{"type":"string","description":"要翻译的内容"},
      "to":{"type":"string","description":"目标语言，例如「英文」「中文」，不填就中英互译"}
    },"required":["text"]}}},
  {"type":"function","function":{
    "name":"tool_weather",
    "description":"查实时天气。用户问天气、气温、下不下雨时调用。",
    "parameters":{"type":"object","properties":{
      "city":{"type":"string","description":"城市名，例如「杭州」"}
    }}}},
  {"type":"function","function":{
    "name":"msg_send_text",
    "description":"给联系人发微信/QQ 消息。只会打开聊天并填好内容，不会真的发出去——用户确认后再调 msg_confirm_send。找不到同名联系人会失败，这时别自己换人。",
    "parameters":{"type":"object","properties":{
      "platform":{"type":"string","enum":["wechat","qq"],"description":"默认 wechat"},
      "to":{"type":"string","description":"联系人的名字，要和通讯录里一致"},
      "content":{"type":"string","description":"要发的话"}
    },"required":["to","content"]}}},
  {"type":"function","function":{
    "name":"msg_confirm_send",
    "description":"确认并真正发送刚才准备好的那条消息。只有用户明确说「确认 / 发吧」时才调。",
    "parameters":{"type":"object","properties":{
      "trace_id":{"type":"string","description":"msg_send_text 返回的编号，不知道就别填"}
    }}}}
]
""".trimIndent()
    }
}

/**
 * 极简 JSON 参数读取。
 *
 * 模型返回的 `arguments` 是一个 JSON 字符串，字段很少，用 [JsonLite] 直接取，不引序列化库。
 * 取不到就返回 null，由调用方决定怎么兜。
 */
object JsonArgs {

    fun str(raw: String?, key: String): String? {
        val root = parse(raw) ?: return null
        return JsonLite.str(JsonLite.get(root, key))
    }

    fun num(raw: String?, key: String): Double? {
        val root = parse(raw) ?: return null
        return JsonLite.num(JsonLite.get(root, key))
    }

    /**
     * 把模型给的参数摊平成 `键 → 字符串`。
     *
     * 数值和布尔要按原样写成字符串，否则 `percent: 50` 会变成 `"50.0"`，
     * 总线里 `toIntOrNull()` 拿到的就是 50（能转），但 `on: true` 会变成 `"true"`——
     * 这正是 [BusArgs.bool] 认的写法。
     */
    fun flatMap(raw: String?): Map<String, String> {
        val root = parse(raw) ?: return emptyMap()
        val table = JsonLite.obj(root)
        if (table.isEmpty()) return emptyMap()

        val result = LinkedHashMap<String, String>(table.size)
        for ((key, value) in table) {
            if (key == null) continue
            val text = when (value) {
                null -> continue
                is String -> value
                is Double -> if (value == value.toLong().toDouble()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
                is Map<*, *> -> null // 我们不用嵌套参数，丢掉比传个乱字符串好
                is List<*> -> null
                else -> value.toString()
            } ?: continue
            if (text.isNotBlank()) result[key] = text
        }
        return result
    }

    private fun parse(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return runCatching { JsonLite.parseOrNull(raw) }.getOrNull()
    }
}
