package com.example.voiceassistant.data

/**
 * 一条对话记录（一个「会话」）。
 *
 * 抽屉里列的就是它：id 用于取消息，title 是**大模型读完前两句后起的名字**，
 * updatedAt 决定排序（最近说过的排最上面）。
 *
 * 没有 title 时先用首句当临时名字（见 [ConversationStore.create]），
 * 等模型起了名再换——这样即使没网，抽屉里也不是一排「未命名」。
 */
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 标题是否已经由大模型总结过；false 表示现在显示的是首句截断 */
    val named: Boolean = false
) {
    companion object {
        /** 还没起名的会话占位 */
        const val UNTITLED = "新对话"

        /** 抽屉里最多留多少条会话，再多就淘汰最旧的 */
        const val MAX = 60
    }
}
