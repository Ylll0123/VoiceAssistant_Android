package com.example.voiceassistant.assistant

import com.example.voiceassistant.service.AudioController
import com.example.voiceassistant.service.DeviceController
import com.example.voiceassistant.tool.ReminderTool
import com.example.voiceassistant.tool.ToolHandler
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能页一致性测试。
 *
 * 技能页展示的每个示例说法，都必须能被引擎真正执行；
 * 否则就是「页面上写着能做、实际却答不上来」的假功能。
 *
 * 依赖上下文的两个技能（接着算、多轮追问）需要前置对话，单独测。
 */
class SkillCatalogTest {

    /** 依赖上下文的技能（接着算、多轮追问）需要前置对话，单独测 */
    private val contextDependent = setOf("calc_continue", "follow_up")

    /** 依赖联网的技能由 Activity 层异步执行，纯引擎层跑不到，在 OnlineQueryTest 里验证 */
    private val networkDependent = setOf("online")

    /** 和真机一致的引擎：带一个本地记忆库（测试用内存实现） */
    private fun newEngine() = AssistantEngine()

    @Test
    fun `技能页的每个示例都能被引擎执行`() {
        SkillCatalog.skills
            .filterNot { it.id in contextDependent }
            .filterNot { it.id in networkDependent }
            .filterNot { it.tool }
            .forEach { skill ->
                skill.examples.forEach { example ->
                    val response = newEngine().respond(example)
                    assertNotEquals(
                        "技能「${skill.title}」的示例「$example」没命中任何规则",
                        AssistantEngine.INTENT_FALLBACK,
                        response.intent
                    )
                }
            }
    }

    /**
     * 工具类技能（提醒 / 计时 / 手电筒…）由工具层执行，引擎测不到。
     * 但「页面上写着能做、实际却做不了」的底线同样要守——
     * 这里换成验证「工具层认领了它」。
     */
    @Test
    fun `工具类技能的每个示例都能被工具层认领`() {
        ReminderTool.sink.clear()
        val handler = ToolHandler(object : AudioController {
            override fun setMuted(muted: Boolean) = Unit
            override fun isMuted(): Boolean = false
            override fun adjustVolume(up: Boolean) = Unit
        }, object : DeviceController {
            override fun battery(): String = "现在电量 80%。"
            override fun setTorch(on: Boolean): Boolean = true
            override fun openApp(name: String): String? = name
        })
        val local = AssistantResponse("兜底", "fallback")

        val toolSkills = SkillCatalog.skills.filter { it.tool }
        assertTrue("应当有工具类技能", toolSkills.isNotEmpty())

        toolSkills.forEach { skill ->
            skill.examples.forEach { example ->
                assertTrue(
                    "技能「${skill.title}」的示例「$example」没被工具层认领",
                    handler.claims(example, local)
                )
            }
        }
    }

    @Test
    fun `接着算的示例在上下文存在时可以执行`() {
        val engine = newEngine()
        engine.reply("帮我算 12*8")

        val example = SkillCatalog.find("calc_continue")!!.examples.first()
        assertNotEquals(AssistantEngine.INTENT_FALLBACK, engine.respond(example).intent)
    }

    @Test
    fun `每个分类下都有技能`() {
        SkillCatalog.categories.forEach { category ->
            assert(SkillCatalog.byCategory(category).isNotEmpty()) {
                "分类「$category」下没有技能"
            }
        }
    }
}
