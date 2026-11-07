package com.example.voiceassistant.provider

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.core.LlmBridge

/**
 * 学习辅助域：`study/…`
 *
 * 这一域**全部依赖大模型**，没有什么可以本地化的：释义、例句、原理讲解都是生成任务。
 * 它们能存在总线上的意义不是「省一次请求」，而是「让提醒、消息、播报也能顺手用上」——
 * 比如发消息前说「帮我解释一下这个词再发」，两条服务可以串起来。
 *
 * ## 生词本
 * 查过的词记在进程内的 [WORD_BOOK] 里，`study/word_review` 从里面出题。
 * 只存最近 40 个：够复习用，也不至于把内存当数据库使。
 */
class StudySupply(private val llm: LlmBridge) {

    val all: List<Supply> = listOf(wordQuery(), wordReview(), explain())

    // ------------------------------------------------------------------ 查词

    private fun wordQuery(): Supply = object : Supply {
        override val name = "study/word_query"
        override val summary =
            "查单词。参数 word 是要查的词。适合「XX 什么意思」「XX 怎么用」。"

        override fun invoke(args: BusArgs): BusReply {
            val word = args.str("word")?.take(40)
                ?: return BusReply.fail("要查哪个词？")

            val answer = llm.complete(
                prompt = "解释这个单词：$word\n" +
                    "按这个顺序说，每条一句、不要编号：中文意思；词性；常见搭配或变形；" +
                    "一个英文例句加中文翻译。整体不超过 6 行。",
                systemHint = "你是一位英语老师，讲解要准确、简短，不说套话。",
                maxTokens = 400
            ) ?: return BusReply.fail("查词需要联网，现在没连上。")

            remember(word)
            return BusReply.ok(answer)
        }
    }

    // ------------------------------------------------------------------ 复习

    private fun wordReview(): Supply = object : Supply {
        override val name = "study/word_review"
        override val summary = "复习单词。不给参数就从最近查过的词里挑一个出提示，让用户回忆意思。"

        override fun invoke(args: BusArgs): BusReply {
            val word = args.str("word") ?: WORD_BOOK.randomOrNull()
                ?: return BusReply.fail("还没查过单词，先让我查几个再来复习吧。")

            val answer = llm.complete(
                prompt = "我要复习单词「$word」。不要直接给中文意思，" +
                    "先给一个含这个词的英文例句（留着这个词的空格），再问「它是什么意思？」，" +
                    "最后另起一行用「参考答案：」给出简短释义。",
                systemHint = "你是一位英语老师，出题要能唤起记忆，不要一上来就报答案。",
                maxTokens = 300
            ) ?: return BusReply.fail("复习需要联网，现在没连上。")

            return BusReply.ok(answer, "word" to word)
        }
    }

    // ------------------------------------------------------------------ 讲知识点

    private fun explain(): Supply = object : Supply {
        override val name = "study/explain"
        override val summary = "解释概念、原理、实验。参数 topic 是要讲的内容。"

        override fun invoke(args: BusArgs): BusReply {
            val topic = args.str("topic")?.take(200)
                ?: return BusReply.fail("要讲什么？")
            val simple = args.bool("simple", false)

            val answer = llm.complete(
                prompt = if (simple) {
                    "用尽量大白话解释「$topic」，打一个生活里的比方，三四句话说完。"
                } else {
                    "解释「$topic」：先说一句话结论，再分两三点讲清关键，" +
                        "最后补一个常见误区。整体不超过 8 行。"
                },
                systemHint = "你讲东西习惯先给结论再展开，不铺垫、不客套。",
                maxTokens = 600
            ) ?: return BusReply.fail("讲这个需要联网，现在没连上。")

            return BusReply.ok(answer)
        }
    }

    // ------------------------------------------------------------------ 生词本

    private fun remember(word: String) {
        val key = word.trim().lowercase()
        if (key.isEmpty()) return
        synchronized(WORD_BOOK) {
            WORD_BOOK.remove(key)
            WORD_BOOK.add(key)
            if (WORD_BOOK.size > MAX_WORDS) WORD_BOOK.removeAt(0)
        }
    }

    private companion object {
        val WORD_BOOK = mutableListOf<String>()
        const val MAX_WORDS = 40
    }
}
