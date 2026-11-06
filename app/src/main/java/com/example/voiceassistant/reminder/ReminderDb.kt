package com.example.voiceassistant.reminder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 提醒的 SQLite 持久化（零新增依赖）。
 *
 * ## 表结构
 * | 字段 | 类型 | 说明 |
 * |---|---|---|
 * | id | INTEGER PK | 自增；和闹钟 requestCode、通知 id 一一对应 |
 * | content | TEXT | 提醒内容（已剥掉时间表达） |
 * | trigger_at | INTEGER | 触发时刻（毫秒） |
 * | repeat_rule | TEXT | none / daily / week |
 * | status | TEXT | pending / triggered / canceled |
 * | notify_mode | INTEGER | 位掩码：1 弹窗 + 2 语音 |
 * | ctx_id | TEXT | 建这条提醒的会话 id（多轮指代用） |
 * | created_at | INTEGER | 建出来的时刻 |
 *
 * ## 为什么主键用自增整数而不是框架图里的 uuid
 * AlarmManager 的 `requestCode` 和通知的 `notificationId` 都是 int，
 * 用 uuid 得再做一层映射表，多一处出错的机会（提醒重复响 / 撤不掉）；
 * 自增整数天然能当这两个 id。要「全局唯一」时再加 uuid 列也不冲突。
 */
class ReminderDb(context: Context) : ReminderRepository {

    private val helper = Helper(context.applicationContext)

    override fun all(): List<Reminder> {
        val list = mutableListOf<Reminder>()
        runCatching {
            helper.readableDatabase.rawQuery(
                "SELECT $COL_ID, $COL_CONTENT, $COL_TRIGGER_AT, $COL_CREATED_AT, " +
                    "$COL_REPEAT, $COL_STATUS, $COL_NOTIFY, $COL_CTX FROM $TABLE " +
                    "ORDER BY $COL_TRIGGER_AT = 0, $COL_TRIGGER_AT, $COL_ID",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list += Reminder(
                        id = cursor.getInt(0),
                        content = cursor.getString(1),
                        triggerAt = cursor.getLong(2),
                        createdAt = cursor.getLong(3),
                        repeat = Reminder.Repeat.of(cursor.getString(4)),
                        status = Reminder.Status.of(cursor.getString(5)),
                        notifyMode = cursor.getInt(6),
                        ctxId = cursor.getString(7)
                    )
                }
            }
        }.onFailure { Log.w(TAG, "读取提醒失败", it) }
        return list
    }

    override fun insert(reminder: Reminder): Reminder {
        val values = ContentValues().apply {
            put(COL_CONTENT, reminder.content)
            put(COL_TRIGGER_AT, reminder.triggerAt)
            put(COL_CREATED_AT, reminder.createdAt)
            put(COL_REPEAT, reminder.repeat.code)
            put(COL_STATUS, reminder.status.code)
            put(COL_NOTIFY, reminder.notifyMode)
            put(COL_CTX, reminder.ctxId)
        }
        val id = runCatching {
            helper.writableDatabase.insert(TABLE, null, values)
        }.getOrDefault(-1L)
        if (id <= 0) {
            Log.w(TAG, "写入提醒失败：${reminder.content}")
            return reminder
        }
        return reminder.copy(id = id.toInt())
    }

    override fun update(reminder: Reminder): Boolean {
        if (reminder.id <= 0) return false
        val values = ContentValues().apply {
            put(COL_CONTENT, reminder.content)
            put(COL_TRIGGER_AT, reminder.triggerAt)
            put(COL_REPEAT, reminder.repeat.code)
            put(COL_STATUS, reminder.status.code)
            put(COL_NOTIFY, reminder.notifyMode)
            put(COL_CTX, reminder.ctxId)
        }
        val rows = runCatching {
            helper.writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(reminder.id.toString()))
        }.getOrDefault(0)
        return rows > 0
    }

    override fun delete(id: Int): Boolean {
        val rows = runCatching {
            helper.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        }.getOrDefault(0)
        return rows > 0
    }

    override fun deleteAll() {
        runCatching { helper.writableDatabase.delete(TABLE, null, null) }
    }

    override fun pruneTriggered(before: Long): Int = runCatching {
        helper.writableDatabase.delete(
            TABLE,
            "$COL_STATUS = ? AND $COL_TRIGGER_AT < ?",
            arrayOf(Reminder.Status.TRIGGERED.code, before.toString())
        )
    }.getOrDefault(0)

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_CONTENT TEXT NOT NULL,
                    $COL_TRIGGER_AT INTEGER NOT NULL DEFAULT 0,
                    $COL_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                    $COL_REPEAT TEXT NOT NULL DEFAULT 'none',
                    $COL_STATUS TEXT NOT NULL DEFAULT 'pending',
                    $COL_NOTIFY INTEGER NOT NULL DEFAULT 3,
                    $COL_CTX TEXT
                )
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 提醒是用户资产：升级一律不删表，加字段用 ALTER TABLE
        }
    }

    private companion object {
        const val TAG = "ReminderDb"
        const val DB_NAME = "assistant_reminder.db"
        const val DB_VERSION = 1
        const val TABLE = "reminder"
        const val COL_ID = "id"
        const val COL_CONTENT = "content"
        const val COL_TRIGGER_AT = "trigger_at"
        const val COL_CREATED_AT = "created_at"
        const val COL_REPEAT = "repeat_rule"
        const val COL_STATUS = "status"
        const val COL_NOTIFY = "notify_mode"
        const val COL_CTX = "ctx_id"
    }
}
