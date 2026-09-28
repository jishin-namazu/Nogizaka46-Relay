package com.nogirelay.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Background-only source for cards on a page. Cards never sample themselves or each other;
 * the outer app source can still capture the finished page for the floating navigation.
 */
@Composable
fun RelayGlassBackdrop(
    background: Brush,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    RelayGlassBackdrop(background = { background }, modifier = modifier, content = content)
}

/** Dynamic backdrop colors are observed only by drawing, not by the page composition. */
@Composable
fun RelayGlassBackdrop(
    background: () -> Brush,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = rememberRelayHazeState()
    Box(modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .relayHazeSource(hazeState)
                .drawWithCache {
                    val brush = background()
                    onDrawBehind { drawRect(brush) }
                },
        )
        ProvideRelayHazeState(hazeState) { content() }
    }
}

/** Shared mirror finish. An opaque background bypasses page sampling entirely. */
@Composable
fun RelayMirrorGlassBackground(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFFF1F5FF).copy(alpha = 0.12f),
    reflectionTint: Color? = null,
    opaqueBackground: Color? = null,
    preserveSourceColors: Boolean = false,
    emphasizeEdges: Boolean = false,
) {
    val backgroundModifier = if (opaqueBackground != null) {
        Modifier.background(opaqueBackground.copy(alpha = 1f), shape)
    } else {
        Modifier.relayGlass(
            shape = shape,
            tint = tint,
            borderColor = Color.Transparent,
            blurRadius = 48.dp,
            useWindowBackdrop = false,
            specularIntensity = 0.95f,
            ambientResponse = 0.26f,
            preserveSourceColors = preserveSourceColors,
        )
    }
    Box(
        modifier = modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x1A3F4C66),
                spotColor = Color(0x243F4C66),
            )
            .clip(shape)
            .relayMirrorSheen(
                shape = shape,
                reflectionTint = reflectionTint,
                emphasizeEdges = emphasizeEdges,
            )
            .then(backgroundModifier),
    )
}

/** Shared navigation/tab selection: tint and reflections only, with no second backdrop sample. */
@Composable
fun RelayMirrorGlassSelection(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: Float = 1f,
    accentStrength: Float = 0f,
    enabledProgress: Float = if (enabled) 1f else 0f,
) {
    val progress = enabledProgress.coerceIn(0f, 1f)
    val accent = lerp(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.primary, progress)
    val tint = lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        lerp(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, accentStrength.coerceIn(0f, 1f)),
        progress,
    )
    val reflectionStrength = 0.55f + 0.45f * progress
    val tintStrength = emphasis.coerceIn(0f, 1.4f) * reflectionStrength
    Box(
        modifier = modifier
            .relayMirrorSheen(
                shape = shape,
                reflectionTint = accent,
                strength = reflectionStrength,
            )
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        tint.copy(alpha = 0.48f * tintStrength),
                        tint.copy(alpha = 0.24f * tintStrength),
                        tint.copy(alpha = 0.36f * tintStrength),
                    ),
                ),
                shape,
            ),
    )
}

/** A single backdrop sample underneath the exact same selection finish as the navigation. */
@Composable
fun RelayMirrorGlassActionBackground(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RelayNavigationSelectionShape,
    enabled: Boolean = true,
    emphasis: Float = 1f,
    opaqueBackground: Color? = null,
    accentStrength: Float = 0f,
    enabledProgress: Float = if (enabled) 1f else 0f,
) {
    Box(modifier) {
        RelayMirrorGlassBackground(
            shape = shape,
            modifier = Modifier.matchParentSize(),
            opaqueBackground = opaqueBackground,
        )
        RelayMirrorGlassSelection(
            shape = shape,
            modifier = Modifier.matchParentSize(),
            enabled = enabled,
            emphasis = emphasis,
            accentStrength = accentStrength,
            enabledProgress = enabledProgress,
        )
    }
}

/** A foreground content layer over an independent liquid-glass button surface. */
@Composable
fun RelayMirrorGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RelayNavigationSelectionShape,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    opaqueBackground: Color? = null,
    animateEnabledChanges: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Interaction/semantics change immediately; only the optional visual transition animates.
    val enabledProgress = if (animateEnabledChanges) {
        val progress by animateFloatAsState(
            targetValue = if (enabled) 1f else 0f,
            animationSpec = tween(240),
            label = "relay_glass_button_enabled",
        )
        progress
    } else if (enabled) 1f else 0f
    val foreground = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        MaterialTheme.colorScheme.primary,
        enabledProgress,
    )
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.75f),
        label = "relay_glass_button_press",
    )
    Box(
        modifier = modifier.heightIn(min = 48.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        propagateMinConstraints = true,
    ) {
        RelayMirrorGlassActionBackground(
            shape = shape,
            modifier = Modifier.matchParentSize(),
            enabled = enabled,
            opaqueBackground = opaqueBackground,
            enabledProgress = enabledProgress,
        )
        // Retain Material's text style, focus, button role and disabled semantics. Only the
        // empty sibling above samples the backdrop; the button itself stays transparent.
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = foreground,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = foreground,
            ),
            elevation = null,
            contentPadding = contentPadding,
            interactionSource = interactionSource,
            content = content,
        )
    }
}

/** Compact glass surface for icon-only actions. */
@Composable
fun RelayMirrorGlassIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        RelayMirrorGlassActionBackground(
            modifier = Modifier.matchParentSize(),
            enabled = enabled,
        )
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun RelayMirrorGlassIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    RelayMirrorGlassIconButton(
        onClick = onClick,
        contentDescription = contentDescription,
        modifier = modifier,
        enabled = enabled,
        active = active,
    ) {
        Icon(imageVector, contentDescription = null, modifier = Modifier.size(22.dp))
    }
}

/** The same glass action accepts custom icons, such as the translation and speaker artwork. */
@Composable
fun RelayMirrorGlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    opaqueBackground: Color? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.75f),
        label = "relay_glass_icon_press",
    )
    val emphasis by animateFloatAsState(
        targetValue = if (active) 1.3f else 1f,
        label = "relay_glass_icon_active",
    )
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                if (active) selected = true
            }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(40.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
            contentAlignment = Alignment.Center,
        ) {
            RelayMirrorGlassActionBackground(
                modifier = Modifier.matchParentSize(),
                enabled = enabled,
                emphasis = emphasis,
                opaqueBackground = opaqueBackground,
            )
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f),
                content = content,
            )
        }
    }
}

/** A switch track and thumb drawn above the same glass surface used by buttons and cards. */
@Composable
fun RelayMirrorGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val trackShape = RelayControlShape
    val interactionSource = remember { MutableInteractionSource() }
    val accent = if (checked) BrandPurple else MaterialTheme.colorScheme.onSurfaceVariant
    val thumbBase = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val thumbHighlight = if (enabled) Color.White else lerp(thumbBase, Color.White, 0.50f)
    val thumbShade = lerp(accent, Color.White, 0.86f).copy(alpha = 1f)
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 23.dp else 3.dp,
        animationSpec = spring(dampingRatio = 0.75f),
        label = "relay_glass_switch_thumb_position",
    )
    val trackProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f),
        label = "relay_glass_switch_tint",
    )
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 48.dp)
            .semantics { if (label != null) contentDescription = label }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(width = 52.dp, height = 32.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            RelayMirrorGlassActionBackground(
                shape = trackShape,
                modifier = Modifier.matchParentSize(),
                enabled = enabled,
                emphasis = 0.35f + 0.65f * trackProgress,
                // Blend the checked track into the primary purple beneath the shared
                // reflections, retaining the same gradient opacity and a single sample.
                accentStrength = trackProgress,
            )
            // Opaque gradient stops keep the track's optical highlights out of the thumb.
            // Disabled styling changes colour instead of exposing the track through alpha.
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                    .size(26.dp)
                    .shadow(4.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                thumbHighlight,
                                if (enabled) thumbShade else lerp(thumbBase, thumbShade, 0.34f),
                            ),
                        ),
                        CircleShape,
                    ),
            )
        }
    }
}

/** Cards show the page's background colours, or an opaque surface when used in settings. */
@Composable
fun RelayMirrorGlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RelayCardShape,
    onClick: (() -> Unit)? = null,
    opaqueBackground: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickInteractionSource = remember { MutableInteractionSource() }
    Box(modifier = modifier, propagateMinConstraints = true) {
        RelayMirrorGlassBackground(
            shape = shape,
            modifier = Modifier.matchParentSize(),
            tint = Color.White.copy(alpha = 0.04f),
            opaqueBackground = opaqueBackground,
            preserveSourceColors = true,
            emphasizeEdges = true,
        )
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier
                            .clip(shape)
                            .clickable(
                                interactionSource = clickInteractionSource,
                                indication = null,
                                onClick = onClick,
                            )
                    } else {
                        Modifier
                    },
                ),
            content = content,
        )
    }
}
