package com.example.voiceassistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.voiceassistant.assistant.FuzzyMatcher

/**
 * 记忆的本地持久化（SQLite，零新增依赖）。
 *
 * 为什么不用 Room：工程当前的依赖缓存里没有 Room / KSP，加进来会引入
 * 编译插件和联网下载；记忆表结构简单（一张表、六个字段），
 * 直接用系统自带 SQLite 更稳，答辩时也更好讲清楚「数据存在哪」。
 *
 * 全部读写加锁并维护一份内存缓存：对话时检索只查内存，
 * 写入时才落库，几百条记忆的匹配不会卡住 UI。
 */
class SqliteMemoryRepository(context: Context) : MemoryRepository {

    private val helper = Helper(context.applicationContext)

    @Volatile
    private var cache: List<MemoryEntry>? = null

    private val lock = Any()

    init {
        seedIfEmpty()
    }

    override fun entries(): List<MemoryEntry> = load()

    override fun count(): Int = load().size

    override fun remember(question: String, answer: String, source: String): MemoryEntry {
        val normalized = FuzzyMatcher.normalize(question)
        val display = question.trim()
        val reply = answer.trim()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val existing = load().firstOrNull { it.normalized == normalized }
            val db = helper.writableDatabase
            val values = ContentValues().apply {
                put(COL_QUESTION, display)
                put(COL_NORMALIZED, normalized)
                put(COL_ANSWER, reply)
                put(COL_SOURCE, source)
                put(COL_UPDATED_AT, now)
            }
            val id = if (existing != null) {
                db.update(TABLE, values, "$COL_ID = ?", arrayOf(existing.id.toString()))
                existing.id
            } else {
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            cache = null
            return load().firstOrNull { it.id == id }
                ?: MemoryEntry(id, display, normalized, reply, source, updatedAt = now)
        }
    }

    override fun delete(id: Long) {
        synchronized(lock) {
            helper.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
            cache = null
        }
    }

    override fun clear() {
        synchronized(lock) {
            helper.writableDatabase.delete(TABLE, null, null)
            cache = null
        }
    }

    override fun bumpHit(id: Long) {
        synchronized(lock) {
            helper.writableDatabase.execSQL(
                "UPDATE $TABLE SET $COL_HITS = $COL_HITS + 1 WHERE $COL_ID = ?",
                arrayOf(id)
            )
            cache = null
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun load(): List<MemoryEntry> {
        cache?.let { return it }
        val list = mutableListOf<MemoryEntry>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT $COL_ID, $COL_QUESTION, $COL_NORMALIZED, $COL_ANSWER, $COL_SOURCE, " +
                    "$COL_HITS, $COL_UPDATED_AT FROM $TABLE " +
                    "ORDER BY $COL_HITS DESC, $COL_UPDATED_AT DESC",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list += MemoryEntry(
                        id = cursor.getLong(0),
                        question = cursor.getString(1),
                        normalized = cursor.getString(2),
                        answer = cursor.getString(3),
                        source = cursor.getString(4),
                        hits = cursor.getInt(5),
                        updatedAt = cursor.getLong(6)
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读取记忆失败", it) }
        cache = list
        return list
    }

    /** 首次启动灌入预置常识；表里已有数据就不动，用户删掉的不会被重新塞回来 */
    private fun seedIfEmpty() {
        runCatching {
            if (count() > 0) return
            MemorySeed.pairs.forEach { (question, answer) ->
                remember(question, answer, MemoryRepository.SOURCE_SEED)
            }
        }.onFailure { Log.w(TAG, "预置记忆失败", it) }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_QUESTION TEXT NOT NULL,
                    $COL_NORMALIZED TEXT NOT NULL UNIQUE,
                    $COL_ANSWER TEXT NOT NULL,
                    $COL_SOURCE TEXT NOT NULL,
                    $COL_HITS INTEGER NOT NULL DEFAULT 0,
                    $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 记忆是用户资产，升级不删表；后续加字段时在这里做 ALTER TABLE
        }
    }

    private companion object {
        const val TAG = "MemoryStore"
        const val DB_NAME = "assistant_memory.db"
        const val DB_VERSION = 1
        const val TABLE = "memory"
        const val COL_ID = "id"
        const val COL_QUESTION = "question"
        const val COL_NORMALIZED = "normalized"
        const val COL_ANSWER = "answer"
        const val COL_SOURCE = "source"
        const val COL_HITS = "hits"
        const val COL_UPDATED_AT = "updated_at"
    }
}
