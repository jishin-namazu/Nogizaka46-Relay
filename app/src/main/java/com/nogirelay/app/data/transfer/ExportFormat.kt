package com.nogirelay.app.data.transfer

import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.MediaRefs
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** 导出覆盖的内容族。每个界面各自拥有一种类型，所以任何导出都不会混用它们。 */
enum class ExportKind(val wire: String, val label: String, val entryName: String, val fileStem: String) {
    MESSAGES("messages", "消息", "data/messages.jsonl", "messages"),
    BLOGS("blogs", "博客", "data/blogs.jsonl", "blogs");

    companion object {
        fun fromWire(value: String): ExportKind? = values().firstOrNull { it.wire == value }
    }
}

/** 导出携带的一行成员名册，使期别分组和头像能在恢复后保留。 */
data class ManifestMember(
    val id: String,
    val name: String,
    val category: String,
    val avatarUrl: String?,
    val displayOrder: Int,
    val directory: Boolean,
    /** 当官方名册将该成员标记为毕业后为 true，使 卒業 标签能在恢复后保留。 */
    val graduated: Boolean = false,
)

/** 记录引用的一份媒体文件。[path] 是存放其字节的 ZIP 条目。 */
data class MediaRef(val role: String, val url: String, val path: String)

/**
 * 媒体候选：唯一实现已移到 [com.nogirelay.app.data.MediaRefs]，这里保留类型别名，
 * 导出、补齐等既有调用点因此无需改动。
 */
typealias MediaCandidate = com.nogirelay.app.data.MediaCandidate

data class ExportManifest(
    val formatVersion: Int,
    val appVersionName: String,
    val appVersionCode: Int,
    val exportedAt: String,
    val kind: ExportKind,
    val includesMedia: Boolean,
    val includesTranslations: Boolean,
    val members: List<ManifestMember>,
)

/**
 * [DataExporter] 与 [DataImporter] 共享的磁盘格式契约。
 *
 * 布局（manifest 在最前，因此预览无需遍历载荷）：
 *
 *     manifest.json          # 很小，在写入载荷之前就完全已知
 *     data/messages.jsonl    # 或 data/blogs.jsonl，每行一个 JSON 对象
 *     media/<sha256>.<ext>   # 内容寻址，因此共享媒体只存一次
 *     data/skipped.jsonl     # 可选：被引用但未在本地缓存的媒体
 */
object ExportFormat {
    const val FORMAT = "nogirelay-export"
    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val SKIPPED_ENTRY = "data/skipped.jsonl"
    const val MEDIA_PREFIX = "media/"

    /** 防止导入遭受恶意归档攻击：条目总数与每个条目的解码后大小。 */
    const val MAX_ENTRIES = 200_000
    const val MAX_ENTRY_BYTES = 100L * 1024L * 1024L

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private val displayTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun isoNow(): String = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun fileNameTimestamp(): String = OffsetDateTime.now().format(timestampFormat)

    /**
     * 为 UI 渲染 manifest 时间戳。偏移量按原样保留，因此导出显示的是
     * 导出设备的本地时间；无法解析的值原样传递。
     */
    fun displayTimestamp(iso: String): String = runCatching {
        OffsetDateTime.parse(iso).format(displayTimestampFormat)
    }.getOrDefault(iso)

    fun manifestToJson(manifest: ExportManifest): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("formatVersion", manifest.formatVersion)
        put("appVersionName", manifest.appVersionName)
        put("appVersionCode", manifest.appVersionCode)
        put("exportedAt", manifest.exportedAt)
        put("kind", manifest.kind.wire)
        put("includesMedia", manifest.includesMedia)
        put("includesTranslations", manifest.includesTranslations)
        put(
            "members",
            JSONArray().apply {
                manifest.members.forEach { member ->
                    put(
                        JSONObject().apply {
                            put("id", member.id)
                            put("name", member.name)
                            put("category", member.category)
                            putNullable("avatar_url", member.avatarUrl)
                            put("display_order", member.displayOrder)
                            put("directory", member.directory)
                            put("graduated", member.graduated)
                        },
                    )
                }
            },
        )
    }

    /** @throws IllegalArgumentException 当载荷不是我们能读取的 Nogi Relay 导出时。 */
    fun manifestFromJson(json: JSONObject): ExportManifest {
        require(json.optString("format") == FORMAT) { "不是 Nogi Relay 导出的归档文件" }
        val version = json.optInt("formatVersion", 0)
        require(version in 1..FORMAT_VERSION) {
            "归档格式版本 " + version + " 不受支持（当前支持至 " + FORMAT_VERSION + "），请先升级 App"
        }
        val kind = ExportKind.fromWire(json.optString("kind"))
            ?: throw IllegalArgumentException("归档缺少有效的内容类型")
        val members = mutableListOf<ManifestMember>()
        val array = json.optJSONArray("members") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            members += ManifestMember(
                id = id,
                name = item.optString("name").trim().ifBlank { id },
                category = item.optString("category").trim().ifBlank { "其他" },
                avatarUrl = item.stringOrNull("avatar_url"),
                displayOrder = item.optInt("display_order", 10_000),
                directory = item.optBoolean("directory", false),
                graduated = item.optBoolean("graduated", false),
            )
        }
        return ExportManifest(
            formatVersion = version,
            appVersionName = json.optString("appVersionName"),
            appVersionCode = json.optInt("appVersionCode", 0),
            exportedAt = json.optString("exportedAt"),
            kind = kind,
            includesMedia = json.optBoolean("includesMedia", false),
            includesTranslations = json.optBoolean("includesTranslations", true),
            members = members,
        )
    }

    fun messageToJson(
        message: RelayMessage,
        media: List<MediaRef>,
        includeTranslations: Boolean,
    ): JSONObject = JSONObject().apply {
        put("id", message.id)
        put("member_id", message.memberId)
        put("member_name", message.memberName)
        putNullable("member_avatar_url", message.memberAvatarUrl)
        putNullable("phone_image_url", message.phoneImageUrl)
        put("type", message.type.name)
        putNullable("text", message.text)
        putNullable("media_url", message.mediaUrl)
        putNullable("thumbnail_url", message.thumbnailUrl)
        putNullable("duration_seconds", message.durationSeconds)
        put("sent_at", message.sentAt)
        putNullable("incoming_call_from", message.incomingCallFrom)
        putNullable("ringtone_url", message.ringtoneUrl)
        put("is_played", message.isPlayed)
        if (includeTranslations) {
            putNullable("translation", message.translation)
            put("translation_done", message.translationDone)
        }
        put("media", mediaToJson(media))
    }

    fun jsonToMessage(json: JSONObject): RelayMessage = RelayMessage(
        id = json.optString("id").trim().ifBlank { throw IllegalArgumentException("消息缺少 id") },
        memberId = json.optString("member_id").trim(),
        memberName = json.optString("member_name").trim().ifBlank { "乃木坂46" },
        memberAvatarUrl = json.stringOrNull("member_avatar_url"),
        phoneImageUrl = json.stringOrNull("phone_image_url"),
        type = MessageType.fromWire(json.optString("type")),
        text = json.stringOrNull("text"),
        mediaUrl = json.stringOrNull("media_url"),
        thumbnailUrl = json.stringOrNull("thumbnail_url"),
        durationSeconds = json.optInt("duration_seconds", -1).takeIf { it >= 0 },
        sentAt = json.optString("sent_at").trim(),
        incomingCallFrom = json.stringOrNull("incoming_call_from"),
        ringtoneUrl = json.stringOrNull("ringtone_url"),
        isPlayed = json.optBoolean("is_played", false),
        translation = json.stringOrNull("translation"),
        translationDone = json.optBoolean("translation_done", false),
        // 已读状态是设备本地的：导入绝不能重新唤起未读角标。
        isUnread = false,
    )

    fun blogToJson(
        post: BlogPost,
        media: List<MediaRef>,
        includeTranslations: Boolean,
    ): JSONObject = JSONObject().apply {
        put("id", post.id)
        put("member_id", post.memberId)
        put("member_name", post.memberName)
        putNullable("member_avatar_url", post.memberAvatarUrl)
        put("title", post.title)
        put("body_html", post.bodyHtml)
        putNullable("image_url", post.imageUrl)
        put("published_at", post.publishedAt)
        put("post_url", post.postUrl)
        if (includeTranslations) {
            putNullable("translation", post.translation)
            put("translation_done", post.translationDone)
        }
        put("media", mediaToJson(media))
    }

    fun jsonToBlog(json: JSONObject): BlogPost = BlogPost(
        id = json.optString("id").trim().ifBlank { throw IllegalArgumentException("博客缺少 id") },
        memberId = json.optString("member_id").trim(),
        memberName = json.optString("member_name").trim().ifBlank { "乃木坂46" },
        memberAvatarUrl = json.stringOrNull("member_avatar_url"),
        title = json.optString("title"),
        bodyHtml = json.optString("body_html"),
        imageUrl = json.stringOrNull("image_url"),
        publishedAt = json.optString("published_at").trim(),
        postUrl = json.optString("post_url").trim(),
        translation = json.stringOrNull("translation"),
        translationDone = json.optBoolean("translation_done", false),
        isUnread = false,
    )

    /** 归档记录归属的成员 key；与媒体引用表同口径，见 [MediaRefs.messageMemberKey]。 */
    fun messageMemberKey(message: RelayMessage): String = MediaRefs.messageMemberKey(message)

    /** BLOG 的成员 key；与媒体引用表同口径，见 [MediaRefs.blogMemberKey]。 */
    fun blogMemberKey(post: BlogPost): String = MediaRefs.blogMemberKey(post)

    /**
     * 归档里**显式写出**的链接列。规则：缺键或 JSON null ＝ 归档没带这个信息，本地值不动；
     * 显式空串 ＝ 归档就是要清空它，导入重复记录时照写（空值覆盖）。
     */
    fun messageLinksFrom(json: JSONObject): Map<String, String> = explicitColumns(
        json,
        listOf("member_avatar_url", "phone_image_url", "media_url", "thumbnail_url", "ringtone_url"),
    )

    fun blogLinksFrom(json: JSONObject): Map<String, String> {
        val columns = explicitColumns(json, listOf("image_url", "post_url", "member_avatar_url"))
        // body_html 里内嵌图片地址，算链接列；但正文内容不能被空值抹掉，所以只有非空才覆盖。
        json.optString("body_html").takeIf(String::isNotBlank)?.let { columns["body_html"] = it }
        // 成员身份不是链接：空值会让文章失去归属，同样只在非空时覆盖。
        listOf("member_id", "member_name").forEach { key ->
            json.optString(key).trim().takeIf(String::isNotBlank)?.let { columns[key] = it }
        }
        return columns
    }

    private fun explicitColumns(json: JSONObject, keys: List<String>): MutableMap<String, String> {
        val columns = linkedMapOf<String, String>()
        keys.forEach { key ->
            // isNull() 对"缺键"也返回 true，所以这里只收显式存在且不是 JSON null 的值；
            // 字面量 "null" 是脏数据（旧格式用字符串表示空），同样不写。
            if (json.isNull(key)) return@forEach
            val value = json.optString(key).trim()
            if (value != "null") columns[key] = value
        }
        return columns
    }

    fun mediaRefsFrom(json: JSONObject): List<MediaRef> {
        val array = json.optJSONArray("media") ?: return emptyList()
        val refs = mutableListOf<MediaRef>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val url = item.optString("url").trim()
            val path = item.optString("path").trim()
            if (url.isEmpty() || path.isEmpty()) continue
            refs += MediaRef(role = item.optString("role").trim().ifBlank { "media" }, url = url, path = path)
        }
        return refs
    }

    private fun mediaToJson(media: List<MediaRef>): JSONArray = JSONArray().apply {
        media.forEach { ref ->
            put(
                JSONObject().apply {
                    put("role", ref.role)
                    put("url", ref.url)
                    put("path", ref.path)
                },
            )
        }
    }

    /** 消息的媒体候选；规则与媒体引用表共用，实现在 [MediaRefs.candidates]。 */
    fun mediaCandidates(message: RelayMessage): List<MediaCandidate> = MediaRefs.candidates(message)

    /** BLOG 的媒体候选；规则与媒体引用表共用，实现在 [MediaRefs.candidates]。 */
    fun mediaCandidates(post: BlogPost): List<MediaCandidate> = MediaRefs.candidates(post)

    fun mediaEntryName(sha256: String, extension: String): String {
        val suffix = extension.takeIf { it.isNotBlank() } ?: "bin"
        return MEDIA_PREFIX + sha256 + "." + suffix
    }

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        val value = optString(key, "").trim()
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }
}
