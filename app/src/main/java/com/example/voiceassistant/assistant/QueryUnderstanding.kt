package com.example.voiceassistant.assistant

/**
 * 本地语义理解（「听得懂」的第一层，也是给云端省 token 的关键一层）。
 *
 * ## 为什么需要它
 * 用户的话有两种：短句（「几点了」「天气怎么样」）和长句
 * （「明天我要去上海出差，那边天气怎么样，要不要带伞」）。
 * 前者靠关键词也能碰对，但同长度的句子容易互相误伤；
 * 后者一句话里塞了背景、条件、真正的问题三层信息，
 * 直接整句丢给云端，既费 token 又容易被旁枝末节带偏。
 *
 * ## 这一层做的事
 * 1. **短句精准匹配**：≤8 字先查 [EXACT] 表（归一化后精确比对），命中就直接用，
 *    不走模糊匹配，避免「几点」被判成「几天」这类误伤。
 * 2. **长句切分**：按标点与「然后 / 还有 / 另外 / 但是」这类连词切成子句，
 *    逐段识别，再挑信息量最大的那段当主问题。
 * 3. **关键词 → 意图**：每类意图一组触发词，按特异度排序取第一个命中的。
 * 4. **指代消解**：「它 / 那边 / 这个」换成最近 6 轮里出现过的实体。
 * 5. **归纳成简要问题 [Understanding.brief]**：发给云端的是这句话，而不是原始长句。
 *
 * 全过程纯本地、纯函数，不碰 Android，也不发任何网络请求。
 */
object QueryUnderstanding {

    /** 短句阈值：不超过这个字数先走精准表（按原始输入算，含标点） */
    const val SHORT_LIMIT = 8

    /** 本地逻辑分析回看几轮历史（用户要求 5~6 条，这里取 6） */
    const val LOCAL_MEMORY_TURNS = 6

    /** 思考过程每一步的展示间隔（毫秒），UI 层用它算总时长 */
    const val STEP_MS = 260L

    // ------------------------------------------------------------------ 意图

    /**
     * 粗粒度意图。
     *
     * 只分到「这句话在问什么类型」这一层，不负责具体答案——
     * 答案仍由规则 / 知识库 / 云端各自处理。
     */
    enum class Intent(val label: String) {
        GREET("打招呼"),
        WEATHER("天气"),
        TRANSLATE("翻译"),
        TIME("时间日期"),
        MATH("计算"),
        COMMAND("操作指令"),
        CAUSE("问原因"),
        METHOD("求办法"),
        COMPARE("比较选择"),
        OPINION("看法建议"),
        FACT("知识问答"),
        CHAT("闲聊"),
        OTHER("其它")
    }

    /** 一次理解的结果 */
    data class Understanding(
        /** 用户原话 */
        val raw: String,
        /** 切出来的子句（短句时只有一句，就是它自己） */
        val clauses: List<String>,
        /** 识别到的意图 */
        val intent: Intent,
        /** 命中的关键词 / 实体 */
        val keywords: List<String>,
        /** 指代消解后的句子；没有指代时与 [brief] 相同 */
        val resolved: String,
        /**
         * 归纳后的简要问题——**发给云端的就是这句**。
         * 长句会被压成一句核心问句，省掉背景铺垫的那部分 token。
         */
        val brief: String,
        /** 展示用的思考过程（一行一步） */
        val steps: List<String>
    )

    // ------------------------------------------------------------------ ① 短句精准表

    /**
     * 8 字以内常用句 → 意图 + 规范说法。
     *
     * key 是归一化后的写法（无标点空格、已剥客套前缀），
     * value 第二项是该说法的规范形式，直接当 [Understanding.brief] 用。
     * 注意：这张表**不改变本地回答流程**，它只保证「识别」和「重述」是准的。
     */
    private val EXACT: Map<String, Pair<Intent, String>> = mapOf(
        // 打招呼
        "你好" to (Intent.GREET to "打个招呼"),
        "你好啊" to (Intent.GREET to "打个招呼"),
        "hi" to (Intent.GREET to "打个招呼"),
        "hello" to (Intent.GREET to "打个招呼"),
        "哈喽" to (Intent.GREET to "打个招呼"),
        "嗨" to (Intent.GREET to "打个招呼"),
        "早上好" to (Intent.GREET to "早上打招呼"),
        "中午好" to (Intent.GREET to "中午打招呼"),
        "下午好" to (Intent.GREET to "下午打招呼"),
        "晚上好" to (Intent.GREET to "晚上打招呼"),
        "晚安" to (Intent.GREET to "道晚安"),
        "在吗" to (Intent.GREET to "问助手在不在"),
        // 自我介绍 / 能力
        "你是谁" to (Intent.CHAT to "问助手是谁"),
        "你叫什么" to (Intent.CHAT to "问助手叫什么"),
        "你叫什么名字" to (Intent.CHAT to "问助手的名字"),
        "你是机器人吗" to (Intent.CHAT to "问助手是不是机器人"),
        "你会什么" to (Intent.CHAT to "问助手会做什么"),
        "你能做什么" to (Intent.CHAT to "问助手能做什么"),
        "你能干什么" to (Intent.CHAT to "问助手能干什么"),
        "有什么功能" to (Intent.CHAT to "问助手有什么功能"),
        "你多大了" to (Intent.CHAT to "问助手年龄"),
        // 时间日期
        "几点了" to (Intent.TIME to "现在几点"),
        "几点" to (Intent.TIME to "现在几点"),
        "现在几点" to (Intent.TIME to "现在几点"),
        "什么时间" to (Intent.TIME to "现在几点"),
        "现在时间" to (Intent.TIME to "现在几点"),
        "今天几号" to (Intent.TIME to "今天几号"),
        "几号了" to (Intent.TIME to "今天几号"),
        "今天日期" to (Intent.TIME to "今天几号"),
        "星期几" to (Intent.TIME to "今天星期几"),
        "今天星期几" to (Intent.TIME to "今天星期几"),
        "今天周几" to (Intent.TIME to "今天星期几"),
        "明天星期几" to (Intent.TIME to "明天星期几"),
        "后天星期几" to (Intent.TIME to "后天星期几"),
        // 天气
        "天气怎么样" to (Intent.WEATHER to "今天天气怎么样"),
        "今天天气" to (Intent.WEATHER to "今天天气怎么样"),
        "明天天气" to (Intent.WEATHER to "明天天气怎么样"),
        "天气如何" to (Intent.WEATHER to "今天天气怎么样"),
        "会下雨吗" to (Intent.WEATHER to "今天会不会下雨"),
        "下雨吗" to (Intent.WEATHER to "今天会不会下雨"),
        "下雪吗" to (Intent.WEATHER to "今天会不会下雪"),
        "冷不冷" to (Intent.WEATHER to "今天冷不冷"),
        "热不热" to (Intent.WEATHER to "今天热不热"),
        "多少度" to (Intent.WEATHER to "今天多少度"),
        // 闲聊 / 情绪
        "讲个笑话" to (Intent.CHAT to "讲个笑话"),
        "说个笑话" to (Intent.CHAT to "讲个笑话"),
        "来个笑话" to (Intent.CHAT to "讲个笑话"),
        "讲个故事" to (Intent.CHAT to "讲个故事"),
        "我累了" to (Intent.CHAT to "说自己很累"),
        "好累" to (Intent.CHAT to "说自己很累"),
        "累死了" to (Intent.CHAT to "说自己很累"),
        "我不高兴" to (Intent.CHAT to "说自己不高兴"),
        "不开心" to (Intent.CHAT to "说自己不开心"),
        "心情不好" to (Intent.CHAT to "说自己心情不好"),
        "我难过" to (Intent.CHAT to "说自己难过"),
        "谢谢" to (Intent.CHAT to "表示感谢"),
        "多谢" to (Intent.CHAT to "表示感谢"),
        "再见" to (Intent.CHAT to "道别"),
        "拜拜" to (Intent.CHAT to "道别")
    )

    // ------------------------------------------------------------------ ② 关键词 → 意图

    /**
     * 各意图的触发词，**按特异度从高到低排列**，取第一个命中的。
     *
     * 顺序很关键：「多少度」先归天气，不能被「多少」抢走算计算；
     * 「用英语怎么说」要先归翻译，不能落进知识问答。
     */
    private val KEYWORD_RULES: List<Pair<Intent, List<String>>> = listOf(
        Intent.WEATHER to listOf(
            "天气", "气温", "下雨", "下雪", "刮风", "台风", "多少度", "几度",
            "冷不冷", "热不热", "穿什么", "带伞", "湿度", "空气质量", "紫外线", "保暖"
        ),
        Intent.TRANSLATE to listOf(
            "翻译", "用英语", "用英文", "英语怎么说", "英文怎么说", "用日语", "用韩语",
            "怎么说", "怎么写", "用中文", "中文怎么说", "什么意思", "是什么意思"
        ),
        Intent.TIME to listOf(
            "几点", "时间", "几号", "日期", "星期", "周几", "礼拜", "什么时候",
            "多久", "工作日", "倒计时", "几月几号"
        ),
        Intent.MATH to listOf(
            "加", "减", "乘以", "乘", "除以", "等于", "算一下", "算算", "计算",
            "之和", "平均", "百分比", "多少", "汇率", "打折"
        ),
        Intent.COMMAND to listOf(
            "提醒", "闹钟", "定时", "打开", "播放", "记一下", "帮我记", "记着",
            "定个", "设个", "关掉", "搜一下", "查一下"
        ),
        Intent.CAUSE to listOf("为什么", "为啥", "为何", "原因", "怎么会", "凭什么", "怎么会这样"),
        Intent.METHOD to listOf(
            "怎么办", "怎么做", "怎么弄", "如何做", "该怎么", "有什么办法", "怎么解决",
            "如何是好", "怎么才能", "怎么提高", "怎么学"
        ),
        Intent.COMPARE to listOf(
            "还是", "哪个", "区别", "对比", "相比", "优缺点", "哪个好", "哪家", "谁更", "选哪个"
        ),
        Intent.OPINION to listOf(
            "你觉得", "你怎么看", "看法", "建议", "该不该", "要不要", "好不好",
            "值不值", "行不行", "合适吗", "值得吗"
        ),
        Intent.FACT to listOf(
            "是什么", "什么是", "介绍", "讲解", "原理", "意思", "怎么运作", "如何工作",
            "有哪些", "定义", "常识", "知识"
        ),
        Intent.GREET to listOf("你好", "早上好", "晚上好", "晚安", "嗨", "在吗")
    )

    // ------------------------------------------------------------------ ③ 分句

    /** 并列/转折连词：语音识别不带标点时靠它们断句 */
    private val CLAUSE_CONNECTORS = listOf(
        "然后", "还有", "另外", "而且", "但是", "不过", "顺便", "接着", "同时", "再说", "问一下"
    )

    /**
     * 把长句切成子句。
     *
     * 先按标点断，再对仍然很长的片段按连词二次切分；
     * 短到没有分析价值（≤[SHORT_LIMIT]）的片段直接合并回上一句，
     * 免得把「嗯，那个」也当成一段。
     */
    fun split(raw: String): List<String> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()

        val parts = text.split(Regex("[，,。.；;！!？?、\\n]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()

        // 连词二次切分
        val refined = mutableListOf<String>()
        for (part in parts) {
            if (part.length <= 12) {
                refined += part
                continue
            }
            var rest = part
            var cut = true
            while (cut) {
                cut = false
                for (conn in CLAUSE_CONNECTORS) {
                    val at = rest.indexOf(conn)
                    if (at > 0 && at < rest.length - conn.length) {
                        refined += rest.substring(0, at).trim()
                        rest = rest.substring(at + conn.length).trim()
                        cut = true
                        break
                    }
                }
            }
            if (rest.isNotEmpty()) refined += rest
        }

        val cleaned = refined.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return listOf(text)

        // 太短的碎片并回上一句（「嗯」「那个」这类没有独立意义）
        val merged = mutableListOf<String>()
        for (part in cleaned) {
            if (part.length <= 2 && merged.isNotEmpty()) {
                merged[merged.lastIndex] = merged[merged.lastIndex] + part
            } else {
                merged += part
            }
        }
        return merged
    }

    // ------------------------------------------------------------------ ④ 识别

    /** 关键词展示顺序：按它在触发词表里的重要程度（越靠前的意图越先列） */
    private fun keywordsOrder(word: String): Int {
        val index = KEYWORD_RULES.indexOfFirst { (_, words) -> words.contains(word) }
        return if (index < 0) KEYWORD_RULES.size else index
    }

    /** 一句话里命中的关键词（按触发词表顺序，去重） */
    fun keywordsOf(text: String): List<String> {
        val norm = FuzzyMatcher.normalize(text)
        val hits = linkedSetOf<String>()
        for ((_, words) in KEYWORD_RULES) {
            for (word in words) {
                if (norm.contains(word)) hits += word
            }
        }
        // 「乘以」和「乘」会同时命中，留长的那个，展示和发送都干净些
        val kept = hits.sortedByDescending { it.length }
        val filtered = mutableListOf<String>()
        for (word in kept) {
            if (filtered.none { it.contains(word) }) filtered += word
        }
        hits.clear()
        hits.addAll(filtered.sortedBy { keywordsOrder(it) })
        // 城市名是强实体，单独拎出来
        CityClimate.find(text)?.let { hits += it.city }
        // 英文单词（翻译的目标词常常就是它）
        Regex("[A-Za-z][A-Za-z\\-']{1,}")
            .findAll(text)
            .map { it.value }
            .forEach { hits += it }
        return hits.toList()
    }

    /** 单句意图识别：短句先查精准表，命中不了再走关键词 */
    fun intentOf(text: String): Intent {
        val norm = FuzzyMatcher.normalize(text)
        EXACT[norm]?.let { return it.first }
        for ((intent, words) in KEYWORD_RULES) {
            if (words.any { norm.contains(it) }) return intent
        }
        return Intent.OTHER
    }

    // ------------------------------------------------------------------ ⑤ 指代消解

    private val PRONOUNS = listOf(
        "这个城市", "那个城市", "这座城市", "这里", "那里", "这边", "那边",
        "这个", "那个", "这种", "那种", "它", "他", "她", "前者", "后者"
    )

    /**
     * 从最近几轮里找回「正在聊的那个东西」。
     *
     * 优先取城市名（最常被指代），其次取上一句的主话题。
     */
    fun rememberedEntity(memory: List<String>): String? {
        for (line in memory.asReversed()) {
            CityClimate.find(line)?.let { return it.city }
        }
        for (line in memory.asReversed()) {
            val topic = DialogLogic.stripToTopic(line)
                .trim('？', '?', '！', '!', '。', '.', '，', ',', '啊', '呢', '呀', '吧', '嘛', ' ')
            if (topic.length in 2..8) return topic
        }
        return null
    }

    /** 把代词换成记住的实体；没有代词或没有实体时原样返回 */
    fun resolve(text: String, entity: String?): String {
        if (entity.isNullOrEmpty()) return text
        val hit = PRONOUNS.firstOrNull { text.contains(it) } ?: return text
        val replaced = text.replace(hit, entity)
        // 替换完反而变得更短/没变化，说明这个「它」不是指代，别硬换
        return if (replaced == text) text else replaced
    }

    // ------------------------------------------------------------------ ⑥ 归纳

    /**
     * 给子句打分，挑出「真正的问题所在」那一段。
     *
     * 不能按长度挑：背景铺垫（「明天我要去上海出差」）往往比真正的问句还长。
     * 这里按**意图的特异度**排——[KEYWORD_RULES] 越靠前的意图越具体，
     * 命中它的子句越可能是主问题；长度只作为同分时的次要依据。
     * 命中精准表的子句直接给最高分。
     */
    fun scoreClause(clause: String): Int {
        val norm = FuzzyMatcher.normalize(clause)
        val exact = EXACT[norm]
        if (exact != null) {
            // 寒暄（「你好啊」）常常只是开场白，后面才跟着真正的请求，
            // 所以精准表里只有「正事」才给最高分，寒暄给一个偏低的分数，
            // 免得「你好啊，帮我算一下…」被判成打招呼。
            val trivial = exact.first == Intent.GREET || exact.first == Intent.CHAT
            return (if (trivial) 300 else 10_000) + clause.length
        }
        val index = KEYWORD_RULES.indexOfFirst { (_, words) -> words.any { norm.contains(it) } }
        val base = if (index < 0) 0 else (KEYWORD_RULES.size - index) * 100
        return base + clause.length
    }

    /** 信息量最大的子句；空列表返回空串 */
    fun mainClauseOf(clauses: List<String>): String =
        clauses.maxByOrNull { scoreClause(it) } ?: ""

    /**
     * 归纳成一句简要问题。
     *
     * 原则是**只留能决定答案的部分**：
     * 「明天我要去上海出差，那边天气怎么样，要不要带伞」→「上海天气怎么样」。
     * 背景（出差）不进云端请求，省下的都是 token。
     */
    fun briefOf(clauses: List<String>, intent: Intent, entity: String?): String {
        if (clauses.isEmpty()) return ""

        val main = mainClauseOf(clauses).ifEmpty { clauses.first() }

        val exact = EXACT[FuzzyMatcher.normalize(main)]
        if (exact != null) return exact.second

        // 先做指代消解（代词是原文里的），再归一化（剥客套前缀、去标点）压缩长度
        var text = FuzzyMatcher.normalize(resolve(main, entity)).ifBlank { resolve(main, entity) }
        text = text.trim()
            .removePrefix("那么")
            .removePrefix("顺便")
            .removePrefix("还有")
            .removePrefix("然后")
            .removePrefix("另外")
            .trim('，', ',', '。', '.', '、', '；', ';')
            .trim()
        if (text.isEmpty()) text = main

        // 同一件事重复说（「天气怎么样天气怎么样」）只留一次
        text = dedupe(text)

        // 超过 24 字就砍到核心：留前 24 字，尽量停在标点或「的」之前
        if (text.length > 24) {
            val cut = text.substring(0, 24)
            val stop = cut.lastIndexOfAny(charArrayOf('，', ',', '、', '；', '的'))
            text = if (stop >= 12) cut.substring(0, stop) else cut
        }
        return text.trim('，', ',', '。', '.', '？', '?', '的')
    }

    /** 去掉紧邻重复片段：「天气怎么样天气怎么样」→「天气怎么样」 */
    private fun dedupe(text: String): String {
        if (text.length < 6) return text
        val half = text.length / 2
        for (size in half downTo 3) {
            val head = text.substring(0, size)
            if (text.substring(size).startsWith(head)) return head
        }
        return text
    }

    // ------------------------------------------------------------------ ⑦ 组装

    /**
     * 完整理解一句话。
     *
     * @param raw 用户这一句
     * @param memory 最近几轮用户说过的话（用于指代消解），最新的在最后
     */
    fun analyze(raw: String, memory: List<String> = emptyList()): Understanding {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Understanding(
                raw = raw, clauses = emptyList(), intent = Intent.OTHER,
                keywords = emptyList(), resolved = "", brief = "", steps = emptyList()
            )
        }

        val norm = FuzzyMatcher.normalize(text)
        val exact = EXACT[norm]
        val clauses = if (text.length <= SHORT_LIMIT) listOf(text) else split(text)

        val steps = mutableListOf<String>()
        if (exact != null) {
            steps += "短句精准匹配：${exact.second}"
        } else if (clauses.size > 1) {
            steps += "拆成 ${clauses.size} 段：${clauses.joinToString(" / ")}"
        }

        val intent = if (exact != null) {
            exact.first
        } else {
            intentOf(mainClauseOf(clauses).ifEmpty { text })
        }
        steps += "类型：${intent.label}"

        val keywords = keywordsOf(text).take(4)
        if (keywords.isNotEmpty()) steps += "关键词：${keywords.joinToString("、")}"

        val entity = rememberedEntity(memory)
        val resolved = resolve(text, entity)
        if (resolved != text && entity != null) {
            val pronoun = PRONOUNS.firstOrNull { text.contains(it) }
            if (pronoun != null) steps += "指代：$pronoun → $entity"
        }

        val brief = if (exact != null) exact.second else briefOf(clauses, intent, entity)
        if (brief.isNotBlank() && brief != text) steps += "重述：$brief"

        return Understanding(
            raw = text,
            clauses = clauses,
            intent = intent,
            keywords = keywords,
            resolved = resolved,
            brief = brief.ifBlank { text },
            steps = steps
        )
    }

    /** 思考过程的总展示时长：每步 [STEP_MS]，另加收尾停顿 */
    fun holdMs(stepCount: Int): Long = (stepCount.coerceAtMost(5)) * STEP_MS + 240L
}
