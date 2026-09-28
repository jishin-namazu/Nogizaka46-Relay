package com.nogirelay.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Transparent overlays for media, without a pale backdrop or SurfaceView frame sampling. */
@Composable
internal fun RelayMediaGlassBackground(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RelayHomeCardShape,
) {
    Box(
        modifier = modifier
            // Native elevation treats the outline as opaque and skips part of its shadow.
            // That cutout shows through a translucent surface; draw a full shape mask instead.
            .dropShadow(
                shape = shape,
                shadow = Shadow(
                    radius = 8.dp,
                    color = Color.Black.copy(alpha = 0.18f),
                    offset = DpOffset(0.dp, 2.dp),
                ),
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.28f),
                        Color.Black.copy(alpha = 0.16f),
                        Color.Black.copy(alpha = 0.24f),
                    ),
                ),
                shape,
            )
            .background(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.07f),
                    0.45f to Color.Transparent,
                    1f to Color.White.copy(alpha = 0.035f),
                ),
                shape,
            )
            .border(
                BorderStroke(
                    1.dp,
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.34f),
                        0.5f to Color.White.copy(alpha = 0.06f),
                        1f to Color.White.copy(alpha = 0.22f),
                    ),
                ),
                shape,
            ),
    )
}

/** Central playback action shared by video previews and the full-screen viewer. */
@Composable
internal fun RelayMediaPlaybackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPlaying: Boolean = false,
    isCompleted: Boolean = false,
    contentDescription: String = when {
        isCompleted -> "重播"
        isPlaying -> "暂停"
        else -> "播放"
    },
) {
    RelayMediaGlassIconButton(
        onClick = onClick,
        contentDescription = contentDescription,
        modifier = modifier.size(72.dp),
        enabled = enabled,
    ) {
        Icon(
            imageVector = when {
                isCompleted -> Icons.Rounded.Replay
                isPlaying -> Icons.Rounded.Pause
                else -> Icons.Rounded.PlayArrow
            },
            contentDescription = null,
            modifier = Modifier.size(42.dp),
        )
    }
}

@Composable
internal fun RelayMediaGlassIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    RelayMediaGlassIconButton(onClick, contentDescription, modifier, enabled) {
        Icon(imageVector, contentDescription = null, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun RelayMediaGlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.75f),
        label = "media_glass_press",
    )
    Box(
        modifier = modifier.size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                onClick = onClick,
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(4.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            contentAlignment = Alignment.Center,
        ) {
            RelayMediaGlassBackground(
                modifier = Modifier.matchParentSize(),
                shape = RelayNavigationSelectionShape,
            )
            CompositionLocalProvider(
                LocalContentColor provides Color.White.copy(alpha = if (enabled) 0.95f else 0.45f),
                content = content,
            )
        }
    }
}
