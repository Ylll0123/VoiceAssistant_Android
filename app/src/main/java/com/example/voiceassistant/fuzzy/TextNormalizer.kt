package com.example.voiceassistant.fuzzy

/**
 * 文本归一化 + 错字纠正：把「明天山点提醒我交作业」洗成「明天三点提醒我交作业」。
 *
 * 这一步是模糊容错引擎的**地基**。ASR 出来的原文有三个通病：
 *
 * | 通病 | 例子 | 处理 |
 * |---|---|---|
 * | 全角半角、标点、空格混着来 | 「明天，８点」 | 全角转半角 + 去标点空格 |
 * | 语气词、口癖 | 「嗯那个明天啊八点」 | 去语气词表 |
 * | 同音字/近音字错 | 「提醒」→「提星」、「山点」→「三点」 | 词表纠正 + 上下文纠正 |
 *
 * ## 两个刻意的取舍
 *
 * 1. **同音字只在「像指令」时才纠**。
 *    「还是点外卖吧」里的「是点」不能被改成「四点」——
 *    所以时间词的纠正要求整句里出现「提醒 / 闹钟 / 计时 / 叫我」这类线索，
 *    再看「点」前面那个字。宁可漏纠，也不能把正常句子改坏。
 * 2. **归一化只在引擎内部用，不覆盖用户原话**。
 *    界面上显示的仍然是用户说的那句，纠正结果只用来做意图判定，
 *    否者用户会觉得「我说的明明不是这个」。
 */
object TextNormalizer {

    /** 归一化 + 纠正的结果；[fixes] 是给界面解释「按什么理解的」 */
    data class Fixed(val text: String, val fixes: List<String>)

    /** 语气词：只去掉纯语气、不承载语义的字 */
    private const val FILLERS = "啊哦嗯呃呀嘛吧呢啦咯噢诶欸呐哦咧"

    /** 标点：中文标点 + 英文标点 + 空白 */
    private const val PUNCTUATION = "，。、！？；：“”‘’（）《》〈〉【】…—～·,.;:!?\"'()<>[]{}|/\\-_=+*&^%$#@`~ \t\n\r"

    /**
     * 同义说法归一（长的在前，避免「提醒一下」被「提醒」先吃掉）。
     *
     * 这张表就是「同一个意思有多少种说法」的知识，
     * 它比正则模板更省事：不管用户怎么说，先统一成一个词，后面的规则只用认一个。
     */
    private val SYNONYMS: List<Pair<String, String>> = listOf(
        "帮我记着" to "提醒我",
        "帮我记一下" to "提醒我",
        "帮我记" to "提醒我",
        "到点叫我" to "提醒我",
        "到点喊我" to "提醒我",
        "提醒一下我" to "提醒我",
        "提醒下我" to "提醒我",
        "提醒我一下" to "提醒我",
        "提醒一下" to "提醒我",
        "记得提醒" to "提醒我",
        "记一下" to "提醒我",
        "提个醒" to "提醒我",
        "定个闹钟" to "提醒我",
        "设个闹钟" to "提醒我",
        "再提醒我一次" to "再提醒我",
        "往后推" to "推迟",
        "延后" to "推迟",
        "晚一点" to "推迟",
        "早一点" to "提前",
        "挪到" to "改到",
        "换到" to "改到",
        "换成" to "改成",
        "改为" to "改成",
        "调到" to "改到",
        "全部" to "全部",
        "所有" to "全部",
        "统统" to "全部",
        "查看" to "看看",
        "瞧一眼" to "看看",
        "给我看看" to "看看",
        "明儿" to "明天",
        "今儿" to "今天",
        "后儿" to "后天"
    )

    /** 词级错字纠正：这些组合在正常中文里几乎不出现，替换是安全的 */
    private val WORD_FIXES: List<Pair<String, String>> = listOf(
        "提星" to "提醒",
        "题醒" to "提醒",
        "体醒" to "提醒",
        "提酲" to "提醒",
        "闹中" to "闹钟",
        "脑钟" to "闹钟",
        "记时" to "计时",
        "计事" to "计时",
        "手电同" to "手电筒",
        "手电桶" to "手电筒",
        "说一便" to "说一遍",
        "念一便" to "念一遍",
        "取消掉" to "取消",
        "删掉" to "取消",
        "去掉" to "取消",
        "移除" to "取消"
    )

    /** 时刻前面的同音字 → 正确数字 */
    private val HOUR_CONFUSION = mapOf(
        '山' to '三', '伞' to '三',
        '是' to '四', '事' to '四',
        '无' to '五', '舞' to '五',
        '留' to '六', '溜' to '六',
        '期' to '七', '其' to '七',
        '坝' to '八', '爸' to '八',
        '酒' to '九', '久' to '九',
        '石' to '十', '时' to '十'
    )

    /** 「点」前面那个字如果跟在这些字后面，就不能纠（「还是点外卖」） */
    private const val BLOCK_BEFORE = "还就也都再可但不总别想说要该应该不是"

    /** 出现这些词才认为整句像指令，才敢动同音字 */
    private val COMMAND_CUES = listOf(
        "提醒", "闹钟", "计时", "倒计时", "叫我", "喊我", "定时", "记一下", "提星", "题醒"
    )

    /** 全量清洗：归一化 + 纠正 */
    fun normalizeAndFix(raw: String): Fixed {
        val fixes = mutableListOf<String>()
        val half = toHalfWidth(raw)
        val cleaned = strip(half)
        var text = applySynonyms(cleaned)
        text = applyWordFixes(text, fixes)
        text = fixHours(text, fixes)
        // 同义归一放在最后再跑一次：纠正过的词也要享受同义映射
        text = applySynonyms(text)
        return Fixed(text, fixes)
    }

    /** 只要归一化（不需要纠错时用，比如做相似度计算） */
    fun normalize(raw: String): String = applySynonyms(strip(toHalfWidth(raw)))

    // ------------------------------------------------------------------ 内部

    /** 全角字符（！~ 与全角空格）转半角 */
    private fun toHalfWidth(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when {
                ch == '\u3000' -> sb.append(' ')
                ch in '\uFF01'..'\uFF5E' -> sb.append(ch - 0xFEE0)
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** 去标点、空格、语气词 */
    private fun strip(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in PUNCTUATION) continue
            if (ch in FILLERS) continue
            sb.append(ch)
        }
        return sb.toString().trim()
    }

    private fun applySynonyms(text: String): String {
        var value = text
        for ((from, to) in SYNONYMS) {
            if (from != to && value.contains(from)) value = value.replace(from, to)
        }
        return value
    }

    private fun applyWordFixes(text: String, fixes: MutableList<String>): String {
        var value = text
        for ((from, to) in WORD_FIXES) {
            if (value.contains(from)) {
                value = value.replace(from, to)
                fixes += "「$from」→「$to」"
            }
        }
        return value
    }

    /**
     * 时刻同音字纠正：「山点」→「三点」。
     *
     * 三重护栏，缺一不可：
     * ① 整句得像指令（有「提醒 / 闹钟 / 计时 / 叫我」等线索）；
     * ② 错字必须紧跟「点 / 时」；
     * ③ 错字前一个字不能是「还 / 就 / 也 / 都 / 再」——
     *    否则「还是点外卖」会被改成「还四点外卖」。
     */
    private fun fixHours(text: String, fixes: MutableList<String>): String {
        if (COMMAND_CUES.none { text.contains(it) }) return text
        val chars = text.toCharArray()
        for (i in chars.indices) {
            val next = chars.getOrNull(i + 1) ?: continue
            if (next != '点' && next != '时') continue
            val fixed = HOUR_CONFUSION[chars[i]] ?: continue
            val before = chars.getOrNull(i - 1)
            if (before != null && before in BLOCK_BEFORE) continue
            fixes += "「${chars[i]}$next」→「$fixed$next」"
            chars[i] = fixed
        }
        return String(chars)
    }
}
