package com.nogirelay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlogImportMergeTest {
    private fun post(body: String, title: String = "記事") = BlogPost(
        id = "123", memberId = "1", memberName = "Member", memberAvatarUrl = null,
        title = title, bodyHtml = body, imageUrl = null, publishedAt = "2026-09-18",
        postUrl = "https://www.nogizaka46.com/s/n46/diary/detail/123",
    )

    @Test fun imageOnlyArchiveCannotEraseExistingText() {
        val existing = post("<p>残しておく本文</p><img src=\"https://old.example/a.jpg\">")
        val updates = BlogImportMerge.linkUpdates(existing, mapOf(
            "body_html" to "<img src=\"https://www.nogizaka46.com/a.jpg\">",
            "post_url" to "https://www.nogizaka46.com/s/n46/diary/detail/123",
        ))
        assertFalse(updates.containsKey("body_html"))
        assertTrue(updates.containsKey("post_url"))
    }

    @Test fun truncatedOrDifferentArchiveCannotReplaceExistingBody() {
        val existing = post("<p>一段目</p><p>二段目</p>")
        assertFalse(BlogImportMerge.linkUpdates(existing,
            mapOf("body_html" to "<p>一段目</p>")).containsKey("body_html"))
    }

    @Test fun blankFieldsCannotClearExistingContentOrLinks() {
        assertTrue(BlogImportMerge.linkUpdates(post("本文"), mapOf(
            "body_html" to " \n", "post_url" to "", "image_url" to " ",
            "member_avatar_url" to "null",
        )).isEmpty())
    }

    @Test fun emptyLocalBodyCanBeFilledFromArchive() {
        assertEquals("<p>本文</p>", BlogImportMerge.linkUpdates(post(" \n"),
            mapOf("body_html" to "<p>本文</p>"))["body_html"])
    }

    @Test fun imageHostChangesStillWorkWhenArticleContentMatches() {
        val existing = post("<p>本文</p><img src=\"https://old.example/a.jpg\">")
        val incoming = "<p>本文</p><img src=\"https://www.nogizaka46.com/a.jpg\">"
        assertEquals(incoming, BlogImportMerge.linkUpdates(existing,
            mapOf("body_html" to incoming))["body_html"])
        assertTrue(BlogImportMerge.sameTranslationSource(existing, post(incoming)))
    }

    @Test fun translationFromDifferentBodyOrTitleIsNotBackfilled() {
        assertFalse(BlogImportMerge.sameTranslationSource(post("本文"), post("抜粋")))
        assertFalse(BlogImportMerge.sameTranslationSource(post("本文"), post("本文", "旧題名")))
    }
}
