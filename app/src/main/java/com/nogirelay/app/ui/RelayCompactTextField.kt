package com.nogirelay.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 48dp 的高密度单行输入/选择框。Material 的 OutlinedTextField 最小高度为 56dp，
 * 直接压到 48dp 会裁掉浮动标签和正文，因此这里用 BasicTextField + 独立玻璃底板。
 */
@Composable
fun RelayCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val labelColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        focused -> BrandPurple
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val valueColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        focused -> BrandPurple
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
    }

    Box(
        modifier = modifier.height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        RelayMirrorGlassBackground(
            shape = RelayControlShape,
            modifier = Modifier.matchParentSize(),
            opaqueBackground = MaterialTheme.colorScheme.surface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(start = 14.dp, end = 8.dp),
        ) {
            Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Text(
                    text = label,
                    color = labelColor,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank() && placeholder.isNotBlank()) {
                        Text(
                            text = placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        readOnly = readOnly,
                        singleLine = true,
                        visualTransformation = visualTransformation,
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(BrandPurple),
                        textStyle = TextStyle(fontSize = 13.sp, color = valueColor),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            trailingContent?.invoke()
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(1.dp, borderColor, RelayControlShape),
        )
    }
}
