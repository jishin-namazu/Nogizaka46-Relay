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

private const val TEXT_PRESENTATION_SELECTOR = "\uFE0E"

fun String.withoutTextPresentationSelector(): String =
    if (contains(TEXT_PRESENTATION_SELECTOR)) {
        replace(TEXT_PRESENTATION_SELECTOR, "")
    } else {
        this
    }

private const val SNIPPET_WORD_EXTENSION_LIMIT = 10

private const val SNIPPET_MERGE_GAP = 10

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

            val bounds = result.getPathForRange(rangeStart, rangeEnd).getBounds()
            val left = bounds.left.coerceAtLeast(0f)
            val right = minOf(bounds.right, result.size.width.toFloat())
            if (right <= left) continue

            val baselineDistance = result.firstBaseline - result.getLineTop(0)
            val baseline = result.getLineTop(line) + baselineDistance
            val lineFontSizePx = if (result.layoutInput.style.fontSize.isSpecified) {
                baseFontSizePx
            } else {
                (result.getLineBottom(line) - result.getLineTop(line)) * 0.72f
            }

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

        onTextLayout = { result -> if (isSearching) textLayoutResult = result },
    )
}
