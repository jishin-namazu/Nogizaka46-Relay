package com.nogirelay.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.MediaCandidate
import com.nogirelay.app.data.MediaRefKind
import com.nogirelay.app.data.MediaRefRow
import com.nogirelay.app.data.MediaRefs
import com.nogirelay.app.data.MessageDatabase
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage

/**
 * The media reference index: every media URL a message or blog post points
 * at, by member. Export, backfill and cache bookkeeping read it.
 */
class MediaRefDao internal constructor(
    private val database: MessageDatabase,
    private val syncState: SyncStateDao,
) {

    fun mediaRefsReady(): Boolean =
        syncState.value(MEDIA_REFS_VERSION_KEY)?.toIntOrNull() == MediaRefs.PARSE_VERSION

    fun markMediaRefsReady() =
        syncState.putValue(MEDIA_REFS_VERSION_KEY, MediaRefs.PARSE_VERSION.toString())

    fun mediaRefKindsForUrl(url: String): Set<MediaRefKind> {
        // Before the reference index is complete, invalidate both estimates
        // rather than miss a cache change belonging to an unindexed record.
        if (!mediaRefsReady()) return MediaRefKind.entries.toSet()
        return database.readableDatabase.rawQuery(
            "SELECT DISTINCT kind FROM media_refs WHERE url = ?",
            arrayOf(url),
        ).readAll { MediaRefKind.valueOf(getString(0)) }.toSet()
    }

    fun mediaRefsFor(kind: MediaRefKind, memberKeys: Collection<String>): List<MediaRefRow> {
        if (memberKeys.isEmpty()) return emptyList()
        val placeholders = memberKeys.joinToString(",") { "?" }
        return database.readableDatabase.rawQuery(
            "SELECT record_id, role, url, media_type, ordinal FROM media_refs " +
                "WHERE kind = ? AND member_key IN ($placeholders) ORDER BY record_id, ordinal",
            arrayOf(kind.name, *memberKeys.toTypedArray()),
        ).readAll {
            MediaRefRow(
                recordId = getString(0),
                role = getString(1),
                url = getString(2),
                type = MessageType.valueOf(getString(3)),
                ordinal = getInt(4),
            )
        }
    }

    internal fun refreshForMessage(id: String) {
        val message = database.readableDatabase.query("messages", null, "id = ?", arrayOf(id), null, null, null, "1")
            .readAll { toMessage() }.firstOrNull()
        if (message == null) {
            delete(MediaRefKind.MESSAGES, id)
            return
        }
        replace(MediaRefKind.MESSAGES, id, MediaRefs.messageMemberKey(message), MediaRefs.candidates(message))
    }

    internal fun refreshForBlog(id: String) {
        val canonical = Db.canonicalBlogId(id)
        val post = database.readableDatabase.query("blog_posts", null, "id = ?", arrayOf(canonical), null, null, null, "1")
            .readAll { toBlogPost() }.firstOrNull()
        if (post == null) {
            delete(MediaRefKind.BLOGS, canonical)
            return
        }
        replace(MediaRefKind.BLOGS, canonical, MediaRefs.blogMemberKey(post), MediaRefs.candidates(post))
    }

    fun rebuildMediaRefs(onProgress: ((done: Int, total: Int) -> Unit)? = null) {
        val db = database.readableDatabase
        val messageTotal = db.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE id NOT GLOB ?",
            arrayOf(Db.TEST_MESSAGE_GLOB),
        ).readCount()
        val blogTotal = db.rawQuery("SELECT COUNT(*) FROM blog_posts", null).readCount()
        val total = messageTotal + blogTotal
        database.writableDatabase.delete("media_refs", null, null)

        var done = 0
        var lastMessageId: String? = null
        while (true) {
            val page = readMessagePage(lastMessageId)
            if (page.isEmpty()) break
            val rows = mutableListOf<ContentValues>()
            page.forEach { message ->
                appendValues(rows, MediaRefKind.MESSAGES, message.id, MediaRefs.messageMemberKey(message), MediaRefs.candidates(message))
            }
            insertRows(rows)
            done += page.size
            lastMessageId = page.last().id
            onProgress?.invoke(done, total)
        }

        var lastBlogId: String? = null
        while (true) {
            val page = readBlogPage(lastBlogId)
            if (page.isEmpty()) break
            val rows = mutableListOf<ContentValues>()
            page.forEach { post ->
                appendValues(rows, MediaRefKind.BLOGS, Db.canonicalBlogId(post.id), MediaRefs.blogMemberKey(post), MediaRefs.candidates(post))
            }
            insertRows(rows)
            done += page.size
            lastBlogId = page.last().id
            onProgress?.invoke(done, total)
        }
        onProgress?.invoke(total, total)
    }

    private fun replace(kind: MediaRefKind, recordId: String, memberKey: String, candidates: List<MediaCandidate>) {
        database.transaction {
            val db = database.writableDatabase
            db.delete("media_refs", "kind = ? AND record_id = ?", arrayOf(kind.name, recordId))
            val pending = mutableListOf<ContentValues>()
            appendValues(pending, kind, recordId, memberKey, candidates)
            pending.forEach { db.insertWithOnConflict("media_refs", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
        }
    }

    private fun delete(kind: MediaRefKind, recordId: String) {
        database.writableDatabase.delete("media_refs", "kind = ? AND record_id = ?", arrayOf(kind.name, recordId))
    }

    private fun appendValues(
        out: MutableList<ContentValues>,
        kind: MediaRefKind,
        recordId: String,
        memberKey: String,
        candidates: List<MediaCandidate>,
    ) {
        candidates.forEachIndexed { index, candidate ->
            out += ContentValues().apply {
                put("kind", kind.name)
                put("record_id", recordId)
                put("member_key", memberKey)
                put("role", candidate.role)
                put("url", candidate.url)
                put("media_type", candidate.type.name)
                put("ordinal", index)
                put("parse_version", MediaRefs.PARSE_VERSION)
            }
        }
    }

    private fun readMessagePage(afterId: String?): List<RelayMessage> {
        val selection = if (afterId == null) "id NOT GLOB ?" else "id NOT GLOB ? AND id > ?"
        val arguments = if (afterId == null) arrayOf(Db.TEST_MESSAGE_GLOB) else arrayOf(Db.TEST_MESSAGE_GLOB, afterId)
        return database.readableDatabase.query(
            "messages", null, selection, arguments, null, null, "id ASC", MEDIA_REF_BATCH.toString(),
        ).readAll { toMessage() }
    }

    private fun readBlogPage(afterId: String?): List<BlogPost> {
        val selection = if (afterId == null) null else "id > ?"
        val arguments = if (afterId == null) null else arrayOf(afterId)
        return database.readableDatabase.query(
            "blog_posts", null, selection, arguments, null, null, "id ASC", MEDIA_REF_BATCH.toString(),
        ).readAll { toBlogPost() }
    }

    private fun insertRows(rows: List<ContentValues>) {
        if (rows.isEmpty()) return
        database.transaction {
            val db = database.writableDatabase
            rows.forEach { db.insertWithOnConflict("media_refs", null, it, SQLiteDatabase.CONFLICT_REPLACE) }
        }
    }

    private companion object {
        const val MEDIA_REFS_VERSION_KEY = "media_refs_parse_version_v1"
        const val MEDIA_REF_BATCH = 500
    }
}
