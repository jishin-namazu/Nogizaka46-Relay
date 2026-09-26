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
 * VS15 (U+FE0E) 请求前一个字符使用单色的“文本呈现”形式。
 * 成员消息中可能包含它（例如 "☺︎"），但 Android 字体栈通常没有
 * 该基础字符的单色字形，因此这一组合会渲染成豆腐块，而普通形式
 * 或 emoji 呈现形式（"☺" / "☺️"）却能正常显示。
 * 该选择符不可见，去掉它只会放宽所请求的呈现方式；
 * 已存储的消息和翻译输入保持不变。
 */
private const val TEXT_PRESENTATION_SELECTOR = "\uFE0E"

fun String.withoutTextPresentationSelector(): String =
    if (contains(TEXT_PRESENTATION_SELECTOR)) {
        replace(TEXT_PRESENTATION_SELECTOR, "")
    } else {
        this
    }

/** 摘录在单词边界处开始或结束时最多可额外借用的字符数。 */
private const val SNIPPET_WORD_EXTENSION_LIMIT = 10
/** 相邻匹配之间合并为单个摘录的最大间隔。 */
private const val SNIPPET_MERGE_GAP = 10

/**
 * 返回 [text] 中所有不区分大小写的 [query] 匹配附近的简短摘录；文本不包含
 * 查询时返回空列表。彼此间距在 [mergeGap] 个字符以内的匹配会合并为单个摘录，
 * 避免相邻出现位置产生重复片段。
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
 * 返回 [text] 中第一个不区分大小写的 [query] 匹配附近的简短摘录；文本不
 * 包含查询时返回 null。
 *
 * 匹配之前只保留少量文本，确保匹配词本身始终位于列表
 * 可显示的第一行内；随后把截断点移到最近的单词边界，
 * 使摘录绝不会从单词中间开始或结束。搜索结果用它作为
 * 摘要，因此列表永远不会展开整篇 BLOG 正文。
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
            // 字符主体的视觉中心：baseline - 0.38 * fontSize。
            // 总高度：1.14 * fontSize（0.76 字形主体 + 0.19 上方空白 + 0.19 下方空白）。
            // 顶部 = baseline - 0.95 * fontSize（空白 = 0.19 * fontSize）。
            // 底部 = baseline + 0.19 * fontSize（空白 = 0.19 * fontSize）。
            // 这保证了所有字号下文本上下方空白都精确地 1:1 对称。
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
