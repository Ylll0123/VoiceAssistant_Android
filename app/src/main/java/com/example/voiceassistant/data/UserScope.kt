package com.example.voiceassistant.data

import android.content.Context

/**
 * 「这份数据是谁的」——本机所有个人数据的分区键。
 *
 * 需求是「账号登录用于区分后来问答的个人数据，保证每个人都不一样」，
 * 所以对话历史、我教的记忆、提醒清单、待学习问题都按这个键分开存：
 * 换个账号登进来，看到的就是另一套数据，互相看不见也改不着。
 *
 * ## 为什么不用用户 id 而是用户名
 * 本地账号没有后端、也没有自增 id（见 [AccountStore]），
 * 用户名在注册时已经唯一（不区分大小写），直接当键最省事。
 * 改名要连数据一起搬，这是本地账号的固有限制，接受。
 *
 * ## 游客也有自己的分区
 * 没登录直接进来的人用一个固定键 [GUEST]，
 * 这样「游客 → 注册登录」不会把游客的对话混进新账号里。
 */
object UserScope {

    /** 游客分区 */
    const val GUEST = "guest"

    /** 当前登录者的分区键；未登录时是 [GUEST] */
    fun of(context: Context): String {
        val user = AccountStore(context).currentUser ?: return GUEST
        return key(user)
    }

    /**
     * 用户名 → 能当文件名 / prefs 键用的分区名。
     *
     * 只替换路径分隔符和空白这类真会出问题的字符，中文照原样保留：
     * 转成拼音或哈希之后，出问题时连是哪个账号都看不出来。
     */
    fun key(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("""[\\/:*?"<>|\s]"""), "_")
            .ifEmpty { GUEST }

    /**
     * 把升级前那份「没有归属」的库判给当前账号。
     *
     * 只在新库还不存在、旧库存在时改名：改名是原子的、不用搬数据，
     * 也不会出现「两个库同时有数据」的中间状态。
     */
    fun adoptLegacyDb(context: Context, legacyName: String, newName: String) {
        if (legacyName == newName) return
        runCatching {
            val dir = context.getDatabasePath(legacyName).parentFile ?: return
            val old = java.io.File(dir, legacyName)
            val target = java.io.File(dir, newName)
            if (!target.exists() && old.exists()) old.renameTo(target)
        }
    }
}
