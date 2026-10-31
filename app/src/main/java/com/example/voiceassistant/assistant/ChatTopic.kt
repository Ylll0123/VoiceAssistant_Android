package com.example.voiceassistant.assistant

/**
 * 一个日常闲聊话题。
 *
 * 每个话题都不止一种回答，这是「同一句话问两遍，答案不会一模一样」的基础：
 * - [replies] 对这句话的几种回应，按轮次轮换，并避开刚说过的那句
 * - [more] 被追问「为什么 / 然后呢 / 真的吗」时的展开
 * - [advice] 被问「那怎么办 / 我该怎么做」时的具体建议
 * - [followUps] 反问，用来把话头递回去，让对话能继续
 * - [keywords] 强触发词；[samples] 是没带关键词的常见说法，靠相似度兜住
 *
 * 内容全部离线内置，不联网、不上传。
 */
data class ChatTopic(
    val id: String,
    val title: String,
    val keywords: List<String>,
    val samples: List<String>,
    val replies: List<String>,
    val more: List<String> = emptyList(),
    val advice: List<String> = emptyList(),
    val followUps: List<String> = emptyList()
)
