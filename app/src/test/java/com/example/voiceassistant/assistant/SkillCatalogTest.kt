package com.example.voiceassistant.assistant

import com.example.voiceassistant.memory.InMemoryMemoryRepository
import org.junit.Assert.assertNotEquals
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
    private fun newEngine() = AssistantEngine(memory = InMemoryMemoryRepository())

    @Test
    fun `技能页的每个示例都能被引擎执行`() {
        SkillCatalog.skills
            .filterNot { it.id in contextDependent }
            .filterNot { it.id in networkDependent }
            .forEach { skill ->
                skill.examples.forEach { example ->
                    val reply = newEngine().reply(example)
                    assertNotEquals(
                        "技能「${skill.title}」的示例「$example」没命中任何规则",
                        AssistantEngine.FALLBACK,
                        reply
                    )
                }
            }
    }

    @Test
    fun `接着算的示例在上下文存在时可以执行`() {
        val engine = newEngine()
        engine.reply("帮我算 12*8")

        val example = SkillCatalog.find("calc_continue")!!.examples.first()
        assertNotEquals(AssistantEngine.FALLBACK, engine.reply(example))
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
