package com.nogirelay.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Rounded playback controls with Material's seeking, keyboard and accessibility behavior. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayPlaybackSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val opaqueThumbColor = color.copy(alpha = 1f)
    val colors = SliderDefaults.colors(
        thumbColor = opaqueThumbColor,
        activeTrackColor = color.copy(alpha = 0.9f),
        inactiveTrackColor = color.copy(alpha = 0.28f),
        disabledThumbColor = opaqueThumbColor,
        disabledActiveTrackColor = color.copy(alpha = 0.2f),
        disabledInactiveTrackColor = color.copy(alpha = 0.12f),
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val dragged by interactionSource.collectIsDraggedAsState()
    val thumbScale by animateFloatAsState(
        targetValue = if (enabled && (pressed || dragged)) 1.15f else 1f,
        animationSpec = spring(dampingRatio = 1f),
        label = "playback_thumb_scale",
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        valueRange = valueRange,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            // Material top-aligns thumb content inside a slot as tall as the Slider.
            // Fill that bounded height, then center the circle on the track's centerline.
            Box(
                modifier = Modifier.width(14.dp).fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                // Scale only the drawing so the seek range stays fixed during interaction.
                Spacer(
                    Modifier.size(14.dp)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        }
                        .hoverable(interactionSource, enabled)
                        .background(if (enabled) colors.thumbColor else colors.disabledThumbColor, CircleShape),
                )
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(6.dp),
                enabled = enabled,
                colors = colors,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 3.dp,
            )
        },
    )
}
