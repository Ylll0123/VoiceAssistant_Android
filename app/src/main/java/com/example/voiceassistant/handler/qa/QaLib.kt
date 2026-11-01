package com.example.voiceassistant.handler.qa

import android.content.Context
import com.example.voiceassistant.core.TextCleaner
import com.example.voiceassistant.data.JsonLite

/**
 * 处理器层：**可编辑的本地问答表**。
 *
 * 内置的 200 多条常识写在 Kotlin 里（KnowledgeBase），加一条要改代码、重新编译。
 * 这里额外支持一份放在 `src/main/assets/qa_lib.json` 的问答表：
 *
 * ```json
 * { "qa": [ { "q": "实验室wifi密码", "a": "lab-2026" } ] }
 * ```
 *
 * 用法：直接改 JSON 文件就能加 / 改 / 删说法，**不用动代码**。
 * 命中它优先于其它本地逻辑，且完全离线、0 token。
 *
 * ## 容错
 * 文件不存在、格式写错、某条缺字段 → 只跳过那一条，
 * 不会让整个问答表失效，更不会崩溃。
 */
class QaLib private constructor(private val items: List<Entry>) {

    /** 一条问答；[variants] 是 `|` 拆出来的多种答法，会轮换 */
    data class Entry(
        val question: String,
        val answer: String,
        val variants: List<String> = split(answer)
    )

    /** 归一化后的问题 → 条目下标，查表 O(1) */
    private val exact: Map<String, Int> = buildMap {
        // 注意别写裸 `entries`：buildMap 的接收者本身是 Map，会解析成它自己的
        // entries 集合（此时还是空的），结果整张表建不出来
        items.forEachIndexed { index, item ->
            val key = TextCleaner.normalize(item.question)
            if (key.isNotEmpty()) put(key, index)
        }
    }

    /** 用于「包含匹配」的兜底：长问题在前，短的在后，避免被短句抢先 */
    private val contains: List<Pair<String, Int>> = items
        .mapIndexed { index, item -> TextCleaner.normalize(item.question) to index }
        .filter { (q, _) -> q.length >= MIN_CONTAINS_LENGTH }
        .sortedByDescending { (q, _) -> q.length }

    /** 每种答法轮到第几个了；跟 items 一一对应 */
    private val cursors = IntArray(items.size)

    /** 表里的条目数（用于容错测试：坏数据有没有被跳过） */
    fun size(): Int = exact.size

    /**
     * 查表。
     *
     * 先精确匹配（归一化后完全一样），再包含匹配（用户的话里包含某个问题，
     * 或问题里包含用户的话——后者照顾到只说了关键词的情况）。
     *
     * 命中后如果写了多种答法（`|` 分隔），这次给下一种，
     * 同一个问题反复问不会总听到同一句。
     */
    fun match(raw: String): String? {
        val norm = TextCleaner.normalize(raw)
        if (norm.isEmpty()) return null
        exact[norm]?.let { return take(it) }
        val hit = contains.firstOrNull { (q, _) -> norm.contains(q) || q.contains(norm) }
        return hit?.let { (_, index) -> take(index) }
    }

    /** 取这条问答的当前答法，并把游标推进一格 */
    @Synchronized
    private fun take(index: Int): String {
        val variants = items[index].variants
        if (variants.isEmpty()) return items[index].answer
        val position = cursors[index]
        cursors[index] = (position + 1) % variants.size
        return variants[position % variants.size]
    }

    companion object {

        /** 一条问答里 `|` 分隔的多种答法，全角竖线也认 */
        private val SEPARATOR = Regex("\\s*[|｜]\\s*")

        private fun split(answer: String): List<String> =
            SEPARATOR.split(answer).map { it.trim() }.filter { it.isNotEmpty() }

        /** 短于这个长度的问题不参与包含匹配，否则太容易误命中 */
        private const val MIN_CONTAINS_LENGTH = 3

        fun empty(): QaLib = QaLib(emptyList())

        /** 从 assets/qa_lib.json 读取；任何问题都退化成空表 */
        fun load(context: Context): QaLib = runCatching {
            val raw = context.assets.open(FILE).bufferedReader().use { it.readText() }
            fromJson(raw)
        }.getOrDefault(empty())

        /** 解析 JSON；坏数据只跳过，不抛异常 */
        fun fromJson(raw: String): QaLib {
            val root = JsonLite.parseOrNull(raw) ?: return empty()
            val array = JsonLite.arr(JsonLite.get(root, "qa"))
            val entries = mutableListOf<Entry>()
            for (item in array) {
                val fields = JsonLite.obj(item)
                val q = JsonLite.str(fields["q"])?.trim().orEmpty()
                val a = JsonLite.str(fields["a"])?.trim().orEmpty()
                if (q.isNotEmpty() && a.isNotEmpty()) entries += Entry(q, a)
            }
            return QaLib(entries)
        }

        private const val FILE = "qa_lib.json"
    }
}
