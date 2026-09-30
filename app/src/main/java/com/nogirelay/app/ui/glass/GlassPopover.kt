package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Morphing glass popover.
 *
 * The trigger button itself expands into the menu: the popup opens with the
 * glass body exactly covering the button (same position, size, capsule
 * radius), then springs outward to the full panel while menu content fades
 * and slides in slightly late. Dismissal is the exact reverse: content
 * vanishes first, then the panel shrinks back onto the button.
 */
class GlassPopoverState {
    var expanded by mutableStateOf(false)
        private set
    internal var exiting by mutableStateOf(false)
    internal var anchorBounds by mutableStateOf<IntRect?>(null)

    fun open() {
        exiting = false
        expanded = true
    }

    /** Animate closed; [onClosed] fires after the morph finishes. */
    fun dismiss(scope: CoroutineScope, onClosed: (() -> Unit)? = null) {
        if (!expanded || exiting) return
        exiting = true
        scope.launch {
            // Delay matching the exit animation before removing the popup.
            kotlinx.coroutines.delay(210)
            expanded = false
            exiting = false
            onClosed?.invoke()
        }
    }
}

@Composable
fun rememberGlassPopoverState(): GlassPopoverState = remember { GlassPopoverState() }

/** Records the anchor bounds so the popover can morph from the trigger. */
fun Modifier.glassPopoverAnchor(state: GlassPopoverState): Modifier = onGloballyPositioned { coords ->
    state.anchorBounds = IntRect(
        left = coords.boundsInWindow().left.toInt(),
        top = coords.boundsInWindow().top.toInt(),
        right = coords.boundsInWindow().right.toInt(),
        bottom = coords.boundsInWindow().bottom.toInt(),
    )
}

private class GlassPopoverPositionProvider(
    private val density: androidx.compose.ui.unit.Density,
    private val marginPx: Int,
) : PopupPositionProvider {
    var popupOffset by mutableStateOf(IntOffset.Zero)
    var originX by mutableFloatStateOf(1f)
    var opensUpward by mutableStateOf(false)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = with(density) { 6.dp.roundToPx() }
        val fitsBelow = anchorBounds.bottom + gap + popupContentSize.height <= windowSize.height - marginPx
        opensUpward = !fitsBelow && anchorBounds.top - gap - popupContentSize.height >= marginPx
        val y = when {
            fitsBelow -> anchorBounds.bottom + gap
            opensUpward -> anchorBounds.top - gap - popupContentSize.height
            else -> (windowSize.height - popupContentSize.height) / 2
        }
        val alignEnd = anchorBounds.center.x > windowSize.width / 2
        val rawX = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = rawX.coerceIn(marginPx, (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx))
        originX = ((anchorBounds.center.x - x).toFloat() / popupContentSize.width.coerceAtLeast(1))
            .coerceIn(0f, 1f)
        popupOffset = IntOffset(x, y)
        return popupOffset
    }
}

@Composable
fun GlassPopover(
    state: GlassPopoverState,
    modifier: Modifier = Modifier,
    width: Dp = 252.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!state.expanded) return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val marginPx = with(density) { 10.dp.roundToPx() }
    val provider = remember(density, marginPx) { GlassPopoverPositionProvider(density, marginPx) }
    val shape = GlassShapes.Popover
    val progress = remember { Animatable(0f) }
    val exiting = state.exiting

    LaunchedEffect(exiting) {
        if (exiting) {
            progress.animateTo(0f, GlassMotion.MorphSpec)
        } else {
            progress.animateTo(1f, GlassMotion.MorphSpec)
        }
    }

    Popup(
        popupPositionProvider = provider,
        properties = PopupProperties(focusable = true, clippingEnabled = false),
        onDismissRequest = { state.dismiss(scope) },
    ) {
        // Popups live in their own window: no shared haze source there.
        CompositionLocalProvider(LocalGlassHazeState provides null) {
            val anchor = state.anchorBounds
            val p = progress.value
            // Grow from the anchor: start as a capsule exactly over the button.
            val anchorW = anchor?.width ?: 0
            val anchorH = anchor?.height ?: 0
            Box(
                modifier = modifier
                    .width(width)
                    .widthIn(max = 300.dp)
                    .graphicsLayer {
                        // Scale from the anchor bounds toward full size.
                        val naturalW = size.width.coerceAtLeast(1f)
                        val naturalH = size.height.coerceAtLeast(1f)
                        val fromSx = if (anchorW > 0) anchorW / naturalW else 0.35f
                        val fromSy = if (anchorH > 0) anchorH / naturalH else 0.35f
                        val sx = fromSx + (1f - fromSx) * p
                        val sy = fromSy + (1f - fromSy) * p
                        scaleX = sx
                        scaleY = sy
                        transformOrigin = TransformOrigin(provider.originX, if (provider.opensUpward) 1f else 0f)
                        alpha = 0.35f + 0.65f * p
                        shadowElevation = (GlassDepths.High.elevation * (0.4f + 0.6f * p)).toPx()
                        this.shape = shape
                        clip = false
                        ambientShadowColor = GlassDepths.High.ambient
                        spotShadowColor = GlassDepths.High.spot
                    }
                    .clip(shape)
                    .glass(shape = shape, fillAlpha = 0.86f, blur = GlassOpticsPresets.BlurOverlay.dp)
                    .glassEdgeLight(shape),
            ) {
                // Content appears late and slides in from the anchor side.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(6.dp)
                        .graphicsLayer {
                            val contentP = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                            alpha = contentP
                            translationY = with(density) {
                                (if (provider.opensUpward) 8.dp else (-8).dp).toPx() * (1f - contentP)
                            }
                        },
                    content = content,
                )
            }
        }
    }
}

@Composable
fun GlassPopoverItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    customIcon: (@Composable () -> Unit)? = null,
    tint: Color = GlassColors.Ink,
    iconTint: Color = GlassColors.Accent,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .glassPress(press)
            .clip(GlassShapes.CardSmall)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint.copy(alpha = if (enabled) 1f else 0.4f),
                modifier = Modifier.size(20.dp),
            )
        } else {
            customIcon?.invoke()
        }
        Text(
            text = label,
            color = tint.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

