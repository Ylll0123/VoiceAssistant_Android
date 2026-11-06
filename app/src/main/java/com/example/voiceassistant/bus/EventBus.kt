package com.example.voiceassistant.bus

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 事件总线的消息基类。
 *
 * 只要求「是个对象」，不规定字段——事件长什么样由发它的模块决定，
 * 总线本身不认识任何业务类型。
 */
interface Event

/**
 * 进程内事件总线：**四个模块之间唯一的通信方式**。
 *
 * ```
 * 语音链路 ──RawText──▶ 模糊容错 ──IntentRecognized──▶ 对话管理
 *                                                        │
 * 提醒中心 ◀─────────── 指令 ────────────────────────────┘
 *    │
 *    └──ReminderFired──▶ UI / TTS
 * ```
 *
 * ## 为什么自己写而不用 EventBus / Flow
 * 1. 引入第三方库只为几十行分发逻辑不划算（答辩时也不好讲清「它做了什么」）；
 * 2. [publish] 是**同步**分发的：谁发的事件，订阅者就在同一线程里被调完。
 *    这样「发意图 → 对话管理器处理 → 发回复」的先后顺序是确定的，
 *    不会出现「回复比提问先到」这种只有异步总线才有的怪问题；
 * 3. 单个订阅者抛异常**不会**影响其它订阅者——业务出错不能连累通知。
 *
 * ## 订阅约定
 * - 用 [on] 订阅，传一个 `owner`（通常是 Activity / Fragment），
 *   页面销毁时用 [off] 一次注销该 owner 的全部订阅，不会漏；
 * - 事件类型按 [on] 的第一个参数精确匹配（`isInstance`），
 *   父类型订阅收不到子类型事件——避免「所有事件都进一个 handler」的倒退写法。
 */
class EventBus {

    /** 一条订阅：谁订的、订的是什么类型、怎么处理 */
    internal class Entry(
        val owner: Any?,
        val type: Class<*>,
        val handler: (Event) -> Unit
    )

    private val entries = CopyOnWriteArrayList<Entry>()

    /** 订阅者内部抛出的异常交给它；默认丢掉（业务异常不该拖垮分发） */
    var onError: ((Throwable) -> Unit)? = null

    /** 一条订阅的凭证；也可以直接 [EventBus.off] 按 owner 批量注销 */
    inner class Subscription internal constructor(private val entry: Entry) {
        fun cancel() {
            entries.remove(entry)
        }

        val active: Boolean get() = entries.contains(entry)
    }

    /** 订阅某类事件 */
    fun <T : Event> on(type: Class<T>, owner: Any? = null, handler: (T) -> Unit): Subscription {
        @Suppress("UNCHECKED_CAST")
        val entry = Entry(owner, type, handler as (Event) -> Unit)
        entries += entry
        return Subscription(entry)
    }

    /** Kotlin 侧更好写的重载：`bus.on<RawText> { ... }` */
    inline fun <reified T : Event> on(owner: Any? = null, noinline handler: (T) -> Unit): EventBus.Subscription =
        on(T::class.java, owner, handler)

    /**
     * 发一条事件。
     *
     * @return 实际被调到的订阅者数量（0 表示没人关心，这是正常的）
     */
    fun publish(event: Event): Int {
        var delivered = 0
        for (entry in entries) {
            if (!entry.type.isInstance(event)) continue
            delivered++
            runCatching { entry.handler(event) }
                .onFailure { onError?.invoke(it) }
        }
        return delivered
    }

    /** 注销某个 owner 的全部订阅（页面销毁时调一次即可） */
    fun off(owner: Any) {
        entries.removeAll { it.owner === owner }
    }

    /** 清空全部订阅（单测之间隔离用） */
    fun clear() {
        entries.clear()
    }

    /** 当前订阅条数，单测用来钉「注销后不残留」 */
    val size: Int get() = entries.size
}
