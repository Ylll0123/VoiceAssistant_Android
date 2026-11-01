package com.example.voiceassistant.handler.web

import com.example.voiceassistant.assistant.AssistantEngine
import com.example.voiceassistant.assistant.CityClimate
import com.example.voiceassistant.assistant.OnlineQuery
import com.example.voiceassistant.core.WebSource
import com.example.voiceassistant.network.SearchApi

/**
 * 处理器层：**按需联网**。
 *
 * 只做一件事：拿一条 [OnlineQuery.Plan] 去公开数据源取数，把结果写成一段人话。
 *
 * ## 为什么这一层是「免费」的
 * 用的全是免 Key 的公开接口（Open-Meteo 天气、必应搜索 RSS），
 * 不经过任何大模型，所以**一毛钱 token 都不花**。
 * 这也决定了它在调度链里的位置——排在云端**前面**：
 * 能搜到就直接用，搜不到才轮到花钱的那一步。
 *
 * ## 数据源与降级
 * | 用途 | 主源 | 降级源 |
 * |---|---|---|
 * | 天气（有城市） | Open-Meteo（经纬度） | 地名反查 → wttr.in |
 * | 天气（无城市） | wttr.in 按 IP 定位 | 无 → 返回 null 交给上层兜底 |
 * | 百科 | 必应搜索 RSS | （历史上用过维基，现网络不可达） |
 *
 * 全部失败返回 null，由上层回落到本地答案——**断网时助手不会变傻，只是少了实时数据**。
 */
object WebHandler : WebSource {

    /** 一次联网结果 */
    data class Result(
        val text: String,
        val intent: String = AssistantEngine.INTENT_WEB,
        /** 查的是哪个城市（天气时才有），供下一轮「那里呢」复用 */
        val city: CityClimate.CityClimate? = null
    )

    /**
     * 执行一条联网计划（**阻塞**，只能在子线程调用）。
     *
     * @return 拿到内容返回结果；查不到返回 null，调用方继续降级
     */
    override fun execute(plan: OnlineQuery.Plan): Result? = when (plan.kind) {
        OnlineQuery.Kind.WEATHER -> {
            // 没说城市就按网络定位查，让「天气怎么样」这种问题也有实时答案
            val snapshot = if (plan.city != null) {
                SearchApi.fetchWeather(plan.city, plan.dayOffset)
            } else {
                SearchApi.fetchWeatherHere(plan.dayOffset)
            }
            snapshot?.let {
                Result(
                    text = SearchApi.composeWeather(it, plan.dayLabel),
                    city = plan.city
                )
            }
        }

        OnlineQuery.Kind.SEARCH ->
            SearchApi.fetchSummary(plan.query)?.let {
                Result(text = SearchApi.composeSearch(it))
            }
    }
}
