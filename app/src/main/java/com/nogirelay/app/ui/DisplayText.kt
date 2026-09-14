package com.nogirelay.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * VS15 (U+FE0E) requests the monochrome "text presentation" of the preceding
 * character. Member messages can contain it (for example "☺︎"), but Android font
 * stacks often have no monochrome glyph for the base character, so the pair is
 * rendered as a tofu box while the plain or emoji-presentation form ("☺" / "☺️")
 * displays fine. The selector is invisible, so dropping it only relaxes the
 * requested presentation; stored messages and translation input stay untouched.
 */
private const val TEXT_PRESENTATION_SELECTOR = "\uFE0E"

fun String.withoutTextPresentationSelector(): String =
    if (contains(TEXT_PRESENTATION_SELECTOR)) {
        replace(TEXT_PRESENTATION_SELECTOR, "")
    } else {
        this
    }

/** Longest extra characters the excerpt may borrow to start or end on a word boundary. */
private const val SNIPPET_WORD_EXTENSION_LIMIT = 10

/**
 * Short excerpt around the first case-insensitive match of [query] in [text], or null when the text
 * does not contain the query.
 *
 * Only a little text is kept before the match so the matched term itself is always inside the first
 * line the list can display; the cut points are then moved to the nearest word boundary so the
 * excerpt never starts or ends in the middle of a word. Search results use it as a summary so a list
 * never expands the whole BLOG body.
 */
fun searchSnippet(text: String, query: String, leading: Int = 12, trailing: Int = 28): String? {
    val needle = query.trim()
    if (needle.isEmpty()) return null

    val flattened = text.replace(Regex("\\s+"), " ").trim()
    val matchStart = flattened.indexOf(needle, ignoreCase = true)
    if (matchStart < 0) return null

    var start = (matchStart - leading).coerceAtLeast(0)
    var end = (matchStart + needle.length + trailing).coerceAtMost(flattened.length)

    if (start > 0) {
        val boundary = flattened.lastIndexOf(' ', start)
        if (boundary >= 0) {
            val extended = boundary + 1
            if (start - extended <= SNIPPET_WORD_EXTENSION_LIMIT) start = extended
        }
    }
    if (end < flattened.length) {
        val boundary = flattened.indexOf(' ', end)
        if (boundary >= 0 && boundary - end <= SNIPPET_WORD_EXTENSION_LIMIT) end = boundary
    }

    return buildString {
        if (start > 0) append("…")
        append(flattened.substring(start, end))
        if (end < flattened.length) append("…")
    }
}

fun highlightMatches(
    text: String,
    query: String,
    backgroundColor: Color,
    textColor: Color,
): AnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val matchStart = text.indexOf(needle, startIndex = cursor, ignoreCase = true)
            if (matchStart < 0) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, matchStart))
            withStyle(SpanStyle(background = backgroundColor, color = textColor)) {
                append(text.substring(matchStart, matchStart + needle.length))
            }
            cursor = matchStart + needle.length
        }
    }
}
