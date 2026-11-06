package com.example.voiceassistant.assistant

/**
 * 对话行为配置（纯 Kotlin，不依赖 Android，可在单元测试里直接改）。
 *
 * 设置页改的「回答风格 / 主动追问」最终都落到这里：
 * 界面写 [AppSettings]，Activity 启动时把值灌进本对象，
 * 调度层再按它决定给大模型追加什么风格指令（见 AssistantRouter.buildExtraSystem）。
 *
 * 分成两层的理由：规则 / 引擎是纯逻辑，不应该知道 SharedPreferences 的存在，
 * 否则每个用例都要先造一个 Context。
 */
object AssistantConfig {

    /** 回答详细程度 */
    enum class ReplyStyle {
        /** 简洁：只给一句核心回应，不展开、不追问 */
        CONCISE,

        /** 标准：核心回应 + 隔轮反问 */
        STANDARD,

        /** 详细：核心回应 + 一句展开 + 反问 */
        DETAILED
    }

    var replyStyle: ReplyStyle = ReplyStyle.STANDARD

    /** 回答完是否主动反问一句（把话头递回去，让对话能继续） */
    var proactiveFollowUp: Boolean = true

    /**
     * 联网查询：实时天气、百科问答会去网上查，并用查到的内容替换本地答案。
     * 关掉之后完全离线（只给常年气候数据），没网也能用基础功能。
     */
    var onlineSearch: Boolean = true

    fun reset() {
        replyStyle = ReplyStyle.STANDARD
        proactiveFollowUp = true
        onlineSearch = true
    }
}
