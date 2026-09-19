package com.nogirelay.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 锁定媒体引用表使用的候选口径。 */
class MediaRefsTest {

    private fun audioMessage() = RelayMessage(
        id = "m-1",
        memberId = "48",
        memberName = "井上 和",
        memberAvatarUrl = null,
        phoneImageUrl = "https://example.test/phone.jpg",
        type = MessageType.AUDIO,
        text = null,
        mediaUrl = "https://example.test/voice.m4a",
        thumbnailUrl = null,
        durationSeconds = 10,
        sentAt = "2026-09-14T09:30:00.000Z",
        incomingCallFrom = "井上 和",
        ringtoneUrl = null,
        isPlayed = false,
        translation = null,
        translationDone = false,
        isUnread = false,
    )

    private fun blog(bodyHtml: String, imageUrl: String?) = BlogPost(
        id = "295",
        memberId = "48",
        memberName = "井上 和",
        memberAvatarUrl = null,
        title = "标题",
        bodyHtml = bodyHtml,
        imageUrl = imageUrl,
        publishedAt = "2026-09-14T09:30:00+09:00",
        postUrl = "https://www.nogizaka46.com/s/n46/diary/detail/295",
    )

    @Test
    fun messageCandidatesKeepRoleAndType() {
        val candidates = MediaRefs.candidates(audioMessage())
        assertEquals(listOf("media", "phone_image"), candidates.map { it.role })
        assertEquals(listOf(MessageType.AUDIO, MessageType.IMAGE), candidates.map { it.type })
    }

    @Test
    fun imageMessageFallsBackToItsThumbnail() {
        val message = audioMessage().copy(
            type = MessageType.IMAGE,
            mediaUrl = "",
            thumbnailUrl = "https://example.test/only.jpg",
            phoneImageUrl = null,
        )
        assertEquals(listOf("https://example.test/only.jpg"), MediaRefs.candidates(message).map { it.url })
    }

    @Test
    fun memberKeysMatchExportSelection() {
        assertEquals("48", MediaRefs.messageMemberKey(audioMessage()))
        assertEquals(
            "運営スタッフ",
            MediaRefs.messageMemberKey(audioMessage().copy(memberId = "", memberName = "運営スタッフ")),
        )
        assertEquals("48", MediaRefs.blogMemberKey(blog("<p>x</p>", null)))
    }
}
