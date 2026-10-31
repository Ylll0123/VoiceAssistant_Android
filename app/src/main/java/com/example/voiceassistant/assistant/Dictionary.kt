package com.example.voiceassistant.assistant

/**
 * 内置中英基础词典（纯离线，不联网、不调任何翻译接口）。
 *
 * 覆盖日常高频词约 330 条：生活物品、食物、交通出行、天气、时间、学习、
 * 家庭称呼、动物、颜色数字、身体、科技、情绪形容词、常用动词。
 * 双向查询：中文 → 英文（「苹果用英语怎么说」）、英文 → 中文（「apple 是什么意思」）。
 *
 * 选择标准只有一个：**日常真会用到的词**。生僻词交给用户教（记忆库），
 * 词典只保证基础词一定能查到，这也是课程演示里最容易讲清的一块。
 */
object Dictionary {

    /** 词条：中文 → 英文 */
    private val words: List<Pair<String, String>> = listOf(
        // 日常物品
        "苹果" to "apple", "香蕉" to "banana", "水" to "water", "牛奶" to "milk",
        "咖啡" to "coffee", "茶" to "tea", "米饭" to "rice", "面条" to "noodle",
        "面包" to "bread", "鸡蛋" to "egg", "肉" to "meat", "鱼" to "fish",
        "蔬菜" to "vegetable", "水果" to "fruit", "糖" to "sugar", "盐" to "salt",
        "书" to "book", "笔" to "pen", "纸" to "paper", "桌子" to "desk",
        "椅子" to "chair", "门" to "door", "窗户" to "window", "钥匙" to "key",
        "手机" to "phone", "电脑" to "computer", "书包" to "bag", "衣服" to "clothes",
        "鞋子" to "shoe", "帽子" to "hat", "伞" to "umbrella", "钱" to "money",
        "家" to "home", "房间" to "room", "床" to "bed", "灯" to "light",
        "杯子" to "cup", "碗" to "bowl", "刀" to "knife", "地图" to "map",
        // 交通出行
        "汽车" to "car", "公交车" to "bus", "地铁" to "subway", "火车" to "train",
        "飞机" to "plane", "自行车" to "bike", "出租车" to "taxi", "船" to "ship",
        "路" to "road", "车站" to "station", "机场" to "airport", "票" to "ticket",
        "旅行" to "travel", "地图导航" to "navigation", "行李" to "luggage", "红绿灯" to "traffic light",
        "司机" to "driver", "乘客" to "passenger", "高速公路" to "highway", "加油站" to "gas station",
        // 天气环境
        "天气" to "weather", "雨" to "rain", "雪" to "snow", "风" to "wind",
        "云" to "cloud", "太阳" to "sun", "月亮" to "moon", "星星" to "star",
        "温度" to "temperature", "冷" to "cold", "热" to "hot", "暖和" to "warm",
        "夏天" to "summer", "冬天" to "winter", "春天" to "spring", "秋天" to "autumn",
        "海" to "sea", "山" to "mountain", "河" to "river", "树" to "tree",
        "花" to "flower", "草" to "grass", "天空" to "sky", "地球" to "earth",
        // 时间
        "时间" to "time", "今天" to "today", "明天" to "tomorrow", "昨天" to "yesterday",
        "早上" to "morning", "中午" to "noon", "晚上" to "evening", "夜晚" to "night",
        "星期" to "week", "月" to "month", "年" to "year", "小时" to "hour",
        "分钟" to "minute", "秒" to "second", "现在" to "now", "以后" to "later",
        "以前" to "before", "生日" to "birthday", "假期" to "holiday", "周末" to "weekend",
        // 学习工作
        "学校" to "school", "老师" to "teacher", "学生" to "student", "同学" to "classmate",
        "教室" to "classroom", "考试" to "exam", "作业" to "homework", "课程" to "course",
        "问题" to "question", "答案" to "answer", "单词" to "word", "句子" to "sentence",
        "语言" to "language", "英语" to "English", "中文" to "Chinese", "数学" to "math",
        "历史" to "history", "科学" to "science", "音乐" to "music", "画" to "painting",
        "工作" to "work", "会议" to "meeting", "办公室" to "office", "老板" to "boss",
        "同事" to "colleague", "公司" to "company", "项目" to "project", "计划" to "plan",
        // 人物家庭
        "爸爸" to "father", "妈妈" to "mother", "儿子" to "son", "女儿" to "daughter",
        "哥哥" to "older brother", "弟弟" to "younger brother", "姐姐" to "older sister",
        "妹妹" to "younger sister", "爷爷" to "grandfather", "奶奶" to "grandmother",
        "朋友" to "friend", "家人" to "family", "孩子" to "child", "男人" to "man",
        "女人" to "woman", "医生" to "doctor", "护士" to "nurse", "警察" to "police",
        // 动物
        "猫" to "cat", "狗" to "dog", "鸟" to "bird", "鱼" to "fish",
        "猪" to "pig", "牛" to "cow", "马" to "horse", "羊" to "sheep",
        "鸡" to "chicken", "鸭子" to "duck", "老虎" to "tiger", "熊猫" to "panda",
        "兔子" to "rabbit", "老鼠" to "mouse", "蜜蜂" to "bee", "大象" to "elephant",
        // 颜色数字
        "红色" to "red", "蓝色" to "blue", "绿色" to "green", "黄色" to "yellow",
        "黑色" to "black", "白色" to "white", "灰色" to "gray", "粉色" to "pink",
        "一" to "one", "二" to "two", "三" to "three", "四" to "four",
        "五" to "five", "六" to "six", "七" to "seven", "八" to "eight",
        "九" to "nine", "十" to "ten", "百" to "hundred", "千" to "thousand",
        // 身体与健康
        "头" to "head", "眼睛" to "eye", "耳朵" to "ear", "鼻子" to "nose",
        "嘴" to "mouth", "手" to "hand", "脚" to "foot", "心" to "heart",
        "身体" to "body", "病" to "illness", "药" to "medicine", "医院" to "hospital",
        "疼" to "pain", "累" to "tired", "健康" to "health", "运动" to "exercise",
        // 情绪与形容词
        "高兴" to "happy", "开心" to "happy", "难过" to "sad", "生气" to "angry",
        "害怕" to "afraid", "喜欢" to "like", "爱" to "love", "累的" to "tired",
        "忙" to "busy", "大" to "big", "小" to "small", "好" to "good",
        "坏" to "bad", "新" to "new", "旧" to "old", "漂亮" to "beautiful",
        "快" to "fast", "慢" to "slow", "贵" to "expensive", "便宜" to "cheap",
        "重要" to "important", "容易" to "easy", "困难" to "difficult", "有趣" to "interesting",
        // 常用动词
        "吃" to "eat", "喝" to "drink", "睡" to "sleep", "走" to "walk",
        "跑" to "run", "看" to "look", "听" to "listen", "说" to "speak",
        "读" to "read", "写" to "write", "学" to "learn", "教" to "teach",
        "买" to "buy", "卖" to "sell", "去" to "go", "来" to "come",
        "做" to "do", "想" to "think", "知道" to "know", "理解" to "understand",
        "帮助" to "help", "等待" to "wait", "开始" to "start", "结束" to "end",
        "喜欢" to "like", "需要" to "need", "给" to "give", "拿" to "take",
        // 科技与生活
        "网络" to "network", "密码" to "password", "消息" to "message", "照片" to "photo",
        "视频" to "video", "电池" to "battery", "屏幕" to "screen", "应用" to "app",
        "数据" to "data", "文件" to "file", "软件" to "software", "机器人" to "robot",
        "城市" to "city", "国家" to "country", "世界" to "world", "中国" to "China",
        "北京" to "Beijing", "上海" to "Shanghai", "商店" to "shop", "超市" to "supermarket",
        "银行" to "bank", "饭店" to "restaurant", "公园" to "park", "图书馆" to "library",
        "邮局" to "post office", "酒店" to "hotel", "礼物" to "gift", "音乐" to "music",
        "电影" to "movie", "游戏" to "game", "新闻" to "news", "音乐" to "music"
    )

    /** 中文 → 英文（一个中文词可能对应多条，取第一条） */
    private val zhToEn: Map<String, List<String>> by lazy {
        words.groupBy({ it.first }, { it.second })
    }

    /** 英文 → 中文（小写索引） */
    private val enToZh: Map<String, List<String>> by lazy {
        words.groupBy({ it.second.lowercase() }, { it.first })
    }

    /** 词条数量，设置页展示用 */
    fun size(): Int = zhToEn.size

    /** 中文查英文；查不到返回 null */
    fun toEnglish(chinese: String): String? {
        val key = chinese.trim()
        val direct = zhToEn[key]
        if (direct != null) return direct.first()
        // 兜底：输入里含有某个词条（「苹果手机」里含「苹果」）
        val contained = zhToEn.keys.filter { key.contains(it) }.maxByOrNull { it.length }
        return contained?.let { zhToEn[it]?.first() }
    }

    /** 英文查中文；查不到返回 null */
    fun toChinese(english: String): String? {
        val key = english.trim().lowercase()
        val direct = enToZh[key]
        if (direct != null) return direct.first()
        val contained = enToZh.keys.filter { key.contains(it) }.maxByOrNull { it.length }
        return contained?.let { enToZh[it]?.first() }
    }

    /** 判断一段文本是不是英文（纯拉丁字母 + 空格） */
    fun isEnglish(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        return value.all { it.isLetter() && it.code < 128 || it.isWhitespace() }
    }

    /** 词典里的全部中文词，用于检索兜底和界面展示 */
    fun chineseWords(): List<String> = zhToEn.keys.toList()
}
