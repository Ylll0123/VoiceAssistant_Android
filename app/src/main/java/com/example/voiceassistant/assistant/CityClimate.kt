package com.example.voiceassistant.assistant

import java.time.LocalDate

/**
 * 城市常年气候数据（离线内置，用于"天气 / 温度 / 出行建议"这类问题）。
 *
 * ## 说明
 * 纯离线助手拿不到实时天气，与其答"不知道"，不如答**有价值的东西**：
 * 每个城市四季的常年气温区间 + 气候特点 + 对应月份的穿衣出行建议。
 * 这是可查证的气候常识（不是编造的实时数据），回答里会明确说清是
 * 「常年平均」而不是「今天实时」，避免误导。
 *
 * 数据为各城市常年（30 年平均）气温的近似区间，单位是摄氏度，
 * 按气象四季划分：冬（12~2 月）、春（3~5 月）、夏（6~8 月）、秋（9~11 月）。
 */
object CityClimate {

    /** 一个城市的四季气温区间（低~高）与气候特点 */
    data class CityClimate(
        val city: String,
        val aliases: List<String> = emptyList(),
        val winter: IntRange,
        val spring: IntRange,
        val summer: IntRange,
        val autumn: IntRange,
        val feature: String
    )

    val cities: List<CityClimate> = listOf(
        CityClimate("北京", listOf("北平"), -5..5, 10..22, 24..33, 10..22, "四季分明，冬干冷、夏闷热，春天多风沙，秋季最舒服"),
        CityClimate("上海", listOf("沪"), 2..10, 12..23, 26..34, 15..26, "冬湿冷夏湿热，梅雨季在六月，春秋短暂"),
        CityClimate("广州", listOf("羊城"), 10..19, 18..27, 27..34, 20..29, "长夏无冬，湿度大，回南天墙体返潮"),
        CityClimate("深圳", emptyList(), 12..20, 19..28, 27..33, 21..29, "亚热带海洋性气候，冬季温暖，台风季在夏秋"),
        CityClimate("成都", listOf("蓉城"), 3..11, 13..24, 24..32, 14..23, "冬湿冷少雪，多云雾日照少，夏季闷热"),
        CityClimate("重庆", listOf("山城"), 5..12, 14..25, 27..37, 16..25, "著名的火炉，夏季酷热，冬季多雾"),
        CityClimate("杭州", emptyList(), 2..10, 12..23, 26..35, 14..25, "四季分明，梅雨明显，夏天闷热冬天湿冷"),
        CityClimate("南京", emptyList(), 0..9, 12..23, 26..34, 13..24, "冬冷夏热，梅雨明显，秋天短暂"),
        CityClimate("武汉", emptyList(), 1..10, 13..24, 26..35, 14..25, "夏季炎热潮湿，冬季湿冷，春秋短"),
        CityClimate("西安", listOf("长安"), -3..8, 11..24, 24..34, 11..23, "冬冷夏热较干燥，春季多风沙"),
        CityClimate("天津", emptyList(), -5..4, 11..22, 24..32, 10..22, "温带季风，冬干冷春风大，夏季多雨"),
        CityClimate("苏州", emptyList(), 2..10, 12..23, 26..34, 14..25, "梅雨明显，冬湿冷夏闷热，四季温和"),
        CityClimate("青岛", emptyList(), -2..6, 9..18, 23..28, 12..22, "海洋性气候，夏凉冬不严寒，海风大"),
        CityClimate("大连", emptyList(), -6..2, 8..18, 22..28, 10..21, "冬有雪夏凉爽，海风大，是避暑地"),
        CityClimate("沈阳", emptyList(), -15..-3, 6..19, 22..29, 6..19, "冬季漫长寒冷，夏季短促温暖"),
        CityClimate("长春", emptyList(), -18..-6, 5..18, 21..28, 4..18, "冬季严寒漫长，有冰雪，夏季凉爽"),
        CityClimate("哈尔滨", emptyList(), -22..-10, 3..17, 20..28, 2..16, "冬季极寒，冰雪著名，夏天凉爽短暂"),
        CityClimate("郑州", emptyList(), -3..8, 12..24, 25..33, 12..23, "冬冷夏热，春季干燥多风，秋高气爽"),
        CityClimate("济南", emptyList(), -3..8, 12..24, 25..33, 12..23, "冬冷夏热，春季多风，泉水著名"),
        CityClimate("长沙", emptyList(), 2..10, 13..24, 26..35, 14..25, "夏热冬湿冷，春季多雨，吃辣驱湿"),
        CityClimate("福州", emptyList(), 8..16, 16..25, 26..35, 18..27, "冬暖夏热，湿度大，台风季注意"),
        CityClimate("厦门", emptyList(), 11..18, 17..25, 26..33, 20..28, "海洋性气候，冬暖夏长，台风季在夏秋"),
        CityClimate("昆明", listOf("春城"), 4..16, 12..24, 17..25, 11..22, "四季如春，早晚温差大，紫外线强"),
        CityClimate("贵阳", emptyList(), 2..10, 12..22, 21..28, 12..22, "夏季凉爽是避暑地，冬季阴雨湿冷"),
        CityClimate("南宁", emptyList(), 10..18, 19..27, 26..34, 20..29, "夏长冬短，湿热明显，回南天潮湿"),
        CityClimate("海口", emptyList(), 15..22, 22..30, 27..34, 22..30, "热带气候，全年无冬，台风季雨量大"),
        CityClimate("兰州", emptyList(), -10..2, 8..21, 20..30, 6..20, "大陆性气候，干燥温差大，春季有沙尘"),
        CityClimate("乌鲁木齐", emptyList(), -18..-6, 6..20, 22..32, 4..18, "冬严寒夏炎热，昼夜温差大，干燥"),
        CityClimate("呼和浩特", emptyList(), -17..-4, 4..18, 20..29, 3..17, "冬季寒冷漫长，多大风，夏季凉爽"),
        CityClimate("拉萨", emptyList(), -8..8, 2..18, 13..24, 0..18, "高原气候，日照强紫外线强，昼夜温差极大"),
        CityClimate("中国香港", listOf("香港"), 13..19, 19..26, 27..32, 22..28, "亚热带，湿热多雨，台风季在夏秋"),
        CityClimate("中国澳门", listOf("澳门"), 13..19, 19..26, 27..32, 22..28, "亚热带海洋性气候，湿热多雨"),
        CityClimate("中国台北", listOf("台北"), 13..19, 18..26, 26..34, 21..28, "亚热带，冬季多雨，夏季闷热多台风")
    )

    /** 城市数量，界面展示用 */
    fun size(): Int = cities.size

    /** 在文本里找城市名（先长后短，避免「北京」被「北平」抢） */
    fun find(text: String): CityClimate? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        return cities
            .flatMap { city -> (listOf(city.city) + city.aliases).map { city to it } }
            .filter { (_, name) -> raw.contains(name) }
            .maxByOrNull { (_, name) -> name.length }
            ?.first
    }

    /** 按公历月份给出季节名 */
    fun seasonName(month: Int): String = when (month) {
        12, 1, 2 -> "冬季"
        in 3..5 -> "春季"
        in 6..8 -> "夏季"
        in 9..11 -> "秋季"
        else -> "当季"
    }

    /** 取某城市当前月份的气温区间 */
    fun rangeOf(city: CityClimate, month: Int): IntRange = when (month) {
        12, 1, 2 -> city.winter
        in 3..5 -> city.spring
        in 6..8 -> city.summer
        else -> city.autumn
    }

    /** 按平均高温给一句穿衣建议 */
    fun dressAdvice(high: Int): String = when {
        high >= 30 -> "短袖为主，注意防晒补水，正午尽量别在户外"
        high >= 25 -> "短袖加薄外套，早晚一件就够"
        high >= 20 -> "长袖单穿或加薄外套，早晚偏凉"
        high >= 15 -> "长袖加外套，怕冷的人加件马甲"
        high >= 10 -> "毛衣或卫衣加风衣，围巾可以带上"
        high >= 5 -> "厚外套加毛衣，注意保暖"
        high >= 0 -> "棉服或薄羽绒，手套帽子安排上"
        else -> "厚羽绒加保暖内衣，护好头颈手脚"
    }

    /** 按季节给一句出行提醒 */
    fun outingTip(month: Int, city: CityClimate?): String {
        val season = seasonName(month)
        val base = when (season) {
            "冬季" -> "路面可能结冰，鞋底要防滑；早晚温差大，出门多带一件"
            "春季" -> "早晚凉中午热，穿脱方便最好；多风地区注意防风沙"
            "夏季" -> "避开正午烈日，防晒补水，随身带伞（既能遮阳也能挡阵雨）"
            "秋季" -> "秋高气爽最适合出行，早晚温差大记得带外套"
            else -> "看天气决定装备，带伞最保险"
        }
        val extra = city?.let { "；${it.city}的${season}特点是${it.feature}" } ?: ""
        return base + extra
    }

    /**
     * 组装一句天气回答：城市 + 月份 + 常年气温 + 穿衣建议。
     * 明确标注是常年平均、不是实时数据。
     */
    fun describe(city: CityClimate?, date: LocalDate = LocalDate.now()): String {
        val month = date.monthValue
        val season = seasonName(month)
        if (city == null) {
            return "${month}月全国大部分地区进入${season}，南方普遍偏高、北方偏低，" +
                "具体差多少要看城市——你说一个城市名，我给你这个月的常年气温区间和穿衣建议。"
        }
        val range = rangeOf(city, month)
        return "${city.city}${month}月处在${season}，常年气温大约 ${range.first}℃ 到 ${range.last}℃。" +
            "这是多年气候平均值，不是今天的实时天气（我目前纯离线，不联网查实时数据）。" +
            "穿衣上：${dressAdvice(range.last)}。${city.feature}。"
    }
}
