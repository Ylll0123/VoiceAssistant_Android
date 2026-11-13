package com.example.voiceassistant.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * 通讯录查询。
 *
 * 抽成接口是为了让「查名字 → 拿到号码」这段能被单测替换成假实现：
 * 真机上要 READ_CONTACTS 和 ContentResolver，单测里两样都没有，
 * 而「重名怎么办」「没权限怎么回话」恰恰是最该测的分支。
 */
interface PhoneBook {

    /** 一次按名字查找的结果 */
    sealed class Lookup {

        /** 找到唯一一个 */
        data class One(val name: String, val number: String) : Lookup()

        /** 重名：把候选名字带出来，让用户说全名，而不是替他挑一个 */
        data class Many(val names: List<String>) : Lookup()

        /** 通讯录里没有 */
        object None : Lookup()

        /** 没给读通讯录的权限 */
        object Denied : Lookup()
    }

    fun find(name: String): Lookup
}

/**
 * [PhoneBook] 的安卓实现。
 *
 * ## 为什么先精确再模糊
 * 用户说「妈妈」，通讯录里可能存的是「妈妈」也可能存的是「王秀兰（妈妈）」。
 * 先按显示名完全相等找一遍，找不到再用 `LIKE` 模糊匹配；
 * 模糊匹配一次最多取 8 条，超过就当重名让用户说清楚——
 * **绝不替用户在几个同名/近似的人里挑一个**，打错电话比打不出去糟糕得多。
 *
 * ## 号码去重
 * 一个人常有手机、家庭、工作三个号码，同一号码也可能在多个账号下重复。
 * 按号码去重后仍然多于一个，就按重名处理。
 */
class AndroidPhoneBook(context: Context) : PhoneBook {

    private val app: Context = context.applicationContext

    override fun find(name: String): PhoneBook.Lookup {
        val key = name.trim()
        if (key.isEmpty()) return PhoneBook.Lookup.None
        if (!canRead()) return PhoneBook.Lookup.Denied

        exact(key)?.let { return it }
        return fuzzy(key) ?: PhoneBook.Lookup.None
    }

    private fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        app,
        Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    /** 显示名完全一致 */
    private fun exact(name: String): PhoneBook.Lookup? {
        val rows = query(
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
            args = arrayOf(name)
        )
        return rows.toLookup()
    }

    /**
     * 模糊：`LIKE %关键字%`。
     *
     * 只在精确没命中时走，所以「妈妈」不会因为通讯录里有个「妈妈的同事」而变成重名。
     */
    private fun fuzzy(name: String): PhoneBook.Lookup? {
        // LIKE 的通配符要转义，否则用户名字里的 % 会把整张表捞出来
        val escaped = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = query(
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? ESCAPE '\\'",
            args = arrayOf("%$escaped%")
        )
        return rows.toLookup()
    }

    private data class Row(val name: String, val number: String)

    private fun query(selection: String, args: Array<String>): List<Row> {
        val rows = ArrayList<Row>(4)
        runCatching {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                selection,
                args,
                // 名字长的排前面：「王秀兰（妈妈）」比「妈妈的朋友」更可能是本人
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (cursor.moveToNext() && rows.size < MAX_ROWS) {
                    val display = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                    val number = if (numberIndex >= 0) cursor.getString(numberIndex).orEmpty() else ""
                    val clean = PhoneFormats.normalizeNumber(number)
                    if (clean.isEmpty()) continue
                    rows += Row(display, clean)
                }
            }
        }.onFailure { error ->
            android.util.Log.w("PhoneBook", "查通讯录失败: ${error.message}")
        }
        return rows
    }

    /**
     * 多行 → [PhoneBook.Lookup]。
     *
     * 同一个号码出现多次只算一个人（同一个人多个账号很常见）；
     * 去重后还是多个，就是真的有几个人，交给用户决定。
     */
    private fun List<Row>.toLookup(): PhoneBook.Lookup? {
        if (isEmpty()) return null
        val byNumber = LinkedHashMap<String, String>()
        for (row in this) {
            byNumber.putIfAbsent(row.number, row.name.ifBlank { row.number })
        }
        val first = byNumber.entries.first()
        return if (byNumber.size == 1) {
            PhoneBook.Lookup.One(first.value, first.key)
        } else {
            PhoneBook.Lookup.Many(byNumber.values.toList())
        }
    }

    private companion object {
        /** 一次最多看 8 行：够判断重名，也不至于把整个通讯录读出来 */
        const val MAX_ROWS = 8
    }
}

/**
 * 号码与地图链接的纯函数。
 *
 * 单独拎出来是为了能单测：`+86 138-0000-0000`、`138 0000 0000`、`13800000000`
 * 都得变成同一个号码，`geo:` 的查询串必须转义——这两件事出错都会表现成
 * 「说了没反应」，而在真机上很难一眼看出来。
 */
object PhoneFormats {

    /** 看起来像号码：只允许数字、空格、横线、括号、加号，且数字不少于 3 位 */
    private val NUMBERISH = Regex("^[+\\d][\\d\\s\\-()]{1,24}$")

    /**
     * 归一成一个可直接拨的号码。
     *
     * 只留数字和开头的 `+`（国际区号）；`+` 必须留在最前面，
     * 挪到后面拨号会失败。
     */
    fun normalizeNumber(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        var plus = false
        val digits = StringBuilder(text.length)
        for ((index, ch) in text.withIndex()) {
            when {
                ch == '+' && index == text.indexOfFirst { !it.isWhitespace() } -> plus = true
                ch.isDigit() -> digits.append(ch)
                else -> Unit
            }
        }
        if (digits.length < MIN_DIGITS) return ""
        return if (plus) "+$digits" else digits.toString()
    }

    /** 「这就是个号码，不是人名」 */
    fun looksLikeNumber(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !NUMBERISH.matches(text)) return false
        return text.count { it.isDigit() } >= MIN_DIGITS
    }

    /**
     * 地图查询链接。
     *
     * `geo:0,0?q=目的地` 是最通用的写法：高德、百度、Google 地图都接这个 scheme，
     * 具体用哪个由用户设的默认地图决定，不需要我们判断装了谁。
     * `mode` 是 Geo URI 的通行写法：d 驾车 / w 步行 / b 骑行 / l 公交。
     */
    fun geoUri(destination: String, mode: String? = null): String {
        val query = destination.trim()
        val flag = when (mode?.trim()?.lowercase()) {
            "walk", "walking", "步行", "走路" -> "w"
            "bike", "bicycle", "骑行", "骑车" -> "b"
            "transit", "bus", "公交", "地铁" -> "l"
            "drive", "driving", "驾车", "开车" -> "d"
            else -> null
        }
        val encoded = encode(query)
        return if (flag == null) "geo:0,0?q=$encoded" else "geo:0,0?q=$encoded&mode=$flag"
    }

    /** 没有地图应用时的网页退路：高德的公开搜索页，国内可用、不需要 key */
    fun mapWebFallback(destination: String): String =
        "https://uri.amap.com/search?keyword=" + encode(destination.trim())

    /**
     * 百分号编码（UTF-8）。
     *
     * 不用 `Uri.encode`：那是 Android 的类，在 JVM 单测里调用会直接抛 "Stub!"，
     * 「目的地里带空格会不会把链接拼坏」就永远测不到。这里只保留 unreserved 字符，
     * 其余一律转义——`?`、`&`、`=` 必须被转，否则地名里的 `&` 会把参数截断。
     */
    private fun encode(text: String): String {
        val out = StringBuilder(text.length * 3)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val ch = value.toChar()
            val plain = value < 0x80 &&
                (ch.isLetterOrDigit() || ch == '-' || ch == '.' || ch == '_' || ch == '~')
            if (plain) {
                out.append(ch)
            } else {
                out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"

    /** 至少几位数字才算号码：3 位能覆盖 110/119 这类短号 */
    private const val MIN_DIGITS = 3
}
