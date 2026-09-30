package com.nogirelay.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.CancellationSignal
import com.nogirelay.app.blog.BlogContentParser

data class ExportMember(
    val memberKey: String,
    val name: String,
    val avatarUrl: String?,
    val category: String,
    val displayOrder: Int,
    val directory: Boolean,
)

class MessageDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                member_id TEXT NOT NULL,
                member_name TEXT NOT NULL,
                member_avatar_url TEXT,
                phone_image_url TEXT,
                type TEXT NOT NULL,
                text_content TEXT,
                media_url TEXT,
                thumbnail_url TEXT,
                duration_seconds INTEGER,
                sent_at TEXT NOT NULL,
                incoming_call_from TEXT,
                ringtone_url TEXT,
                is_played INTEGER NOT NULL DEFAULT 0,
                is_unread INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                video_has_audio INTEGER,
                translation TEXT,
                translation_done INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_sent_at ON messages(sent_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_unread_member ON messages(is_unread, member_id, member_name)")
        createBlogTables(db)
        createMediaRefSchema(db)
        createMessageFavoriteIndex(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN translation TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN translation_done INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {

            db.delete("messages", "id GLOB ?", arrayOf(TEST_MESSAGE_GLOB))
        }
        if (oldVersion < 4) {

            db.execSQL("ALTER TABLE messages ADD COLUMN is_unread INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX idx_messages_unread_member ON messages(is_unread, member_id, member_name)")
        }
        if (oldVersion < 5) createBlogTables(db)
        if (oldVersion in 5 until 6) {
            db.execSQL("ALTER TABLE blog_posts ADD COLUMN is_unread INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_unread ON blog_posts(is_unread, published_at DESC)")
        }
        if (oldVersion < 7) {
            createBlogMemberTable(db)

            db.execSQL("UPDATE blog_posts SET translation = NULL, translation_done = 0")
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE blog_members ADD COLUMN graduated INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE blog_members ADD COLUMN latest_post_at TEXT")
            db.execSQL(
                "UPDATE blog_members SET latest_post_at = " +
                    "(SELECT MAX(published_at) FROM blog_posts WHERE blog_posts.member_id = blog_members.id)",
            )
        }
        if (oldVersion < 10) {

            db.execSQL(
                "DELETE FROM blog_posts " +
                    "WHERE id GLOB '[0-9]*' AND id LIKE '0%' AND length(id) > 1 " +
                    "AND EXISTS (SELECT 1 FROM blog_posts p WHERE p.id = LTRIM(blog_posts.id, '0'))",
            )
        }
        if (oldVersion < 11) {

            createMediaRefSchema(db)
        }
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE messages ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
            createMessageFavoriteIndex(db)
        }
        if (oldVersion < 13) {

            db.execSQL("ALTER TABLE messages ADD COLUMN video_has_audio INTEGER")
        }
    }

    private fun createBlogTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blog_posts (
                id TEXT PRIMARY KEY,
                member_id TEXT NOT NULL,
                member_name TEXT NOT NULL,
                member_avatar_url TEXT,
                title TEXT NOT NULL,
                body_html TEXT NOT NULL,
                image_url TEXT,
                published_at TEXT NOT NULL,
                post_url TEXT NOT NULL,
                translation TEXT,
                translation_done INTEGER NOT NULL DEFAULT 0,
                is_unread INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_date ON blog_posts(published_at DESC, id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_member ON blog_posts(member_id, member_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_unread ON blog_posts(is_unread, published_at DESC)")
        createBlogMemberTable(db)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_state (
                state_key TEXT PRIMARY KEY,
                state_value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createBlogMemberTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blog_members (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                avatar_url TEXT,
                display_order INTEGER NOT NULL,
                latest_post_at TEXT,
                graduated INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_members_order ON blog_members(display_order ASC)")
    }

    private fun createMediaRefSchema(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS media_refs (
                kind TEXT NOT NULL,
                record_id TEXT NOT NULL,
                member_key TEXT NOT NULL,
                role TEXT NOT NULL,
                url TEXT NOT NULL,
                media_type TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                parse_version INTEGER NOT NULL,
                PRIMARY KEY (kind, record_id, role, url)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_refs_member ON media_refs(kind, member_key)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_member_sent ON messages(" +
                "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END, " +
                "sent_at DESC, received_at DESC, id DESC)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_blog_posts_member_date ON blog_posts(" +
                "member_id, published_at DESC, id DESC)",
        )
    }

    private fun createMessageFavoriteIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_favorite_member ON messages(" +
                "is_favorite, CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END, " +
                "sent_at DESC, received_at DESC, id DESC)",
        )
    }

    fun insert(message: RelayMessage, isUnread: Boolean = false): Boolean {
        val values = ContentValues().apply {
            put("id", message.id)
            put("member_id", message.memberId)
            put("member_name", message.memberName)
            put("member_avatar_url", message.memberAvatarUrl)
            put("phone_image_url", message.phoneImageUrl)
            put("type", message.type.name)
            put("text_content", message.text)
            put("media_url", message.mediaUrl)
            put("thumbnail_url", message.thumbnailUrl)
            put("duration_seconds", message.durationSeconds)
            put("sent_at", message.sentAt)
            put("incoming_call_from", message.incomingCallFrom)
            put("ringtone_url", message.ringtoneUrl)
            put("is_played", if (message.isPlayed) 1 else 0)
            put("is_unread", if (isUnread || message.isUnread) 1 else 0)
            put("is_favorite", if (message.isFavorite) 1 else 0)
            put("video_has_audio", message.videoHasAudio?.let { if (it) 1 else 0 })
            put("translation", message.translation)
            put("translation_done", if (message.translationDone) 1 else 0)
            put("received_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        if (!inserted) {

            val mediaValues = ContentValues().apply {
                message.mediaUrl?.takeIf { it.isNotBlank() }?.let { put("media_url", it) }
                message.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { put("thumbnail_url", it) }
                message.memberAvatarUrl?.takeIf { it.isNotBlank() }?.let { put("member_avatar_url", it) }
                message.phoneImageUrl?.takeIf { it.isNotBlank() }?.let { put("phone_image_url", it) }
            }
            if (mediaValues.size() > 0) {
                writableDatabase.update("messages", mediaValues, "id = ?", arrayOf(message.id))
            }
        }
        refreshMessageMediaRefs(message.id)
        return inserted
    }

    fun latest(limit: Int = 200): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        readableDatabase.query(
            "messages",
            null,
            "id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            "sent_at DESC, received_at DESC",
            limit.coerceIn(1, 500).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun latestMessagePerMember(): List<RelayMessage> {
        val memberKeyExpression = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"
        val sql = """
            SELECT *, MAX(sent_at) AS max_sent_at
            FROM messages
            WHERE id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)
            GROUP BY $memberKeyExpression
            ORDER BY sent_at DESC, received_at DESC
        """.trimIndent()
        val result = mutableListOf<RelayMessage>()
        readableDatabase.rawQuery(sql, arrayOf(TEST_MESSAGE_GLOB)).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
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
        val result = mutableListOf<RelayMessage>()
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        readableDatabase.query(
            false,
            "messages",
            null,
            filter.selection,
            filter.arguments,
            null,
            null,
            MEMBER_MESSAGE_ORDER,
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
            cancellationSignal,
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun favoriteMessagesForMember(
        memberKey: String,
        limit: Int = 500,
        offset: Int = 0,
        cancellationSignal: CancellationSignal? = null,
    ): List<RelayMessage> = queryFilteredMessages(
        memberFilter(memberKey, "", null, null, favoritesOnly = true),
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
    ): List<RelayMessage> = queryFilteredMessages(
        memberFilter(memberKey, "", null, null, mediaType = mediaType),
        limit,
        offset,
        cancellationSignal,
    )

    fun countFavoriteMessagesForMember(
        memberKey: String,
        cancellationSignal: CancellationSignal? = null,
    ): Int = countFilteredMessages(
        memberFilter(memberKey, "", null, null, favoritesOnly = true),
        cancellationSignal,
    )

    fun countMediaMessagesForMember(
        memberKey: String,
        mediaType: MessageType,
        cancellationSignal: CancellationSignal? = null,
    ): Int = countFilteredMessages(
        memberFilter(memberKey, "", null, null, mediaType = mediaType),
        cancellationSignal,
    )

    fun setMessageFavorite(id: String, favorite: Boolean): Boolean {
        if (id.isBlank()) return false
        val values = ContentValues().apply { put("is_favorite", if (favorite) 1 else 0) }
        return writableDatabase.update("messages", values, "id = ?", arrayOf(id)) > 0
    }

    fun setVideoHasAudio(id: String, hasAudio: Boolean): Boolean {
        if (id.isBlank()) return false
        val values = ContentValues().apply { put("video_has_audio", if (hasAudio) 1 else 0) }
        return writableDatabase.update("messages", values, "id = ?", arrayOf(id)) > 0
    }

    private fun queryFilteredMessages(
        filter: QueryFilter,
        limit: Int,
        offset: Int,
        cancellationSignal: CancellationSignal?,
    ): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        readableDatabase.query(
            false,
            "messages",
            null,
            filter.selection,
            filter.arguments,
            null,
            null,
            MEMBER_MEDIA_ORDER,
            "${limit.coerceIn(1, 1000)} OFFSET ${offset.coerceAtLeast(0)}",
            cancellationSignal,
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.toMessage() }
        return result
    }

    private fun countFilteredMessages(
        filter: QueryFilter,
        cancellationSignal: CancellationSignal?,
    ): Int = readableDatabase.query(
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
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

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

        val exists = readableDatabase.rawQuery(
            "SELECT 1 FROM messages WHERE ${filter.selection} AND id = ? LIMIT 1",
            args + messageId, cancellationSignal,
        ).use { it.moveToFirst() }
        if (!exists) return -1
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE ${filter.selection} AND " +
                "(sent_at, received_at, id) > (SELECT sent_at, received_at, id FROM messages WHERE id = ?)",
            args + messageId, cancellationSignal,
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun memberMessagesByIds(
        memberKey: String, ids: Set<String>, searchQuery: String = "",
        startMillis: Long? = null, endMillisExclusive: Long? = null, nickname: String = "",
        cancellationSignal: CancellationSignal? = null,
    ): List<RelayMessage> {
        if (ids.isEmpty()) return emptyList()
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        return ids.chunked(200).flatMap { batch ->
            readableDatabase.rawQuery(
                "SELECT * FROM messages WHERE ${filter.selection} AND id IN (${batch.joinToString(",") { "?" }}) ORDER BY $MEMBER_MESSAGE_ORDER",
                filter.arguments + batch, cancellationSignal,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }
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
        val order = if (newer) "sent_at ASC, received_at ASC, id ASC" else MEMBER_MESSAGE_ORDER
        val result = readableDatabase.rawQuery(
            "SELECT * FROM messages WHERE ${filter.selection} AND " +
                "(sent_at, received_at, id) $operator (SELECT sent_at, received_at, id FROM messages WHERE id = ?) " +
                "ORDER BY $order LIMIT ${limit.coerceIn(1, 100)}",
            filter.arguments + anchorId, cancellationSignal,
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }
        return if (newer) result.reversed() else result
    }

    fun countMessagesForMember(
        memberKey: String,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        nickname: String = "",
        cancellationSignal: CancellationSignal? = null,
    ): Int {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
        readableDatabase.query(
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
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun unreadCountsByMember(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        val memberKeyExpression = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"
        readableDatabase.query(
            "messages",
            arrayOf("$memberKeyExpression AS member_key", "COUNT(*) AS unread_count"),
            "is_unread = 1 AND id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)",
            arrayOf(TEST_MESSAGE_GLOB),
            memberKeyExpression,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getInt(1)
            }
        }
        return result
    }

    fun countUnreadMessages(): Int {
        readableDatabase.query(
            "messages",
            arrayOf("COUNT(*)"),
            "is_unread = 1 AND id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            null,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun markMessagesReadByIds(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val values = ContentValues().apply { put("is_unread", 0) }
        var updated = 0
        ids.toList().chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            updated += writableDatabase.update(
                "messages",
                values,
                "is_unread = 1 AND id IN ($placeholders)",
                chunk.toTypedArray(),
            )
        }
        return updated
    }

    fun find(id: String): RelayMessage? {
        readableDatabase.query(
            "messages",
            null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.toMessage() else null }
    }

    fun markPlayed(id: String) {
        val values = ContentValues().apply { put("is_played", 1) }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    fun deleteTestMessages(): List<String> {
        val ids = mutableListOf<String>()
        writableDatabase.query(
            "messages",
            arrayOf("id"),
            "id GLOB ?",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        if (ids.isNotEmpty()) {
            writableDatabase.delete("messages", "id GLOB ?", arrayOf(TEST_MESSAGE_GLOB))
        }
        return ids
    }

    fun pendingTranslationIds(blogs: Boolean, afterId: String? = null, limit: Int = 24): List<String> {
        val table = if (blogs) "blog_posts" else "messages"
        val textColumn = if (blogs) "body_html" else "text_content"
        val selection = "translation_done = 0 AND $textColumn IS NOT NULL AND TRIM($textColumn) <> ''" +
            (if (blogs) "" else " AND id NOT GLOB ?") + (if (afterId != null) " AND id > ?" else "")
        val args = buildList {
            if (!blogs) add(TEST_MESSAGE_GLOB)
            if (afterId != null) add(afterId)
        }.toTypedArray()
        return readableDatabase.query(table, arrayOf("id"), selection, args, null, null, "id ASC", limit.coerceIn(1, 100).toString())
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    fun pendingTranslations(limit: Int = 100): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        readableDatabase.query(
            "messages",
            null,
            "id NOT GLOB ? AND translation_done = 0 AND text_content IS NOT NULL AND TRIM(text_content) <> ''",
            arrayOf(TEST_MESSAGE_GLOB),
            null,
            null,
            "sent_at DESC, received_at DESC",
            limit.coerceIn(1, MAX_TRANSLATION_BATCH).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun saveTranslation(id: String, translation: String?) {
        val values = ContentValues().apply {
            put(
                "translation",
                translation
                    ?.takeIf { it.isNotBlank() },
            )
            put("translation_done", 1)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    fun markForRetranslation(id: String) {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        writableDatabase.update("messages", values, "id = ?", arrayOf(id))
    }

    fun markAllMessagesForRetranslation(): Int {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        return writableDatabase.update(
            "messages",
            values,
            "id NOT GLOB ? AND text_content IS NOT NULL AND TRIM(text_content) <> ''",
            arrayOf(TEST_MESSAGE_GLOB),
        )
    }

    fun upsertBlog(post: BlogPost, isUnread: Boolean = false): Boolean {
        val id = canonicalBlogId(post.id)
        val values = ContentValues().apply {
            put("id", id)
            put("member_id", post.memberId)
            put("member_name", post.memberName)
            put("member_avatar_url", post.memberAvatarUrl)
            put("title", post.title)
            put("body_html", post.bodyHtml)
            put("image_url", post.imageUrl)
            put("published_at", post.publishedAt)
            put("post_url", post.postUrl)
            put("translation", post.translation)
            put("translation_done", if (post.translationDone) 1 else 0)
            put("is_unread", if (isUnread || post.isUnread) 1 else 0)
            put("received_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "blog_posts",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        updateMemberLatestPost(post.memberId, post.publishedAt)
        if (inserted) {
            refreshBlogMediaRefs(id)
            return true
        }

        val existingBody = readableDatabase.query(
            "blog_posts",
            arrayOf("body_html"),
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
        val update = ContentValues().apply {
            if (post.memberId.isNotBlank()) put("member_id", post.memberId)
            if (post.memberName.isNotBlank()) put("member_name", post.memberName)
            if (post.memberAvatarUrl != null) put("member_avatar_url", post.memberAvatarUrl)
            if (post.title.isNotBlank()) put("title", post.title)
            if (post.imageUrl != null) put("image_url", post.imageUrl)
            if (post.publishedAt.isNotBlank()) put("published_at", post.publishedAt)
            if (post.postUrl.isNotBlank()) put("post_url", post.postUrl)
            if (post.bodyHtml.isNotBlank()) {
                put("body_html", post.bodyHtml)

                if (existingBody != post.bodyHtml &&
                    BlogContentParser.bodyTextShape(existingBody) != BlogContentParser.bodyTextShape(post.bodyHtml)
                ) {
                    put("translation", null as String?)
                    put("translation_done", 0)
                }
            }
        }
        writableDatabase.update("blog_posts", update, "id = ?", arrayOf(id))
        refreshBlogMediaRefs(id)
        return false
    }

    fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun messageExportMembers(): List<ExportMember> {
        val sql = """
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
        """.trimIndent()
        val result = mutableListOf<ExportMember>()
        readableDatabase.rawQuery(sql, arrayOf(TEST_MESSAGE_GLOB)).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(cursor.getColumnIndexOrThrow("member_key"))
                val order = cursor.getInt(cursor.getColumnIndexOrThrow("display_order"))
                val category = cursor.getString(cursor.getColumnIndexOrThrow("category"))
                val name = cursor.getString(cursor.getColumnIndexOrThrow("member_name")).ifBlank { key }
                result += ExportMember(
                    memberKey = key,
                    name = name,
                    avatarUrl = cursor.nullableString("avatar_url"),
                    category = BlogMemberCategories.normalizeCategory(key, name, category),
                    displayOrder = if (order >= 0) order else 10_000,
                    directory = order >= 0,
                )
            }
        }
        return result
    }

    fun countMessagesForMembers(memberKeys: Collection<String>): Int {
        if (memberKeys.isEmpty()) return 0
        val placeholders = memberKeys.joinToString(",") { "?" }
        val keyExpression = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"
        return readableDatabase.rawQuery(
            """
            SELECT COUNT(*) FROM messages
            WHERE id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)
              AND $keyExpression IN ($placeholders)
            """.trimIndent(),
            arrayOf(TEST_MESSAGE_GLOB, *memberKeys.toTypedArray()),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun forEachMessageForMembers(memberKeys: Collection<String>, action: (RelayMessage) -> Unit) {
        if (memberKeys.isEmpty()) return
        val placeholders = memberKeys.joinToString(",") { "?" }
        val keyExpression = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"
        readableDatabase.rawQuery(
            """
            SELECT * FROM messages
            WHERE id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)
              AND $keyExpression IN ($placeholders)
            ORDER BY sent_at DESC, received_at DESC, id DESC
            """.trimIndent(),
            arrayOf(TEST_MESSAGE_GLOB, *memberKeys.toTypedArray()),
        ).use { cursor ->
            while (cursor.moveToNext()) action(cursor.toMessage())
        }
    }

    fun forEachBlogForMembers(memberIds: Collection<String>, action: (BlogPost) -> Unit) {
        if (memberIds.isEmpty()) return
        val placeholders = memberIds.joinToString(",") { "?" }
        readableDatabase.rawQuery(
            "SELECT * FROM blog_posts WHERE member_id IN ($placeholders) " +
                "ORDER BY published_at DESC, id DESC",
            memberIds.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) action(cursor.toBlogPost())
        }
    }

    fun countBlogsForMembers(memberIds: Collection<String>): Int {
        if (memberIds.isEmpty()) return 0
        val placeholders = memberIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM blog_posts WHERE member_id IN ($placeholders)",
            memberIds.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun insertImported(message: RelayMessage): Boolean {
        val values = ContentValues().apply {
            put("id", message.id)
            put("member_id", message.memberId)
            put("member_name", message.memberName)
            put("member_avatar_url", message.memberAvatarUrl)
            put("phone_image_url", message.phoneImageUrl)
            put("type", message.type.name)
            put("text_content", message.text)
            put("media_url", message.mediaUrl)
            put("thumbnail_url", message.thumbnailUrl)
            put("duration_seconds", message.durationSeconds)
            put("sent_at", message.sentAt)
            put("incoming_call_from", message.incomingCallFrom)
            put("ringtone_url", message.ringtoneUrl)
            put("is_played", if (message.isPlayed) 1 else 0)
            put("is_unread", 0)
            put("is_favorite", if (message.isFavorite) 1 else 0)
            put("video_has_audio", message.videoHasAudio?.let { if (it) 1 else 0 })
            put("translation", message.translation)
            put("translation_done", if (message.translationDone) 1 else 0)
            put("received_at", 0L)
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        refreshMessageMediaRefs(message.id)
        return inserted
    }

    fun insertBlogIfAbsent(post: BlogPost, isUnread: Boolean = false): Boolean {
        val id = canonicalBlogId(post.id)
        val values = ContentValues().apply {
            put("id", id)
            put("member_id", post.memberId)
            put("member_name", post.memberName)
            put("member_avatar_url", post.memberAvatarUrl)
            put("title", post.title)
            put("body_html", post.bodyHtml)
            put("image_url", post.imageUrl)
            put("published_at", post.publishedAt)
            put("post_url", post.postUrl)
            put("translation", post.translation)
            put("translation_done", if (post.translationDone) 1 else 0)
            put("is_unread", if (isUnread) 1 else 0)
            put("received_at", System.currentTimeMillis())
        }
        val inserted = writableDatabase.insertWithOnConflict(
            "blog_posts",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
        updateMemberLatestPost(post.memberId, post.publishedAt)
        refreshBlogMediaRefs(id)
        return inserted
    }

    fun backfillMessageTranslation(id: String, translation: String): Boolean {
        if (translation.isBlank()) return false
        val values = ContentValues().apply {
            put("translation", translation)
            put("translation_done", 1)
        }
        return writableDatabase.update(
            "messages",
            values,
            "id = ? AND (translation IS NULL OR TRIM(translation) = '' OR translation_done = 0)",
            arrayOf(id),
        ) > 0
    }

    fun backfillBlogTranslation(id: String, translation: String): Boolean {
        if (translation.isBlank()) return false
        val values = ContentValues().apply {
            put("translation", translation)
            put("translation_done", 1)
        }
        return writableDatabase.update(
            "blog_posts",
            values,
            "id = ? AND (translation IS NULL OR TRIM(translation) = '' OR translation_done = 0)",
            arrayOf(id),
        ) > 0
    }

    fun refreshImportedLinks(id: String, links: Map<String, String>): Boolean {
        val updated = updateLinksIfDifferent("messages", id, links)
        if (updated) refreshMessageMediaRefs(id)
        return updated
    }

    fun refreshImportedBlogLinks(id: String, links: Map<String, String>): Boolean {
        val updated = updateLinksIfDifferent("blog_posts", id, links)
        if (updated) refreshBlogMediaRefs(id)
        return updated
    }

    private fun updateLinksIfDifferent(table: String, id: String, links: Map<String, String>): Boolean {
        if (links.isEmpty()) return false
        val values = ContentValues().apply { links.forEach { (column, value) -> put(column, value) } }
        val conditions = links.keys.joinToString(" OR ") { "COALESCE($it, '') <> ?" }
        return writableDatabase.update(
            table,
            values,
            "id = ? AND ($conditions)",
            arrayOf(id, *links.values.toTypedArray()),
        ) > 0
    }

    fun mediaRefsReady(): Boolean =
        syncStateValue(MEDIA_REFS_VERSION_KEY)?.toIntOrNull() == MediaRefs.PARSE_VERSION

    fun markMediaRefsReady() =
        putSyncStateValue(MEDIA_REFS_VERSION_KEY, MediaRefs.PARSE_VERSION.toString())

    fun mediaRefsFor(kind: MediaRefKind, memberKeys: Collection<String>): List<MediaRefRow> {
        if (memberKeys.isEmpty()) return emptyList()
        val placeholders = memberKeys.joinToString(",") { "?" }
        val result = mutableListOf<MediaRefRow>()
        readableDatabase.rawQuery(
            "SELECT record_id, role, url, media_type, ordinal FROM media_refs " +
                "WHERE kind = ? AND member_key IN ($placeholders) ORDER BY record_id, ordinal",
            arrayOf(kind.name, *memberKeys.toTypedArray()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += MediaRefRow(
                    recordId = cursor.getString(0),
                    role = cursor.getString(1),
                    url = cursor.getString(2),
                    type = MessageType.valueOf(cursor.getString(3)),
                    ordinal = cursor.getInt(4),
                )
            }
        }
        return result
    }

    fun refreshMessageMediaRefs(id: String) {
        val message = find(id)
        if (message == null) {
            deleteMediaRefs(MediaRefKind.MESSAGES, id)
            return
        }
        replaceMediaRefs(
            MediaRefKind.MESSAGES,
            id,
            MediaRefs.messageMemberKey(message),
            MediaRefs.candidates(message),
        )
    }

    fun refreshBlogMediaRefs(id: String) {
        val canonical = canonicalBlogId(id)
        val post = findBlog(canonical)
        if (post == null) {
            deleteMediaRefs(MediaRefKind.BLOGS, canonical)
            return
        }
        replaceMediaRefs(
            MediaRefKind.BLOGS,
            canonical,
            MediaRefs.blogMemberKey(post),
            MediaRefs.candidates(post),
        )
    }

    fun rebuildMediaRefs(onProgress: ((done: Int, total: Int) -> Unit)? = null) {
        val messageTotal = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM messages WHERE id NOT GLOB ?",
            arrayOf(TEST_MESSAGE_GLOB),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val blogTotal = readableDatabase.rawQuery("SELECT COUNT(*) FROM blog_posts", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val total = messageTotal + blogTotal
        writableDatabase.delete("media_refs", null, null)

        var done = 0
        var lastMessageId: String? = null
        while (true) {
            val page = readMessagePage(lastMessageId)
            if (page.isEmpty()) break
            val rows = mutableListOf<ContentValues>()
            page.forEach { message ->
                appendMediaRefValues(
                    rows,
                    MediaRefKind.MESSAGES,
                    message.id,
                    MediaRefs.messageMemberKey(message),
                    MediaRefs.candidates(message),
                )
            }
            insertRefRows(rows)
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
                appendMediaRefValues(
                    rows,
                    MediaRefKind.BLOGS,
                    canonicalBlogId(post.id),
                    MediaRefs.blogMemberKey(post),
                    MediaRefs.candidates(post),
                )
            }
            insertRefRows(rows)
            done += page.size
            lastBlogId = page.last().id
            onProgress?.invoke(done, total)
        }
        onProgress?.invoke(total, total)
    }

    private fun replaceMediaRefs(
        kind: MediaRefKind,
        recordId: String,
        memberKey: String,
        candidates: List<MediaCandidate>,
    ) {
        inWriteTransaction {
            writableDatabase.delete("media_refs", "kind = ? AND record_id = ?", arrayOf(kind.name, recordId))
            val pending = mutableListOf<ContentValues>()
            appendMediaRefValues(pending, kind, recordId, memberKey, candidates)
            pending.forEach {
                writableDatabase.insertWithOnConflict("media_refs", null, it, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun deleteMediaRefs(kind: MediaRefKind, recordId: String) {
        writableDatabase.delete("media_refs", "kind = ? AND record_id = ?", arrayOf(kind.name, recordId))
    }

    private fun appendMediaRefValues(
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
        val arguments = if (afterId == null) {
            arrayOf(TEST_MESSAGE_GLOB)
        } else {
            arrayOf(TEST_MESSAGE_GLOB, afterId)
        }
        val result = mutableListOf<RelayMessage>()
        readableDatabase.query(
            "messages",
            null,
            selection,
            arguments,
            null,
            null,
            "id ASC",
            MEDIA_REF_BATCH.toString(),
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.toMessage() }
        return result
    }

    private fun readBlogPage(afterId: String?): List<BlogPost> {
        val selection = if (afterId == null) null else "id > ?"
        val arguments = if (afterId == null) null else arrayOf(afterId)
        val result = mutableListOf<BlogPost>()
        readableDatabase.query(
            "blog_posts",
            null,
            selection,
            arguments,
            null,
            null,
            "id ASC",
            MEDIA_REF_BATCH.toString(),
        ).use { cursor -> while (cursor.moveToNext()) result += cursor.toBlogPost() }
        return result
    }

    private fun insertRefRows(rows: List<ContentValues>) {
        if (rows.isEmpty()) return
        inWriteTransaction {
            rows.forEach {
                writableDatabase.insertWithOnConflict("media_refs", null, it, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private fun inWriteTransaction(block: () -> Unit) {
        val db = writableDatabase
        if (db.inTransaction()) {
            block()
            return
        }
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun markMemberGraduated(memberId: String): Boolean =
        writableDatabase.update(
            "blog_members",
            ContentValues().apply { put("graduated", 1) },
            "id = ? AND graduated = 0",
            arrayOf(memberId),
        ) > 0

    fun insertMemberIfAbsent(member: BlogMember, latestPostAt: String? = null): Boolean {
        if (member.graduated) markMemberGraduated(member.id)
        val values = ContentValues().apply {
            put("id", member.id)
            put("name", member.name)
            put("category", member.category)
            put("avatar_url", member.avatarUrl)
            put("display_order", member.displayOrder)
            put("graduated", if (member.graduated) 1 else 0)
            put("latest_post_at", latestPostAt)
        }
        return writableDatabase.insertWithOnConflict(
            "blog_members",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    private fun updateMemberLatestPost(memberId: String, publishedAt: String) {
        if (memberId.isBlank() || publishedAt.isBlank()) return
        writableDatabase.execSQL(
            """
            UPDATE blog_members
            SET latest_post_at = CASE
                WHEN latest_post_at IS NULL OR latest_post_at < ? THEN ?
                ELSE latest_post_at
            END
            WHERE id = ?
            """.trimIndent(),
            arrayOf(publishedAt, publishedAt, memberId),
        )
    }

    fun blogSummaries(
        memberIds: Set<String>? = null,
        searchQuery: String = "",
        oldestFirst: Boolean = false,
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
        cancellationSignal: CancellationSignal? = null,
    ): List<BlogSummary> {
        val result = mutableListOf<BlogSummary>()
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        readableDatabase.query(
            false,
            "blog_posts",
            arrayOf("id", "member_id", "member_name", "member_avatar_url", "title", "image_url", "published_at", "is_unread", "translation"),
            filter.selection,
            filter.arguments,
            null,
            null,
            if (oldestFirst) "published_at ASC, id ASC" else "published_at DESC, id DESC",
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
            cancellationSignal,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlogSummary(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    memberId = cursor.getString(cursor.getColumnIndexOrThrow("member_id")),
                    memberName = cursor.getString(cursor.getColumnIndexOrThrow("member_name")),
                    memberAvatarUrl = cursor.nullableString("member_avatar_url"),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    imageUrl = cursor.nullableString("image_url"),
                    publishedAt = cursor.getString(cursor.getColumnIndexOrThrow("published_at")),
                    isUnread = cursor.getInt(cursor.getColumnIndexOrThrow("is_unread")) == 1,
                    translatedTitle = cursor.nullableString("translation")?.let(::translatedTitleFromJson),
                )
            }
        }
        return result
    }

    private fun translatedTitleFromJson(serialized: String): String? = runCatching {
        org.json.JSONArray(serialized).optString(0).takeIf { it.isNotBlank() }
    }.getOrNull()

    fun countBlogs(
        memberIds: Set<String>? = null,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        cancellationSignal: CancellationSignal? = null,
    ): Int {
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        return readableDatabase.query(
            false,
            "blog_posts",
            arrayOf("COUNT(*)"),
            filter.selection,
            filter.arguments,
            null,
            null,
            null,
            null,
            cancellationSignal,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun blogMembers(): List<BlogMember> {
        val result = mutableListOf<BlogMember>()
        val knownIds = mutableSetOf<String>()

        val postAvatars = postAvatarByMember()
        readableDatabase.rawQuery(
            """
            SELECT id, name, category, avatar_url, display_order, latest_post_at, graduated
            FROM blog_members
            WHERE EXISTS (
                SELECT 1 FROM blog_posts WHERE blog_posts.member_id = blog_members.id
            )
            ORDER BY display_order ASC, latest_post_at DESC, name ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                knownIds += id
                val rawName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                val isStaff = id == "10001" || id == "40003" || rawName == "乃木坂46" || rawName.contains("運営") || rawName.contains("スタッフ")
                val name = if (isStaff) "運営スタッフ" else rawName
                val rawCat = cursor.getString(cursor.getColumnIndexOrThrow("category"))
                val category = if (isStaff) {
                    "運営スタッフ"
                } else {

                    BlogMemberCategories.normalizeCategory(id, rawName, rawCat)
                }
                val rawAvatar = cursor.nullableString("avatar_url")
                val avatarUrl = blogAvatarUrl(rawAvatar) ?: blogAvatarUrl(postAvatars[id])
                result += BlogMember(
                    id = id,
                    name = name,
                    category = category,
                    avatarUrl = avatarUrl,
                    displayOrder = cursor.getInt(cursor.getColumnIndexOrThrow("display_order")),
                    graduated = !isStaff && cursor.getInt(cursor.getColumnIndexOrThrow("graduated")) == 1,
                )
            }
        }

        readableDatabase.rawQuery(
            """
            SELECT
                member_id,
                MAX(member_name) AS member_name,
                MAX(member_avatar_url) AS avatar_url,
                MAX(published_at) AS latest_post_at
            FROM blog_posts
            GROUP BY member_id
            ORDER BY latest_post_at DESC
            """.trimIndent(),
            null,
        ).use { cursor ->
            var extraOrder = 10000
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow("member_id"))
                if (id in knownIds) continue
                knownIds += id
                val rawName = cursor.getString(cursor.getColumnIndexOrThrow("member_name")).ifBlank { "乃木坂46" }
                val isStaff = id == "10001" || id == "40003" || rawName == "乃木坂46" || rawName.contains("運営") || rawName.contains("スタッフ")
                val name = if (isStaff) "運営スタッフ" else rawName
                val rawAvatar = cursor.nullableString("avatar_url")
                val avatarUrl = blogAvatarUrl(rawAvatar) ?: blogAvatarUrl(postAvatars[id])
                val category = if (isStaff) {
                    "運営スタッフ"
                } else {
                    BlogMemberCategories.normalizeCategory(id, rawName, null)
                }
                result += BlogMember(
                    id = id,
                    name = name,
                    category = category,
                    avatarUrl = avatarUrl,
                    displayOrder = extraOrder++,
                )
            }
        }

        return result
    }

    private fun blogAvatarUrl(raw: String?): String? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith("/") -> "https://www.nogizaka46.com$raw"
        else -> raw
    }

    private fun postAvatarByMember(): Map<String, String?> {
        val avatars = mutableMapOf<String, String?>()
        readableDatabase.rawQuery(
            "SELECT member_id, MAX(member_avatar_url) AS avatar_url FROM blog_posts GROUP BY member_id",
            null,
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("member_id")
            while (cursor.moveToNext()) {
                avatars[cursor.getString(idIndex)] = cursor.nullableString("avatar_url")
            }
        }
        return avatars
    }

    fun replaceBlogMembers(members: List<BlogMember>) {
        require(members.isNotEmpty()) { "官网成员列表为空" }
        val db = writableDatabase
        db.beginTransaction()
        try {

            val alreadyGraduated = mutableSetOf<String>()
            db.rawQuery("SELECT id FROM blog_members WHERE graduated = 1", null).use { cursor ->
                while (cursor.moveToNext()) alreadyGraduated += cursor.getString(0)
            }
            members.forEach { member ->
                val values = ContentValues().apply {
                    put("id", member.id)
                    put("name", member.name)
                    put("category", member.category)
                    put("avatar_url", member.avatarUrl)
                    put("display_order", member.displayOrder)
                    put("graduated", if (member.graduated || member.id in alreadyGraduated) 1 else 0)
                    put(
                        "latest_post_at",
                        db.rawQuery(
                            "SELECT MAX(published_at) FROM blog_posts WHERE member_id = ?",
                            arrayOf(member.id),
                        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null },
                    )
                }

                val updated = db.update("blog_members", values, "id = ?", arrayOf(member.id))
                if (updated == 0) db.insertOrThrow("blog_members", null, values)
            }
            backfillBlogAvatars(db, members)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun backfillBlogAvatars(db: SQLiteDatabase, members: List<BlogMember>) {
        val avatarByMember = members
            .mapNotNull { member ->
                member.avatarUrl?.takeIf(String::isNotBlank)?.let { member.id to it }
            }
            .toMap()
        if (avatarByMember.isEmpty()) return
        val blankMembers = mutableSetOf<String>()
        db.rawQuery(
            "SELECT DISTINCT member_id FROM blog_posts " +
                "WHERE member_avatar_url IS NULL OR TRIM(member_avatar_url) = ''",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) blankMembers += cursor.getString(0)
        }
        blankMembers.forEach { memberId ->
            val avatar = avatarByMember[memberId] ?: return@forEach
            db.update(
                "blog_posts",
                ContentValues().apply { put("member_avatar_url", avatar) },
                "member_id = ? AND (member_avatar_url IS NULL OR TRIM(member_avatar_url) = '')",
                arrayOf(memberId),
            )
        }
    }

    fun blogSearchSources(ids: List<String>): List<BlogSearchSource> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<BlogSearchSource>()
        readableDatabase.query(
            "blog_posts",
            arrayOf("id", "body_html", "translation"),
            "id IN (${ids.joinToString(",") { "?" }})",
            ids.toTypedArray(),
            null,
            null,
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlogSearchSource(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    bodyHtml = cursor.getString(cursor.getColumnIndexOrThrow("body_html")),
                    translation = cursor.nullableString("translation"),
                )
            }
        }
        return result
    }

    fun findBlog(id: String): BlogPost? = readableDatabase.query(
        "blog_posts",
        null,
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toBlogPost() else null }

    fun blogRank(blogId: String): Int? {
        val post = findBlog(blogId) ?: return null
        val publishedAt = post.publishedAt
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM blog_posts WHERE published_at > ? OR (published_at = ? AND id > ?)",
            arrayOf(publishedAt, publishedAt, blogId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else null
        }
    }

    fun saveBlogTranslation(id: String, translation: String?) {
        val values = ContentValues().apply {
            put("translation", translation?.takeIf { it.isNotBlank() })
            put("translation_done", 1)
        }
        writableDatabase.update("blog_posts", values, "id = ?", arrayOf(id))
    }

    fun pendingBlogTranslations(limit: Int = 100): List<String> {
        val ids = mutableListOf<String>()
        readableDatabase.query(
            "blog_posts",
            arrayOf("id"),
            "translation_done = 0 AND TRIM(body_html) <> ''",
            null,
            null,
            null,
            "published_at DESC, received_at DESC, id DESC",
            limit.coerceIn(1, MAX_TRANSLATION_BATCH).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids
    }

    fun markBlogForRetranslation(id: String) {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        writableDatabase.update("blog_posts", values, "id = ?", arrayOf(id))
    }

    fun markAllBlogsForRetranslation(): Int {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        return writableDatabase.update("blog_posts", values, "TRIM(body_html) <> ''", null)
    }

    fun countUnreadBlogs(): Int = readableDatabase.query(
        "blog_posts",
        arrayOf("COUNT(*)"),
        "is_unread = 1",
        null,
        null,
        null,
        null,
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    fun markBlogRead(id: String): Int {
        val values = ContentValues().apply { put("is_unread", 0) }
        return writableDatabase.update("blog_posts", values, "id = ? AND is_unread = 1", arrayOf(id))
    }

    fun isBlogFullSyncComplete(): Boolean = syncStateFlag(BLOG_FULL_SYNC_KEY)

    fun blogSyncHeadId(): String? = syncStateValue(BLOG_SYNC_HEAD_KEY)

    fun markBlogFullSyncComplete(headId: String?) {
        putSyncStateFlag(BLOG_FULL_SYNC_KEY)
        markBlogSyncHead(headId)
    }

    fun markBlogSyncHead(headId: String?) = putSyncStateValue(BLOG_SYNC_HEAD_KEY, headId)

    fun isMessageFullSyncComplete(): Boolean = syncStateFlag(MESSAGE_FULL_SYNC_KEY)

    fun messageSyncHeadId(): String? = syncStateValue(MESSAGE_SYNC_HEAD_KEY)

    fun markMessageFullSyncComplete(headId: String?) {
        putSyncStateFlag(MESSAGE_FULL_SYNC_KEY)
        markMessageSyncHead(headId)
    }

    fun markMessageSyncHead(headId: String?) = putSyncStateValue(MESSAGE_SYNC_HEAD_KEY, headId)

    private fun syncStateValue(key: String): String? = readableDatabase.query(
        "sync_state",
        arrayOf("state_value"),
        "state_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null }

    private fun syncStateFlag(key: String): Boolean = syncStateValue(key) == "1"

    private fun putSyncStateValue(key: String, value: String?) {
        if (value.isNullOrBlank()) return
        val values = ContentValues().apply {
            put("state_key", key)
            put("state_value", value)
        }
        writableDatabase.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun putSyncStateFlag(key: String) {
        val values = ContentValues().apply {
            put("state_key", key)
            put("state_value", "1")
        }
        writableDatabase.insertWithOnConflict("sync_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun blogFilter(
        memberIds: Set<String>?,
        searchQuery: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
    ): QueryFilter {
        val clauses = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        when {
            memberIds == null -> Unit
            memberIds.isEmpty() -> clauses += "0"
            else -> {
                clauses += "member_id IN (${memberIds.joinToString(",") { "?" }})"
                arguments += memberIds
            }
        }
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            clauses += """
                (
                    title LIKE ? ESCAPE '\'
                    OR body_html LIKE ? ESCAPE '\'
                    OR COALESCE(translation, '') LIKE ? ESCAPE '\'
                    OR member_name LIKE ? ESCAPE '\'
                    OR published_at LIKE ? ESCAPE '\'
                )
            """.trimIndent()
            val pattern = "%${escapeLike(query)}%"
            repeat(5) { arguments += pattern }
        }
        addTimeClause(clauses, arguments, "published_at", startMillis, endMillisExclusive)
        return QueryFilter(
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ") ?: "1",
            arguments = arguments.toTypedArray(),
        )
    }

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
            "(CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END) = ?",
        )
        val arguments = mutableListOf(TEST_MESSAGE_GLOB, memberKey)
        if (favoritesOnly) clauses += "is_favorite = 1"
        if (mediaType != null) {
            clauses += "type = ?"
            arguments += mediaType.name
        }
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {

            val patterns = LinkedHashSet<String>()
            patterns += escapeLike(query)
            if (nickname.isNotBlank() && query.contains(nickname)) {
                patterns += escapeLike(query.replace(nickname, "%%%"))
            }
            clauses += patterns.joinToString(" OR ") {
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
                val like = "%" + pattern + "%"
                repeat(6) { arguments += like }
            }
        }
        addTimeClause(clauses, arguments, "sent_at", startMillis, endMillisExclusive)
        return QueryFilter(clauses.joinToString(" AND "), arguments.toTypedArray())
    }

    private fun addTimeClause(
        clauses: MutableList<String>,
        arguments: MutableList<String>,
        column: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
    ) {

        if (startMillis != null) {
            clauses += "CAST(strftime('%s', $column) AS INTEGER) * 1000 >= CAST(? AS INTEGER)"
            arguments += startMillis.toString()
        }
        if (endMillisExclusive != null) {
            clauses += "CAST(strftime('%s', $column) AS INTEGER) * 1000 < CAST(? AS INTEGER)"
            arguments += endMillisExclusive.toString()
        }
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private data class QueryFilter(val selection: String, val arguments: Array<String>)

    private fun Cursor.toMessage(): RelayMessage = RelayMessage(
        id = getString(getColumnIndexOrThrow("id")),
        memberId = getString(getColumnIndexOrThrow("member_id")),
        memberName = getString(getColumnIndexOrThrow("member_name")),
        memberAvatarUrl = nullableString("member_avatar_url"),
        phoneImageUrl = nullableString("phone_image_url"),
        type = MessageType.valueOf(getString(getColumnIndexOrThrow("type"))),
        text = nullableString("text_content"),
        mediaUrl = nullableString("media_url"),
        thumbnailUrl = nullableString("thumbnail_url"),
        durationSeconds = nullableInt("duration_seconds"),
        sentAt = getString(getColumnIndexOrThrow("sent_at")),
        incomingCallFrom = nullableString("incoming_call_from"),
        ringtoneUrl = nullableString("ringtone_url"),
        isPlayed = getInt(getColumnIndexOrThrow("is_played")) == 1,
        translation = nullableString("translation"),
        translationDone = getInt(getColumnIndexOrThrow("translation_done")) == 1,
        isUnread = getInt(getColumnIndexOrThrow("is_unread")) == 1,
        isFavorite = getInt(getColumnIndexOrThrow("is_favorite")) == 1,
        videoHasAudio = nullableInt("video_has_audio")?.let { it == 1 },
    )

    private fun Cursor.toBlogPost(): BlogPost = BlogPost(
        id = getString(getColumnIndexOrThrow("id")),
        memberId = getString(getColumnIndexOrThrow("member_id")),
        memberName = getString(getColumnIndexOrThrow("member_name")),
        memberAvatarUrl = nullableString("member_avatar_url"),
        title = getString(getColumnIndexOrThrow("title")),
        bodyHtml = getString(getColumnIndexOrThrow("body_html")),
        imageUrl = nullableString("image_url"),
        publishedAt = getString(getColumnIndexOrThrow("published_at")),
        postUrl = getString(getColumnIndexOrThrow("post_url")),
        translation = nullableString("translation"),
        translationDone = getInt(getColumnIndexOrThrow("translation_done")) == 1,
        isUnread = getInt(getColumnIndexOrThrow("is_unread")) == 1,
    )

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    private fun canonicalBlogId(id: String): String =
        if (id.length > 1 && id.all { it in '0'..'9' }) id.trimStart('0').ifEmpty { "0" } else id

    companion object {
        private const val DB_NAME = "messages.db"
        private const val DB_VERSION = 13
        private const val TEST_MESSAGE_GLOB = "test[-_]*"

        private const val MEDIA_REFS_VERSION_KEY = "media_refs_parse_version_v1"

        private const val MEDIA_REF_BATCH = 500

        const val MAX_TRANSLATION_BATCH = 20_000
        private const val MEMBER_MESSAGE_ORDER = "sent_at DESC, received_at DESC, id DESC"

        private const val MEMBER_MEDIA_ORDER =
            "CAST(strftime('%s', sent_at) AS INTEGER) DESC, sent_at DESC, received_at DESC, id DESC"
        private const val BLOG_FULL_SYNC_KEY = "blog_full_sync_complete_v2"
        private const val BLOG_SYNC_HEAD_KEY = "blog_sync_head_id_v2"
        private const val MESSAGE_FULL_SYNC_KEY = "message_full_sync_complete_v1"
        private const val MESSAGE_SYNC_HEAD_KEY = "message_sync_head_id_v1"
    }
}
