package com.example.voiceassistant.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地账号：注册、登录、退出。
 *
 * 没有后端，也不打算有：需求是「加一个登录注册界面」，
 * 而这台手机上唯一需要区分的就是「谁的对话和提醒」。
 * 所以账号信息全部落在 SharedPreferences，密码只存 [Passwords] 的加盐哈希。
 *
 * **两态**（v4.0 起取消游客）：已登录 / 未登录。没登录一律挡在登录页——
 * 对话记录、提醒、标题总结都按账号隔离，没有身份就没有归属。
 */
class AccountStore(context: Context) {

    /** 登录/注册能出的所有错，界面负责翻译成文案 */
    enum class Error {
        EMPTY,
        SHORT_USERNAME,
        SHORT_PASSWORD,
        MISMATCH,
        USER_EXISTS,
        NO_USER,
        WRONG_PASSWORD
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 当前登录的用户名；未登录是 null */
    val currentUser: String?
        get() = prefs.getString(KEY_CURRENT, null)

    /** 是否已经登录 */
    val isSignedIn: Boolean
        get() = currentUser != null

    /** 已注册的用户名（设置页/调试用） */
    fun users(): List<String> =
        prefs.getStringSet(KEY_USERS, emptySet()).orEmpty().sorted()

    /**
     * 注册并直接登录。
     * 返回 null 表示成功，否则是对应的错误。
     */
    fun register(username: String, password: String, confirm: String): Error? {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) return Error.EMPTY
        if (name.length < 2) return Error.SHORT_USERNAME
        if (password.length < MIN_PASSWORD) return Error.SHORT_PASSWORD
        if (password != confirm) return Error.MISMATCH
        if (users().any { it.equals(name, ignoreCase = true) }) return Error.USER_EXISTS

        val salt = Passwords.newSalt()
        val hash = Passwords.hash(password, salt)
        prefs.edit()
            .putStringSet(KEY_USERS, users().toSet() + name)
            .putString(saltKey(name), salt)
            .putString(hashKey(name), hash)
            .putString(KEY_CURRENT, name)
            .apply()
        return null
    }

    /** 登录；返回 null 表示成功 */
    fun login(username: String, password: String): Error? {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) return Error.EMPTY

        // 用户名不区分大小写：手机键盘很容易把首字母打成大写
        val stored = users().firstOrNull { it.equals(name, ignoreCase = true) }
            ?: return Error.NO_USER
        val salt = prefs.getString(saltKey(stored), null) ?: return Error.NO_USER
        val hash = prefs.getString(hashKey(stored), null) ?: return Error.NO_USER
        if (!Passwords.verify(password, salt, hash)) return Error.WRONG_PASSWORD

        prefs.edit().putString(KEY_CURRENT, stored).apply()
        return null
    }

    /** 退出登录：清掉当前身份，账号本身保留（下次还能登回来） */
    fun signOut() {
        prefs.edit().remove(KEY_CURRENT).remove(KEY_GUEST).apply()
    }

    private fun saltKey(name: String) = "salt_${name.lowercase()}"

    private fun hashKey(name: String) = "hash_${name.lowercase()}"

    private companion object {
        const val PREFS = "accounts"
        const val KEY_USERS = "users"
        const val KEY_CURRENT = "current_user"
        /** 旧版本存过游客标记；v4.0 起停用，只在退出时顺手清掉 */
        private const val KEY_GUEST = "guest"
        const val MIN_PASSWORD = 6
    }
}
