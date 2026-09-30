package com.nogirelay.app.data

import com.nogirelay.app.blog.BlogContentParser

/** An older archive may refresh links, but must not replace an existing article. */
internal object BlogImportMerge {
    private val columns = setOf(
        "image_url", "post_url", "member_avatar_url", "member_id", "member_name", "body_html",
    )

    fun linkUpdates(existing: BlogPost, incoming: Map<String, String>): Map<String, String> =
        incoming.filter { (column, value) ->
            column in columns && value.isNotBlank() && value != "null" &&
                (column != "body_html" || existing.bodyHtml.isBlank() ||
                    sameBody(existing.bodyHtml, value))
        }

    fun sameBody(first: String, second: String): Boolean =
        first == second || BlogContentParser.bodyTextShape(first) == BlogContentParser.bodyTextShape(second)

    fun sameTranslationSource(first: BlogPost, second: BlogPost): Boolean =
        first.title.trim() == second.title.trim() && sameBody(first.bodyHtml, second.bodyHtml)
}
