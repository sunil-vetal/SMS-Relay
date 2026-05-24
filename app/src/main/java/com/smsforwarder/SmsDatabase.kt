package com.smsforwarder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite database with two tables:
 *  - pending_messages : SMS waiting to be forwarded (queued when offline)
 *  - forward_log      : timestamped history of every forwarding attempt
 */
class SmsDatabase(context: Context) :
    SQLiteOpenHelper(context, "sms_forwarder.db", null, 3) {

    companion object {
        // pending_messages
        const val TBL_PENDING   = "pending_messages"
        const val COL_ID        = "_id"
        const val COL_SENDER    = "sender"
        const val COL_BODY      = "body"
        const val COL_TIME_MS   = "time_ms"
        const val COL_METHOD    = "method"
        const val COL_QUEUED_AT = "queued_at_ms"
        const val COL_RETRIES   = "retry_count"

        // forward_log
        const val TBL_LOG       = "forward_log"
        const val COL_LOG_ID    = "_id"
        const val COL_LOG_TIME  = "timestamp_ms"
        const val COL_LOG_SENDER  = "sender"
        const val COL_LOG_PREVIEW = "body_preview"   // first 80 chars
        const val COL_LOG_METHOD  = "method"
        const val COL_LOG_STATUS  = "status"         // "sent"|"queued"|"failed"
        const val COL_LOG_ERROR   = "error_msg"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TBL_PENDING (
                $COL_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SENDER    TEXT NOT NULL,
                $COL_BODY      TEXT NOT NULL,
                $COL_TIME_MS   INTEGER NOT NULL,
                $COL_METHOD    TEXT NOT NULL,
                $COL_QUEUED_AT INTEGER NOT NULL,
                $COL_RETRIES   INTEGER DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TBL_LOG (
                $COL_LOG_ID      INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_LOG_TIME    INTEGER NOT NULL,
                $COL_LOG_SENDER  TEXT NOT NULL,
                $COL_LOG_PREVIEW TEXT NOT NULL,
                $COL_LOG_METHOD  TEXT NOT NULL,
                $COL_LOG_STATUS  TEXT NOT NULL,
                $COL_LOG_ERROR   TEXT
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TBL_PENDING")
        db.execSQL("DROP TABLE IF EXISTS $TBL_LOG")
        onCreate(db)
    }

    // ── Pending queue ─────────────────────────────────────────────────────────

    fun enqueue(sender: String, body: String, timeMs: Long, method: String): Long {
        val cv = ContentValues().apply {
            put(COL_SENDER,    sender)
            put(COL_BODY,      body)
            put(COL_TIME_MS,   timeMs)
            put(COL_METHOD,    method)
            put(COL_QUEUED_AT, System.currentTimeMillis())
            put(COL_RETRIES,   0)
        }
        return writableDatabase.insert(TBL_PENDING, null, cv)
    }

    fun allPending(): List<PendingMessage> {
        val list = mutableListOf<PendingMessage>()
        readableDatabase.query(TBL_PENDING, null, null, null, null, null, "$COL_QUEUED_AT ASC")
            .use { c ->
                while (c.moveToNext()) {
                    list.add(PendingMessage(
                        id       = c.getLong(c.getColumnIndexOrThrow(COL_ID)),
                        sender   = c.getString(c.getColumnIndexOrThrow(COL_SENDER)),
                        body     = c.getString(c.getColumnIndexOrThrow(COL_BODY)),
                        timeMs   = c.getLong(c.getColumnIndexOrThrow(COL_TIME_MS)),
                        method   = c.getString(c.getColumnIndexOrThrow(COL_METHOD)),
                        retries  = c.getInt(c.getColumnIndexOrThrow(COL_RETRIES))
                    ))
                }
            }
        return list
    }

    fun deletePending(id: Long) {
        writableDatabase.delete(TBL_PENDING, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun incrementRetry(id: Long) {
        writableDatabase.execSQL(
            "UPDATE $TBL_PENDING SET $COL_RETRIES = $COL_RETRIES + 1 WHERE $COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TBL_PENDING", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ── Forward log ───────────────────────────────────────────────────────────

    fun addLog(sender: String, body: String, method: String, status: String, error: String? = null) {
        val cv = ContentValues().apply {
            put(COL_LOG_TIME,    System.currentTimeMillis())
            put(COL_LOG_SENDER,  sender)
            put(COL_LOG_PREVIEW, body.replace(Regex("[\\r\\n\\t]+"), " ").take(500))
            put(COL_LOG_METHOD,  method)
            put(COL_LOG_STATUS,  status)
            put(COL_LOG_ERROR,   error)
        }
        writableDatabase.insert(TBL_LOG, null, cv)
        pruneLog()  // keep last 200 entries
    }

    fun recentLogs(limit: Int = 100): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        readableDatabase.query(
            TBL_LOG, null, null, null, null, null,
            "$COL_LOG_TIME DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                list.add(LogEntry(
                    id        = c.getLong(c.getColumnIndexOrThrow(COL_LOG_ID)),
                    timestamp = c.getLong(c.getColumnIndexOrThrow(COL_LOG_TIME)),
                    sender    = c.getString(c.getColumnIndexOrThrow(COL_LOG_SENDER)),
                    preview   = c.getString(c.getColumnIndexOrThrow(COL_LOG_PREVIEW)),
                    method    = c.getString(c.getColumnIndexOrThrow(COL_LOG_METHOD)),
                    status    = c.getString(c.getColumnIndexOrThrow(COL_LOG_STATUS)),
                    error     = c.getString(c.getColumnIndexOrThrow(COL_LOG_ERROR))
                ))
            }
        }
        return list
    }

    fun clearLog() {
        writableDatabase.delete(TBL_LOG, null, null)
    }

    private fun pruneLog() {
        writableDatabase.execSQL("""
            DELETE FROM $TBL_LOG WHERE $COL_LOG_ID NOT IN (
                SELECT $COL_LOG_ID FROM $TBL_LOG ORDER BY $COL_LOG_TIME DESC LIMIT 200
            )
        """.trimIndent())
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

data class PendingMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val timeMs: Long,
    val method: String,
    val retries: Int
)

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val sender: String,
    val preview: String,
    val method: String,
    val status: String,   // "sent" | "queued" | "failed"
    val error: String?
)
