package com.nogirelay.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared dialogs adopt the host page's styling without changing other tabs. */
val LocalRelayMirrorStyle = staticCompositionLocalOf { false }

enum class RelayDialogButtonStyle { Filled, Outlined, Text }

@Composable
fun RelayDialogButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: RelayDialogButtonStyle = RelayDialogButtonStyle.Text,
    contentColor: Color = BrandPurple,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalRelayMirrorStyle.current) {
        RelayMirrorGlassButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            contentPadding = contentPadding ?: PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            opaqueBackground = MaterialTheme.colorScheme.surface,
            content = content,
        )
    } else when (style) {
        RelayDialogButtonStyle.Filled -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            content = content,
        )
        RelayDialogButtonStyle.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
            contentPadding = contentPadding ?: ButtonDefaults.ContentPadding,
            content = content,
        )
        RelayDialogButtonStyle.Text -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
            contentPadding = contentPadding ?: ButtonDefaults.TextButtonContentPadding,
            content = content,
        )
    }
}

@Composable
fun RelayDialogIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (LocalRelayMirrorStyle.current) {
        RelayMirrorGlassIconButton(
            onClick = onClick,
            contentDescription = contentDescription,
            modifier = modifier,
            enabled = enabled,
            opaqueBackground = MaterialTheme.colorScheme.surface,
        ) { Icon(imageVector, contentDescription = null, modifier = Modifier.size(22.dp)) }
    } else {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Icon(imageVector, contentDescription = contentDescription, tint = BrandPurple)
        }
    }
}

@Composable
fun RelayDialogCard(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalRelayMirrorStyle.current) {
        RelayMirrorGlassCard(
            modifier = modifier.fillMaxWidth(),
            shape = RelayHomeCardShape,
            opaqueBackground = MaterialTheme.colorScheme.surface,
            content = content,
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth().relayGlass(shape = RelayCardShape),
            shape = RelayCardShape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = border,
            content = content,
        )
    }
}

@Composable
fun RelaySelectionSurface(
    onClick: () -> Unit,
    selected: Boolean,
    modifier: Modifier = Modifier,
    legacyShape: RoundedCornerShape = RoundedCornerShape(12.dp),
    emphasizeEdges: Boolean = false,
    useNavigationStyle: Boolean = false,
    enabled: Boolean = true,
    visualHeight: Dp? = null,
    announceSelected: Boolean = true,
    content: @Composable BoxScope.(selectionProgress: Float) -> Unit,
) {
    val mirrorStyle = LocalRelayMirrorStyle.current
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (mirrorStyle) tween(durationMillis = 220) else snap(),
        label = "relay_selection_progress",
    )
    val shape = if (mirrorStyle) {
        if (useNavigationStyle) RelayNavigationSelectionShape else RelayControlShape
    } else {
        legacyShape
    }
    val fill = if (selected) BrandPurpleLight else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val glassBase = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    val unselectedGlass = BrandPurple.copy(alpha = 0.04f).compositeOver(glassBase)
    val selectedGlass = BrandPurple.copy(alpha = 0.16f).compositeOver(glassBase)
    Box(modifier = modifier, propagateMinConstraints = true) {
        if (mirrorStyle) {
            Box(
                modifier = Modifier.matchParentSize(),
                contentAlignment = Alignment.Center,
            ) {
                val backgroundModifier = if (visualHeight == null) {
                    Modifier.matchParentSize()
                } else {
                    Modifier.fillMaxWidth().height(visualHeight)
                }
                if (useNavigationStyle) {
                    RelayMirrorGlassActionBackground(
                        shape = shape,
                        modifier = backgroundModifier,
                        opaqueBackground = glassBase,
                        emphasis = 0.35f + 0.65f * selectionProgress,
                    )
                } else {
                    RelayMirrorGlassBackground(
                        shape = shape,
                        modifier = backgroundModifier,
                        opaqueBackground = lerp(unselectedGlass, selectedGlass, selectionProgress),
                        reflectionTint = BrandPurple,
                        emphasizeEdges = emphasizeEdges,
                    )
                }
            }
        }
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            color = if (mirrorStyle) Color.Transparent else fill,
            contentColor = lerp(
                MaterialTheme.colorScheme.onSurfaceVariant,
                if (mirrorStyle && useNavigationStyle) MaterialTheme.colorScheme.primary else BrandPurpleDark,
                selectionProgress,
            ),
            border = if (mirrorStyle) {
                if (useNavigationStyle) null else BorderStroke(1.dp, BrandPurple.copy(alpha = 0.25f * selectionProgress))
            } else if (selected) {
                BorderStroke(1.5.dp, BrandPurple)
            } else {
                BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            },
            modifier = if (announceSelected) Modifier.semantics { this.selected = selected } else Modifier,
        ) { Box { content(selectionProgress) } }
    }
}
