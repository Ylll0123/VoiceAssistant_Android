package com.example.voiceassistant.core

import com.example.voiceassistant.assistant.FuzzyMatcher

/**
 * 通用工具层：文本清洗。
 *
 * 「把用户说的话变成可以比较的形式」这件事以前散落在规则、记忆、搜索各处，
 * 各写各的，规则对不齐（有的去标点、有的不去）。现在统一收口在这里：
 * 匹配、检索、缓存键都先过这一道，保证同一句话只有一种内部写法。
 *
 * 底层复用 [FuzzyMatcher]（去标点空格 + 同义词归一 + 剥客套前缀），
 * 这里只补语音场景特有的两件事：口语填充词、缓存键。
 *
 * 纯函数、无状态、无平台依赖，可以直接在单测里断言。
 */
object TextCleaner {

    /** 归一化：去标点空格、同义词归一、剥掉「帮我查一下」这类客套前缀 */
    fun normalize(raw: String): String = FuzzyMatcher.normalize(raw)

    /** 是否没有实际内容（纯标点 / 空格 / 「嗯啊哦」都算空） */
    fun isBlank(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return true
        return normalize(raw).isEmpty()
    }

    /** 去掉首尾标点与多余空白，保留内容骨架 */
    fun trim(raw: String): String = raw.trim()
        .trim('，', ',', '。', '.', '！', '!', '？', '?', '、', '；', ';', '：', ':', ' ')

    /**
     * 截断到指定字数。
     *
     * 发给云端的历史和问题都限长：一是省 token，
     * 二是避免半句话被模型当成完整语义理解。超出部分用省略号收尾。
     */
    fun clip(text: String, max: Int = DEFAULT_CLIP_CHARS): String {
        val value = text.trim()
        return if (value.length <= max) value else value.substring(0, max) + "…"
    }

    /**
     * 缓存键：归一化之后再抹掉所有标点空格。
     * 「上海天气怎么样」和「上海 天气 怎么样！」应该命中同一条缓存。
     */
    fun cacheKey(raw: String): String = PUNCT.replace(normalize(raw), "")

    private val PUNCT = Regex("[\\s\\p{Punct}，。！？、；：\"'（）《》〈〉…—～·]+")

    const val DEFAULT_CLIP_CHARS = 40
}
