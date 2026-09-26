package com.nogirelay.app.data

import com.nogirelay.app.blog.BlogContentBlock
import com.nogirelay.app.blog.BlogContentParser

/** 扫描记录时收集到的媒体候选。导出打包、统计与媒体引用表共用这一份定义。 */
data class MediaCandidate(val role: String, val url: String, val type: MessageType)

/** 媒体引用表里的一行：某条记录引用的一份媒体。 */
data class MediaRefRow(
    val recordId: String,
    val role: String,
    val url: String,
    val type: MessageType,
    val ordinal: Int,
)

/** 媒体引用表覆盖的内容族；与导出的 ExportKind 一一对应。 */
enum class MediaRefKind { MESSAGES, BLOGS }

/**
 * 媒体引用的唯一计算口径：一条记录引用哪些媒体、它归属哪个成员。
 *
 * [MessageDatabase] 在写入记录时调用这里算一次并落进 `media_refs` 表，统计与导出读同一张表。
 * 改动候选规则时必须同时提升 [PARSE_VERSION]。
 */
object MediaRefs {
    /** 媒体候选计算规则的版本号；[MediaRefIndex] 据此重建引用表。 */
    const val PARSE_VERSION = 1

    /** 消息归属的成员 key：有 `member_id` 用它，否则回退 `member_name`。 */
    fun messageMemberKey(message: RelayMessage): String =
        message.memberId.trim().ifBlank { message.memberName.trim() }

    /** BLOG 的成员 key：帖子的 `member_id`。 */
    fun blogMemberKey(post: BlogPost): String = post.memberId.trim()

    /**
     * 消息归档携带的媒体：它的主媒体，并且仅对语音消息还包括
     * 全屏通话照片。缩略图不打包。
     *
     * 主 URL 遵循 MediaDownloader 的解析顺序：没有媒体
     * URL 的 IMAGE 消息回退到它的缩略图。
     */
    fun candidates(message: RelayMessage): List<MediaCandidate> = buildList {
        val primary = message.mediaUrl?.takeIf(String::isNotBlank)
            ?: if (message.type == MessageType.IMAGE) {
                message.thumbnailUrl?.takeIf(String::isNotBlank)
            } else {
                null
            }
        primary?.let { add(MediaCandidate("media", it, message.type)) }
        // 只有语音消息能唤起全屏通话，所以只有它拥有通话照片；其他
        // 类型附带的所有照片 URL 都不属于这条消息所展示的内容。
        if (message.type == MessageType.AUDIO) {
            message.phoneImageUrl?.takeIf(String::isNotBlank)?.let {
                add(MediaCandidate("phone_image", it, MessageType.IMAGE))
            }
        }
    }.distinctBy { it.role to it.url }

    /**
     * 一篇 BLOG 的封面加上正文中的每张图片，按与阅读器一致的方式解析。
     *
     * 官方 API 通常把文章的第一张图片同时作为封面和内嵌图片给出，因此只有当
     * 正文尚未包含它时才把封面单独列为一项；该 URL 只列一次，
     * 正文重复出现的图片也会合并。
     */
    fun candidates(post: BlogPost): List<MediaCandidate> {
        val cover = post.imageUrl?.takeIf(::isRealBlogImageUrl)
        val bodyUrls = BlogContentParser.blocks(post.bodyHtml)
            .filterIsInstance<BlogContentBlock.Image>()
            .map(BlogContentBlock.Image::url)
            .filter(String::isNotBlank)
        val inBody = bodyUrls.toHashSet()
        return buildList {
            cover?.takeIf { it !in inBody }?.let { add(MediaCandidate("cover", it, MessageType.IMAGE)) }
            bodyUrls.forEach { add(MediaCandidate("body", it, MessageType.IMAGE)) }
        }.distinctBy { it.url }
    }
}
