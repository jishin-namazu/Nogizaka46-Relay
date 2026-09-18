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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
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
) {
    val text = buildAnnotatedString {
        append(name)
        if (isUnread) appendInlineContent(UNREAD_INLINE_ID, " 未读")
    }
    val inlineContent = if (isUnread) {
        val placeholderWidth = if (compact) 30.sp else 36.sp
        val placeholderHeight = if (compact) 12.sp else 16.sp
        mapOf(
            UNREAD_INLINE_ID to InlineTextContent(
                placeholder = Placeholder(
                    width = placeholderWidth,
                    height = placeholderHeight,
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
    } else {
        emptyMap()
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
