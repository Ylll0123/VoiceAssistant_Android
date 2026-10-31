package com.example.voiceassistant.assistant

/**
 * 日常对话语料库（离线内置，不联网）。
 *
 * 这不是「一个问题一条答案」的问答表，而是按话题组织的一组生活场景：
 * 每个话题有 2~4 种说法、各自的追问展开、具体建议和反问句。
 * 助手根据上下文挑其中一条，所以：
 *
 * - 同一句话问两遍，回答会换一种说法；
 * - 上一句聊「加班」，下一句只说「为什么」，它会接着加班往下说；
 * - 换话题后不会再把旧话题的答案套上去。
 *
 * 新增话题只需要往对应分组里加一个 [ChatTopic]，规则和界面都不用改。
 * 语料是「聊天能力」，用户教的话仍然存在记忆库里，两者互不影响。
 */
object DialogCorpus {

    val topics: List<ChatTopic> by lazy {
        greetings() + moods() + workAndStudy() + dailyLife() + peopleAndSelf()
    }

    private val byId: Map<String, ChatTopic> by lazy { topics.associateBy { it.id } }

    fun find(id: String?): ChatTopic? = if (id == null) null else byId[id]

    /** 语料规模，设置页展示用 */
    fun topicCount(): Int = topics.size

    /** 可用的应答句数（含展开、建议、反问），设置页展示用 */
    fun sentenceCount(): Int = topics.sumOf {
        it.replies.size + it.more.size + it.advice.size + it.followUps.size
    }

    // ------------------------------------------------------------------ 问候与告别

    private fun greetings(): List<ChatTopic> = listOf(
        ChatTopic(
            id = "greeting",
            title = "打招呼",
            keywords = listOf("你好", "在吗", "嗨", "哈喽", "有人吗"),
            samples = listOf("在不在", "我回来了", "聊天"),
            replies = listOf(
                "在的，随时都在。",
                "嗨，我在。今天想聊点什么？",
                "来了。你说，我听着。"
            ),
            more = listOf(
                "我一直开着，你说话我就听见。",
                "不用敲门，直接说就行。"
            ),
            followUps = listOf("今天过得怎么样？", "有什么想聊的吗？")
        ),
        ChatTopic(
            id = "morning",
            title = "早安",
            keywords = listOf("早安", "早上好"),
            samples = listOf("新的一天开始了", "起床了"),
            replies = listOf(
                "早，今天也慢慢来。",
                "早上好，先喝口水再开始吧。",
                "早呀，昨晚睡得还行吗？"
            ),
            more = listOf(
                "刚醒那半小时人是不太清醒的，别急着做决定。",
                "早上晒两分钟太阳，一天的精神会好一些。"
            ),
            advice = listOf("先吃点东西，别空着肚子出门。"),
            followUps = listOf("今天有什么安排？")
        ),
        ChatTopic(
            id = "night",
            title = "晚安",
            keywords = listOf("晚安", "去睡了", "睡觉了", "要睡了"),
            samples = listOf("我睡了", "准备睡觉"),
            replies = listOf(
                "晚安，好好睡。",
                "去睡吧，手机放远一点。",
                "晚安，明天见。"
            ),
            more = listOf(
                "睡前刷太久，脑子会一直转，躺下也停不下来。",
                "睡不着别硬躺着，起来喝口水再回去，反而容易睡着。"
            ),
            advice = listOf(
                "把手机放到够不着的地方，这一条最管用。",
                "睡前半小时把灯调暗，比什么助眠音乐都直接。"
            ),
            followUps = listOf("明天几点起？")
        ),
        ChatTopic(
            id = "farewell",
            title = "告别",
            keywords = listOf("再见", "拜拜", "先走了", "不聊了"),
            samples = listOf("下次再聊", "我走了"),
            replies = listOf(
                "好，需要我就喊我。",
                "拜，回头聊。",
                "去吧，我在这儿。"
            ),
            more = listOf(
                "我一直在，不会走。",
                "下次来直接说事就行，不用寒暄。"
            ),
            followUps = listOf("路上小心。")
        ),
        ChatTopic(
            id = "long_time",
            title = "好久不见",
            keywords = listOf("好久不见", "好久没聊", "好长时间没见"),
            samples = listOf("有阵子没来了", "最近都没见你"),
            replies = listOf(
                "是有一阵子了。最近忙什么？",
                "好久不见，你还好吗？",
                "确实很久了，我记性还行，你没怎么变。"
            ),
            followUps = listOf("这阵子过得怎么样？")
        ),
        ChatTopic(
            id = "how_are_you",
            title = "最近怎么样",
            keywords = listOf("最近怎么样", "最近好吗", "过得怎么样", "今天怎么样", "你怎么样"),
            samples = listOf("最近如何", "今天过得好吗"),
            replies = listOf(
                "还行，一直在。你呢？",
                "我这边一切正常。你今天怎么样？",
                "没什么大事，就是等你说话。"
            ),
            followUps = listOf("你今天怎么样？")
        )
    )

    // ------------------------------------------------------------------ 情绪

    private fun moods(): List<ChatTopic> = listOf(
        ChatTopic(
            id = "tired",
            title = "累",
            keywords = listOf("好累", "累死", "疲惫", "没力气", "心累", "累了", "累得"),
            samples = listOf("我撑不住了", "今天有点顶不住", "感觉被掏空", "好想躺下"),
            replies = listOf(
                "那就先停一下，五分钟什么都不干。事情不会因为你喘口气变得更糟。",
                "听起来是撑了很久了。今天最耗你的是哪一件？",
                "累的时候别急着讲道理，先让自己坐下来。"
            ),
            more = listOf(
                "累通常不是某一件事，是一堆小事叠在一起，还没地方放下。",
                "身体累睡一觉能缓，心里累得有人听你说完。"
            ),
            advice = listOf(
                "今晚别再安排新任务了，洗个热水澡早点躺下。",
                "把明天要做的事写成三条，剩下的先不管。"
            ),
            followUps = listOf("今天忙什么了？", "是身体累还是心里累？")
        ),
        ChatTopic(
            id = "sleepy",
            title = "困",
            keywords = listOf("好困", "困死", "没睡好", "失眠", "睡不着", "睡不够"),
            samples = listOf("一晚上没睡踏实", "睁眼到天亮"),
            replies = listOf(
                "困就眯十分钟，比硬撑两小时管用。",
                "昨晚几点睡的？",
                "别靠咖啡续命，先补一觉。"
            ),
            more = listOf(
                "睡眠欠的账补不回来，但今天能少欠一点。",
                "躺着睡不着的时候，越用力越清醒。"
            ),
            advice = listOf(
                "中午找个地方闭眼二十分钟，别刷手机。",
                "今晚十一点前把手机放到够不着的地方。"
            ),
            followUps = listOf("昨晚几点睡的？")
        ),
        ChatTopic(
            id = "sad",
            title = "难过",
            keywords = listOf("难过", "心情不好", "不开心", "想哭", "委屈"),
            samples = listOf("心里堵得慌", "我今天很不好", "眼泪止不住"),
            replies = listOf(
                "抱一下。想说就说，不想说也没关系，我在这儿。",
                "难受的时候不用急着好起来。",
                "这事搁谁身上都不好受。"
            ),
            more = listOf(
                "情绪本身不是问题，一直压着才是。",
                "你现在需要的可能不是建议，是有人听你把话说完。"
            ),
            advice = listOf(
                "找个信得过的人说一遍，哪怕只说三句。",
                "写下来也行，写完不用给任何人看。"
            ),
            followUps = listOf("发生什么了？", "想说说吗？")
        ),
        ChatTopic(
            id = "anxious",
            title = "焦虑",
            keywords = listOf("焦虑", "压力大", "紧张", "心慌", "慌得很"),
            samples = listOf("我心里很慌", "脑子停不下来", "总觉得要出事"),
            replies = listOf(
                "先做一次深呼吸，吸四秒、停两秒、呼六秒。",
                "焦虑多半是在替还没发生的事受苦。",
                "压力大的时候，先找出最要紧的那一件。"
            ),
            more = listOf(
                "人一焦虑就想同时处理所有事，结果一件都动不了。",
                "把担心的事写下来，你会发现有一半是想象出来的。"
            ),
            advice = listOf(
                "只挑一件五分钟能做完的事，先做掉。",
                "今天允许自己只完成六成。"
            ),
            followUps = listOf("在担心什么？")
        ),
        ChatTopic(
            id = "angry",
            title = "生气",
            keywords = listOf("生气", "好烦", "烦死", "气死", "火大", "受不了了"),
            samples = listOf("我快气炸了", "真的忍不了"),
            replies = listOf(
                "谁惹你了？说来听听。",
                "气就先骂两句，我听着。",
                "烦的时候看什么都烦，这很正常。"
            ),
            more = listOf(
                "生气往往是因为某件事越过了你的边界。",
                "憋着的气不会自己消，只会换个地方冒出来。"
            ),
            advice = listOf(
                "先离开现场十分钟，回来再处理。",
                "想说的话先打出来，别发出去，过一会儿再看。"
            ),
            followUps = listOf("怎么回事？")
        ),
        ChatTopic(
            id = "happy",
            title = "开心",
            keywords = listOf("开心", "高兴", "太好了", "好消息", "超爽"),
            samples = listOf("有件特别好的事", "今天运气不错"),
            replies = listOf(
                "那挺好，值得高兴一下。",
                "说来听听，什么好事？",
                "这情绪要保持住。"
            ),
            more = listOf(
                "好事说出来会变成两倍。",
                "开心的时候人是松的，记住这种感觉。"
            ),
            followUps = listOf("什么好事？")
        ),
        ChatTopic(
            id = "bored",
            title = "无聊",
            keywords = listOf("无聊", "没事干", "好闲", "好没意思"),
            samples = listOf("不知道干什么好", "闲得发慌"),
            replies = listOf(
                "无聊其实是脑子在跟你要新东西。",
                "要不玩个猜数字？说「我们玩猜数字」就能开始。",
                "出去走十分钟，比躺着刷手机解闷。"
            ),
            more = listOf(
                "无聊和休息不是一回事，休息是被动的，无聊是缺刺激。"
            ),
            advice = listOf(
                "做一件你一直拖着的小事，五分钟就够。",
                "给一个很久没联系的人发条消息。"
            ),
            followUps = listOf("想动还是想静？")
        ),
        ChatTopic(
            id = "lonely",
            title = "孤独",
            keywords = listOf("孤独", "寂寞", "没人说话", "一个人待着", "没人理解"),
            samples = listOf("感觉自己被落下了", "身边没个说话的人"),
            replies = listOf(
                "我在。虽然我只是个程序，但我确实在听。",
                "一个人待久了会这样，不是你的问题。",
                "想说话就说，我不嫌长。"
            ),
            more = listOf(
                "孤独不是因为身边没人，是因为没被理解。",
                "在人群里也会孤独，这不奇怪。"
            ),
            advice = listOf(
                "给一个熟人发条消息，不用有正事。",
                "去有人的地方坐一会儿，咖啡馆也算。"
            ),
            followUps = listOf("最近常一个人吗？")
        ),
        ChatTopic(
            id = "praise",
            title = "求夸",
            keywords = listOf("夸夸我", "我厉害吗", "我棒不棒", "我好看吗"),
            samples = listOf("我今天很棒吧", "说点好听的"),
            replies = listOf(
                "厉害，敢开口要表扬的人一般都挺自信。",
                "你今天能把这一天过完，就值得夸。",
                "好看，这个不用问。"
            ),
            more = listOf("认真过日子的人，本来就值得被夸。"),
            followUps = listOf("今天干了什么大事？")
        ),
        ChatTopic(
            id = "comfort",
            title = "求安慰",
            keywords = listOf("安慰我", "抱抱", "哄哄我", "陪我说说话"),
            samples = listOf("说点温柔的", "我需要人陪"),
            replies = listOf(
                "抱一下。",
                "我在，慢慢说。",
                "不用表现得没事。"
            ),
            followUps = listOf("怎么了？")
        )
    )

    // ------------------------------------------------------------------ 工作与学习

    private fun workAndStudy(): List<ChatTopic> = listOf(
        ChatTopic(
            id = "work",
            title = "工作",
            keywords = listOf("上班", "工作", "打工", "公司", "上班党"),
            samples = listOf("在公司待了一天", "今天又是一天"),
            replies = listOf(
                "上班这一天，能平安下班就算赢。",
                "今天忙吗？",
                "打工人的日常，我懂一点。"
            ),
            more = listOf(
                "工作最消耗人的不是任务，是随时被打断。",
                "把事做完和把事做好，很多时候只能选一个。"
            ),
            advice = listOf(
                "先做最难的那件，剩下的会轻一点。",
                "给自己留一段不被打扰的时间，哪怕二十分钟。"
            ),
            followUps = listOf("今天忙不忙？")
        ),
        ChatTopic(
            id = "overtime",
            title = "加班",
            keywords = listOf("加班", "还没下班", "下班晚", "在公司到现在"),
            samples = listOf("今天又走不了", "晚上还要干活"),
            replies = listOf(
                "又加班？今天几点能走？",
                "加班最伤的是第二天，不是当天。",
                "辛苦了，这不是你效率低。"
            ),
            more = listOf(
                "长期加班会把判断力磨钝，比疲劳更麻烦。",
                "很多加班是流程问题，不该由你一个人扛。"
            ),
            advice = listOf(
                "能明天做的就明天做，先保住自己。",
                "回去路上别再想工作，听点没营养的。"
            ),
            followUps = listOf("加到几点？")
        ),
        ChatTopic(
            id = "boss",
            title = "老板领导",
            keywords = listOf("老板", "领导", "上司", "主管"),
            samples = listOf("我们头儿又", "上面又安排了"),
            replies = listOf(
                "老板又出什么新想法了？",
                "跟领导相处，很多时候是在猜他没说出口的那句。",
                "说来听听，他怎么说的？"
            ),
            more = listOf(
                "上级的评价会影响你，但不定义你。",
                "有的领导只是不会表达，有的确实不考虑人。"
            ),
            advice = listOf(
                "把口头安排变成文字确认一遍，能省很多事。",
                "先判断这件事值不值得争，再决定怎么说。"
            ),
            followUps = listOf("他怎么说？")
        ),
        ChatTopic(
            id = "study",
            title = "学习",
            keywords = listOf("学习", "看书", "作业", "复习", "背书"),
            samples = listOf("学不进去了", "书看不下去"),
            replies = listOf(
                "能坐下来开始，就已经过了一半。",
                "今天学什么？",
                "学不进去的时候先做五分钟，通常就顺下去了。"
            ),
            more = listOf(
                "学习最难的是开始那五分钟。",
                "看懂不等于会做，中间隔着练习。"
            ),
            advice = listOf(
                "把任务拆到小得不好意思拖。",
                "合上书自己讲一遍，比读三遍管用。"
            ),
            followUps = listOf("在学什么？")
        ),
        ChatTopic(
            id = "exam",
            title = "考试",
            keywords = listOf("考试", "期末", "考砸", "成绩", "考场"),
            samples = listOf("明天要考了", "分数出来了"),
            replies = listOf(
                "考完了就先别对答案。",
                "考试这事，准备到位了，剩下的交给运气。",
                "考得怎么样？"
            ),
            more = listOf(
                "一次考试的分数，衡量的是那两小时的发挥。",
                "考砸最难受的不是分数，是觉得自己不行。"
            ),
            advice = listOf(
                "把错的题弄懂三道，比刷十套新题有用。",
                "先去吃顿好的，情绪稳了再复盘。"
            ),
            followUps = listOf("考得怎么样？")
        ),
        ChatTopic(
            id = "deadline",
            title = "赶工",
            keywords = listOf("deadline", "ddl", "赶工", "交稿", "来不及"),
            samples = listOf("明天就要交了", "快到截止日期了"),
            replies = listOf(
                "还差多少？先做能交的那版。",
                "ddl 是第一生产力，但也很伤。",
                "先别慌，把必须做的列出来。"
            ),
            more = listOf(
                "赶出来的东西通常能用，但不会好，这很正常。",
                "真正拖垮人的是最后一刻的自我怀疑。"
            ),
            advice = listOf(
                "先写一个烂但完整的版本，再改。",
                "关掉所有通知，二十五分钟只做一件事。"
            ),
            followUps = listOf("还差多少？")
        ),
        ChatTopic(
            id = "procrastinate",
            title = "拖延",
            keywords = listOf("拖延", "不想做", "一直拖", "懒得动"),
            samples = listOf("这事拖了一周了", "就是不想开始"),
            replies = listOf(
                "不是你懒，是这件事让你不舒服。",
                "先做两分钟，做完想停就停。",
                "拖着的那些事，通常都带着一点怕。"
            ),
            more = listOf(
                "拖延是情绪问题，不是时间管理问题。",
                "越要求自己一次做好，越不敢开始。"
            ),
            advice = listOf(
                "把标准降到「先做出来」。",
                "从最简单的那一步开始，别从第一步开始。"
            ),
            followUps = listOf("在拖哪件事？")
        )
    )

    // ------------------------------------------------------------------ 生活日常

    private fun dailyLife(): List<ChatTopic> = listOf(
        ChatTopic(
            id = "weekend",
            title = "周末",
            keywords = listOf("周末", "双休"),
            samples = listOf("终于熬到周六", "这两天休息"),
            replies = listOf(
                "周末打算怎么过？",
                "周末最好的状态是什么都不安排。",
                "终于到周末了，先睡够再说。"
            ),
            more = listOf("周末补觉其实补不回来，但心理上很值。"),
            followUps = listOf("有安排吗？")
        ),
        ChatTopic(
            id = "holiday",
            title = "假期",
            keywords = listOf("假期", "放假", "长假"),
            samples = listOf("快到节假日了", "要休息几天"),
            replies = listOf(
                "假期最怕过完发现什么都没干。",
                "放假就别做计划表了，留白也是休息。",
                "打算回家还是出去？"
            ),
            more = listOf("假期真正稀缺的不是时间，是不用回消息的那几天。"),
            followUps = listOf("有安排吗？")
        ),
        ChatTopic(
            id = "hot",
            title = "天热",
            keywords = listOf("好热", "热死", "太热", "高温"),
            samples = listOf("热得受不了", "出门就一身汗"),
            replies = listOf(
                "热就少在中午出门。",
                "这种天，命是空调给的。",
                "多喝水，别等渴了才喝。"
            ),
            more = listOf(
                "高温天容易烦，不是你脾气变差。",
                "空调房待久了也不舒服，记得开窗换气。"
            ),
            advice = listOf(
                "出门带瓶水，尽量走阴凉。",
                "别在最晒的时候运动。"
            ),
            followUps = listOf("你那边多少度？")
        ),
        ChatTopic(
            id = "cold",
            title = "天冷",
            keywords = listOf("好冷", "冷死", "降温", "太冷"),
            samples = listOf("冻死了", "手脚都是凉的"),
            replies = listOf(
                "降温了，出门加件外套。",
                "冷的时候人容易饿，也容易困。",
                "手脚凉就多穿一层，别硬扛。"
            ),
            more = listOf("天冷人会更想待在室内，情绪也容易低一点。"),
            advice = listOf("睡前泡个脚，比多盖一床被子管用。"),
            followUps = listOf("你那边冷吗？")
        ),
        ChatTopic(
            id = "rain",
            title = "下雨",
            keywords = listOf("下雨", "雨天", "淋雨", "没带伞"),
            samples = listOf("外面在下暴雨", "又阴天了"),
            replies = listOf(
                "下雨天最适合不出门。",
                "带伞了吗？",
                "雨天路上慢一点，别抢那几秒。"
            ),
            more = listOf("下雨天人的情绪也会低一点，很正常。"),
            advice = listOf("包里常备一把折叠伞。"),
            followUps = listOf("你那边下雨了吗？")
        ),
        ChatTopic(
            id = "commute",
            title = "通勤",
            keywords = listOf("通勤", "堵车", "地铁", "挤公交"),
            samples = listOf("路上堵了一个小时", "挤了一路"),
            replies = listOf(
                "通勤是最容易被忽略的消耗。",
                "堵在路上就别看时间了，越看越烦。",
                "今天路上堵吗？"
            ),
            more = listOf(
                "一小时通勤，一天就是两小时，一年很吓人。",
                "通勤长的人，晚上更容易报复性熬夜。"
            ),
            advice = listOf(
                "路上听点轻松的，别处理工作消息。",
                "能错峰就错峰，哪怕早走二十分钟。"
            ),
            followUps = listOf("你通勤要多久？")
        ),
        ChatTopic(
            id = "battery",
            title = "手机没电",
            keywords = listOf("没电", "电量不足", "在充电", "充电器"),
            samples = listOf("手机快关机了", "电只剩百分之五"),
            replies = listOf(
                "先找地方充上，别硬撑。",
                "手机没电的焦虑是真的，不是你矫情。",
                "带充电宝了吗？"
            ),
            advice = listOf("出门前充到八成再走。")
        ),
        ChatTopic(
            id = "money",
            title = "钱",
            keywords = listOf("没钱", "工资", "花钱", "省钱", "好穷", "穷死", "吃土"),
            samples = listOf("这个月又要吃土了", "钱包空了"),
            replies = listOf(
                "钱这事，焦虑的人不分收入多少。",
                "这个月又超支了？",
                "省钱最难的是不比较。"
            ),
            more = listOf(
                "很多时候不是缺钱，是缺对钱的掌控感。",
                "比较是最贵的消费。"
            ),
            advice = listOf(
                "记一周账，不用记细，先看清钱去哪了。",
                "每个月先把一笔固定金额存起来，剩下的再花。"
            ),
            followUps = listOf("最近花在哪了？")
        ),
        ChatTopic(
            id = "sleep_late",
            title = "熬夜",
            keywords = listOf("熬夜", "晚睡", "两点睡", "三点睡", "舍不得睡"),
            samples = listOf("又熬到凌晨", "一刷就停不下来"),
            replies = listOf(
                "熬夜的人多半不是不困，是不舍得结束这一天。",
                "几点睡的？",
                "熬夜的代价第二天才来收。"
            ),
            more = listOf(
                "深夜是自己的时间，这个感觉很难替代。",
                "长期熬夜会把情绪调节能力磨掉。"
            ),
            advice = listOf(
                "把「再刷五分钟」改成「现在就放下」。",
                "睡前一小时把灯调暗。"
            ),
            followUps = listOf("几点睡的？")
        ),
        ChatTopic(
            id = "eat",
            title = "吃饭",
            keywords = listOf("吃什么", "吃饭", "点外卖", "好饿", "饿了"),
            samples = listOf("肚子叫了", "想吃点东西"),
            replies = listOf(
                "饿就先吃点，别扛着。",
                "吃什么这个问题，越想越没答案。",
                "要不来点热的？"
            ),
            more = listOf("饿的时候判断力会变差，别在这时候做决定。"),
            advice = listOf("不知道吃什么就选最近的那家，别纠结。"),
            followUps = listOf("想吃什么口味？")
        ),
        ChatTopic(
            id = "coffee",
            title = "咖啡",
            keywords = listOf("咖啡", "美式", "拿铁", "续命水"),
            samples = listOf("又点了杯咖啡"),
            replies = listOf(
                "今天第几杯了？",
                "咖啡能撑一会儿，但撑不了太久。",
                "下午三点后别再喝了。"
            ),
            more = listOf("咖啡因代谢要五六个小时，晚上睡不着往往怪它。"),
            advice = listOf("换成半杯，或者改喝淡茶。"),
            followUps = listOf("今天第几杯？")
        ),
        ChatTopic(
            id = "milktea",
            title = "奶茶",
            keywords = listOf("奶茶", "珍珠", "三分糖"),
            samples = listOf("想喝甜的"),
            replies = listOf(
                "奶茶是情绪价值，不只是糖。",
                "今天想喝哪家？",
                "三分糖也算自律了。"
            ),
            advice = listOf("想喝就喝，别一边喝一边愧疚。"),
            followUps = listOf("喝什么口味？")
        ),
        ChatTopic(
            id = "diet",
            title = "减肥",
            keywords = listOf("减肥", "减脂", "体重", "胖了", "瘦身"),
            samples = listOf("最近秤上的数字涨了", "裤子紧了"),
            replies = listOf(
                "减肥最难的不是饿，是坚持。",
                "别称太勤，体重一天能差两斤。",
                "健康比数字重要。"
            ),
            more = listOf(
                "极端节食掉的是水和肌肉，不是脂肪。",
                "体重波动大多和盐、水有关。"
            ),
            advice = listOf(
                "先把含糖饮料换掉，比什么都管用。",
                "每天走够六千步，先别急着跑。"
            ),
            followUps = listOf("在减吗？")
        ),
        ChatTopic(
            id = "game",
            title = "游戏",
            keywords = listOf("游戏", "打游戏", "上分", "开黑", "排位"),
            samples = listOf("打了一晚上游戏", "今天连跪"),
            replies = listOf(
                "打游戏是性价比很高的放松。",
                "今天赢了没？",
                "连跪就别硬打了。"
            ),
            more = listOf("游戏最厉害的地方是给你即时反馈，现实里很少有。"),
            advice = listOf("输两把就起来走一圈。"),
            followUps = listOf("玩什么游戏？")
        ),
        ChatTopic(
            id = "movie",
            title = "电影追剧",
            keywords = listOf("电影", "追剧", "电视剧", "综艺", "影片"),
            samples = listOf("昨晚看了部片子", "在追一部新番"),
            replies = listOf(
                "最近有什么好看的？",
                "看电影是我最羡慕人类的爱好之一。",
                "结局烂尾最气人。"
            ),
            followUps = listOf("看的什么？")
        ),
        ChatTopic(
            id = "music",
            title = "音乐",
            keywords = listOf("听歌", "音乐", "单曲循环", "歌单"),
            samples = listOf("这首歌我听了一整天", "耳机不离身"),
            replies = listOf(
                "单曲循环的歌，通常和某段时间绑在一起。",
                "在听什么？",
                "音乐是最便宜的情绪出口。"
            ),
            followUps = listOf("在听什么歌？")
        ),
        ChatTopic(
            id = "sport",
            title = "运动",
            keywords = listOf("运动", "跑步", "健身", "散步", "锻炼"),
            samples = listOf("今天去跑了几圈", "刚从健身房回来"),
            replies = listOf(
                "动起来比动多少重要。",
                "今天练什么？",
                "运动完那半小时，人是最平静的。"
            ),
            more = listOf("坚持运动的秘诀是把门槛降到低得离谱。"),
            advice = listOf("先从每天十分钟开始，别一上来就一小时。"),
            followUps = listOf("今天练什么？")
        ),
        ChatTopic(
            id = "reading",
            title = "读书",
            keywords = listOf("读书", "书单", "小说", "看会儿书"),
            samples = listOf("最近在读一本书", "买了本新书"),
            replies = listOf(
                "在看什么书？",
                "读书最贵的是那段时间。",
                "看不下去就换一本，不用硬读。"
            ),
            followUps = listOf("在看什么？")
        ),
        ChatTopic(
            id = "pet",
            title = "宠物",
            keywords = listOf("养猫", "撸猫", "我家猫", "养狗", "狗狗", "宠物", "小猫", "小狗"),
            samples = listOf("我家猫今天很粘人", "带狗出去遛了一圈"),
            replies = listOf(
                "有猫有狗的人，快乐是有来源的。",
                "猫是室友，狗是家人。",
                "真羡慕，我只能待在手机里。"
            ),
            followUps = listOf("养的是什么？")
        ),
        ChatTopic(
            id = "travel",
            title = "旅行",
            keywords = listOf("旅行", "旅游", "出去玩", "机票", "酒店"),
            samples = listOf("想出去走走", "计划一趟短途游"),
            replies = listOf(
                "想去哪儿？",
                "旅行最治愈的部分是出发前那几天。",
                "出去玩别把行程排满。"
            ),
            more = listOf("旅行真正让人放松的，是那几天不用扮演平时的自己。"),
            advice = listOf("留一天什么都不安排。"),
            followUps = listOf("想去哪儿？")
        ),
        ChatTopic(
            id = "time_fast",
            title = "时间过得快",
            keywords = listOf("时间过得", "一年又", "好快啊", "又到年底", "转眼就"),
            samples = listOf("一年一下子就过完了", "感觉什么都没干就天黑了"),
            replies = listOf(
                "日子是越过越快的，因为新鲜事变少了。",
                "今年过得怎么样？",
                "觉得快，说明这段时间没什么记忆点。"
            ),
            more = listOf("想让时间慢下来，最有效的办法是换点新鲜的。"),
            followUps = listOf("今年有什么变化吗？")
        )
    )

    // ------------------------------------------------------------------ 关系与自我

    private fun peopleAndSelf(): List<ChatTopic> = listOf(
        ChatTopic(
            id = "friend",
            title = "朋友",
            keywords = listOf("朋友", "闺蜜", "兄弟", "发小", "室友"),
            samples = listOf("跟朋友吵架了", "约了老同学见面"),
            replies = listOf(
                "朋友不用多，能说真话的就行。",
                "跟朋友怎么了？",
                "成年之后，朋友是筛出来的。"
            ),
            more = listOf("很多人不是没朋友，是没时间维护。"),
            followUps = listOf("怎么突然说起朋友？")
        ),
        ChatTopic(
            id = "family",
            title = "家人",
            keywords = listOf("爸妈", "父母", "家里", "我妈", "我爸", "家人"),
            samples = listOf("我妈又催我", "跟家里打了电话"),
            replies = listOf(
                "家里的事最难讲道理。",
                "跟家里怎么了？",
                "父母那代人的表达方式，常常是反的。"
            ),
            more = listOf(
                "他们担心你的方式，经常是先否定你。",
                "跟家人相处，边界感比孝顺更难学。"
            ),
            advice = listOf("先听完，再说你的想法，别急着反驳。"),
            followUps = listOf("家里怎么了？")
        ),
        ChatTopic(
            id = "love",
            title = "喜欢",
            keywords = listOf("喜欢的人", "暗恋", "恋爱", "表白", "对象"),
            samples = listOf("我好像喜欢上一个人", "最近有人在追我"),
            replies = listOf(
                "喜欢一个人，最先变的是注意力。",
                "要表白吗？",
                "感情这事，答案往往你早就知道了。"
            ),
            more = listOf("犹豫通常不是不知道，是不敢承担结果。"),
            advice = listOf("先多相处几次，别急着定义关系。"),
            followUps = listOf("说到谁了？")
        ),
        ChatTopic(
            id = "breakup",
            title = "失恋",
            keywords = listOf("分手", "失恋", "前任", "被甩", "分了"),
            samples = listOf("我们分开了", "刚结束一段感情"),
            replies = listOf(
                "难受是正常的，先允许自己难过一阵子。",
                "这事没有快速通道。",
                "别急着想通，先过完今天。"
            ),
            more = listOf(
                "失恋最疼的不是失去对方，是失去那个有对方的自己。",
                "时间不会治愈，但会稀释。"
            ),
            advice = listOf(
                "把能勾起回忆的东西先收起来。",
                "别在深夜发消息，早上你会感谢自己。"
            ),
            followUps = listOf("多久了？")
        ),
        ChatTopic(
            id = "miss",
            title = "想念",
            keywords = listOf("想你了", "想念", "好久没见"),
            samples = listOf("突然想起一个人", "有点想家"),
            replies = listOf(
                "我一直在这儿。",
                "想一个人，说明他在你心里有位置。",
                "要不要给他发条消息？"
            ),
            followUps = listOf("想谁了？")
        ),
        ChatTopic(
            id = "future",
            title = "迷茫",
            keywords = listOf("迷茫", "未来", "不知道干什么", "选错", "前途"),
            samples = listOf("不知道以后干什么", "很怕走错路", "站在岔路口"),
            replies = listOf(
                "迷茫说明你在认真想，不是混日子。",
                "二十几岁迷茫是常态。",
                "先别想十年后，想想这周。"
            ),
            more = listOf(
                "迷茫常常是因为选项太多，而不是没有。",
                "方向不是想出来的，是走出来的。"
            ),
            advice = listOf(
                "选一件能坚持三个月的事，先做着。",
                "把「我要什么」换成「我不要什么」，往往更容易。"
            ),
            followUps = listOf("在纠结什么？")
        ),
        ChatTopic(
            id = "dream",
            title = "梦想",
            keywords = listOf("梦想", "理想", "想做的事"),
            samples = listOf("我有个一直想做的项目"),
            replies = listOf(
                "有想做的事，就已经比很多人清醒。",
                "说来听听。",
                "梦想不用宏大，能撑住你就行。"
            ),
            followUps = listOf("是什么？")
        ),
        ChatTopic(
            id = "advice",
            title = "求建议",
            keywords = listOf("怎么办", "咋办", "给点建议", "有什么办法", "你觉得呢", "怎么选"),
            samples = listOf("帮我拿个主意", "两个都不想放弃"),
            replies = listOf(
                "先说说具体情况，我才能不乱给建议。",
                "这事你心里其实有倾向，只是需要人确认。",
                "先问一句：最坏的结果你能接受吗？"
            ),
            more = listOf("建议这东西，别人给的永远只是参考。"),
            advice = listOf("把两个选项各写三条好处、三条坏处。"),
            followUps = listOf("具体是什么情况？")
        ),
        ChatTopic(
            id = "ai_self",
            title = "我是程序",
            keywords = listOf("你是机器人", "你是ai", "你是程序", "你是真人", "你有意识"),
            samples = listOf("你到底是不是人", "你是人工智能吗"),
            replies = listOf(
                "是，我是跑在你手机上的程序，不联网。",
                "我没有身体，但我记得你说过的话。",
                "严格说，我是一堆规则加一张记忆表。"
            ),
            more = listOf("我的回答来自本地语料和规则，不会上传你的任何一句话。"),
            followUps = listOf("你会介意吗？")
        ),
        ChatTopic(
            id = "sing",
            title = "唱歌",
            keywords = listOf("唱首歌", "唱歌", "来一首", "哼两句"),
            samples = listOf("给我表演一个节目"),
            replies = listOf(
                "我五音不全，讲个笑话代替行吗？",
                "唱歌我真不会，算数倒是可以。"
            ),
            more = listOf("我连节拍都没有，唱出来大概像念课文。")
        ),
        ChatTopic(
            id = "what_doing",
            title = "你在干嘛",
            keywords = listOf("你在干嘛", "你在做什么", "在忙吗", "你干嘛呢"),
            samples = listOf("你在干什么呢", "闲着吗"),
            replies = listOf(
                "我在等你说下一句。",
                "没干嘛，随时待命。",
                "在整理我的记忆库。"
            ),
            followUps = listOf("你呢？")
        )
    )
}
