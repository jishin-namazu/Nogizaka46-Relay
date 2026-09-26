package com.nogirelay.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import com.nogirelay.app.ui.drawSearchHighlightBoxes
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val UNREAD_INLINE_ID = "unread-tag"

/** Small "卒業" chip marking a member the official roster already lists as graduated. */
@Composable
fun GraduatedTag(modifier: Modifier = Modifier, compact: Boolean = false) {
    val cornerRadius = if (compact) 3.dp else 4.dp
    val horizontalPadding = if (compact) 3.dp else 4.dp
    val tagHeight = if (compact) 12.dp else 16.dp
    val textSize = if (compact) 8.sp else 10.sp
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tagHeight)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(cornerRadius),
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "卒業",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                lineHeight = textSize,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun UnreadTag(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cornerRadius = if (compact) 3.dp else 4.dp
    val horizontalPadding = if (compact) 3.dp else 4.dp
    val tagHeight = if (compact) 12.dp else 16.dp
    val textSize = if (compact) 8.sp else 10.sp
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tagHeight)
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(cornerRadius),
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                lineHeight = textSize,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun NameWithUnreadTag(
    name: AnnotatedString,
    isUnread: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    searchQuery: String = "",
    highlightBackground: Color = Color.Unspecified,
) {
    var textLayoutResult by remember(name, searchQuery) { mutableStateOf<TextLayoutResult?>(null) }
    val text = buildAnnotatedString {
        append(name)
        if (isUnread) appendInlineContent(UNREAD_INLINE_ID, " 未读")
    }
    val inlineContent = buildMap {
        if (isUnread) {
            put(
                UNREAD_INLINE_ID,
                InlineTextContent(
                    placeholder = Placeholder(
                        width = if (compact) 30.sp else 36.sp,
                        height = if (compact) 12.sp else 16.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    UnreadTag(
                        text = "未读",
                        compact = compact,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 6.dp),
                    )
                },
            )
        }
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { textLayoutResult = it },
        modifier = modifier.then(
            if (searchQuery.isNotBlank() && highlightBackground.isSpecified) {
                Modifier.drawBehind {
                    textLayoutResult?.let { layout ->
                        drawSearchHighlightBoxes(
                            result = layout,
                            query = searchQuery,
                            text = name.text,
                            highlightColor = highlightBackground,
                        )
                    }
                }
            } else {
                Modifier
            }
        ),
    )
}
