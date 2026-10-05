package com.nogirelay.app.data.db

import android.database.Cursor
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage

/** Shared SQL fragments, row mappers and filters for the DAOs. */
internal object Db {
    const val TEST_MESSAGE_GLOB = "test[-_]*"

    /** A message's member: its id, or its name for rows without one. */
    const val MEMBER_KEY = "CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END"

    /** Rows that are real, displayable messages. */
    const val VISIBLE_MESSAGE = "id NOT GLOB ? AND (text_content IS NOT NULL OR media_url IS NOT NULL)"

    const val MEMBER_MESSAGE_ORDER = "sent_at DESC, received_at DESC, id DESC"
    const val MEMBER_MEDIA_ORDER = "sent_ms DESC, sent_at DESC, received_at DESC, id DESC"

    const val MAX_TRANSLATION_BATCH = 20_000

    fun canonicalBlogId(id: String): String =
        if (id.length > 1 && id.all { it in '0'..'9' }) id.trimStart('0').ifEmpty { "0" } else id

    fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    /** Limits [millisColumn] (epoch milliseconds, kept by triggers) to a time range. */
    fun addTimeClause(
        clauses: MutableList<String>,
        arguments: MutableList<String>,
        millisColumn: String,
        startMillis: Long?,
        endMillisExclusive: Long?,
    ) {
        if (startMillis != null) {
            clauses += "$millisColumn >= CAST(? AS INTEGER)"
            arguments += startMillis.toString()
        }
        if (endMillisExclusive != null) {
            clauses += "$millisColumn < CAST(? AS INTEGER)"
            arguments += endMillisExclusive.toString()
        }
    }
}

internal class QueryFilter(val selection: String, val arguments: Array<String>)

internal fun Cursor.toMessage(): RelayMessage = RelayMessage(
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

internal fun Cursor.toBlogPost(): BlogPost = BlogPost(
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

internal fun Cursor.nullableString(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

internal fun Cursor.nullableInt(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

internal inline fun <T> Cursor.readAll(row: Cursor.() -> T): List<T> =
    use { buildList { while (moveToNext()) add(row()) } }

internal fun Cursor.readCount(): Int = use { if (it.moveToFirst()) it.getInt(0) else 0 }
