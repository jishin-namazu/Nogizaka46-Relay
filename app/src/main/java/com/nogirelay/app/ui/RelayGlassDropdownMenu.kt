package com.nogirelay.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A popup-local glass surface with Material's anchoring, focus and dismissal behavior. */
@Composable
fun RelayGlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RelayControlShape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        // A Popup has its own window: keep the empty sampling source in that same window.
        RelayGlassBackdrop(
            background = RelayLightBackdrop,
            modifier = Modifier.widthIn(min = 200.dp, max = 280.dp).clip(RelayControlShape),
        ) {
            RelayMirrorGlassBackground(
                shape = RelayControlShape,
                modifier = Modifier.matchParentSize(),
                tint = Color.White.copy(alpha = 0.04f),
                reflectionTint = BrandPurple,
                preserveSourceColors = true,
                emphasizeEdges = true,
            )
            Column(Modifier.padding(6.dp), content = content)
        }
    }
}
