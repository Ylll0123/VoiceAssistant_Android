package com.example.voiceassistant.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 密码哈希：PBKDF2-HMAC-SHA1 + 随机盐。
 *
 * 为什么不是「直接存密码」或者「MD5 一下」：
 * 本地账号虽然只在这台手机上，但 SharedPreferences 是明文 XML，
 * root 之后能直接读；加盐慢哈希能让「拖出来也算不出来」。
 *
 * 迭代次数取 12000：手机上约 20~40ms，登录手感无感，
 * 暴力破解成本却比单次 SHA-256 高三个数量级。
 *
 * 纯 JVM 实现（不碰 android.util.Base64），所以能直接跑单测。
 */
object Passwords {

    private const val ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 12_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    /** 生成一串随机盐（Base64，16 字节 → 24 个字符） */
    fun newSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** 用盐把密码揉成哈希（Base64） */
    fun hash(password: String, salt: String): String {
        val spec = PBEKeySpec(
            password.toCharArray(),
            Base64.getDecoder().decode(salt),
            ITERATIONS,
            KEY_BITS
        )
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        val bytes = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * 校验密码。
     *
     * 用 [MessageDigest.isEqual] 做定长比较：普通 `==` 会在第一个不同字节处返回，
     * 时间差可以被用来逐字节猜哈希（时序攻击）。
     */
    fun verify(password: String, salt: String, expected: String): Boolean {
        val actual = runCatching { hash(password, salt) }.getOrNull() ?: return false
        val a = actual.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }
}
