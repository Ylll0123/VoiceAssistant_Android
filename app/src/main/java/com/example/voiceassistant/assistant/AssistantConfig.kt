package com.example.voiceassistant.assistant

/**
 * 对话行为配置（纯 Kotlin，不依赖 Android，可在单元测试里直接改）。
 *
 * 设置页改的「回答风格 / 主动追问 / 自学习」最终都落到这里：
 * 界面写 [AppSettings]，Activity 启动时把值灌进本对象，
 * 闲聊引擎和对话引擎读本对象决定怎么说话。
 *
 * 分成两层的理由：规则 / 语料 / 引擎是纯逻辑，不应该知道 SharedPreferences 的存在，
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
     * 自学习：遇到和记忆里某条相似但不一样的说法时，
     * 借用它的答案作答，并把这个新说法自动记下来。
     */
    var autoLearn: Boolean = true

    fun reset() {
        replyStyle = ReplyStyle.STANDARD
        proactiveFollowUp = true
        autoLearn = true
    }
}
