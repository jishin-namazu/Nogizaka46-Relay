package com.nogirelay.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.nogirelay.app.data.BlogMemberCategories
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataInvalidationTracker
import com.nogirelay.app.data.ExportMember
import com.nogirelay.app.data.MessageDatabase
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage

/** Member messages: timeline queries, read/played/favorite state, translations. */
class MessageDao internal constructor(
    private val database: MessageDatabase,
    private val mediaRefs: MediaRefDao,
    private val invalidation: DataInvalidationTracker,
) {
    private val readable get() = database.readableDatabase
    private val writable get() = database.writableDatabase

    fun insert(message: RelayMessage, isUnread: Boolean = false): Boolean {
        val values = message.toValues(
            isUnread = isUnread || message.isUnread,
            receivedAt = System.currentTimeMillis(),
        )
        val inserted = writable.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) {
            mediaRefs.refreshForMessage(message.id)
            refreshMemberLatest(message.memberKey)
            invalidation.publish(DataChange.MESSAGES)
            return true
        }
        // Known message: only fresher media links may change.
        val links = buildMap {
            message.mediaUrl?.takeIf { it.isNotBlank() }?.let { put("media_url", it) }
            message.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnail_url", it) }
            message.memberAvatarUrl?.takeIf { it.isNotBlank() }?.let { put("member_avatar_url", it) }
            message.phoneImageUrl?.takeIf { it.isNotBlank() }?.let { put("phone_image_url", it) }
        }
        if (updateLinksIfDifferent(message.id, links)) {
            mediaRefs.refreshForMessage(message.id)
            // A message without text gains visibility with its first media link.
            refreshMemberLatest(message.memberKey)
            invalidation.publish(DataChange.MESSAGE_ROWS, setOf(message.id))
        }
        return false
    }

    fun insertImported(message: RelayMessage): Boolean {
        val values = message.toValues(isUnread = false, receivedAt = 0L)
        val inserted = writable.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        mediaRefs.refreshForMessage(message.id)
        if (inserted) {
            refreshMemberLatest(message.memberKey)
            invalidation.publish(DataChange.MESSAGES)
        }
        return inserted
    }

    fun latest(limit: Int = 200): List<RelayMessage> = readable.query(
        "messages",
        null,
        Db.VISIBLE_MESSAGE,
        arrayOf(Db.TEST_MESSAGE_GLOB),
        null,
        null,
        "sent_at DESC, received_at DESC",
        limit.coerceIn(1, 500).toString(),
    ).readAll { toMessage() }

    /** Each member's newest visible message, from the `member_latest` summary. */
    fun latestMessagePerMember(): List<RelayMessage> = readable.rawQuery(
        """
        SELECT m.*
        FROM member_latest l
        JOIN messages m ON m.id = l.message_id
        ORDER BY m.sent_at DESC, m.received_at DESC
        """.trimIndent(),
        null,
    ).readAll { toMessage() }

    /** Recomputes [memberKey]'s newest visible message: one indexed lookup. */
    private fun refreshMemberLatest(memberKey: String) {
        val latestId = readable.rawQuery(
            "SELECT id FROM messages WHERE ${Db.MEMBER_KEY} = ? AND ${Db.VISIBLE_MESSAGE} " +
                "ORDER BY ${Db.MEMBER_MESSAGE_ORDER} LIMIT 1",
            arrayOf(memberKey, Db.TEST_MESSAGE_GLOB),
        ).readAll { getString(0) }.firstOrNull()
        if (latestId == null) {
            writable.delete("member_latest", "member_key = ?", arrayOf(memberKey))
        } else {
            writable.insertWithOnConflict(
                "member_latest",
                null,
                ContentValues().apply {
                    put("member_key", memberKey)
                    put("message_id", latestId)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun messagesForMember(
        memberKey: String,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
        nickname: String = "",
        cancellationSignal: CancellationSignal? = null,
    ): List<RelayMessage> {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        return readable.query(
            false,
            "messages",
            null,
            filter.selection,
            filter.arguments,
            null,
            null,
            Db.MEMBER_MESSAGE_ORDER,
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
            cancellationSignal,
        ).readAll { toMessage() }
    }

    fun favoriteMessagesForMember(
        memberKey: String,
        limit: Int = 500,
        offset: Int = 0,
        cancellationSignal: CancellationSignal? = null,
        searchQuery: String = "",
        nickname: String = "",
    ): List<RelayMessage> = queryFiltered(
        memberFilter(memberKey, searchQuery, null, null, nickname, favoritesOnly = true),
        limit,
        offset,
        cancellationSignal,
    )

    fun mediaMessagesForMember(
        memberKey: String,
        mediaType: MessageType,
        limit: Int = 500,
        offset: Int = 0,
        cancellationSignal: CancellationSignal? = null,
    ): List<RelayMessage> = queryFiltered(
        memberFilter(memberKey, "", null, null, mediaType = mediaType),
        limit,
        offset,
        cancellationSignal,
    )

    fun countFavoriteMessagesForMember(memberKey: String, cancellationSignal: CancellationSignal? = null): Int =
        countFiltered(memberFilter(memberKey, "", null, null, favoritesOnly = true), cancellationSignal)

    fun countMediaMessagesForMember(
        memberKey: String,
        mediaType: MessageType,
        cancellationSignal: CancellationSignal? = null,
    ): Int = countFiltered(memberFilter(memberKey, "", null, null, mediaType = mediaType), cancellationSignal)

    fun messageIndexForMember(
        memberKey: String,
        messageId: String,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        nickname: String = "",
        cancellationSignal: CancellationSignal? = null,
    ): Int {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        val args = filter.arguments
        val exists = readable.rawQuery(
            "SELECT 1 FROM messages WHERE ${filter.selection} AND id = ? LIMIT 1",
            args + messageId, cancellationSignal,
        ).use { it.moveToFirst() }
        if (!exists) return -1
        return readable.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE ${filter.selection} AND " +
                "(sent_at, received_at, id) > (SELECT sent_at, received_at, id FROM messages WHERE id = ?)",
            args + messageId, cancellationSignal,
        ).readCount()
    }

    fun memberMessagesByIds(
        memberKey: String, ids: Set<String>, searchQuery: String = "",
        startMillis: Long? = null, endMillisExclusive: Long? = null, nickname: String = "",
        cancellationSignal: CancellationSignal? = null,
    ): List<RelayMessage> {
        if (ids.isEmpty()) return emptyList()
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        return ids.chunked(200).flatMap { batch ->
            readable.rawQuery(
                "SELECT * FROM messages WHERE ${filter.selection} AND id IN (${batch.joinToString(",") { "?" }}) ORDER BY ${Db.MEMBER_MESSAGE_ORDER}",
                filter.arguments + batch, cancellationSignal,
            ).readAll { toMessage() }
        }
    }

    fun memberMessagesRelativeTo(
        memberKey: String, anchorId: String, newer: Boolean, limit: Int,
        searchQuery: String = "", startMillis: Long? = null, endMillisExclusive: Long? = null,
        nickname: String = "", cancellationSignal: CancellationSignal? = null,
    ): List<RelayMessage> {
        if (limit <= 0) return emptyList()
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        val operator = if (newer) ">" else "<"
        val order = if (newer) "sent_at ASC, received_at ASC, id ASC" else Db.MEMBER_MESSAGE_ORDER
        val result = readable.rawQuery(
            "SELECT * FROM messages WHERE ${filter.selection} AND " +
                "(sent_at, received_at, id) $operator (SELECT sent_at, received_at, id FROM messages WHERE id = ?) " +
                "ORDER BY $order LIMIT ${limit.coerceIn(1, 100)}",
            filter.arguments + anchorId, cancellationSignal,
        ).readAll { toMessage() }
        return if (newer) result.reversed() else result
    }

    fun countMessagesForMember(
        memberKey: String,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        nickname: String = "",
        cancellationSignal: CancellationSignal? = null,
    ): Int = countFiltered(
        memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname),
        cancellationSignal,
    )

    fun unreadCountsByMember(): Map<String, Int> = readable.query(
        "messages",
        arrayOf("${Db.MEMBER_KEY} AS member_key", "COUNT(*) AS unread_count"),
        "is_unread = 1 AND ${Db.VISIBLE_MESSAGE}",
        arrayOf(Db.TEST_MESSAGE_GLOB),
        Db.MEMBER_KEY,
        null,
        null,
    ).readAll { getString(0) to getInt(1) }.toMap()

    fun countUnreadMessages(): Int = readable.query(
        "messages",
        arrayOf("COUNT(*)"),
        "is_unread = 1 AND ${Db.VISIBLE_MESSAGE}",
        arrayOf(Db.TEST_MESSAGE_GLOB),
        null,
        null,
        null,
    ).readCount()

    fun markMessagesReadByIds(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val values = ContentValues().apply { put("is_unread", 0) }
        var updated = 0
        ids.toList().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            updated += writable.update("messages", values, "is_unread = 1 AND id IN ($placeholders)", chunk.toTypedArray())
        }
        if (updated > 0) invalidation.publish(DataChange.MESSAGE_READ, ids.toSet())
        return updated
    }

    fun find(id: String): RelayMessage? =
        readable.query("messages", null, "id = ?", arrayOf(id), null, null, null, "1")
            .readAll { toMessage() }.firstOrNull()

    fun markPlayed(id: String) {
        val values = ContentValues().apply { put("is_played", 1) }
        if (writable.update("messages", values, "id = ? AND is_played = 0", arrayOf(id)) > 0) {
            invalidation.publish(DataChange.MESSAGE_ROWS, setOf(id))
        }
    }

    fun setMessageFavorite(id: String, favorite: Boolean): Boolean = updateRow(id, "is_favorite", if (favorite) 1 else 0)

    fun setVideoHasAudio(id: String, hasAudio: Boolean): Boolean = updateRow(id, "video_has_audio", if (hasAudio) 1 else 0)

    private fun updateRow(id: String, column: String, value: Int): Boolean {
        if (id.isBlank()) return false
        val values = ContentValues().apply { put(column, value) }
        val updated = writable.update("messages", values, "id = ?", arrayOf(id)) > 0
        if (updated) invalidation.publish(DataChange.MESSAGE_ROWS, setOf(id))
        return updated
    }

    fun deleteTestMessages(): List<String> {
        val ids = writable.query("messages", arrayOf("id"), "id GLOB ?", arrayOf(Db.TEST_MESSAGE_GLOB), null, null, null)
            .readAll { getString(0) }
        if (ids.isNotEmpty()) {
            writable.delete("messages", "id GLOB ?", arrayOf(Db.TEST_MESSAGE_GLOB))
            writable.delete("member_latest", "message_id GLOB ?", arrayOf(Db.TEST_MESSAGE_GLOB))
            invalidation.publish(DataChange.MESSAGES)
        }
        return ids
    }

    fun pendingTranslationIds(afterId: String? = null, limit: Int = 24): List<String> {
        val selection = "translation_done = 0 AND text_content IS NOT NULL AND TRIM(text_content) <> '' AND id NOT GLOB ?" +
            (if (afterId != null) " AND id > ?" else "")
        val args = listOfNotNull(Db.TEST_MESSAGE_GLOB, afterId).toTypedArray()
        return readable.query("messages", arrayOf("id"), selection, args, null, null, "id ASC", limit.coerceIn(1, 100).toString())
            .readAll { getString(0) }
    }

    fun pendingTranslations(limit: Int = 100): List<RelayMessage> = readable.query(
        "messages",
        null,
        "id NOT GLOB ? AND translation_done = 0 AND text_content IS NOT NULL AND TRIM(text_content) <> ''",
        arrayOf(Db.TEST_MESSAGE_GLOB),
        null,
        null,
        "sent_at DESC, received_at DESC",
        limit.coerceIn(1, Db.MAX_TRANSLATION_BATCH).toString(),
    ).readAll { toMessage() }

    fun saveTranslation(id: String, translation: String?) {
        val values = ContentValues().apply {
            put("translation", translation?.takeIf { it.isNotBlank() })
            put("translation_done", 1)
        }
        if (writable.update("messages", values, "id = ?", arrayOf(id)) > 0) {
            invalidation.publish(DataChange.MESSAGE_ROWS, setOf(id))
        }
    }

    fun markForRetranslation(id: String) {
        if (writable.update("messages", clearedTranslation(), "id = ?", arrayOf(id)) > 0) {
            invalidation.publish(DataChange.MESSAGE_ROWS, setOf(id))
        }
    }

    fun markAllMessagesForRetranslation(): Int {
        val updated = writable.update(
            "messages",
            clearedTranslation(),
            "id NOT GLOB ? AND text_content IS NOT NULL AND TRIM(text_content) <> ''",
            arrayOf(Db.TEST_MESSAGE_GLOB),
        )
        if (updated > 0) invalidation.publish(DataChange.MESSAGE_ROWS)
        return updated
    }

    fun backfillMessageTranslation(id: String, translation: String): Boolean {
        if (translation.isBlank()) return false
        val values = ContentValues().apply {
            put("translation", translation)
            put("translation_done", 1)
        }
        val updated = writable.update(
            "messages",
            values,
            "id = ? AND (translation IS NULL OR TRIM(translation) = '' OR translation_done = 0)",
            arrayOf(id),
        ) > 0
        if (updated) invalidation.publish(DataChange.MESSAGE_ROWS, setOf(id))
        return updated
    }

    fun refreshImportedLinks(id: String, links: Map<String, String>): Boolean {
        val updated = updateLinksIfDifferent(id, links)
        if (updated) {
            mediaRefs.refreshForMessage(id)
            find(id)?.let { refreshMemberLatest(it.memberKey) }
            invalidation.publish(DataChange.MESSAGE_ROWS, setOf(id))
        }
        return updated
    }

    fun messageExportMembers(): List<ExportMember> = readable.rawQuery(
        """
        SELECT
            CASE WHEN TRIM(m.member_id) <> '' THEN m.member_id ELSE m.member_name END AS member_key,
            MAX(m.member_name) AS member_name,
            MAX(m.member_avatar_url) AS avatar_url,
            MAX(m.sent_at) AS latest_sent_at,
            MAX(COALESCE(b.category, '')) AS category,
            MAX(COALESCE(b.display_order, -1)) AS display_order
        FROM messages m
        LEFT JOIN blog_members b
            ON b.id = CASE WHEN TRIM(m.member_id) <> '' THEN m.member_id ELSE m.member_name END
        WHERE m.id NOT GLOB ? AND (m.text_content IS NOT NULL OR m.media_url IS NOT NULL)
        GROUP BY member_key
        ORDER BY latest_sent_at DESC
        """.trimIndent(),
        arrayOf(Db.TEST_MESSAGE_GLOB),
    ).readAll {
        val key = getString(getColumnIndexOrThrow("member_key"))
        val order = getInt(getColumnIndexOrThrow("display_order"))
        val category = getString(getColumnIndexOrThrow("category"))
        val name = getString(getColumnIndexOrThrow("member_name")).ifBlank { key }
        ExportMember(
            memberKey = key,
            name = name,
            avatarUrl = nullableString("avatar_url"),
            category = BlogMemberCategories.normalizeCategory(key, name, category),
            displayOrder = if (order >= 0) order else 10_000,
            directory = order >= 0,
        )
    }

    fun countMessagesForMembers(memberKeys: Collection<String>): Int {
        if (memberKeys.isEmpty()) return 0
        val placeholders = memberKeys.joinToString(",") { "?" }
        return readable.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE ${Db.VISIBLE_MESSAGE} AND ${Db.MEMBER_KEY} IN ($placeholders)",
            arrayOf(Db.TEST_MESSAGE_GLOB, *memberKeys.toTypedArray()),
        ).readCount()
    }

    fun forEachMessageForMembers(memberKeys: Collection<String>, action: (RelayMessage) -> Unit) {
        if (memberKeys.isEmpty()) return
        val placeholders = memberKeys.joinToString(",") { "?" }
        readable.rawQuery(
            "SELECT * FROM messages WHERE ${Db.VISIBLE_MESSAGE} AND ${Db.MEMBER_KEY} IN ($placeholders) " +
                "ORDER BY sent_at DESC, received_at DESC, id DESC",
            arrayOf(Db.TEST_MESSAGE_GLOB, *memberKeys.toTypedArray()),
        ).use { cursor ->
            while (cursor.moveToNext()) action(cursor.toMessage())
        }
    }

    private fun updateLinksIfDifferent(id: String, links: Map<String, String>): Boolean {
        if (links.isEmpty()) return false
        val values = ContentValues().apply { links.forEach { (column, value) -> put(column, value) } }
        val conditions = links.keys.joinToString(" OR ") { "COALESCE($it, '') <> ?" }
        return writable.update(
            "messages",
            values,
            "id = ? AND ($conditions)",
            arrayOf(id, *links.values.toTypedArray()),
        ) > 0
    }

    private fun queryFiltered(
        filter: QueryFilter,
        limit: Int,
        offset: Int,
        cancellationSignal: CancellationSignal?,
    ): List<RelayMessage> = readable.query(
        false,
        "messages",
        null,
        filter.selection,
        filter.arguments,
        null,
        null,
        Db.MEMBER_MEDIA_ORDER,
        "${limit.coerceIn(1, 1000)} OFFSET ${offset.coerceAtLeast(0)}",
        cancellationSignal,
    ).readAll { toMessage() }

    private fun countFiltered(filter: QueryFilter, cancellationSignal: CancellationSignal?): Int = readable.query(
        false,
        "messages",
        arrayOf("COUNT(*)"),
        filter.selection,
        filter.arguments,
        null,
        null,
        null,
        null,
        cancellationSignal,
    ).readCount()

    private fun memberFilter(
        memberKey: String,
        searchQuery: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
        nickname: String = "",
        favoritesOnly: Boolean = false,
        mediaType: MessageType? = null,
    ): QueryFilter {
        val clauses = mutableListOf(
            "id NOT GLOB ?",
            "(text_content IS NOT NULL OR media_url IS NOT NULL)",
            "${Db.MEMBER_KEY} = ?",
        )
        val arguments = mutableListOf(Db.TEST_MESSAGE_GLOB, memberKey)
        if (favoritesOnly) clauses += "is_favorite = 1"
        if (mediaType != null) {
            clauses += "type = ?"
            arguments += mediaType.name
        }
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            val patterns = LinkedHashSet<String>()
            patterns += Db.escapeLike(query)
            if (nickname.isNotBlank() && query.contains(nickname)) {
                patterns += Db.escapeLike(query.replace(nickname, "%%%"))
            }
            clauses += patterns.joinToString(separator = " OR ", prefix = "(", postfix = ")") {
                """
                (
                    COALESCE(text_content, '') LIKE ? ESCAPE '\'
                    OR COALESCE(translation, '') LIKE ? ESCAPE '\'
                    OR member_name LIKE ? ESCAPE '\'
                    OR COALESCE(sent_at, '') LIKE ? ESCAPE '\'
                    OR LOWER(type) LIKE LOWER(?) ESCAPE '\'
                    OR CASE type
                        WHEN 'IMAGE' THEN '图片'
                        WHEN 'AUDIO' THEN '语音'
                        WHEN 'VIDEO' THEN '视频'
                        ELSE '文字'
                    END LIKE ? ESCAPE '\'
                )
                """.trimIndent()
            }
            patterns.forEach { pattern ->
                val like = "%$pattern%"
                repeat(6) { arguments += like }
            }
        }
        Db.addTimeClause(clauses, arguments, "sent_ms", startMillis, endMillisExclusive)
        return QueryFilter(clauses.joinToString(" AND "), arguments.toTypedArray())
    }

    private fun clearedTranslation() = ContentValues().apply {
        put("translation", null as String?)
        put("translation_done", 0)
    }

    private fun RelayMessage.toValues(isUnread: Boolean, receivedAt: Long) = ContentValues().apply {
        put("id", id)
        put("member_id", memberId)
        put("member_name", memberName)
        put("member_avatar_url", memberAvatarUrl)
        put("phone_image_url", phoneImageUrl)
        put("type", type.name)
        put("text_content", text)
        put("media_url", mediaUrl)
        put("thumbnail_url", thumbnailUrl)
        put("duration_seconds", durationSeconds)
        put("sent_at", sentAt)
        put("incoming_call_from", incomingCallFrom)
        put("ringtone_url", ringtoneUrl)
        put("is_played", if (isPlayed) 1 else 0)
        put("is_unread", if (isUnread) 1 else 0)
        put("is_favorite", if (isFavorite) 1 else 0)
        put("video_has_audio", videoHasAudio?.let { if (it) 1 else 0 })
        put("translation", translation)
        put("translation_done", if (translationDone) 1 else 0)
        put("received_at", receivedAt)
    }
}
