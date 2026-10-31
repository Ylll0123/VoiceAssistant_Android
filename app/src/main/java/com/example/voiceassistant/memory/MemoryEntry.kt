package com.example.voiceassistant.memory

/**
 * 一条本地记忆：一句话问法 → 一句话答法。
 *
 * @param question 展示用的原始问法（保留用户当时的说法）
 * @param normalized 归一化后的问法，检索匹配用它（去标点、去客套前缀、同义词归一）
 * @param answer 回答
 * @param source 来源：内置 [SOURCE_SEED] / 用户教的 [SOURCE_TAUGHT]
 * @param hits 被命中几次，用于排序「最常用的记忆」
 */
data class MemoryEntry(
    val id: Long,
    val question: String,
    val normalized: String,
    val answer: String,
    val source: String,
    val hits: Int = 0,
    val updatedAt: Long = 0L
) {
    val isTaught: Boolean get() = source == MemoryRepository.SOURCE_TAUGHT
}

/** 记忆检索结果 */
data class MemoryHit(
    val entry: MemoryEntry,
    val score: Double
)

/**
 * 记忆仓库：负责持久化，不负责「像不像」的判断（那是 [MemorySearch] 的事）。
 *
 * 界面只依赖这个接口，测试用 [InMemoryMemoryRepository]，真机用 [SqliteMemoryRepository]，
 * 这样学习逻辑可以完全脱离 Android 做单元测试。
 */
interface MemoryRepository {

    fun entries(): List<MemoryEntry>

    fun count(): Int

    /** 记住一句话；如果归一化后的问题已存在，则覆盖答案 */
    fun remember(question: String, answer: String, source: String = SOURCE_TAUGHT): MemoryEntry

    fun delete(id: Long)

    fun clear()

    fun bumpHit(id: Long)

    companion object {
        const val SOURCE_SEED = "seed"
        const val SOURCE_TAUGHT = "taught"
    }
}
