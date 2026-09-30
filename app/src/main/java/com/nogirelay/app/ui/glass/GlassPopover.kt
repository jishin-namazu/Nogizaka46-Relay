package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
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
 * The menu grows beside the trigger, never covering the selector. It opens
 * downwards or upwards according to available space, scrolling long lists.
 * The material, contents and shadow fade together inside a padded layer.
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

internal class GlassPopoverPositionProvider(
    private val density: androidx.compose.ui.unit.Density,
    private val marginPx: Int,
    private val effectPaddingPx: Int,
    private val topInsetPx: Int = 0,
    private val bottomInsetPx: Int = 0,
) : PopupPositionProvider {
    var originX by mutableFloatStateOf(1f)
    var opensUpward by mutableStateOf(false)
    var maxHeightPx by mutableIntStateOf(Int.MAX_VALUE)
        private set
    var fitsAvailableSpace by mutableStateOf(false)
        private set
    private var previousAnchor: IntRect? = null
    private var previousWindow: IntSize? = null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val gap = with(density) { 6.dp.roundToPx() }
        // Position the visible card, excluding the transparent blur/spring gutter.
        val cardWidth = (popupContentSize.width - effectPaddingPx * 2).coerceAtLeast(1)
        val cardHeight = (popupContentSize.height - effectPaddingPx * 2).coerceAtLeast(1)
        val safeTop = topInsetPx + marginPx
        val safeBottom = (windowSize.height - bottomInsetPx - marginPx).coerceAtLeast(safeTop)
        val aboveEdge = minOf(anchorBounds.top - gap, safeBottom)
        val belowEdge = maxOf(anchorBounds.bottom + gap, safeTop)
        val above = (aboveEdge - safeTop).coerceAtLeast(0)
        val below = (safeBottom - belowEdge).coerceAtLeast(0)
        if (previousAnchor != anchorBounds || previousWindow != windowSize) {
            // Choose from the unconstrained menu once, then keep the chosen side
            // while its scrolling viewport shrinks to fit. Never center on the field.
            opensUpward = when {
                cardHeight <= below -> false
                cardHeight <= above -> true
                else -> above > below
            }
            previousAnchor = anchorBounds
            previousWindow = windowSize
        }
        maxHeightPx = (if (opensUpward) above else below).coerceAtLeast(1)
        fitsAvailableSpace = cardHeight <= maxHeightPx
        val y = (if (opensUpward) aboveEdge - cardHeight else belowEdge)
            .coerceIn(safeTop, (safeBottom - cardHeight).coerceAtLeast(safeTop))
        val alignEnd = anchorBounds.center.x > windowSize.width / 2
        val rawX = if (alignEnd) anchorBounds.right - cardWidth else anchorBounds.left
        val x = rawX.coerceIn(marginPx, (windowSize.width - cardWidth - marginPx).coerceAtLeast(marginPx))
        originX = ((anchorBounds.center.x - x).toFloat() / cardWidth)
            .coerceIn(0f, 1f)
        return IntOffset(x - effectPaddingPx, y - effectPaddingPx)
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
    val marginPx = with(density) { 16.dp.roundToPx() }
    val safeInsets = WindowInsets.safeDrawing
    val topInsetPx = safeInsets.getTop(density)
    val bottomInsetPx = safeInsets.getBottom(density)
    // Includes blur sampling, the complete soft shadow and spring overshoot.
    val effectPadding = 32.dp
    val effectPaddingPx = with(density) { effectPadding.roundToPx() }
    val provider = remember(density, marginPx, effectPaddingPx, topInsetPx, bottomInsetPx) {
        GlassPopoverPositionProvider(density, marginPx, effectPaddingPx, topInsetPx, bottomInsetPx)
    }
    val shape = GlassShapes.Popover
    val progress = remember { Animatable(0f) }
    val exiting = state.exiting
    val overlayLayer = rememberGlassOverlayLayer(minimumLevel = 3f)

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
        // Haze clips its captured texture to the popup root. Keep that root
        // larger than the animated card so spring overshoot never outruns blur.
        Box(
            Modifier.graphicsLayer {
                // Fade a stable padded layer. Alpha on the tight card itself
                // clips its shadow to a gray rectangle until alpha reaches 1.
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = if (provider.fitsAvailableSpace) progress.value.coerceIn(0f, 1f) else 0f
            }.pointerInput(state, effectPaddingPx) {
                detectTapGestures { point ->
                    val inset = effectPaddingPx
                    if (point.x < inset || point.y < inset ||
                        point.x > size.width - inset || point.y > size.height - inset
                    ) {
                        state.dismiss(scope)
                    }
                }
            }.padding(effectPadding),
        ) {
            // Haze resolves cross-window sources in screen coordinates.
            CompositionLocalProvider(LocalGlassOverlayLevel provides overlayLayer.level) {
                val anchor = state.anchorBounds
                val p = progress.value.coerceIn(0f, 1f)
                // Grow away from the anchor edge, keeping its field visible.
                val anchorW = anchor?.width ?: 0
                val anchorH = anchor?.height ?: 0
                Box(
                    modifier = modifier
                        .width(width)
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
                        }
                        .glassControlShadow(shape, depth = GlassDepths.Low)
                        .glassOverlaySurface(overlayLayer, shape),
                ) {
                    // Content appears late and slides in from the anchor side.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = minOf(420.dp, with(density) { provider.maxHeightPx.toDp() }))
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
    trailingContent: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            .padding(contentPadding),
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
            modifier = if (trailingContent != null) Modifier.weight(1f) else Modifier,
        )
        trailingContent?.invoke()
    }
}

