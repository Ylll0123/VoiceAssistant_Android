package com.example.voiceassistant.assistant

import kotlin.random.Random

/**
 * 内置的小规模语言模型（字符级 n-gram，纯 Kotlin 实现）。
 *
 * ## 它是什么
 * 一个真正意义上的「训练出来的模型」，不是规则表：
 * - 用**字符三元组（trigram）+ 二元组（bigram）回退**统计语言的接续概率；
 * - 启动时用内置语料（闲聊话题 + 预置记忆）训练一遍；
 * - 之后**每一轮对话都会增量学习**：用户说的话、助手的回答都进训练集，
 *   聊得越多，模型对「字怎么接字」的估计越准——这就是不需要联网的自学习；
 * - 参数（训练句子集）可以序列化到本地文件，重启后接着学，不归零。
 *
 * ## 它怎么被使用
 * 规则、语料、记忆库全都答不上来时（以前只能提示「你可以教我」），
 * 现在先问这个模型：从用户输入和训练句子里共有的「字组」出发，
 * 沿概率接续随机游走，生成一句相关的话。生成不出来（没共同字组）
 * 才退回「你可以教我」的兜底。
 *
 * ## 为什么不用 llama.cpp 这类真正的 LLM
 * 那类模型需要 NDK 交叉编译 + 几百 MB 权重文件，一台课程作业的 APK 扛不住；
 * 而本项目的核心诉求是「离线、可自学习、可解释」——字符级 n-gram 恰好
 * 全部满足，还能在答辩时把模型参数（学了多少句、多少个字组）直接展示出来。
 */
class NgramModel {

    /** 上下文（两个字符）→ 下一个字符 → 出现次数 */
    private val trigram = HashMap<String, HashMap<Char, Int>>()

    /** 上一个字符 → 下一个字符 → 出现次数（三元组查不到时回退用） */
    private val bigram = HashMap<Char, HashMap<Char, Int>>()

    /** 训练过的句子（保序去重；序列化时只存它，参数由它重建） */
    private val sentences = LinkedHashSet<String>()

    /** 训练过的问答对（保序）：检索式回答的素材，也是"问句接答案"的来源 */
    private val pairs = LinkedHashMap<String, String>()

    /** 训练后有改动、需要写盘 */
    var dirty: Boolean = false
        private set

    /** 写盘后调用，清掉脏标记 */
    fun markSaved() {
        dirty = false
    }

    /** 训练过的句子数 */
    fun sentenceCount(): Int = sentences.size

    /** 模型里不同「上下文 → 接续」节点的数量，答辩展示用 */
    fun nodeCount(): Int = trigram.size + bigram.size

    // ------------------------------------------------------------------ 训练

    /** 学习一句话：把它的二元组 / 三元组统计进模型 */
    fun observe(text: String) {
        val sentence = clean(text)
        if (sentence.length < MIN_SENTENCE_LENGTH) return
        if (!sentences.add(sentence)) return

        for (i in 0 until sentence.length) {
            if (i >= 1) {
                bigram.getOrPut(sentence[i - 1]) { HashMap() }
                    .merge(sentence[i], 1, Int::plus)
            }
            if (i >= 2) {
                trigram.getOrPut(sentence.substring(i - 2, i)) { HashMap() }
                    .merge(sentence[i], 1, Int::plus)
            }
        }
        dirty = true
        trimIfNeeded()
    }

    /** 批量学习（启动时灌内置语料用） */
    fun observeAll(texts: Iterable<String>) {
        texts.forEach { observe(it) }
    }

    /** 训练句子上限：模型不能无限膨胀，超过就丢最早的 */
    private fun trimIfNeeded() {
        while (sentences.size > MAX_SENTENCES) {
            val oldest = sentences.firstOrNull() ?: break
            sentences.remove(oldest)
        }
    }

    // ------------------------------------------------------------------ 生成

    /**
     * 根据用户输入生成一句相关的话。三档策略，从"最讲逻辑"到"最兜底"：
     *
     * 1. **检索问答**（[recall]）：在训练过的问答对里找和输入最像的问句，
     *    直接返回它对应的答案——答案是人写的，所以有因果、讲得通；
     * 2. **续写**（[continueFrom]）：以输入自己的尾巴为起点接着往下走，
     *    生成的是"接在这句话后面的回答"，和上文是连着的；
     * 3. **语料重组**（[walkFrom]）：从前两档都没出结果时，才用共享字组
     *    在训练集里游走拼一句（它能保证"沾边"，但逻辑性最弱）。
     *
     * 前两档是 v2.5 的重点：以前只有第 3 档，所以回答常常"像话但没逻辑"。
     *
     * @return 三档都出不来就返回 null，由调用方提示教学
     */
    fun generate(seed: String, random: Random = Random.Default): String? {
        val cleanedSeed = clean(seed)
        if (cleanedSeed.length < 2 || sentences.isEmpty()) return null

        recall(cleanedSeed)?.let { return it }

        repeat(GENERATE_ATTEMPTS) {
            continueFrom(cleanedSeed, random)?.let {
                // 续写也必须和输入沾边（至少共用一个字组），否则宁可退到下一档
                if (acceptable(it, cleanedSeed) && sharesBigram(it, cleanedSeed)) return it
            }
        }

        val seedBigrams = cleanedSeed.windowed(2).toSet()
        val candidates = sentences.filter { sentence ->
            sentence.windowed(2).any { it in seedBigrams }
        }
        if (candidates.isEmpty()) return null

        repeat(GENERATE_ATTEMPTS) {
            val sentence = candidates[random.nextInt(candidates.size)]
            val text = walkFrom(sentence, seedBigrams, random) ?: return@repeat
            if (text != null && acceptable(text, cleanedSeed)) return text
        }
        return null
    }

    /**
     * 检索式回答：在问答对里找与输入最像的问句，返回它的答案。
     *
     * 用**字符二组重合度**（交集 / 较小的集合）打分，比编辑距离更适合中文短句：
     * 「天气怎么样」和「今天天气咋样」几乎没有字面相同的字序，但共享「天气」。
     */
    fun recall(seed: String): String? {
        if (pairs.isEmpty()) return null
        val seedBigrams = clean(seed).windowed(2).toSet()
        if (seedBigrams.isEmpty()) return null

        var bestAnswer: String? = null
        var bestScore = 0.0
        for ((question, answer) in pairs) {
            val questionBigrams = clean(question).windowed(2).toSet()
            if (questionBigrams.isEmpty()) continue
            val shared = seedBigrams.intersect(questionBigrams).size
            if (shared < MIN_SHARED_BIGRAMS) continue
            val score = shared.toDouble() / minOf(seedBigrams.size, questionBigrams.size)
            if (score > bestScore) {
                bestScore = score
                bestAnswer = answer
            }
        }
        return if (bestScore >= RECALL_MIN_SCORE) bestAnswer else null
    }

    /** 问答对数量，设置页展示用 */
    fun pairCount(): Int = pairs.size

    /** 记一条问答对：答案会挂在问句后面一起训练，于是问句尾能接上答案头 */
    fun observePair(question: String, answer: String) {
        val q = clean(question)
        val a = clean(answer)
        if (q.length < 2 || a.length < MIN_SENTENCE_LENGTH) return
        // 存原句：检索式回答要原样返回（clean 会去掉句末句号）
        pairs[q] = answer.trim()
        observe(question)
        observe(answer)
        // 关键：把「问句 + 答案」当成一句话训练，让续写能接得上
        observe("$question，$answer")
        trimPairsIfNeeded()
    }

    fun observePairs(items: Iterable<Pair<String, String>>) {
        items.forEach { (q, a) -> observePair(q, a) }
    }

    private fun trimPairsIfNeeded() {
        while (pairs.size > MAX_PAIRS) {
            val oldest = pairs.keys.firstOrNull() ?: break
            pairs.remove(oldest)
        }
    }

    /** 以输入的尾巴为起点续写：生成的是"接在这句话后面"的回答 */
    private fun continueFrom(seed: String, random: Random): String? {
        val start = seed.takeLast(2)
        if (start !in trigram) return null
        var context = start
        val sb = StringBuilder()
        while (sb.length < MAX_GENERATE_LENGTH) {
            val next = sampleNext(context, context.last(), random) ?: break
            sb.append(next)
            context = (context + next).takeLast(2)
            if (next in "。！？") break
        }
        val text = sb.toString().trimEnd('，', ',', '、', '：', '；')
        if (text.length < MIN_GENERATE_LENGTH) return null
        return text
    }

    /** 生成结果和输入是否至少共用一个字组（保证"沾边"） */
    private fun sharesBigram(text: String, seed: String): Boolean {
        if (seed.length < 2) return false
        val seedBigrams = seed.windowed(2).toSet()
        return text.windowed(2).any { it in seedBigrams }
    }

    /** 生成结果的体检：不能是问句、不能复读输入、不能是训练原句 */
    private fun acceptable(text: String, seed: String): Boolean {
        if (text.contains('？') || text.contains('?')) return false
        if (text.contains(seed) || seed.contains(text)) return false
        if (text in sentences) return false
        return true
    }

    /** 从某句训练文本的一个位置出发游走，产出候选回复；不合格返回 null */
    private fun walkFrom(
        sentence: String,
        seedBigrams: Set<String>,
        random: Random
    ): String? {
        // 起点：随机挑一个「上下文在模型里有接续」且与输入共享字组的位置
        val starts = (0..sentence.length - 2).filter { i ->
            sentence.substring(i, i + 2) in trigram &&
                sentence.substring(i, i + 2) in seedBigrams
        }.ifEmpty {
            (0..sentence.length - 2).filter { sentence.substring(it, it + 2) in trigram }
        }
        if (starts.isEmpty()) return null

        val start = starts[random.nextInt(starts.size)]
        val sb = StringBuilder(sentence.substring(start, start + 2))

        while (sb.length < MAX_GENERATE_LENGTH) {
            val context = sb.substring(sb.length - 2)
            val next = sampleNext(context, sb.last(), random) ?: break
            sb.append(next)
            // 句子读起来完整的收尾符号就停
            if (next in "。！？") break
        }

        var text = sb.toString().trimEnd('，', ',', '、', '：')
        if (text.length < MIN_GENERATE_LENGTH) return null
        // 不要和训练句一字不差地复读（那就是检索了，不是生成）
        if (text in sentences) return null
        if (!text.endsWith('。') && !text.endsWith('！') && !text.endsWith('？')) text += "。"
        return text
    }

    /** 按统计次数加权采样下一个字符；都没有接续时返回 null */
    private fun sampleNext(context: String, last: Char, random: Random): Char? {
        val third = trigram[context]
        if (third != null) return weightedPick(third, random)
        val second = bigram[last]
        return second?.let { weightedPick(it, random) }
    }

    private fun weightedPick(counts: Map<Char, Int>, random: Random): Char {
        val total = counts.values.sum()
        var roll = random.nextInt(total)
        for ((value, count) in counts) {
            roll -= count
            if (roll < 0) return value
        }
        return counts.keys.first()
    }

    // ------------------------------------------------------------------ 持久化

    /**
     * 序列化：只存训练句子集（模型参数可以由它完整重建），
     * 每行一句，用「␍」转义换行——聊天回复里本来也不会有换行。
     */
    fun serialize(): String = buildString {
        append(sentences.joinToString("\n") { it.replace("\n", "␍") })
        append("\n")
        append(PAIRS_MARKER)
        append("\n")
        append(pairs.entries.joinToString("\n") { (q, a) -> "$q\t$a" })
    }

    /** 从持久化文本恢复模型；内容为空或格式不对返回 null */
    fun loadInto(text: String): Boolean {
        val parts = text.split(PAIRS_MARKER)
        val lines = parts[0].split("\n").map { it.replace("␍", "\n").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        observeAll(lines)
        if (parts.size > 1) {
            parts[1].split("\n").forEach { line ->
                val qa = line.split("\t")
                if (qa.size == 2 && qa[0].isNotBlank() && qa[1].isNotBlank()) {
                    pairs[qa[0]] = qa[1]
                }
            }
        }
        dirty = false
        return true
    }

    companion object {
        /** 太短的句子没有统计意义 */
        private const val MIN_SENTENCE_LENGTH = 3

        /** 训练集上限（句）：要装得下内置常识知识库的问答 */
        private const val MAX_SENTENCES = 6000

        /** 生成重试次数 */
        private const val GENERATE_ATTEMPTS = 4

        /** 生成结果的最短 / 最长长度 */
        private const val MIN_GENERATE_LENGTH = 4
        private const val MAX_GENERATE_LENGTH = 22

        /** 检索问答时至少要有这么多个共同字组，否则认为不相干 */
        private const val MIN_SHARED_BIGRAMS = 2

        /** 检索问答的相似度门槛 */
        private const val RECALL_MIN_SCORE = 0.34

        /** 问答对上限（条） */
        private const val MAX_PAIRS = 4000

        /** 序列化里句子和问答对的分隔标记 */
        private const val PAIRS_MARKER = "###PAIRS###"

        /** 去掉标点空白，只留参与统计的文字（保留句中逗号句号，让生成有停顿感） */
        private fun clean(text: String): String = text
            .replace(Regex("[\\s\\p{So}\\p{Cn}]"), "")
            .trim('！', '？', '。')
    }
}
