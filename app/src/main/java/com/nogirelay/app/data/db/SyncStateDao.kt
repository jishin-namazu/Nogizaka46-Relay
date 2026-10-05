package com.nogirelay.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.nogirelay.app.data.MessageDatabase

/** Sync bookkeeping: full-sync flags, incremental boundaries, index versions. */
class SyncStateDao internal constructor(private val database: MessageDatabase) {

    fun isBlogFullSyncComplete(): Boolean = flag(BLOG_FULL_SYNC_KEY)

    fun blogSyncHeadId(): String? = value(BLOG_SYNC_HEAD_KEY)

    fun markBlogFullSyncComplete(headId: String?) {
        putFlag(BLOG_FULL_SYNC_KEY)
        markBlogSyncHead(headId)
    }

    fun markBlogSyncHead(headId: String?) = putValue(BLOG_SYNC_HEAD_KEY, headId)

    fun isMessageFullSyncComplete(): Boolean = flag(MESSAGE_FULL_SYNC_KEY)

    fun messageSyncHeadId(): String? = value(MESSAGE_SYNC_HEAD_KEY)

    fun markMessageFullSyncComplete(headId: String?) {
        putFlag(MESSAGE_FULL_SYNC_KEY)
        markMessageSyncHead(headId)
    }

    fun markMessageSyncHead(headId: String?) = putValue(MESSAGE_SYNC_HEAD_KEY, headId)

    internal fun value(key: String): String? = database.readableDatabase.query(
        "sync_state",
        arrayOf("state_value"),
        "state_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null }

    private fun flag(key: String): Boolean = value(key) == "1"

    internal fun putValue(key: String, value: String?) {
        if (value.isNullOrBlank()) return
        val values = ContentValues().apply {
            put("state_key", key)
            put("state_value", value)
        }
        database.writableDatabase.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putFlag(key: String) = putValue(key, "1")

    private companion object {
        const val BLOG_FULL_SYNC_KEY = "blog_full_sync_complete_v2"
        const val BLOG_SYNC_HEAD_KEY = "blog_sync_head_id_v2"
        const val MESSAGE_FULL_SYNC_KEY = "message_full_sync_complete_v1"
        const val MESSAGE_SYNC_HEAD_KEY = "message_sync_head_id_v1"
    }
}
