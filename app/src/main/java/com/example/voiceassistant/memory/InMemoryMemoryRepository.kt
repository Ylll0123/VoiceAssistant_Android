package com.example.voiceassistant.memory

import com.example.voiceassistant.assistant.FuzzyMatcher

/**
 * 内存版记忆仓库：单元测试用，也作为 SQLite 打开失败时的兜底，
 * 保证「记忆」这块功能挂掉不影响对话主流程。
 */
class InMemoryMemoryRepository(
    initial: List<MemoryEntry> = emptyList()
) : MemoryRepository {

    private val items = mutableListOf<MemoryEntry>()
    private var nextId = 1L

    init {
        initial.forEach { add(it.question, it.answer, it.source) }
    }

    override fun entries(): List<MemoryEntry> = items.toList()

    override fun count(): Int = items.size

    override fun remember(question: String, answer: String, source: String): MemoryEntry =
        add(question, answer, source)

    private fun add(question: String, answer: String, source: String): MemoryEntry {
        val normalized = FuzzyMatcher.normalize(question)
        val existing = items.firstOrNull { it.normalized == normalized }
        if (existing != null) {
            val updated = existing.copy(answer = answer, source = source, updatedAt = now())
            items[items.indexOf(existing)] = updated
            return updated
        }
        val entry = MemoryEntry(
            id = nextId++,
            question = question.trim(),
            normalized = normalized,
            answer = answer.trim(),
            source = source,
            updatedAt = now()
        )
        items.add(entry)
        return entry
    }

    override fun delete(id: Long) {
        items.removeAll { it.id == id }
    }

    override fun clear() {
        items.clear()
    }

    override fun bumpHit(id: Long) {
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) items[index] = items[index].copy(hits = items[index].hits + 1)
    }

    private fun now(): Long = System.currentTimeMillis()
}
