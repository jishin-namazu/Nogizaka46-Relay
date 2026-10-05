package com.nogirelay.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nogirelay.app.data.db.Db

/**
 * The local SQLite store: schema, migrations and transactions. Queries and
 * writes live in the per-domain DAOs (`data/db`), which share this helper.
 */
class MessageDatabase(
    context: Context,
    private val invalidation: DataInvalidationTracker,
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
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
        createQueryAccelerators(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN translation TEXT")
            db.execSQL("ALTER TABLE messages ADD COLUMN translation_done INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {

            db.delete("messages", "id GLOB ?", arrayOf(Db.TEST_MESSAGE_GLOB))
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
        if (oldVersion < 14) createMediaRefUrlIndex(db)
        if (oldVersion < 15) createQueryAccelerators(db)
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
        createMediaRefUrlIndex(db)
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

    private fun createMediaRefUrlIndex(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_refs_url_kind ON media_refs(url, kind)")
    }

    private fun createMessageFavoriteIndex(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_messages_favorite_member ON messages(" +
                "is_favorite, CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END, " +
                "sent_at DESC, received_at DESC, id DESC)",
        )
    }

    /**
     * Version 15: columns and a table that spare the hot queries full scans.
     *
     * - `sent_ms` / `published_ms`: epoch milliseconds kept by triggers, so
     *   time filters compare an indexed integer instead of running
     *   `strftime` on every row (same conversion as before).
     * - `blog_posts.body_text`: the post's plain text, searched instead of its
     *   HTML. Written by [com.nogirelay.app.data.db.BlogDao]; older rows are
     *   filled in the background and searched by HTML until then.
     * - `member_latest`: each member's newest visible message, so the inbox
     *   no longer groups the whole message table on every new message.
     */
    private fun createQueryAccelerators(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN sent_ms INTEGER")
        db.execSQL("UPDATE messages SET sent_ms = $SENT_MS")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS messages_sent_ms_insert AFTER INSERT ON messages " +
                "BEGIN UPDATE messages SET sent_ms = $SENT_MS WHERE rowid = NEW.rowid; END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS messages_sent_ms_update AFTER UPDATE OF sent_at ON messages " +
                "BEGIN UPDATE messages SET sent_ms = $SENT_MS WHERE rowid = NEW.rowid; END",
        )

        db.execSQL("ALTER TABLE blog_posts ADD COLUMN published_ms INTEGER")
        db.execSQL("ALTER TABLE blog_posts ADD COLUMN body_text TEXT")
        db.execSQL("UPDATE blog_posts SET published_ms = $PUBLISHED_MS")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS blog_posts_published_ms_insert AFTER INSERT ON blog_posts " +
                "BEGIN UPDATE blog_posts SET published_ms = $PUBLISHED_MS WHERE rowid = NEW.rowid; END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS blog_posts_published_ms_update AFTER UPDATE OF published_at ON blog_posts " +
                "BEGIN UPDATE blog_posts SET published_ms = $PUBLISHED_MS WHERE rowid = NEW.rowid; END",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_blog_posts_published_ms ON blog_posts(published_ms)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS member_latest (
                member_key TEXT PRIMARY KEY,
                message_id TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR REPLACE INTO member_latest(member_key, message_id)
            SELECT k.member_key, (
                SELECT m.id FROM messages m
                WHERE (CASE WHEN TRIM(m.member_id) <> '' THEN m.member_id ELSE m.member_name END) = k.member_key
                  AND m.id NOT GLOB '${Db.TEST_MESSAGE_GLOB}'
                  AND (m.text_content IS NOT NULL OR m.media_url IS NOT NULL)
                ORDER BY m.sent_at DESC, m.received_at DESC, m.id DESC
                LIMIT 1
            )
            FROM (
                SELECT DISTINCT ${Db.MEMBER_KEY} AS member_key FROM messages
                WHERE ${Db.VISIBLE_MESSAGE.replace("?", "'${Db.TEST_MESSAGE_GLOB}'")}
            ) k
            """.trimIndent(),
        )
    }

    /**
     * Runs [block] in one write transaction. Changes the DAOs publish inside
     * it reach observers only after the commit; nested calls join the outer
     * transaction.
     */
    fun <T> transaction(block: () -> T): T = invalidation.batched { commit ->
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            commit()
            result
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val DB_NAME = "messages.db"
        const val DB_VERSION = 15
        const val SENT_MS = "CAST(strftime('%s', sent_at) AS INTEGER) * 1000"
        const val PUBLISHED_MS = "CAST(strftime('%s', published_at) AS INTEGER) * 1000"
    }
}
