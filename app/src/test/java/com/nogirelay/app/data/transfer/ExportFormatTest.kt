package com.nogirelay.app.data.transfer

import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定磁盘格式约定：归档携带什么、省略什么，以及导入必须
 * 拒绝哪些载荷。
 */
class ExportFormatTest {

    private fun sampleMessage() = RelayMessage(
        id = "m-1",
        memberId = "48",
        memberName = "井上 和",
        memberAvatarUrl = "https://example.test/avatar.jpg",
        phoneImageUrl = "https://example.test/phone.jpg",
        type = MessageType.AUDIO,
        text = null,
        mediaUrl = "https://example.test/voice.m4a",
        thumbnailUrl = null,
        durationSeconds = 15,
        sentAt = "2026-09-14T09:30:00.000Z",
        incomingCallFrom = "井上 和",
        ringtoneUrl = null,
        isPlayed = true,
        translation = "你好",
        translationDone = true,
        isUnread = true,
    )

    private fun sampleBlog() = BlogPost(
        id = "b-1",
        memberId = "48",
        memberName = "井上 和",
        memberAvatarUrl = null,
        title = "标题",
        bodyHtml = "<p>正文</p><img src=\"https://example.test/1.jpg\">",
        imageUrl = "https://example.test/cover.jpg",
        publishedAt = "2026-09-14T09:30:00+09:00",
        postUrl = "https://www.nogizaka46.com/s/n46/diary/detail/1",
        translation = "译文",
        translationDone = true,
        isUnread = true,
    )

    @Test
    fun messageRoundTripsWithMediaReferences() {
        val ref = MediaRef("media", "https://example.test/voice.m4a", "media/abc.m4a")
        val json = ExportFormat.messageToJson(sampleMessage(), listOf(ref), includeTranslations = true)
        val parsed = ExportFormat.jsonToMessage(json)
        assertEquals("m-1", parsed.id)
        assertEquals(MessageType.AUDIO, parsed.type)
        assertEquals(15, parsed.durationSeconds)
        assertTrue(parsed.isPlayed)
        assertEquals("你好", parsed.translation)
        assertEquals(listOf(ref), ExportFormat.mediaRefsFrom(json))
    }

    @Test
    fun readStateIsNeverCarriedByAnArchive() {
        val json = ExportFormat.messageToJson(sampleMessage(), emptyList(), includeTranslations = true)
        assertFalse(json.has("is_unread"))
        assertFalse(ExportFormat.jsonToMessage(json).isUnread)
    }

    @Test
    fun translationsCanBeLeftOut() {
        val json = ExportFormat.messageToJson(sampleMessage(), emptyList(), includeTranslations = false)
        assertFalse(json.has("translation"))
        assertNull(ExportFormat.jsonToMessage(json).translation)
    }

    @Test
    fun blogRoundTripsBodyAndTranslation() {
        val json = ExportFormat.blogToJson(sampleBlog(), emptyList(), includeTranslations = true)
        val parsed = ExportFormat.jsonToBlog(json)
        assertEquals("b-1", parsed.id)
        assertEquals("<p>正文</p><img src=\"https://example.test/1.jpg\">", parsed.bodyHtml)
        assertEquals("译文", parsed.translation)
        assertFalse(parsed.isUnread)
    }

    @Test
    fun manifestRoundTripsMembers() {
        val manifest = ExportManifest(
            formatVersion = ExportFormat.FORMAT_VERSION,
            appVersionName = "1.0.0",
            appVersionCode = 12,
            exportedAt = "2026-02-14T15:30:00+09:00",
            kind = ExportKind.BLOGS,
            includesMedia = true,
            includesTranslations = true,
            members = listOf(
                ManifestMember("48", "井上 和", "5期生", "https://example.test/avatar.jpg", 3, true),
                ManifestMember("49", "遠藤 さくら", "5期生", null, 4, false),
                ManifestMember("7639", "秋元 真夏", "1期生", null, 5, true, true),
            ),
        )
        val parsed = ExportFormat.manifestFromJson(ExportFormat.manifestToJson(manifest))
        assertEquals(ExportKind.BLOGS, parsed.kind)
        assertEquals(3, parsed.members.size)
        assertEquals("5期生", parsed.members[0].category)
        assertTrue(parsed.members[0].directory)
        assertFalse(parsed.members[1].directory)
        assertNull(parsed.members[1].avatarUrl)
        // 卒業标记与真实期数一起存在，导入后才能把卒業生放回 1期生 分类下并显示 卒業 标签。
        assertEquals("1期生", parsed.members[2].category)
        assertTrue(parsed.members[2].graduated)
        assertFalse(parsed.members[0].graduated)
    }

    @Test
    fun oldArchiveWithoutGraduatedDefaultsToFalse() {
        val manifest = ExportManifest(
            formatVersion = ExportFormat.FORMAT_VERSION,
            appVersionName = "1.0.0",
            appVersionCode = 12,
            exportedAt = "2026-02-14T15:30:00+09:00",
            kind = ExportKind.BLOGS,
            includesMedia = false,
            includesTranslations = false,
            members = listOf(ManifestMember("48", "井上 和", "5期生", null, 3, true)),
        )
        val json = ExportFormat.manifestToJson(manifest)
        json.getJSONArray("members").getJSONObject(0).remove("graduated")
        assertFalse(ExportFormat.manifestFromJson(json).members[0].graduated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignPayloadIsRejected() {
        ExportFormat.manifestFromJson(JSONObject().put("format", "something-else"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun futureFormatVersionIsRejected() {
        val json = ExportFormat.manifestToJson(
            ExportManifest(1, "1.0.0", 1, "now", ExportKind.MESSAGES, false, false, emptyList()),
        ).put("formatVersion", ExportFormat.FORMAT_VERSION + 1)
        ExportFormat.manifestFromJson(json)
    }

    @Test
    fun mediaEntryNamesAreContentAddressed() {
        assertEquals("media/abc123.jpg", ExportFormat.mediaEntryName("abc123", "jpg"))
        assertEquals("media/abc123.bin", ExportFormat.mediaEntryName("abc123", ""))
    }

    @Test
    fun payloadWithoutMediaArrayYieldsNoReferences() {
        assertTrue(ExportFormat.mediaRefsFrom(JSONObject()).isEmpty())
    }

    /**
     * 归档里带了 media 条目但 url 为空：没有 url 就没有本地缓存键，
     * 引用被丢弃后该条目会在导入时计入"媒体缺少引用地址"，而不是静默当成成功。
     */
    @Test
    fun mediaEntryWithoutUrlIsDropped() {
        val json = JSONObject().put(
            "media",
            JSONArray().apply {
                put(JSONObject().put("role", "media").put("url", "").put("path", "media/abc.jpg"))
                put(JSONObject().put("role", "media").put("path", "media/def.jpg"))
                put(JSONObject().put("role", "media").put("url", "https://example.test/a.jpg").put("path", ""))
            },
        )
        assertTrue(ExportFormat.mediaRefsFrom(json).isEmpty())
    }

    @Test
    fun thumbnailsAreNeverPartOfAnArchive() {
        val message = sampleMessage().copy(
            type = MessageType.IMAGE,
            mediaUrl = "https://example.test/full.jpg",
            thumbnailUrl = "https://example.test/thumb.jpg",
            phoneImageUrl = null,
        )
        val candidates = ExportFormat.mediaCandidates(message)
        assertEquals(listOf("media"), candidates.map { it.role })
        assertEquals("https://example.test/full.jpg", candidates.single().url)
    }

    @Test
    fun imageWithoutMediaUrlStillFallsBackToItsThumbnail() {
        val message = sampleMessage().copy(
            type = MessageType.IMAGE,
            mediaUrl = null,
            thumbnailUrl = "https://example.test/only.jpg",
            phoneImageUrl = null,
        )
        assertEquals(
            listOf("https://example.test/only.jpg"),
            ExportFormat.mediaCandidates(message).map { it.url },
        )
    }

    @Test
    fun voiceMessageCarriesItsCallPhoto() {
        val candidates = ExportFormat.mediaCandidates(sampleMessage())
        assertEquals(listOf("media", "phone_image"), candidates.map { it.role })
        assertEquals(listOf("AUDIO", "IMAGE"), candidates.map { it.type.name })
    }

    @Test
    fun manifestTimestampIsRenderedForDisplay() {
        assertEquals("2026-02-14 15:30:00", ExportFormat.displayTimestamp("2026-02-14T15:30:00+09:00"))
        assertEquals("2026-02-14 06:30:00", ExportFormat.displayTimestamp("2026-02-14T06:30:00Z"))
        assertEquals("", ExportFormat.displayTimestamp(""))
        assertEquals("not-a-time", ExportFormat.displayTimestamp("not-a-time"))
    }

    @Test
    fun onlyVoiceMessagesCarryACallPhoto() {
        val image = sampleMessage().copy(
            type = MessageType.IMAGE,
            mediaUrl = "https://example.test/photo.jpg",
            thumbnailUrl = null,
            phoneImageUrl = "https://example.test/phone.jpg",
        )
        assertEquals(listOf("media"), ExportFormat.mediaCandidates(image).map { it.role })
    }

    /** 归档显式写出的空串要覆盖本地链接；缺键或 JSON null 只表示"归档没带这个信息"，不动本地值。 */
    @Test
    fun explicitEmptyLinkOverwritesButMissingKeysDoNot() {
        val json = JSONObject()
            .put("id", "1")
            .put("media_url", "")
            .put("thumbnail_url", "https://example.test/thumb.jpg")
            .put("ringtone_url", JSONObject.NULL)
        val links = ExportFormat.messageLinksFrom(json)
        assertEquals("", links["media_url"])
        assertEquals("https://example.test/thumb.jpg", links["thumbnail_url"])
        assertFalse(links.containsKey("ringtone_url"))
        assertFalse(links.containsKey("phone_image_url"))
        assertFalse(links.containsKey("member_avatar_url"))
    }

    /** 正文与成员身份不是链接：空值不能把本地内容和归属抹掉。 */
    @Test
    fun blogLinksOnlyClearRealLinkColumns() {
        val json = JSONObject()
            .put("id", "1")
            .put("image_url", "")
            .put("body_html", "")
            .put("member_id", "")
            .put("member_name", "山下 美月")
        val links = ExportFormat.blogLinksFrom(json)
        assertEquals("", links["image_url"])
        assertFalse(links.containsKey("body_html"))
        assertFalse(links.containsKey("member_id"))
        assertEquals("山下 美月", links["member_name"])
    }
}
