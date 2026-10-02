package com.nogirelay.app.ui.glass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass buttons. Every button is one continuous liquid-glass body: the
 * material, icon and label squash together on press and recover on a damped
 * spring. No ripples anywhere.
 */

@Composable
fun GlassCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: GlassTone = GlassTone.Neutral,
    size: Dp = 48.dp,
    tint: androidx.compose.ui.graphics.Color? = null,
    fillAlpha: Float = Float.NaN,
    pressScale: Float = 1f,
    depth: GlassDepth = GlassDepths.Low,
    toggleValue: Boolean? = null,
    contentDescription: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled)
    val shape = GlassShapes.Capsule
    Box(
        modifier = modifier
            .size(size)
            .glassPress(press)
            .glassControlShadow(shape, tone, tint, depth, press)
            .graphicsLayer {
                if (pressScale != 1f) {
                    scaleX *= pressScale
                    scaleY *= pressScale
                }
            }
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clip(shape)
            .then(
                if (toggleValue != null) {
                    Modifier.toggleable(
                        value = toggleValue,
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onClick() },
                    )
                } else {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                    )
                },
            )
            .glass(
                shape = shape,
                tone = tone,
                fillAlpha = if (fillAlpha.isNaN()) {
                    controlFillAlpha(tone)
                } else {
                    fillAlpha
                },
                tint = tint,
                blur = GlassOpticsPresets.BlurControl.dp,
                interactionSource = interactionSource,
                control = true,
            )
            .then(if (tone == GlassTone.OnDark) Modifier.glassEdgeLight(shape, tone) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides when (tone) {
                GlassTone.Neutral -> GlassColors.Ink
                GlassTone.Accent -> GlassColors.OnAccent
                GlassTone.OnDark -> GlassColors.OnAccent
            }.copy(alpha = if (enabled) 1f else 0.42f),
        ) {
            content()
        }
    }
}

@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: GlassTone = GlassTone.Neutral,
    size: Dp = 48.dp,
    iconSize: Dp = 22.dp,
) {
    GlassCircleButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        tone = tone,
        size = size,
        contentDescription = contentDescription,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Capsule (pill) button — the primary action form. Accent tone renders as
 * accent liquid glass (still translucent, still refracting), never flat.
 */
@Composable
fun GlassCapsuleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: GlassTone = GlassTone.Neutral,
    depth: GlassDepth = GlassDepths.Low,
    height: Dp = GlassMetrics.ControlHeight,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    contentDescription: String? = null,
    shape: androidx.compose.foundation.shape.RoundedCornerShape = GlassShapes.Capsule,
    tint: androidx.compose.ui.graphics.Color? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled)
    Row(
        modifier = modifier
            .heightIn(min = height)
            .glassPress(press)
            .glassControlShadow(shape, tone, tint, depth = depth, press = press)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .glass(
                shape = shape,
                tone = tone,
                tint = tint,
                fillAlpha = controlFillAlpha(tone),
                blur = GlassOpticsPresets.BlurControl.dp,
                interactionSource = interactionSource,
                control = true,
            )
            .then(if (tone == GlassTone.OnDark) Modifier.glassEdgeLight(shape, tone) else Modifier)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides when (tone) {
                GlassTone.Neutral -> GlassColors.Ink
                GlassTone.Accent -> GlassColors.OnAccent
                GlassTone.OnDark -> GlassColors.OnAccent
            }.copy(alpha = if (enabled) 1f else 0.45f),
        ) {
            content()
        }
    }
}

/** Filter / choice chip: neutral when idle, accent liquid glass when on. */
@Composable
fun GlassChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    height: Dp = GlassMetrics.ControlHeight,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled)
    val tone = if (selected) GlassTone.Accent else GlassTone.Neutral
    val shape = GlassShapes.Chip
    Row(
        modifier = modifier
            .height(height)
            .glassPress(press)
            .glassControlShadow(
                shape, tone,
                depth = if (selected) GlassDepths.Low else GlassDepths.None,
                press = press,
            )
            .semantics { this.selected = selected }
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            )
            .glass(
                shape = shape,
                tone = tone,
                fillAlpha = controlFillAlpha(tone),
                blur = GlassOpticsPresets.BlurControl.dp,
                interactionSource = interactionSource,
                control = true,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides when (tone) {
                GlassTone.Neutral -> GlassColors.InkSecondary
                GlassTone.Accent -> GlassColors.OnAccent
                GlassTone.OnDark -> GlassColors.OnAccent
            }.copy(alpha = if (enabled) 1f else 0.45f),
        ) {
            leadingIcon?.invoke()
            androidx.compose.material3.Text(
                text = label,
                style = GlassType.Callout,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}

/** A plain glass container (panel / card) with the shared material. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: androidx.compose.foundation.shape.RoundedCornerShape = GlassShapes.Card,
    tone: GlassTone = GlassTone.Neutral,
    depth: GlassDepth = GlassDepths.Medium,
    blur: Dp = GlassOpticsPresets.BlurPanel.dp,
    fillAlpha: Float = controlFillAlpha(tone),
    edgeStrength: Float = 1f,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    tint: androidx.compose.ui.graphics.Color? = null,
    shadowAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled = onClick != null)
    Box(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.glassPress(press)
                } else {
                    Modifier
                },
            )
            .glassControlShadow(
                shape, tone, tint, depth,
                press = if (onClick != null) press else null,
                alpha = shadowAlpha,
            )
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = onClickLabel,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .glass(
                shape = shape,
                tone = tone,
                fillAlpha = fillAlpha,
                blur = blur,
                interactionSource = if (onClick != null) interactionSource else null,
                control = true,
                edgeStrength = edgeStrength,
                tint = tint,
            )
            .then(if (tone == GlassTone.OnDark) Modifier.glassEdgeLight(shape, tone, edgeStrength) else Modifier),
        content = content,
    )
}





