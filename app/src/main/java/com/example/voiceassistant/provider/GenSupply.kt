package com.example.voiceassistant.provider

import com.example.voiceassistant.bus.BusArgs
import com.example.voiceassistant.bus.BusReply
import com.example.voiceassistant.bus.Supply
import com.example.voiceassistant.core.LlmBridge

/**
 * 内容生成域：`gen/…`
 *
 * 和「问答」分开路由，是因为**输出的形状不一样**：
 * 问答要的是一段解释，生成要的是「一句能直接发出去的话」。
 * 混在一起时模型经常给「你可以这样说：……」这种带壳的答案，粘到聊天框里还得再删一遍。
 *
 * 所以这三个服务的提示词都咬死一条：**只输出成品，不要解释**。
 *
 * 典型串法：`gen/short_sentence` 拿到一句话 → `msg/send_text` 发出去 → `audio/tts_speak` 确认。
 */
class GenSupply(private val llm: LlmBridge) {

    val all: List<Supply> = listOf(shortSentence(), draft(), summarize())

    // ------------------------------------------------------------------ 短句

    private fun shortSentence(): Supply = object : Supply {
        override val name = "gen/short_sentence"
        override val summary =
            "生成一句可以直接发出去的话（消息回复、祝福、道歉）。参数 scene 是场景，" +
                "tone 是语气（随便 / 客气 / 正式），to 是发给谁。"

        override fun invoke(args: BusArgs): BusReply {
            val scene = args.str("scene") ?: return BusReply.fail("要写什么话？")
            val tone = args.str("tone") ?: "自然"
            val to = args.str("to")

            val answer = llm.complete(
                prompt = buildString {
                    append("写一句可以直接发给")
                    append(to ?: "对方")
                    append("的话。场景：$scene。语气：$tone。")
                    append("\n要求：一句话，不超过 40 字，不要引号、不要「你可以说」这类前缀，只输出这句话本身。")
                },
                maxTokens = 120
            ) ?: return BusReply.fail("写这句话需要联网，现在没连上。")

            return BusReply.ok(cleanOutput(answer))
        }
    }

    // ------------------------------------------------------------------ 起草

    private fun draft(): Supply = object : Supply {
        override val name = "gen/draft"
        override val summary =
            "起草一段短文本（通知、请假、申请、自我介绍）。参数 topic 是要写什么，length 是大概字数。"

        override fun invoke(args: BusArgs): BusReply {
            val topic = args.str("topic") ?: return BusReply.fail("要起草什么？")
            val length = args.int("length")?.coerceIn(50, 800) ?: 200

            val answer = llm.complete(
                prompt = "帮我写一段$topic，大约 $length 字。\n" +
                    "要求：结构清楚、语气得体、不要用「首先其次最后」这种模板腔，" +
                    "不要写标题，直接给正文。",
                maxTokens = 900
            ) ?: return BusReply.fail("起草需要联网，现在没连上。")

            return BusReply.ok(answer.trim())
        }
    }

    // ------------------------------------------------------------------ 摘要

    private fun summarize(): Supply = object : Supply {
        override val name = "gen/summarize"
        override val summary = "给一段文字做摘要。参数 text 是原文，points 是要几条（默认 3）。"

        override fun invoke(args: BusArgs): BusReply {
            val text = args.str("text") ?: return BusReply.fail("要总结哪段内容？")
            if (text.length > 4_000) return BusReply.fail("这段太长了，分两段发给我吧。")
            val points = args.int("points")?.coerceIn(1, 8) ?: 3

            val answer = llm.complete(
                prompt = "把下面这段压缩成 $points 条要点，每条一行、不超过 25 字，" +
                    "不要编号以外的修饰，不要开头结尾的客套话。\n\n$text",
                maxTokens = 500
            ) ?: return BusReply.fail("做摘要需要联网，现在没连上。")

            return BusReply.ok(answer.trim())
        }
    }

    /** 模型偶尔还是会带上「好的，这句话是：」——把这种壳剥掉 */
    private fun cleanOutput(raw: String): String {
        var text = raw.trim()
        text = text.removePrefix("好的").removePrefix("可以").removePrefix("这句话是")
        text = text.trim('：', ':', ' ', '\n')
        text = text.removeSurrounding("\"").removeSurrounding("「", "」").removeSurrounding("“", "”")
        return text.trim()
    }
}
