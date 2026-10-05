package com.nogirelay.app.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogMemberCategories
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataInvalidationTracker
import com.nogirelay.app.data.MessageDatabase

/** The member directory (blog_members), merged with authors seen in posts. */
class BlogMemberDao internal constructor(
    private val database: MessageDatabase,
    private val invalidation: DataInvalidationTracker,
) {
    private val readable get() = database.readableDatabase
    private val writable get() = database.writableDatabase

    /** Members that have posts, in directory order, then unlisted authors by recency. */
    fun blogMembers(): List<BlogMember> {
        val result = mutableListOf<BlogMember>()
        val knownIds = mutableSetOf<String>()
        val postAvatars = postAvatarByMember()

        readable.rawQuery(
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
                val isStaff = isStaff(id, rawName)
                val category = if (isStaff) {
                    STAFF
                } else {
                    BlogMemberCategories.normalizeCategory(id, rawName, cursor.getString(cursor.getColumnIndexOrThrow("category")))
                }
                result += BlogMember(
                    id = id,
                    name = if (isStaff) STAFF else rawName,
                    category = category,
                    avatarUrl = blogAvatarUrl(cursor.nullableString("avatar_url")) ?: blogAvatarUrl(postAvatars[id]),
                    displayOrder = cursor.getInt(cursor.getColumnIndexOrThrow("display_order")),
                    graduated = !isStaff && cursor.getInt(cursor.getColumnIndexOrThrow("graduated")) == 1,
                )
            }
        }

        readable.rawQuery(
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
                if (!knownIds.add(id)) continue
                val rawName = cursor.getString(cursor.getColumnIndexOrThrow("member_name")).ifBlank { "乃木坂46" }
                val isStaff = isStaff(id, rawName)
                result += BlogMember(
                    id = id,
                    name = if (isStaff) STAFF else rawName,
                    category = if (isStaff) STAFF else BlogMemberCategories.normalizeCategory(id, rawName, null),
                    avatarUrl = blogAvatarUrl(cursor.nullableString("avatar_url")) ?: blogAvatarUrl(postAvatars[id]),
                    displayOrder = extraOrder++,
                )
            }
        }
        return result
    }

    fun markMemberGraduated(memberId: String): Boolean {
        val updated = writable.update(
            "blog_members",
            ContentValues().apply { put("graduated", 1) },
            "id = ? AND graduated = 0",
            arrayOf(memberId),
        ) > 0
        if (updated) invalidation.publish(DataChange.BLOGS)
        return updated
    }

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
        val inserted = writable.insertWithOnConflict("blog_members", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        if (inserted) invalidation.publish(DataChange.BLOGS)
        return inserted
    }

    /** Replaces the directory with the official list; graduation is sticky. */
    fun replaceBlogMembers(members: List<BlogMember>) {
        require(members.isNotEmpty()) { "官网成员列表为空" }
        database.transaction {
            val db = writable
            val before = directorySnapshot(db)
            val alreadyGraduated = db.rawQuery("SELECT id FROM blog_members WHERE graduated = 1", null)
                .readAll { getString(0) }.toSet()
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
                        db.rawQuery("SELECT MAX(published_at) FROM blog_posts WHERE member_id = ?", arrayOf(member.id))
                            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null },
                    )
                }
                val updated = db.update("blog_members", values, "id = ?", arrayOf(member.id))
                if (updated == 0) db.insertOrThrow("blog_members", null, values)
            }
            backfillBlogAvatars(db, members)
            if (directorySnapshot(db) != before) invalidation.publish(DataChange.BLOGS)
        }
    }

    private fun directorySnapshot(db: SQLiteDatabase): List<String> = db.rawQuery(
        "SELECT id || '|' || name || '|' || category || '|' || COALESCE(avatar_url, '') || '|' || " +
            "display_order || '|' || graduated FROM blog_members ORDER BY id",
        null,
    ).readAll { getString(0) }

    internal fun updateLatestPost(memberId: String, publishedAt: String) {
        if (memberId.isBlank() || publishedAt.isBlank()) return
        writable.execSQL(
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

    private fun backfillBlogAvatars(db: SQLiteDatabase, members: List<BlogMember>) {
        val avatarByMember = members
            .mapNotNull { member -> member.avatarUrl?.takeIf(String::isNotBlank)?.let { member.id to it } }
            .toMap()
        if (avatarByMember.isEmpty()) return
        val blankMembers = db.rawQuery(
            "SELECT DISTINCT member_id FROM blog_posts WHERE member_avatar_url IS NULL OR TRIM(member_avatar_url) = ''",
            null,
        ).readAll { getString(0) }
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

    private fun postAvatarByMember(): Map<String, String?> = readable.rawQuery(
        "SELECT member_id, MAX(member_avatar_url) AS avatar_url FROM blog_posts GROUP BY member_id",
        null,
    ).readAll { getString(getColumnIndexOrThrow("member_id")) to nullableString("avatar_url") }.toMap()

    private fun blogAvatarUrl(raw: String?): String? = when {
        raw.isNullOrBlank() -> null
        raw.startsWith("/") -> "https://www.nogizaka46.com$raw"
        else -> raw
    }

    private fun isStaff(id: String, rawName: String): Boolean =
        id == "10001" || id == "40003" || rawName == "乃木坂46" || rawName.contains("運営") || rawName.contains("スタッフ")

    private companion object {
        const val STAFF = "運営スタッフ"
    }
}
