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
 * 所以这里的提示词咬死一条：**只输出成品，不要解释**。
 *
 * 典型串法：`gen/short_sentence` 拿到一句话 → `msg/send_text` 发出去 → `audio/tts_speak` 确认。
 *
 * ## 为什么只留这一个
 * 之前还有 `gen/draft`（起草通知/申请）和 `gen/summarize`（做摘要），
 * 但它们和「问答」的差别只是提示词，模型自己就能写，包成工具只占 token、还容易选错。
 * 真正需要单独一个服务的只有「一句能原样粘进聊天框的话」——它要的是**成品形状**。
 */
class GenSupply(private val llm: LlmBridge) {

    val all: List<Supply> = listOf(shortSentence())

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

    /** 模型偶尔还是会带上「好的，这句话是：」——把这种壳剥掉 */
    private fun cleanOutput(raw: String): String {
        var text = raw.trim()
        text = text.removePrefix("好的").removePrefix("可以").removePrefix("这句话是")
        text = text.trim('：', ':', ' ', '\n')
        text = text.removeSurrounding("\"").removeSurrounding("「", "」").removeSurrounding("“", "”")
        return text.trim()
    }
}
