package com.nogirelay.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.nogirelay.app.blog.BlogContentParser
import com.nogirelay.app.data.BlogImportMerge
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.BlogSearchSource
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataInvalidationTracker
import com.nogirelay.app.data.MessageDatabase

/** Blog posts: list pages, search, read state and translations. */
class BlogDao internal constructor(
    private val database: MessageDatabase,
    private val members: BlogMemberDao,
    private val mediaRefs: MediaRefDao,
    private val invalidation: DataInvalidationTracker,
) {
    private val readable get() = database.readableDatabase
    private val writable get() = database.writableDatabase

    /** Inserts [post] or refreshes the stored copy; true when it is new. */
    fun upsertBlog(post: BlogPost, isUnread: Boolean = false): Boolean {
        val id = Db.canonicalBlogId(post.id)
        val values = post.toValues(id, isUnread || post.isUnread)
        val inserted = writable.insertWithOnConflict("blog_posts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        members.updateLatestPost(post.memberId, post.publishedAt)
        if (inserted) {
            // Parsed only for new posts; re-synced known posts skip the work.
            saveBodyText(id, post.bodyHtml)
            mediaRefs.refreshForBlog(id)
            invalidation.publish(DataChange.BLOGS)
            return true
        }

        val existingBody = readable.query("blog_posts", arrayOf("body_html"), "id = ?", arrayOf(id), null, null, null, "1")
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else "" }
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
                if (existingBody != post.bodyHtml) put("body_text", plainText(post.bodyHtml))
                if (existingBody != post.bodyHtml &&
                    BlogContentParser.bodyTextShape(existingBody) != BlogContentParser.bodyTextShape(post.bodyHtml)
                ) {
                    put("translation", null as String?)
                    put("translation_done", 0)
                }
            }
        }
        if (update.size() > 0 && writable.update("blog_posts", update, "id = ? AND (${differs(update)})", arrayOf(id, *differsArgs(update))) > 0) {
            mediaRefs.refreshForBlog(id)
            invalidation.publish(DataChange.BLOG_ROWS, setOf(id))
        }
        return false
    }

    fun insertBlogIfAbsent(post: BlogPost, isUnread: Boolean = false): Boolean {
        val id = Db.canonicalBlogId(post.id)
        val values = post.toValues(id, isUnread)
        val inserted = writable.insertWithOnConflict("blog_posts", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        members.updateLatestPost(post.memberId, post.publishedAt)
        mediaRefs.refreshForBlog(id)
        if (inserted) {
            saveBodyText(id, post.bodyHtml)
            invalidation.publish(DataChange.BLOGS)
        }
        return inserted
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
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        return readable.query(
            false,
            "blog_posts",
            SUMMARY_COLUMNS,
            filter.selection,
            filter.arguments,
            null,
            null,
            if (oldestFirst) "published_at ASC, id ASC" else "published_at DESC, id DESC",
            "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}",
            cancellationSignal,
        ).readAll { toSummary() }
    }

    /** Current list rows for [ids], to patch a loaded list in place. */
    fun blogSummariesByIds(ids: Collection<String>): List<BlogSummary> {
        if (ids.isEmpty()) return emptyList()
        return ids.toList().chunked(500).flatMap { chunk ->
            readable.query(
                "blog_posts",
                SUMMARY_COLUMNS,
                "id IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
                null,
                null,
                null,
            ).readAll { toSummary() }
        }
    }

    private fun android.database.Cursor.toSummary() = BlogSummary(
        id = getString(getColumnIndexOrThrow("id")),
        memberId = getString(getColumnIndexOrThrow("member_id")),
        memberName = getString(getColumnIndexOrThrow("member_name")),
        memberAvatarUrl = nullableString("member_avatar_url"),
        title = getString(getColumnIndexOrThrow("title")),
        imageUrl = nullableString("image_url"),
        publishedAt = getString(getColumnIndexOrThrow("published_at")),
        isUnread = getInt(getColumnIndexOrThrow("is_unread")) == 1,
        translatedTitle = nullableString("translation")?.let(::translatedTitleFromJson),
    )

    /**
     * Matching posts per publishing month ("yyyy-MM" as published), in the
     * same order as [blogSummaries]; drives the blog list's month scroller.
     */
    fun blogMonthCounts(
        memberIds: Set<String>? = null,
        searchQuery: String = "",
        oldestFirst: Boolean = false,
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        cancellationSignal: CancellationSignal? = null,
    ): List<Pair<String, Int>> {
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        return readable.query(
            false,
            "blog_posts",
            arrayOf("substr(published_at, 1, 7) AS month", "COUNT(*)"),
            filter.selection,
            filter.arguments,
            "month",
            null,
            if (oldestFirst) "month ASC" else "month DESC",
            null,
            cancellationSignal,
        ).readAll { getString(0).orEmpty() to getInt(1) }
    }

    fun countBlogs(
        memberIds: Set<String>? = null,
        searchQuery: String = "",
        startMillis: Long? = null,
        endMillisExclusive: Long? = null,
        cancellationSignal: CancellationSignal? = null,
    ): Int {
        val filter = blogFilter(memberIds, searchQuery, startMillis, endMillisExclusive)
        return readable.query(
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
        ).readCount()
    }

    fun blogSearchSources(ids: List<String>): List<BlogSearchSource> {
        if (ids.isEmpty()) return emptyList()
        return readable.query(
            "blog_posts",
            arrayOf("id", "body_html", "translation"),
            "id IN (${ids.joinToString(",") { "?" }})",
            ids.toTypedArray(),
            null,
            null,
            null,
        ).readAll {
            BlogSearchSource(
                id = getString(getColumnIndexOrThrow("id")),
                bodyHtml = getString(getColumnIndexOrThrow("body_html")),
                translation = nullableString("translation"),
            )
        }
    }

    fun findBlog(id: String): BlogPost? =
        readable.query("blog_posts", null, "id = ?", arrayOf(id), null, null, null, "1")
            .readAll { toBlogPost() }.firstOrNull()

    /** Position of [blogId] in the default (newest first) list. */
    fun blogRank(blogId: String): Int? {
        val post = findBlog(blogId) ?: return null
        val publishedAt = post.publishedAt
        return readable.rawQuery(
            "SELECT COUNT(*) FROM blog_posts WHERE published_at > ? OR (published_at = ? AND id > ?)",
            arrayOf(publishedAt, publishedAt, blogId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
    }

    fun countBlogsForMembers(memberIds: Collection<String>): Int {
        if (memberIds.isEmpty()) return 0
        val placeholders = memberIds.joinToString(",") { "?" }
        return readable.rawQuery(
            "SELECT COUNT(*) FROM blog_posts WHERE member_id IN ($placeholders)",
            memberIds.toTypedArray(),
        ).readCount()
    }

    fun forEachBlogForMembers(memberIds: Collection<String>, action: (BlogPost) -> Unit) {
        if (memberIds.isEmpty()) return
        val placeholders = memberIds.joinToString(",") { "?" }
        readable.rawQuery(
            "SELECT * FROM blog_posts WHERE member_id IN ($placeholders) ORDER BY published_at DESC, id DESC",
            memberIds.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) action(cursor.toBlogPost())
        }
    }

    fun saveBlogTranslation(id: String, translation: String?) {
        val values = ContentValues().apply {
            put("translation", translation?.takeIf { it.isNotBlank() })
            put("translation_done", 1)
        }
        if (writable.update("blog_posts", values, "id = ?", arrayOf(id)) > 0) {
            invalidation.publish(DataChange.BLOG_ROWS, setOf(id))
        }
    }

    fun pendingTranslationIds(afterId: String? = null, limit: Int = 24): List<String> {
        val selection = "translation_done = 0 AND body_html IS NOT NULL AND TRIM(body_html) <> ''" +
            (if (afterId != null) " AND id > ?" else "")
        val args = listOfNotNull(afterId).toTypedArray()
        return readable.query("blog_posts", arrayOf("id"), selection, args, null, null, "id ASC", limit.coerceIn(1, 100).toString())
            .readAll { getString(0) }
    }

    fun pendingBlogTranslations(limit: Int = 100): List<String> = readable.query(
        "blog_posts",
        arrayOf("id"),
        "translation_done = 0 AND TRIM(body_html) <> ''",
        null,
        null,
        null,
        "published_at DESC, received_at DESC, id DESC",
        limit.coerceIn(1, Db.MAX_TRANSLATION_BATCH).toString(),
    ).readAll { getString(0) }

    fun markBlogForRetranslation(id: String) {
        if (writable.update("blog_posts", clearedTranslation(), "id = ?", arrayOf(id)) > 0) {
            invalidation.publish(DataChange.BLOG_ROWS, setOf(id))
        }
    }

    fun markAllBlogsForRetranslation(): Int {
        val updated = writable.update("blog_posts", clearedTranslation(), "TRIM(body_html) <> ''", null)
        if (updated > 0) invalidation.publish(DataChange.BLOG_ROWS)
        return updated
    }

    fun backfillBlogTranslation(id: String, translation: String): Boolean {
        if (translation.isBlank()) return false
        val values = ContentValues().apply {
            put("translation", translation)
            put("translation_done", 1)
        }
        val updated = writable.update(
            "blog_posts",
            values,
            "id = ? AND (translation IS NULL OR TRIM(translation) = '' OR translation_done = 0)",
            arrayOf(id),
        ) > 0
        if (updated) invalidation.publish(DataChange.BLOG_ROWS, setOf(id))
        return updated
    }

    fun refreshImportedBlogLinks(id: String, links: Map<String, String>): Boolean {
        val canonical = Db.canonicalBlogId(id)
        val existing = findBlog(canonical) ?: return false
        val safeLinks = BlogImportMerge.linkUpdates(existing, links)
        if (safeLinks.isEmpty()) return false
        val values = ContentValues().apply { safeLinks.forEach { (column, value) -> put(column, value) } }
        val updated = writable.update(
            "blog_posts",
            values,
            "id = ? AND (${differs(values)})",
            arrayOf(canonical, *differsArgs(values)),
        ) > 0
        if (updated) {
            mediaRefs.refreshForBlog(canonical)
            invalidation.publish(DataChange.BLOG_ROWS, setOf(canonical))
        }
        return updated
    }

    fun countUnreadBlogs(): Int =
        readable.query("blog_posts", arrayOf("COUNT(*)"), "is_unread = 1", null, null, null, null).readCount()

    fun markBlogRead(id: String): Int {
        val values = ContentValues().apply { put("is_unread", 0) }
        val updated = writable.update("blog_posts", values, "id = ? AND is_unread = 1", arrayOf(id))
        if (updated > 0) invalidation.publish(DataChange.BLOG_READ, setOf(id))
        return updated
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
                    OR COALESCE(body_text, body_html) LIKE ? ESCAPE '\'
                    OR COALESCE(translation, '') LIKE ? ESCAPE '\'
                    OR member_name LIKE ? ESCAPE '\'
                    OR published_at LIKE ? ESCAPE '\'
                )
            """.trimIndent()
            val pattern = "%${Db.escapeLike(query)}%"
            repeat(5) { arguments += pattern }
        }
        Db.addTimeClause(clauses, arguments, "published_ms", startMillis, endMillisExclusive)
        return QueryFilter(
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ") ?: "1",
            arguments = arguments.toTypedArray(),
        )
    }

    private fun translatedTitleFromJson(serialized: String): String? = runCatching {
        org.json.JSONArray(serialized).optString(0).takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        val SUMMARY_COLUMNS = arrayOf(
            "id", "member_id", "member_name", "member_avatar_url", "title", "image_url", "published_at", "is_unread", "translation",
        )
    }

    private fun clearedTranslation() = ContentValues().apply {
        put("translation", null as String?)
        put("translation_done", 0)
    }

    /** "Any of these columns would change" for an UPDATE writing [values]. */
    private fun differs(values: ContentValues): String =
        values.keySet().joinToString(" OR ") { column ->
            if (values.get(column) == null) "$column IS NOT NULL" else "CAST($column AS TEXT) IS NOT ?"
        }

    private fun differsArgs(values: ContentValues): Array<String> =
        values.keySet().mapNotNull { column -> values.get(column)?.toString() }.toTypedArray()

    /** Posts whose plain text is not stored yet, for the background backfill. */
    fun postsWithoutBodyText(limit: Int): List<Pair<String, String>> = readable.query(
        "blog_posts",
        arrayOf("id", "body_html"),
        "body_text IS NULL",
        null,
        null,
        null,
        null,
        limit.toString(),
    ).readAll { getString(0) to getString(1) }

    /** Stores search text without announcing a change: nothing visible differs. */
    fun saveBodyText(id: String, html: String) {
        writable.update(
            "blog_posts",
            ContentValues().apply { put("body_text", plainText(html)) },
            "id = ? AND body_html = ?",
            arrayOf(id, html),
        )
    }

    private fun plainText(html: String): String =
        if (html.isBlank()) "" else runCatching { BlogContentParser.plainText(BlogContentParser.blocks(html)) }.getOrDefault(html)

    private fun BlogPost.toValues(id: String, isUnread: Boolean) = ContentValues().apply {
        put("id", id)
        put("member_id", memberId)
        put("member_name", memberName)
        put("member_avatar_url", memberAvatarUrl)
        put("title", title)
        put("body_html", bodyHtml)
        put("image_url", imageUrl)
        put("published_at", publishedAt)
        put("post_url", postUrl)
        put("translation", translation)
        put("translation_done", if (translationDone) 1 else 0)
        put("is_unread", if (isUnread) 1 else 0)
        put("received_at", System.currentTimeMillis())
    }
}
