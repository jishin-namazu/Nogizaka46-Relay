package com.nogirelay.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 数据传输成员选择器提供的一位消息作者。[directory] 为 true 时表示
 * category/order 来自官方 BLOG 成员目录而不是兜底值。
 */
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
            // 旧版本使用 test_... 形式的 ID，因此在迁移时删除这些记录。
            db.delete("messages", "id GLOB ?", arrayOf(TEST_MESSAGE_GLOB))
        }
        if (oldVersion < 4) {
            // 引入未读跟踪时，以已有的本地历史记录作为已读基线。
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
            // v7 会按源排版恢复每一条已翻译 BLOG 的换行。
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
            // 历史同步可能会把旧的直连 CDN URL 替换为
            // 受保护的 relay URL，同时不重置播放状态。
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
     * 按 member_key 分组，返回每个已订阅成员的最新消息。
     * 与 [latest] 不同，这可确保低频成员不会被截断在收件箱之外。
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
        nickname: String = "",
    ): List<RelayMessage> {
        val result = mutableListOf<RelayMessage>()
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
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
        nickname: String = "",
    ): Int {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
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
        nickname: String = "",
    ): Int {
        val filter = memberFilter(memberKey, searchQuery, startMillis, endMillisExclusive, nickname)
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

    /** 删除临时测试消息，并返回它们的 ID 供清理通知使用。 */
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
            limit.coerceIn(1, MAX_TRANSLATION_BATCH).toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
        }
        return result
    }

    /**
     * 与 [pendingTranslations] 同一套筛选，但只取这批 id：同步刚写入的记录用它做定向翻译，
     * 这样自动翻译不会顺带把历史积压一起翻掉。分块查询避免 SQLite 的变量上限。
     */
    fun pendingTranslationsByIds(ids: Collection<String>): List<RelayMessage> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<RelayMessage>()
        ids.toSet().toList().chunked(SQL_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.query(
                "messages",
                null,
                "id IN ($placeholders) AND id NOT GLOB ? AND translation_done = 0 " +
                    "AND text_content IS NOT NULL AND TRIM(text_content) <> ''",
                (chunk + TEST_MESSAGE_GLOB).toTypedArray(),
                null,
                null,
                "sent_at DESC, received_at DESC",
                MAX_TRANSLATION_BATCH.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) result += cursor.toMessage()
            }
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

    /**
     * 重置所有已翻译消息，以便整段历史可以重新翻译。用于译文不再
     * 固化设备专属昵称之后的一次性处理，让旧行获得 "%%%"
     * 占位符，使导出结果可移植。返回被重置的行数。
     */
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

    /**
     * 在一个 SQLite 事务中运行 [block]。归档导入器通过它批量写入，
     * 使数千条记录的恢复保持高效。
     */
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

    /** 用于导出成员选择器的去重消息作者，并用 BLOG 成员目录补全。 */
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

    /**
     * 指定作者消息的一页，排序方式与成员时间线一致。导出器通过它分页，
     * 而不是一直持有游标，从而使整个扫描的内存占用保持有界。
     */
    fun messagePageForMembers(memberKeys: Collection<String>, limit: Int, offset: Int): List<RelayMessage> {
        if (memberKeys.isEmpty() || limit <= 0) return emptyList()
        val placeholders = memberKeys.joinToString(",") { "?" }
        val keyExpression = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val result = mutableListOf<RelayMessage>()
        readableDatabase.rawQuery(
            """
            SELECT * FROM messages
            WHERE id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)
              AND $keyExpression IN ($placeholders)
            ORDER BY sent_at DESC, received_at DESC, id DESC
            LIMIT $safeLimit OFFSET $safeOffset
            """.trimIndent(),
            arrayOf(TEST_MESSAGE_GLOB, *memberKeys.toTypedArray()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toMessage()
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

    /** 供导出器使用的一页完整 BLOG 行（包含正文与译文）。 */
    fun blogPageForMembers(memberIds: Collection<String>, limit: Int, offset: Int): List<BlogPost> {
        if (memberIds.isEmpty() || limit <= 0) return emptyList()
        val placeholders = memberIds.joinToString(",") { "?" }
        val safeLimit = limit.coerceAtLeast(1)
        val safeOffset = offset.coerceAtLeast(0)
        val result = mutableListOf<BlogPost>()
        readableDatabase.rawQuery(
            "SELECT * FROM blog_posts WHERE member_id IN ($placeholders) " +
                "ORDER BY published_at DESC, id DESC LIMIT $safeLimit OFFSET $safeOffset",
            memberIds.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.toBlogPost()
        }
        return result
    }

    /**
     * 按从新到旧的顺序流式读取指定作者的消息，对每一行调用 [action]。
     *
     * 单次查询胜过反复执行 LIMIT/OFFSET 分页：每一页都让 SQLite 重新遍历并丢弃
     * offset（WHERE 子句推导出成员 key，因此没有索引能跳过它），这正是分页扫描
     * 在每个分页边界都会明显卡顿的原因。
     */
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

    /** [forEachMessageForMembers] 在 BLOG 上的对应实现。 */
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

    /**
     * 归档导入器使用的插入：绝不触碰已有行，这与 [insert] 不同，后者在冲突时会
     * 刷新媒体 URL。`received_at` 保持为 0，使导入的历史在
     * `sent_at DESC, received_at DESC` 排序下保持原有位置，而不是跳到最前。
     */
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
            put("translation", message.translation)
            put("translation_done", if (message.translationDone) 1 else 0)
            put("received_at", 0L)
        }
        return writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    /**
     * 归档导入器使用的插入。它不会修改已有行；[upsertBlog]
     * 则会在正文变化时覆盖并清除译文。
     */
    fun insertBlogIfAbsent(post: BlogPost, isUnread: Boolean = false): Boolean {
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
        return inserted
    }

    /**
     * 仅在行中没有消息译文时才填入，这样导入永远不会替换
     * 本设备已生成的译文。返回是否更新了某一行。
     */
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

    /** [backfillMessageTranslation] 在 BLOG 上的对应实现。 */
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

    /**
     * 用归档中的值刷新已有消息的链接列，使迁移了主机的 relay 无需任何
     * 媒体往返也能继续解析。内容、阅读状态和译文都不受影响，
     * 且只有链接确实不同时才将该行计为已刷新。
     */
    fun refreshImportedLinks(id: String, links: Map<String, String>): Boolean =
        updateLinksIfDifferent("messages", id, links)

    /**
     * BLOG 上的对应实现。BLOG 的内联图片地址存放在 `body_html` 中，因此刷新这些
     * 链接就意味着替换正文；当正文确实变化时，其译文不再对应，
     * 因此会像 [upsertBlog] 在普通同步时那样被丢弃。
     *
     * `member_id` / `member_name` 也会被刷新：当归档现在携带官方成员
     * 编号（而非爬虫的 slug id）时，必须能把已导入的帖子移到
     * 官方成员上，否则新成员行的卒業标记与期数将永远不会被使用。
     */
    fun refreshImportedBlogLinks(id: String, links: Map<String, String>): Boolean {
        if (links.isEmpty()) return false
        val refreshedBody = links["body_html"]
        val bodyChanged = refreshedBody != null && currentBodyHtml(id) != refreshedBody
        val refreshed = updateLinksIfDifferent("blog_posts", id, links)
        if (refreshed && bodyChanged) {
            val values = ContentValues().apply {
                put("translation", null as String?)
                put("translation_done", 0)
            }
            writableDatabase.update("blog_posts", values, "id = ?", arrayOf(id))
        }
        return refreshed
    }

    private fun currentBodyHtml(id: String): String? = readableDatabase.query(
        "blog_posts",
        arrayOf("body_html"),
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** 只写入给定的列，且仅当其中至少一列与已存储的值不同时才写入。 */
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

    /**
     * 卒業标记是单向的：官网接口只要返回过一次 graduation=YES，本地就不再清除该标记
     * （名册刷新、归档导入都不会回退），这样"后续发现成员卒業"时标记一定补得上。
     * @return 是否真的写入了标记（原本已是卒業生时为 false）。
     */
    fun markMemberGraduated(memberId: String): Boolean =
        writableDatabase.update(
            "blog_members",
            ContentValues().apply { put("graduated", 1) },
            "id = ? AND graduated = 0",
            arrayOf(memberId),
        ) > 0

    /** 添加归档携带的成员目录行，且不影响已有行。 */
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
        val knownIds = mutableSetOf<String>()
        // 成员表头像为空时回落到帖子表的头像；博客列表读的就是后者。
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
                    // 兼容层：早先导入的行仍然写着历史分类「研究生」，读取时折回 2期生。
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

        // 联合查询已存在文章但不在官网当前活跃名册中的成员与接力博客（如运营Staff、3期生、4期生、新4期生、5期生、6期生）
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

    /** 成员头像的绝对地址；空值返回 null。 */
    private fun blogAvatarUrl(raw: String?): String? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith("/") -> "https://www.nogizaka46.com$raw"
        else -> raw
    }

    /**
     * blog_posts 里每个成员记着的头像。博客列表读的就是这一列，所以筛选要跟它保持一致；
     * 成员表里没有头像的行（期别集体帐号）靠它兜底。
     */
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
            // 已标记为卒業生的成员 id：官网名册可能随时把已卒業成员移出返回列表，
            // 但卒業不可逆，所以刷新时保留标记而不是跟着名册一起消失。
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
                // 逐行 upsert 而不是整表删除：归档导入的成员目录行（官网名册未必再返回）保留下来，
                // 卒業标记与自定义分类因此不会在每次同步后丢失。
                val updated = db.update("blog_members", values, "id = ?", arrayOf(member.id))
                if (updated == 0) db.insertOrThrow("blog_members", null, values)
            }
            backfillBlogAvatars(db, members)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * BLOG 列表读的是 `blog_posts.member_avatar_url`（不是成员表），而归档导入的行可能没有头像
     * （例如 umezawa 归档的 manifest 就没带 avatar_url）。官方名册每次同步都会重新拉一遍，
     * 顺手把官方头像补到这些空行上，已导入的博客不必重新导入也能显示头像。
     */
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

    /**
     * 给定 BLOG ID 的正文与已存译文，用于在不加载每一行的情况下构建搜索
     * 摘要。两者都需要，因为搜索词可能匹配原始正文，也可能只匹配其译文。
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

    /** [pendingBlogTranslations] 的定向版本，只取同步新写入的这批 id。 */
    fun pendingBlogTranslationsByIds(ids: Collection<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        ids.toSet().toList().chunked(SQL_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDatabase.query(
                "blog_posts",
                arrayOf("id"),
                "id IN ($placeholders) AND translation_done = 0 AND TRIM(body_html) <> ''",
                chunk.toTypedArray(),
                null,
                null,
                "published_at DESC, received_at DESC, id DESC",
                MAX_TRANSLATION_BATCH.toString(),
            ).use { cursor ->
                while (cursor.moveToNext()) result += cursor.getString(0)
            }
        }
        return result
    }

    fun markBlogForRetranslation(id: String) {
        val values = ContentValues().apply {
            put("translation", null as String?)
            put("translation_done", 0)
        }
        writableDatabase.update("blog_posts", values, "id = ?", arrayOf(id))
    }

    /** [markAllMessagesForRetranslation] 的 BLOG 版本。返回被重置的行数。 */
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

    // 消息历史沿用与博客列表相同的边界方案，见 syncMessagesFromServer。
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
    ): QueryFilter {
        val clauses = mutableListOf(
            "id NOT GLOB ?",
            "(text_content IS NOT NULL OR media_url IS NOT NULL)",
            "((TRIM(member_id) <> '' AND member_id = ?) OR (TRIM(member_id) = '' AND member_name = ?))",
        )
        val arguments = mutableListOf(TEST_MESSAGE_GLOB, memberKey, memberKey)
        val query = searchQuery.trim()
        if (query.isNotEmpty()) {
            // 库里存的是「%%%」昵称占位符，所以搜索用户自己的昵称也要匹配占位符形式。
            // 这里按字面量转义：未转义的 %% 会变成匹配一切的通配符。
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

    /**
     * 消息与 BLOG 存的是带不同偏移量的 ISO 时间戳（"...Z" 与 "+09:00"），所以时间范围按
     * SQLite 解析出的 epoch 秒比较，而不是按原始字符串比较。
     */
    private fun addTimeClause(
        clauses: MutableList<String>,
        arguments: MutableList<String>,
        column: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
    ) {
        // 绑定参数以 TEXT 传入，而 SQLite 把数字排在所有字符串之前，所以必须显式 CAST，
        // 否则比较结果恒为 false。
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
        private const val DB_VERSION = 9
        private const val TEST_MESSAGE_GLOB = "test[-_]*"

        /** 批量重译单次上限；常规入队仍然只取小分页。 */
        const val MAX_TRANSLATION_BATCH = 20_000
        /** SQLite 变量上限之下的安全分块大小，用于 id IN (...) 查询。 */
        private const val SQL_CHUNK = 500
        private const val MEMBER_MESSAGE_ORDER = "sent_at DESC, received_at DESC, id DESC"
        private const val BLOG_FULL_SYNC_KEY = "blog_full_sync_complete_v2"
        private const val BLOG_SYNC_HEAD_KEY = "blog_sync_head_id_v2"
        private const val MESSAGE_FULL_SYNC_KEY = "message_full_sync_complete_v1"
        private const val MESSAGE_SYNC_HEAD_KEY = "message_sync_head_id_v1"
    }
}
