package com.example.voiceassistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码哈希的行为约定。
 *
 * 这一层如果错了，账号系统等于没有：所以把「同盐同密码必得同值」
 * 「不同盐必得不同值」「错密码一定验不过」这几条钉死。
 */
class PasswordsTest {

    @Test
    fun `同一个盐和密码总会算出同一个哈希`() {
        val salt = Passwords.newSalt()
        assertEquals(Passwords.hash("hello123", salt), Passwords.hash("hello123", salt))
    }

    @Test
    fun `换了盐就是另一个哈希`() {
        val a = Passwords.hash("hello123", Passwords.newSalt())
        val b = Passwords.hash("hello123", Passwords.newSalt())
        assertNotEquals(a, b)
    }

    @Test
    fun `密码不同哈希也不同`() {
        val salt = Passwords.newSalt()
        assertNotEquals(
            Passwords.hash("hello123", salt),
            Passwords.hash("hello124", salt)
        )
    }

    @Test
    fun `正确的密码能验过`() {
        val salt = Passwords.newSalt()
        val hash = Passwords.hash("correct-horse", salt)
        assertTrue(Passwords.verify("correct-horse", salt, hash))
    }

    @Test
    fun `错误的密码验不过`() {
        val salt = Passwords.newSalt()
        val hash = Passwords.hash("correct-horse", salt)
        assertFalse(Passwords.verify("wrong-horse", salt, hash))
    }

    @Test
    fun `盐是随机的且长度稳定`() {
        val a = Passwords.newSalt()
        val b = Passwords.newSalt()
        assertNotEquals(a, b)
        assertEquals(a.length, b.length)
    }

    @Test
    fun `损坏的盐不会抛异常，只会验不过`() {
        assertFalse(Passwords.verify("hello", "这不是Base64", "whatever"))
    }
}
