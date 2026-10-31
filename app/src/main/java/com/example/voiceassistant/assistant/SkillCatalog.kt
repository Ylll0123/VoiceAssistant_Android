package com.example.voiceassistant.assistant

/**
 * 一个可展示的技能条目。
 *
 * 这里只放「给用户看的信息」：标题、说明、示例说法和分类。
 * 真正的执行逻辑仍然在 [AssistantRules] 里，靠 [id] 与意图标识对齐。
 * 技能页点击示例后，会把示例原句直接送到对话引擎，
 * 所以技能页永远不会和真实能力脱节——示例跑不通就是 bug。
 */
data class Skill(
    val id: String,
    val title: String,
    val description: String,
    val category: String,
    val examples: List<String>
)

/**
 * 技能目录：界面和引擎共用的唯一数据源。
 * 新增一个能力时，规则 + 这里的条目一起加。
 */
object SkillCatalog {

    const val CATEGORY_TIME = "时间与日期"
    const val CATEGORY_CALC = "计算与换算"
    const val CATEGORY_TOOL = "生活小工具"
    const val CATEGORY_FUN = "娱乐与闲聊"
    const val CATEGORY_DIALOG = "多轮对话"
    const val CATEGORY_LIFE = "天气与出行"
    const val CATEGORY_KNOWLEDGE = "常识问答"

    val categories: List<String> = listOf(
        CATEGORY_TIME, CATEGORY_CALC, CATEGORY_LIFE, CATEGORY_KNOWLEDGE, CATEGORY_TOOL, CATEGORY_FUN, CATEGORY_DIALOG
    )

    val skills: List<Skill> = listOf(
        Skill(
            id = "time",
            title = "现在几点",
            description = "报出当前的时分",
            category = CATEGORY_TIME,
            examples = listOf("现在几点了", "几点了")
        ),
        Skill(
            id = "date",
            title = "今天几号",
            description = "日期、星期，也支持昨天 / 明天 / 后天",
            category = CATEGORY_TIME,
            examples = listOf("今天几号", "明天是几号")
        ),
        Skill(
            id = "weekday_calc",
            title = "日期推算",
            description = "算下周几是几号、距离某个日子还有几天",
            category = CATEGORY_TIME,
            examples = listOf("下周三几号", "还有几天到10月31日")
        ),
        Skill(
            id = "timezone",
            title = "世界时间",
            description = "纽约、伦敦、东京等城市现在几点",
            category = CATEGORY_TIME,
            examples = listOf("纽约现在几点", "东京几点了")
        ),
        Skill(
            id = "arithmetic",
            title = "算数",
            description = "四则运算、括号、百分号，直接说算式就行",
            category = CATEGORY_CALC,
            examples = listOf("帮我算 25 乘以 4", "计算 (12+8)*3")
        ),
        Skill(
            id = "calc_continue",
            title = "接着算",
            description = "在上一次结果上继续加减乘除",
            category = CATEGORY_CALC,
            examples = listOf("再加 5", "再乘以 2")
        ),
        Skill(
            id = "unit_convert",
            title = "单位换算",
            description = "公里/英里、摄氏度/华氏度、斤/千克等",
            category = CATEGORY_CALC,
            examples = listOf("10 公里是多少英里", "30 摄氏度是多少华氏度")
        ),
        Skill(
            id = "age",
            title = "年龄计算",
            description = "说出出生年份，算今年多大",
            category = CATEGORY_CALC,
            examples = listOf("2000年出生今年多大")
        ),
        Skill(
            id = "random",
            title = "做个决定",
            description = "抛硬币、掷骰子、随机数、二选一",
            category = CATEGORY_TOOL,
            examples = listOf("帮我抛个硬币", "掷骰子", "帮我选咖啡还是奶茶")
        ),
        Skill(
            id = "support",
            title = "能力清单",
            description = "看看助手现在会做什么",
            category = CATEGORY_TOOL,
            examples = listOf("你能做什么", "有什么功能")
        ),
        Skill(
            id = "guess",
            title = "猜数字游戏",
            description = "1 到 100 之间猜数，助手会提示大小，可以连猜多轮",
            category = CATEGORY_FUN,
            examples = listOf("我们玩猜数字")
        ),
        Skill(
            id = "joke",
            title = "讲个笑话",
            description = "本地笑话库，随机一条",
            category = CATEGORY_FUN,
            examples = listOf("讲个笑话")
        ),
        Skill(
            id = "quote",
            title = "来句名言",
            description = "随机一句名言或诗句",
            category = CATEGORY_FUN,
            examples = listOf("来句名言", "背句诗")
        ),
        Skill(
            id = "teach",
            title = "教我说话",
            description = "教我一句新说法，记进本地记忆库；用「|」可以教多种回答，我会换着说",
            category = CATEGORY_DIALOG,
            examples = listOf("我教你", "记住：下班了 = 早点回家|路上慢点")
        ),
        Skill(
            id = "chat",
            title = "日常聊天",
            description = "内置日常话题语料：同一句话多种回答，累了、加班、宿舍、快递都能接得住",
            category = CATEGORY_DIALOG,
            examples = listOf("我好累", "今天加班到十点", "宿舍太吵了", "快递还没到")
        ),
        Skill(
            id = "self_learn",
            title = "自学习",
            description = "教过一句之后，相似的新说法它自己就学会了；答不上来的话会收进记忆页等你教",
            category = CATEGORY_DIALOG,
            examples = listOf("记住：下班了 = 早点回家", "我下班了啊")
        ),
        Skill(
            id = "continue_chat",
            title = "接着上文聊",
            description = "「为什么」「然后呢」「那怎么办」会顺着刚才的话题继续说，不会答非所问",
            category = CATEGORY_DIALOG,
            examples = listOf("我好累", "为什么", "那我该怎么办")
        ),
        Skill(
            id = "weather",
            title = "天气与气温",
            description = "给出城市当月的常年气温区间和穿衣建议（纯离线，说明不是实时数据）",
            category = CATEGORY_LIFE,
            examples = listOf("北京天气怎么样", "上海气温多少", "明天天气怎么样")
        ),
        Skill(
            id = "outing",
            title = "出行建议",
            description = "按月份和城市给出出行、穿衣、带伞这类能照做的提醒",
            category = CATEGORY_LIFE,
            examples = listOf("出行有什么建议", "今天出门要带伞吗", "这个月穿什么")
        ),
        Skill(
            id = "translate",
            title = "单词翻译",
            description = "内置中英基础词典双向查询，中翻英、英翻中都能用",
            category = CATEGORY_KNOWLEDGE,
            examples = listOf("苹果用英语怎么说", "apple 是什么意思", "翻译老师")
        ),
        Skill(
            id = "knowledge",
            title = "常识问答",
            description = "天气原理、健康、学习、生活窍门、安全急救等几百条有标准答案的问题",
            category = CATEGORY_KNOWLEDGE,
            examples = listOf("为什么会下雨", "感冒了怎么办", "怎么缓解焦虑", "手机进水怎么办")
        ),
        Skill(
            id = "follow_up",
            title = "多轮追问",
            description = "3 分钟内可以用「那后天呢」「再来一个」这种省略说法接着聊",
            category = CATEGORY_DIALOG,
            examples = listOf("那后天呢", "换一个")
        )
    )

    fun byCategory(category: String): List<Skill> = skills.filter { it.category == category }

    fun find(id: String): Skill? = skills.firstOrNull { it.id == id }
}
