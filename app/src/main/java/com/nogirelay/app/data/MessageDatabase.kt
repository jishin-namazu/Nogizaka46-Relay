package com.nogirelay.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
                translation TEXT,
                translation_done INTEGER NOT NULL DEFAULT 0,
                received_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_messages_sent_at ON messages(sent_at DESC)")
        db.execSQL("CREATE INDEX idx_messages_unread_member ON messages(is_unread, member_id, member_name)")
        createBlogTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN translation TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN translation_done INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // Older builds used test_... IDs, so remove those records during migration.
            db.delete("messages", "id GLOB ?", arrayOf(TEST_MESSAGE_GLOB))
        }
        if (oldVersion < 4) {
            // Existing local history is the read baseline when unread tracking is introduced.
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
            // v7 restores every translated BLOG line break from the source layout.
            db.execSQL("UPDATE blog_posts SET translation = NULL, translation_done = 0")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE blog_members ADD COLUMN latest_post_at TEXT")
            db.execSQL(
                "UPDATE blog_members SET latest_post_at = " +
                    "(SELECT MAX(published_at) FROM blog_posts WHERE blog_posts.member_id = blog_members.id)",
            )
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
                latest_post_at TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_members_order ON blog_members(display_order ASC)")
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
            // A history sync can replace an old direct CDN URL with the
            // protected relay URL without resetting playback state.
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

    /**
     * Returns the newest message for every subscribed member by grouping on member_key.
     * Unlike [latest], this ensures low-frequency members are never truncated out of the inbox.
     */
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
    ): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive)
        readableDatabase.query(
            "messages",
            null,
            filter.selection,
            filter.arguments,
            null,
            null,
            MEMBER_MESSAGE_ORDER,
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    fun messageIndexForMember(
        memberKey: String,
        messageId: String,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
    ): Int {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive)
        readableDatabase.query(
            "messages",
            arrayOf("id"),
            filter.selection,
            filter.arguments,
            null,
            null,
            MEMBER_MESSAGE_ORDER,
        ).use { cursor ->
            var index = 0
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == messageId) return index
                index += 1
            }
        }
        return -1
    }

    fun countMessagesForMember(
        memberKey: String,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
    ): Int {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive)
        readableDatabase.query(
            "messages",
            arrayOf("COUNT(*)"),
            filter.selection,
            filter.arguments,
            null,
            null,
            null,
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

    fun markMessagesReadForMember(memberKey: String): Int {
        val filter = memberFilter(memberKey, "", null, null)
        val values = ContentValues().apply { put("is_unread", 0) }
        return writableDatabase.update(
            "messages",
            values,
            "is_unread = 1 AND ${filter.selection}",
            filter.arguments,
        )
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

    /** Removes transient test messages and returns their IDs for notification cleanup. */
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
            limit.coerceIn(1, 500).toString(),
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

    fun upsertBlog(post: BlogPost, isUnread: Boolean = false): Boolean {
        val values = ContentValues().apply {
            put("id", post.id)
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
        if (inserted) return true

        val existingBody = readableDatabase.query(
            "blog_posts",
            arrayOf("body_html"),
            "id = ?",
            arrayOf(post.id),
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
                if (existingBody != post.bodyHtml) {
                    put("translation", null as String?)
                    put("translation_done", 0)
                }
            }
        }
        writableDatabase.update("blog_posts", update, "id = ?", arrayOf(post.id))
        return false
    }

    fun upsertBlogs(posts: List<BlogPost>): Int {
        if (posts.isEmpty()) return 0
        val db = writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            posts.forEach { post -> if (upsertBlog(post)) inserted += 1 }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
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

    fun hasBlog(id: String): Boolean = readableDatabase.query(
        "blog_posts",
        arrayOf("id"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use(Cursor::moveToFirst)

    fun blogSummaries(
        memberIds: Set<String>? = null,
        searchQuery: String = "",
        oldestFirst: Boolean = false,
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        limit: Int = 20,
        offset: Int = 0,
    ): List<BlogSummary> {
        val result = mutableListOf<BlogSummary>()
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        readableDatabase.query(
            "blog_posts",
            arrayOf("id", "member_id", "member_name", "member_avatar_url", "title", "image_url", "published_at", "is_unread", "translation"),
            filter.selection,
            filter.arguments,
            null,
            null,
            if (oldestFirst) "published_at ASC, id ASC" else "published_at DESC, id DESC",
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
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
    ): Int {
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        return readableDatabase.query(
            "blog_posts",
            arrayOf("COUNT(*)"),
            filter.selection,
            filter.arguments,
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun blogMembers(): List<BlogMember> {
        val result = mutableListOf<BlogMember>()
        readableDatabase.rawQuery(
            """
            SELECT id, name, category, avatar_url, display_order, latest_post_at
            FROM blog_members
            WHERE EXISTS (
                SELECT 1 FROM blog_posts WHERE blog_posts.member_id = blog_members.id
            )
            ORDER BY display_order ASC, latest_post_at DESC, name ASC
            """.trimIndent(),
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BlogMember(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                    category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                    avatarUrl = cursor.nullableString("avatar_url"),
                    displayOrder = cursor.getInt(cursor.getColumnIndexOrThrow("display_order")),
                )
            }
        }
        return result
    }

    fun replaceBlogMembers(members: List<BlogMember>) {
        require(members.isNotEmpty()) { "官网成员列表为空" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("blog_members", null, null)
            members.forEach { member ->
                val values = ContentValues().apply {
                    put("id", member.id)
                    put("name", member.name)
                    put("category", member.category)
                    put("avatar_url", member.avatarUrl)
                    put("display_order", member.displayOrder)
                    put(
                        "latest_post_at",
                        db.rawQuery(
                            "SELECT MAX(published_at) FROM blog_posts WHERE member_id = ?",
                            arrayOf(member.id),
                        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null },
                    )
                }
                db.insertOrThrow("blog_members", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Body and stored translation for the given BLOG IDs, used to build search summaries without
     * loading every row. Both are needed because a search term can match the original body or only
     * its translation.
     */
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
            limit.coerceIn(1, 500).toString(),
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

    // Message history reuses the same boundary scheme as the blog list; see syncMessagesFromServer.
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
    ): QueryFilter {
        val clauses = mutableListOf(
            "id NOT GLOB ?",
            "(text_content IS NOT NULL OR media_url IS NOT NULL)",
            "((TRIM(member_id) <> '' AND member_id = ?) OR (TRIM(member_id) = '' AND member_name = ?))",
        )
        val arguments = mutableListOf(TEST_MESSAGE_GLOB, memberKey, memberKey)
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            clauses += """
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
            val pattern = "%${escapeLike(query)}%"
            repeat(6) { arguments += pattern }
        }
        addTimeClause(clauses, arguments, "sent_at", startMillis, endMillisExclusive)
        return QueryFilter(clauses.joinToString(" AND "), arguments.toTypedArray())
    }

    /**
     * Messages and BLOGs store ISO timestamps with different offsets ("...Z" and "+09:00"), so the
     * range is compared as epoch seconds parsed by SQLite instead of as raw strings.
     */
    private fun addTimeClause(
        clauses: MutableList<String>,
        arguments: MutableList<String>,
        column: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
    ) {
        // Bound arguments arrive as TEXT, and SQLite sorts every number before every string, so the
        // parameter has to be cast explicitly or the comparison is always false.
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

    companion object {
        private const val DB_NAME = "messages.db"
        private const val DB_VERSION = 8
        private const val TEST_MESSAGE_GLOB = "test[-_]*"
        private const val MEMBER_MESSAGE_ORDER = "sent_at DESC, received_at DESC, id DESC"
        private const val BLOG_FULL_SYNC_KEY = "blog_full_sync_complete_v2"
        private const val BLOG_SYNC_HEAD_KEY = "blog_sync_head_id_v2"
        private const val MESSAGE_FULL_SYNC_KEY = "message_full_sync_complete_v1"
        private const val MESSAGE_SYNC_HEAD_KEY = "message_sync_head_id_v1"
    }
}
