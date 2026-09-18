package com.nogirelay.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified

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
/** Maximum gap between adjacent matches to merge them into a single excerpt. */
private const val SNIPPET_MERGE_GAP = 10

/**
 * Short excerpts around all case-insensitive matches of [query] in [text], or empty when the text
 * does not contain the query. Matches occurring within [mergeGap] characters of each other are
 * merged into a single excerpt so nearby occurrences do not produce duplicate snippets.
 */
fun searchSnippets(
    text: String,
    query: String,
    leading: Int = 12,
    trailing: Int = 28,
    mergeGap: Int = SNIPPET_MERGE_GAP,
    maxSnippets: Int = Int.MAX_VALUE,
): List<String> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()

    val flattened = text.replace(Regex("\\s+"), " ").trim()
    if (flattened.isEmpty()) return emptyList()

    class Match(val start: Int, val end: Int)

    val matches = mutableListOf<Match>()
    var cursor = 0
    while (cursor < flattened.length) {
        val matchStart = flattened.indexOf(needle, startIndex = cursor, ignoreCase = true)
        if (matchStart < 0) break
        val matchEnd = matchStart + needle.length
        matches.add(Match(matchStart, matchEnd))
        cursor = matchStart + maxOf(1, needle.length)
    }
    if (matches.isEmpty()) return emptyList()

    class MatchCluster(val start: Int, var end: Int)

    val clusters = mutableListOf<MatchCluster>()
    for (match in matches) {
        if (clusters.isEmpty()) {
            clusters.add(MatchCluster(match.start, match.end))
        } else {
            val last = clusters.last()
            if (match.start - last.end <= mergeGap) {
                last.end = maxOf(last.end, match.end)
            } else {
                clusters.add(MatchCluster(match.start, match.end))
            }
        }
    }

    val snippets = mutableListOf<String>()
    for (cluster in clusters.take(maxSnippets)) {
        var start = (cluster.start - leading).coerceAtLeast(0)
        var end = (cluster.end + trailing).coerceAtMost(flattened.length)

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

        val snippet = buildString {
            if (start > 0) append("…")
            append(flattened.substring(start, end))
            if (end < flattened.length) append("…")
        }
        snippets.add(snippet)
    }

    return snippets
}

/**
 * Short excerpt around the first case-insensitive match of [query] in [text], or null when the text
 * does not contain the query.
 *
 * Only a little text is kept before the match so the matched term itself is always inside the first
 * line the list can display; the cut points are then moved to the nearest word boundary so the
 * excerpt never starts or ends in the middle of a word. Search results use it as a summary so a list
 * never expands the whole BLOG body.
 */
fun searchSnippet(text: String, query: String, leading: Int = 12, trailing: Int = 28): String? =
    searchSnippets(text, query, leading, trailing, maxSnippets = 1).firstOrNull()

fun highlightMatches(
    text: String,
    query: String,
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
            withStyle(SpanStyle(color = textColor)) {
                append(text.substring(matchStart, matchStart + needle.length))
            }
            cursor = matchStart + needle.length
        }
    }
}

fun highlightMatches(
    text: String,
    query: String,
    backgroundColor: Color,
    textColor: Color,
): AnnotatedString = highlightMatches(text, query, textColor)

fun DrawScope.drawSearchHighlightBoxes(
    result: TextLayoutResult,
    query: String,
    text: String,
    highlightColor: Color,
) {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return

    val lineCount = result.lineCount
    if (lineCount == 0) return

    val baseFontSizePx = if (result.layoutInput.style.fontSize.isSpecified) {
        result.layoutInput.style.fontSize.toPx()
    } else {
        (result.getLineBottom(0) - result.getLineTop(0)) * 0.72f
    }
    val cornerRadius = CornerRadius(minOf(baseFontSizePx * 0.12f, 2.dp.toPx()), minOf(baseFontSizePx * 0.12f, 2.dp.toPx()))

    var cursor = 0
    while (cursor < text.length) {
        val matchStart = text.indexOf(needle, startIndex = cursor, ignoreCase = true)
        if (matchStart < 0) break
        val matchEnd = matchStart + needle.length
        cursor = matchEnd

        if (matchStart >= result.layoutInput.text.length) break

        val startLine = result.getLineForOffset(matchStart)
        if (startLine >= lineCount) continue
        val endLine = result.getLineForOffset((matchEnd - 1).coerceAtLeast(matchStart)).coerceAtMost(lineCount - 1)

        for (line in startLine..endLine) {
            val lineStart = result.getLineStart(line)
            val lineEnd = result.getLineEnd(line, visibleEnd = true)
            val rangeStart = maxOf(matchStart, lineStart)
            val rangeEnd = minOf(matchEnd, lineEnd)
            if (rangeStart >= rangeEnd) continue

            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            for (offset in rangeStart until rangeEnd) {
                if (offset < result.layoutInput.text.length) {
                    val box = result.getBoundingBox(offset)
                    if (box.right > box.left && box.left < result.size.width && box.right > 0f) {
                        minX = minOf(minX, box.left)
                        maxX = maxOf(maxX, minOf(box.right, result.size.width.toFloat()))
                    }
                }
            }
            val left = minX
            val right = maxX
            if (right <= left) continue

            val baselineDistance = result.firstBaseline - result.getLineTop(0)
            val baseline = result.getLineTop(line) + baselineDistance
            val lineFontSizePx = if (result.layoutInput.style.fontSize.isSpecified) {
                baseFontSizePx
            } else {
                (result.getLineBottom(line) - result.getLineTop(line)) * 0.72f
            }
            // Visual center of character body: baseline - 0.38 * fontSize.
            // Total height: 1.14 * fontSize (0.76 glyph body + 0.19 top whitespace + 0.19 bottom whitespace).
            // Top = baseline - 0.95 * fontSize (whitespace = 0.19 * fontSize).
            // Bottom = baseline + 0.19 * fontSize (whitespace = 0.19 * fontSize).
            // This guarantees exact 1:1 symmetrical whitespace above and below the text for all font sizes.
            val top = baseline - 0.95f * lineFontSizePx
            val bottom = baseline + 0.19f * lineFontSizePx

            drawRoundRect(
                color = highlightColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = cornerRadius,
            )
        }
    }
}

@Composable
fun SearchHighlightText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    highlightBackground: Color = MaterialTheme.colorScheme.primaryContainer,
    highlightTextColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val needle = query.trim()
    val isSearching = needle.isNotEmpty()
    val annotatedText = remember(text, query, highlightTextColor) {
        if (!isSearching) {
            AnnotatedString(text)
        } else {
            highlightMatches(text, query, textColor = highlightTextColor)
        }
    }

    var textLayoutResult by remember(text, query) { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = annotatedText,
        modifier = modifier.then(
            if (isSearching) {
                Modifier.drawBehind {
                    textLayoutResult?.let { layout ->
                        drawSearchHighlightBoxes(
                            result = layout,
                            query = query,
                            text = text,
                            highlightColor = highlightBackground,
                        )
                    }
                }
            } else {
                Modifier
            }
        ),
        style = style,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        onTextLayout = { textLayoutResult = it },
    )
}
