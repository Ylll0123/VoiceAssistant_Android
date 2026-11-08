package com.example.voiceassistant.bus

/**
 * 供需总线的三块基石：**供给方**（[Supply]）、**参数**（[BusArgs]）、**回执**（[BusReply]）。
 *
 * ## 为什么叫「供需」
 * 界面和路由是「需求方」——它们只知道「我要关手电筒」，不知道手电筒怎么关；
 * 各 Provider 是「供给方」——它们只会关手电筒，不关心是谁提的需求。
 * 中间这层总线负责把 `device/flashlight` 这个名字翻译成一个对象并调它。
 *
 * ## 命名约定
 * 所有服务一律 `域/动作`，例如 `schedule/add_alarm`、`audio/tts_speak`。
 * 好处是路由可以按 `/` 前面那一段做粗分（`if (name.startsWith("device/"))`），
 * 也可以只登记一个域、批量挂上去。
 *
 * ⚠️ 大模型的 function calling 不接受 `/`（函数名只允许字母数字下划线横线），
 * 所以对外暴露时统一用 [ServiceBus.toToolName] 换成下划线，回来时用 [ServiceBus.toServiceName] 换回来。
 */
interface Supply {

    /** 服务全名，必须是 `域/动作`，例如 `device/flashlight` */
    val name: String

    /**
     * 一句话说明「这个服务能干什么、参数怎么给」。
     *
     * 这段文字会被拼进大模型的工具描述里，写得准不准直接决定模型会不会用错。
     */
    val summary: String

    /**
     * 真的去干活。
     *
     * 约定：
     * 1. **不要抛异常**——失败就返回 `BusReply.fail("原因")`，
     *    总线会兜住异常但那样拿不到人话，模型只能干着急；
     * 2. 耗时操作（联网、无障碍点击）允许阻塞，总线默认在后台线程被调用；
     * 3. 所有按钮、视图操作要自己 post 到主线程，总线不帮你切。
     */
    fun invoke(args: BusArgs): BusReply
}

/**
 * 一次调用的参数。
 *
 * 用扁平的「字符串键 → 字符串值」而不是强类型对象，是为了让**同一份参数**
 * 既能由本地 NLU 填、也能由大模型的 JSON 参数填——两边都是字符串，不用各写一套转换。
 * 类型转换在这里做掉（[num] / [int] / [bool]），供给方只管取。
 */
class BusArgs(private val table: Map<String, String> = emptyMap()) {

    /** 取一个非空字符串；空串按「没给」处理，省得每个供给方都写 `if (x.isBlank())` */
    fun str(key: String): String? = table[key]?.trim()?.takeIf { it.isNotEmpty() }

    /** 取数字；给了但不是数字也算没给 */
    fun num(key: String): Double? = str(key)?.toDoubleOrNull()

    fun int(key: String): Int? = str(key)?.toDoubleOrNull()?.toInt()

    /**
     * 取布尔。
     *
     * 模型有时给 `"true"`、有时给 `true`（被转成字符串）、有时给 `"开"`，
     * 三种都认；实在没有就用 [default]。
     */
    fun bool(key: String, default: Boolean = false): Boolean = when (str(key)) {
        null -> default
        "true", "1", "yes", "on", "开", "打开", "是" -> true
        "false", "0", "no", "off", "关", "关闭", "否" -> false
        else -> default
    }

    /** 原样拿全部参数（需要透传时用） */
    fun all(): Map<String, String> = table

    fun isEmpty(): Boolean = table.isEmpty()

    /** 补一个参数，返回新对象（NLU 抽到槽位后逐层拼装时比可变 Map 安全） */
    fun plus(key: String, value: String): BusArgs = BusArgs(table + (key to value))

    override fun toString(): String = table.toString()

    companion object {
        val EMPTY: BusArgs = BusArgs()

        fun of(vararg pairs: Pair<String, String>): BusArgs = BusArgs(pairs.toMap())

        fun from(table: Map<String, String>): BusArgs = BusArgs(table)
    }
}

/**
 * 一次调用的回执。
 *
 * [speech] 是**唯一的输出通道**，故意不分成「给用户的话」和「给模型的话」两份：
 * 同一句「手电筒已打开。」既能直接显示在气泡里，也能原样当作 tool 结果喂回模型，
 * 两边看到的永远是同一件事，不会出现「界面说成了、模型以为失败」这种错位。
 */
data class BusReply(
    /** 是否办成了；false 时 [speech] 必须说清原因（这句话会直接给用户看） */
    val ok: Boolean,
    /** 一句中文结果 */
    val speech: String,
    /** 结构化附加信息（例如 `need_confirm=true`、`trace_id=...`） */
    val data: Map<String, String> = emptyMap()
) {
    companion object {
        fun ok(speech: String, vararg data: Pair<String, String>): BusReply =
            BusReply(true, speech, data.toMap())

        fun fail(speech: String, vararg data: Pair<String, String>): BusReply =
            BusReply(false, speech, data.toMap())

        /** 需要用户再确认一次（发消息这类不可撤销的动作） */
        fun confirm(speech: String, traceId: String, vararg data: Pair<String, String>): BusReply =
            BusReply(true, speech, data.toMap() + mapOf("need_confirm" to "true", "trace_id" to traceId))
    }
}

/**
 * 供需总线：**进程内唯一一张「服务名 → 供给方」的表**。
 *
 * ```
 * 本地 NLU ──┐
 *            ├─▶ ServiceBus.call("device/flashlight", args) ──▶ 各 Provider
 * 大模型工具 ─┘
 * ```
 *
 * ## 两条入口共用一套服务
 * 这是这次改造的核心：本地识别和大模型工具调用**落到同一批实现**上。
 * 以前「本地能做的」和「模型能做的」是两套代码，加一个新能力要改两处；
 * 现在只写一个 [Supply]，两边都自动拥有。
 *
 * ## 找不到服务怎么办
 * 返回失败回执而不是抛异常：模型可能编了个不存在的名字（幻觉），
 * 直接告诉它「没有这个服务」比让整轮对话崩掉划算。
 */
class ServiceBus {

    private val registry = LinkedHashMap<String, Supply>()

    /** 已注册的服务名，按注册顺序（设置页 / 调试日志用） */
    val names: List<String> get() = registry.keys.toList()

    /** 挂一个供给方；同名会覆盖（热替换某条实现时不必先反注册） */
    fun register(supply: Supply): ServiceBus {
        registry[supply.name] = supply
        return this
    }

    fun registerAll(vararg supplies: Supply): ServiceBus {
        supplies.forEach { register(it) }
        return this
    }

    fun has(name: String): Boolean = registry.containsKey(name)

    fun get(name: String): Supply? = registry[name]

    /**
     * 调用一个服务。
     *
     * @return 永远非空。名字不存在、供给方抛异常，都会转成失败回执。
     */
    fun call(name: String, args: BusArgs = BusArgs.EMPTY): BusReply {
        val supply = registry[name] ?: return BusReply.fail("没有「$name」这个服务。")
        return runCatching { supply.invoke(args) }.getOrElse { error ->
            BusReply.fail("这个操作没做成：${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 清空注册表（单测之间隔离用） */
    fun clear() {
        registry.clear()
    }

    companion object {

        /**
         * `schedule/add_alarm` → `schedule_add_alarm`
         *
         * 大模型的函数名只允许 `[a-zA-Z0-9_-]`，斜杠会被网关直接判 400。
         */
        fun toToolName(serviceName: String): String = serviceName.replace('/', '_')

        /** 反向：`schedule_add_alarm` → `schedule/add_alarm` */
        fun toServiceName(toolName: String): String {
            val index = toolName.indexOf('_')
            if (index <= 0) return toolName
            // 只换第一个：动作名自己带下划线（query_can_send），全换会切成四段
            return toolName.substring(0, index) + "/" + toolName.substring(index + 1)
        }

        /** 判断一个名字是不是总线格式（含且仅含一个 `/`，两边都非空） */
        fun isServiceName(name: String): Boolean {
            val parts = name.split('/')
            return parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()
        }
    }
}
