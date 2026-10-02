package com.nogirelay.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nogirelay.app.ui.glass.GlassCircularProgressIndicator

/**
 * The sync glyph shared by every sync control: the arrows shrink away as a
 * spinner grows in while syncing, and back again when done.
 */
@Composable
fun SyncGlyph(
    isSyncing: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    strokeWidth: Dp = 2.2.dp,
) {
    AnimatedContent(
        targetState = isSyncing,
        transitionSpec = {
            (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.72f))
                .togetherWith(fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.72f))
        },
        label = "sync-glyph",
        modifier = modifier,
    ) { syncing ->
        if (syncing) {
            GlassCircularProgressIndicator(
                color = tint,
                strokeWidth = strokeWidth,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size),
            )
        }
    }
}
