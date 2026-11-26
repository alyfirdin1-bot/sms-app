package com.captchapay.smsgateway

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfflineSmsDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    data class PendingSmsRow(
        val id: Long,
        val smsHash: String,
        val deviceId: String,
        val deviceName: String,
        val smsSender: String,
        val rawBody: String,
        val receivedAtMs: Long,
        val simSlot: Int,
        val isTest: Boolean,
        val retryCount: Int
    )

    companion object {
        private const val DB_NAME = "captchapay_offline_sms.db"
        private const val DB_VERSION = 1
        private const val TABLE_PENDING = "pending_sms"
        private const val MAX_BUFFER_ROWS = 1000

        @Volatile
        private var instance: OfflineSmsDb? = null

        fun getInstance(context: Context): OfflineSmsDb {
            return instance ?: synchronized(this) {
                instance ?: OfflineSmsDb(context.applicationContext).also {
                    it.setWriteAheadLoggingEnabled(true)
                    instance = it
                }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PENDING (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sms_hash TEXT UNIQUE NOT NULL,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                sms_sender TEXT NOT NULL,
                raw_body TEXT NOT NULL,
                received_at_ms INTEGER NOT NULL,
                sim_slot INTEGER NOT NULL,
                is_test INTEGER NOT NULL DEFAULT 0,
                retry_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_pending_created ON $TABLE_PENDING (created_at_ms ASC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Reserved for future schema migrations
    }

    @Synchronized
    fun enqueue(
        smsHash: String,
        deviceId: String,
        deviceName: String,
        smsSender: String,
        rawBody: String,
        receivedAtMs: Long,
        simSlot: Int,
        isTest: Boolean
    ): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("sms_hash", smsHash)
            put("device_id", deviceId)
            put("device_name", deviceName)
            put("sms_sender", smsSender)
            put("raw_body", rawBody)
            put("received_at_ms", receivedAtMs)
            put("sim_slot", simSlot)
            put("is_test", if (isTest) 1 else 0)
            put("retry_count", 0)
            put("created_at_ms", System.currentTimeMillis())
        }
        val rowId = db.insertWithOnConflict(TABLE_PENDING, null, values, SQLiteDatabase.CONFLICT_IGNORE)

        // Bound disk growth while keeping the latest MAX_BUFFER_ROWS messages
        db.execSQL(
            """
            DELETE FROM $TABLE_PENDING
            WHERE id NOT IN (
                SELECT id FROM $TABLE_PENDING ORDER BY id DESC LIMIT $MAX_BUFFER_ROWS
            )
            """.trimIndent()
        )
        return rowId
    }

    @Synchronized
    fun getBatch(limit: Int = 25): List<PendingSmsRow> {
        val list = mutableListOf<PendingSmsRow>()
        val db = readableDatabase
        db.rawQuery(
            """
            SELECT id, sms_hash, device_id, device_name, sms_sender, raw_body,
                   received_at_ms, sim_slot, is_test, retry_count
            FROM $TABLE_PENDING
            ORDER BY id ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    PendingSmsRow(
                        id = cursor.getLong(0),
                        smsHash = cursor.getString(1),
                        deviceId = cursor.getString(2),
                        deviceName = cursor.getString(3),
                        smsSender = cursor.getString(4),
                        rawBody = cursor.getString(5),
                        receivedAtMs = cursor.getLong(6),
                        simSlot = cursor.getInt(7),
                        isTest = cursor.getInt(8) == 1,
                        retryCount = cursor.getInt(9)
                    )
                )
            }
        }
        return list
    }

    @Synchronized
    fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val args = ids.map { it.toString() }.toTypedArray()
            db.delete(TABLE_PENDING, "id IN ($placeholders)", args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun markRetryError(ids: List<Long>, errorMsg: String) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        val safeErr = errorMsg.take(200)
        db.beginTransaction()
        try {
            for (id in ids) {
                db.execSQL(
                    "UPDATE $TABLE_PENDING SET retry_count = retry_count + 1, last_error = ? WHERE id = ?",
                    arrayOf<Any>(safeErr, id)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun getPendingCount(): Int {
        val db = readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_PENDING", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}
