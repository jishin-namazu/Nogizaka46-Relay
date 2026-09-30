package com.nogirelay.app.data

import com.nogirelay.app.blog.BlogContentBlock
import com.nogirelay.app.blog.BlogContentParser

data class MediaCandidate(val role: String, val url: String, val type: MessageType)

data class MediaRefRow(
    val recordId: String,
    val role: String,
    val url: String,
    val type: MessageType,
    val ordinal: Int,
)

enum class MediaRefKind { MESSAGES, BLOGS }

object MediaRefs {

    const val PARSE_VERSION = 1

    fun messageMemberKey(message: RelayMessage): String =
        message.memberId.trim().ifBlank { message.memberName.trim() }

    fun blogMemberKey(post: BlogPost): String = post.memberId.trim()

    fun candidates(message: RelayMessage): List<MediaCandidate> = buildList {
        val primary = message.mediaUrl?.takeIf(String::isNotBlank)
            ?: if (message.type == MessageType.IMAGE) {
                message.thumbnailUrl?.takeIf(String::isNotBlank)
            } else {
                null
            }
        primary?.let { add(MediaCandidate("media", it, message.type)) }

        if (message.type == MessageType.AUDIO) {
            message.phoneImageUrl?.takeIf(String::isNotBlank)?.let {
                add(MediaCandidate("phone_image", it, MessageType.IMAGE))
            }
        }
    }.distinctBy { it.role to it.url }

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
