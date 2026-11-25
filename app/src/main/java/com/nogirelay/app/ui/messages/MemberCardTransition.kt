package com.nogirelay.app.ui.messages

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.controlFillAlpha
import com.nogirelay.app.ui.glass.glassControlCastShadow
import kotlin.math.roundToInt

internal val MemberCardCornerRadius = 28.dp

/** Longest the open waits for the first messages before moving anyway. */
internal const val OPEN_CONTENT_WAIT_MILLIS = 150L

/** Progress at which the timeline is settled enough to start media preload & read marks. */
internal const val CARD_TRANSITION_SETTLED_PROGRESS = 0.96f

/** Progress at which the expanded overlay visually covers the full inbox viewport. */
internal const val CARD_INBOX_COVERED_PROGRESS = 0.999f

/** Progress below which a collapsing card unlocks inbox scrolling for responsiveness. */
internal const val CARD_INBOX_SCROLL_UNLOCK_PROGRESS = 0.015f

// Asymmetric critically-damped springs: crisp open without overshoot (~300ms
// visual settle) and an even snappier close (~220ms visual settle) so back
// dismissal feels eager and never traps inbox interaction in a long tail.
internal val CardOpenSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 280f,
    visibilityThreshold = 0.0004f,
)

internal val CardCloseSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 380f,
    visibilityThreshold = 0.0004f,
)

internal val CardBackCancelSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 420f,
    visibilityThreshold = 0.001f,
)

internal enum class CardTransitionPhase {
    IDLE,
    PREPARING_OPEN,
    ANIMATING,
    OPEN,
    PREPARING_CLOSE,
}

@Immutable
internal data class PredictiveBackGesture(
    val progress: Float = 0f,
    val swipeEdge: Int = BackEventCompat.EDGE_LEFT,
    val touchY: Float = 0f,
)

internal fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Static glass body + rim alpha: completely skipped once Backdrop covers the card. */
internal fun cardBodyAlpha(progress: Float): Float =
    1f - smoothstep((progress / 0.25f).coerceIn(0f, 1f))

/** Resting card face alpha: exits cleanly in the first 20% of the expansion. */
internal fun cardFaceAlpha(progress: Float): Float =
    1f - smoothstep((progress / 0.20f).coerceIn(0f, 1f))

/** Page backdrop alpha: independent of timeline content alpha, fully opaque by 22%. */
internal fun cardBackdropAlpha(progress: Float): Float =
    smoothstep((progress / 0.22f).coerceIn(0f, 1f))

/** Timeline content alpha: reveals cleanly over the already-opacifying backdrop. */
internal fun cardTimelineAlpha(progress: Float): Float =
    smoothstep(((progress - 0.08f) / 0.22f).coerceIn(0f, 1f))

/** Pinned slot shadow eases out smoothly as the card lifts out of the list. */
internal fun cardSlotShadowAlpha(progress: Float): Float =
    1f - smoothstep((progress / 0.35f).coerceIn(0f, 1f))

/**
 * Decays the card's residual tap-press squash smoothly over the first 18% of
 * opening, avoiding both a 1-frame snap on handoff and overshoot wobble on the
 * expanded page.
 */
internal fun cardPressDecay(initialPress: Float, progress: Float): Float =
    initialPress.coerceIn(-0.15f, 1f) * (1f - smoothstep((progress / 0.18f).coerceIn(0f, 1f)))

/**
 * Single mutual-exclusion predicate governing whether the sibling overlay owns
 * the card rendering on the current draw pass. Both [MemberInbox] (hiding the
 * real card) and [MemberCardOverlay] (drawing the lifted card/shadow/veil) read
 * this exact predicate so they can never double-draw or leave a blank frame.
 */
internal fun isCardOverlayPresenting(
    hasCardThread: Boolean,
    phase: CardTransitionPhase,
    progress: Float,
    backProgress: Float,
): Boolean {
    if (!hasCardThread) return false
    return when (phase) {
        CardTransitionPhase.IDLE,
        CardTransitionPhase.PREPARING_OPEN,
        -> false
        CardTransitionPhase.OPEN,
        CardTransitionPhase.PREPARING_CLOSE,
        -> true
        CardTransitionPhase.ANIMATING -> progress > 0f || backProgress > 0f
    }
}

/**
 * Interpolates card corner radius concentrically into the device's physical
 * screen corners when available (API 31+), or holds rounded corners until 70%
 * before easing to square on rectangular displays.
 */
internal fun cardTransitionCornerRadiusPx(
    progress: Float,
    cardRadiusPx: Float,
    displayRadiusPx: Float,
): Float {
    val p = progress.coerceIn(0f, 1f)
    return if (displayRadiusPx > 0f) {
        lerp(cardRadiusPx, displayRadiusPx, smoothstep(p))
    } else {
        cardRadiusPx * smoothstep(((1f - p) / 0.3f).coerceIn(0f, 1f))
    }
}

/**
 * Corner radius during an interactive predictive-back drag: peels smoothly off
 * the physical display bezel to at least the card's corner radius.
 */
internal fun predictiveBackCornerRadiusPx(
    backProgress: Float,
    cardRadiusPx: Float,
    displayRadiusPx: Float,
): Float {
    val target = maxOf(cardRadiusPx, displayRadiusPx)
    return lerp(displayRadiusPx, target, smoothstep((backProgress * 1.6f).coerceIn(0f, 1f)))
}

/**
 * Offsets the timeline upward during expansion so its header sits neatly near
 * the top of the floating card (without a phantom status-bar forehead), then
 * settles monotonically below the real status bar as the card reaches y = 0.
 */
internal fun cardHeaderLeadOffsetPx(
    progress: Float,
    slotTopPx: Float,
    statusBarTopPx: Float,
): Float {
    val lead = minOf(statusBarTopPx.coerceAtLeast(0f), slotTopPx.coerceAtLeast(0f))
    return -(1f - progress.coerceIn(0f, 1f)) * lead
}

/**
 * Computes the floating rounded card rect during an interactive predictive-back
 * edge swipe (scaled smoothly down to 86% and nudged away from the swiped edge).
 */
internal fun predictiveBackCardRect(
    viewport: Rect,
    gesture: PredictiveBackGesture,
    maxShiftXPx: Float,
    maxShiftYPx: Float,
    targetCenterY: Float? = null,
): Rect {
    if (viewport.isEmpty) return viewport
    val t = smoothstep(gesture.progress.coerceIn(0f, 1f))
    val scale = lerp(1f, 0.86f, t)
    val width = viewport.width * scale
    val height = viewport.height * scale
    val edgeDirection = if (gesture.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val shiftX = edgeDirection * maxShiftXPx * t
    val verticalBias = when {
        targetCenterY != null && viewport.height > 0f ->
            ((targetCenterY - viewport.center.y) / (viewport.height * 0.5f)).coerceIn(-1f, 1f)
        gesture.touchY > 0f && viewport.height > 0f ->
            ((gesture.touchY - viewport.center.y) / (viewport.height * 0.5f)).coerceIn(-1f, 1f) * 0.5f
        else -> 0f
    }
    val shiftY = verticalBias * maxShiftYPx * t
    val left = viewport.left + (viewport.width - width) * 0.5f + shiftX
    val top = viewport.top + (viewport.height - height) * 0.5f + shiftY
    return Rect(left, top, left + width, top + height)
}

/**
 * Applies decaying press-in geometry (identical to [com.nogirelay.app.ui.glass.GlassPress])
 * around the card's center so handoff from a tapped card has zero 1-frame snap.
 */
internal fun applyPressDeformation(rect: Rect, effectivePress: Float, sinkPx: Float): Rect {
    if (kotlin.math.abs(effectivePress) <= 0.0001f || rect.isEmpty) return rect
    val sx = 1f - 0.055f * effectivePress
    val sy = 1f - 0.09f * effectivePress
    val w = rect.width * sx
    val h = rect.height * sy
    val cx = rect.center.x
    val cy = rect.center.y + sinkPx * effectivePress
    return Rect(cx - w * 0.5f, cy - h * 0.5f, cx + w * 0.5f, cy + h * 0.5f)
}

/**
 * True when opening [target] should wait briefly for its first page of messages
 * and pre-warm frames: either opening from closed, or switching to a different
 * member mid-collapse (reopening the same collapsing member reuses its live state).
 */
internal fun shouldWaitForInitialContent(
    shownEntry: MemberMessageEntry?,
    target: MemberMessageEntry,
): Boolean = shownEntry == null || shownEntry.memberKey != target.memberKey

/**
 * Tracks each visible inbox card's unclipped geometry inside the inbox's own
 * unscaled coordinate space, plus its live press progress and shadow-clipping flag.
 */
@Stable
class MemberCardSlotRegistry {
    private class SlotEntry(
        val coords: LayoutCoordinates,
        val pressProgress: () -> Float,
        val clipShadowBelow: Boolean,
    )

    private var inboxCoords: LayoutCoordinates? = null
    private val slots = HashMap<String, SlotEntry>()

    fun updateInboxCoordinates(coords: LayoutCoordinates) {
        inboxCoords = coords
    }

    fun registerSlot(
        threadId: String,
        coords: LayoutCoordinates,
        pressProgress: () -> Float,
        clipShadowBelow: Boolean,
    ) {
        slots[threadId] = SlotEntry(coords, pressProgress, clipShadowBelow)
    }

    fun unregisterSlot(threadId: String, coords: LayoutCoordinates) {
        if (slots[threadId]?.coords === coords) {
            slots.remove(threadId)
        }
    }

    /** Unclipped slot rect in unscaled inbox coordinates, immune to LazyColumn clipping & recede scale. */
    fun slotRectInInbox(threadId: String): Rect? {
        val root = inboxCoords?.takeIf { it.isAttached } ?: return null
        val entry = slots[threadId] ?: return null
        if (!entry.coords.isAttached) return null
        val rect = runCatching { root.localBoundingBoxOf(entry.coords, clipBounds = false) }.getOrNull()
        return rect?.takeIf { it.width > 0f && it.height > 0f }
    }

    /** Live slot rect only if the card is still visible inside [viewport]. */
    fun visibleSlotRectInInbox(threadId: String, viewport: Rect): Rect? {
        if (viewport.isEmpty) return null
        val rect = slotRectInInbox(threadId) ?: return null
        val intersectsVertically = rect.bottom > viewport.top && rect.top < viewport.bottom
        return rect.takeIf { intersectsVertically }
    }

    fun currentPressProgress(threadId: String): Float =
        slots[threadId]?.pressProgress?.invoke()?.coerceIn(-0.15f, 1f) ?: 0f

    fun clipShadowBelow(threadId: String): Boolean =
        slots[threadId]?.clipShadowBelow ?: true
}

/** Reads the device's physical rounded screen corner radius (API 31+), or 0 on rectangular displays. */
@Composable
internal fun rememberDisplayCornerRadiusPx(): Float {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view, density) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            val radii = listOfNotNull(
                insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius,
                insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius,
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius,
                insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius,
            ).filter { it > 0 }
            radii.minOrNull()?.toFloat() ?: 0f
        } else {
            0f
        }
    }
}

/**
 * Sibling overlay hosting the expanding/collapsing member card and timeline.
 *
 * Keeping the expanded page outside [MemberInbox]'s LazyColumn guarantees it
 * is never evicted when incoming messages reorder threads, eliminates per-frame
 * relayout and GlassPanel cache churn, draws the page backdrop independently of
 * content alpha, matches physical display corners, and replays a single GPU
 * [GraphicsLayer] snapshot during flight so internal Haze blurs stay idle.
 */
@Composable
internal fun MemberCardOverlay(
    thread: MemberThread?,
    userNickname: String,
    progress: () -> Float,
    predictiveBack: () -> PredictiveBackGesture,
    isPresenting: () -> Boolean,
    isClosing: Boolean,
    phase: CardTransitionPhase,
    captureTick: Int,
    canUseSnapshot: Boolean,
    snapshotLayer: GraphicsLayer,
    snapshotSizeRef: () -> IntSize,
    onSnapshotRecorded: (IntSize) -> Unit,
    resolveSlotRect: () -> Rect?,
    collapseStartRect: Rect?,
    collapseStartCornerRadiusPx: Float? = null,
    collapseVeilScale: Float = 1f,
    initialPress: Float,
    clipShadowBelow: Boolean,
    displayCornerRadiusPx: Float,
    blockOutsideTaps: Boolean,
    onReopen: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cardRadiusPx = with(density) { MemberCardCornerRadius.toPx() }
    val pressSinkPx = with(density) { 2.dp.toPx() }
    val memberCardSpacingPx = with(density) { 10.dp.toPx() }
    val maxBackShiftXPx = with(density) { 24.dp.toPx() }
    val maxBackShiftYPx = with(density) { 18.dp.toPx() }
    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val shadowContext = LocalGraphicsContext.current.shadowContext
    val shadowPainter = remember(shadowContext, GlassColors.palette) {
        shadowContext.createDropShadowPainter(GlassShapes.Card, glassControlCastShadow(GlassDepths.Medium))
    }
    val clipPath = remember { Path() }
    var overlayViewportSize by remember { mutableStateOf(Size.Zero) }

    val activeGeometry: (Size) -> OverlayFrame = { viewportSize ->
        val viewport = Rect(Offset.Zero, viewportSize)
        val p = progress().coerceIn(0f, 1f)
        val back = predictiveBack()
        val liveSlot = resolveSlotRect()
        val backActive = back.progress > 0.0001f && !isClosing
        when {
            backActive -> {
                val rect = predictiveBackCardRect(
                    viewport = viewport,
                    gesture = back,
                    maxShiftXPx = maxBackShiftXPx,
                    maxShiftYPx = maxBackShiftYPx,
                    targetCenterY = liveSlot?.center?.y,
                )
                OverlayFrame(
                    rect = rect,
                    slotRect = liveSlot,
                    cornerRadiusPx = predictiveBackCornerRadiusPx(back.progress, cardRadiusPx, displayCornerRadiusPx),
                    veilAlpha = lerp(1f, 0.42f, smoothstep(back.progress)),
                    effectivePress = 0f,
                    isFallbackDismiss = false,
                    needsClip = true,
                    progress = 1f,
                    fallbackAlpha = 1f,
                )
            }
            liveSlot != null -> {
                val startRect = if (isClosing && collapseStartRect != null && !collapseStartRect.isEmpty) {
                    collapseStartRect
                } else {
                    viewport
                }
                val rawRect = lerp(liveSlot, startRect, p)
                val effectivePress = if (isClosing) 0f else cardPressDecay(initialPress, p)
                val rect = applyPressDeformation(rawRect, effectivePress, pressSinkPx)
                val radius = if (isClosing && collapseStartRect != null && !collapseStartRect.isEmpty) {
                    val startRadius = collapseStartCornerRadiusPx ?: maxOf(cardRadiusPx, displayCornerRadiusPx)
                    lerp(cardRadiusPx, startRadius, smoothstep(p))
                } else {
                    cardTransitionCornerRadiusPx(p, cardRadiusPx, displayCornerRadiusPx)
                }
                OverlayFrame(
                    rect = rect,
                    slotRect = liveSlot,
                    cornerRadiusPx = radius,
                    veilAlpha = if (isClosing) p * collapseVeilScale else p,
                    effectivePress = effectivePress,
                    isFallbackDismiss = false,
                    needsClip = p < 1f,
                    progress = p,
                    fallbackAlpha = 1f,
                )
            }
            else -> {
                // Member card scrolled or reordered off-screen while open:
                // dismiss with a calm centered scale + fade instead of jumping.
                val startRect = if (isClosing && collapseStartRect != null && !collapseStartRect.isEmpty) {
                    collapseStartRect
                } else {
                    viewport
                }
                val endScale = 0.90f
                val endW = viewport.width * endScale
                val endH = viewport.height * endScale
                val endLeft = (viewport.width - endW) * 0.5f
                val endTop = (viewport.height - endH) * 0.5f + with(density) { 16.dp.toPx() }
                val endRect = Rect(endLeft, endTop, endLeft + endW, endTop + endH)
                val rect = lerp(endRect, startRect, p)
                val startRadius = collapseStartCornerRadiusPx ?: displayCornerRadiusPx
                OverlayFrame(
                    rect = rect,
                    slotRect = null,
                    cornerRadiusPx = lerp(cardRadiusPx, startRadius, smoothstep(p)),
                    veilAlpha = if (isClosing) p * collapseVeilScale else p,
                    effectivePress = 0f,
                    isFallbackDismiss = true,
                    needsClip = p < 1f,
                    progress = p,
                    fallbackAlpha = smoothstep(p),
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { overlayViewportSize = it.toSize() }
            .then(
                if (blockOutsideTaps) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent() }
                    }
                } else {
                    Modifier
                },
            )
            // Single uniform veil + pinned resting slot shadow underneath the expanding card.
            .drawBehind {
                if (!isPresenting()) return@drawBehind
                val frame = activeGeometry(size)
                if (frame.veilAlpha > 0f) {
                    drawRect(GlassColors.BackdropMid, alpha = 0.55f * frame.veilAlpha)
                    drawRect(Color.Black, alpha = 0.08f * frame.veilAlpha)
                }
                val slot = frame.slotRect
                if (slot != null && !frame.isFallbackDismiss && frame.progress < 0.35f) {
                    val shadowAlpha = cardSlotShadowAlpha(frame.progress) * (1f - 0.45f * frame.effectivePress.coerceAtLeast(0f))
                    if (shadowAlpha > 0f) {
                        val reach = slot.maxDimension
                        val clipBottom = if (clipShadowBelow) slot.height + memberCardSpacingPx else slot.height + reach
                        translate(left = slot.left, top = slot.top) {
                            clipRect(left = -reach, top = -reach, right = slot.width + reach, bottom = clipBottom) {
                                with(shadowPainter) { draw(slot.size, alpha = shadowAlpha) }
                            }
                        }
                    }
                }
            },
    ) {
        // Card container: GPU-clipped to the interpolated RoundRect with zero per-frame relayout.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    if (!isPresenting()) {
                        // Still invisible on screen (idle / preparing / settled back at 0);
                        // during PREPARING_OPEN let children run their offscreen snapshot record.
                        if (phase == CardTransitionPhase.PREPARING_OPEN) {
                            drawContent()
                        }
                        return@drawWithContent
                    }
                    val frame = activeGeometry(size)
                    if (frame.rect.isEmpty || frame.fallbackAlpha <= 0f) return@drawWithContent
                    val drawContainerBodyAndChildren = {
                        // 1. Resting card static glass body (skipped once Backdrop is opaque at p >= 0.25).
                        val bodyAlpha = if (frame.isFallbackDismiss) 0f else cardBodyAlpha(frame.progress)
                        if (bodyAlpha > 0f) {
                            val bodyColor = GlassColors.ControlBody.copy(
                                alpha = (controlFillAlpha(GlassTone.Neutral) * bodyAlpha).coerceIn(0f, 1f),
                            )
                            drawRect(bodyColor, topLeft = frame.rect.topLeft, size = frame.rect.size)
                            val surfaceHighlight = Brush.verticalGradient(
                                0f to GlassColors.RimLight.copy(alpha = 0.10f * GlassColors.RimStrength * bodyAlpha),
                                0.28f to Color.Transparent,
                                0.70f to Color.Transparent,
                                1f to GlassColors.RimLight.copy(alpha = 0.10f * bodyAlpha),
                                startY = frame.rect.top,
                                endY = frame.rect.bottom,
                            )
                            drawRect(surfaceHighlight, topLeft = frame.rect.topLeft, size = frame.rect.size)
                        }
                        // 2. Page backdrop (independent of timeline content alpha, aligned to root gradient).
                        val backdropAlpha = if (frame.isFallbackDismiss) {
                            frame.fallbackAlpha
                        } else {
                            cardBackdropAlpha(frame.progress)
                        }
                        if (backdropAlpha > 0f) {
                            drawRect(
                                brush = GlassColors.Backdrop,
                                topLeft = Offset.Zero,
                                size = size,
                                alpha = backdropAlpha,
                            )
                        }
                        // 3. Children (card face + timeline).
                        drawContent()
                        // 4. Subtle card rim while still a card; fades completely to 0 by p = 0.25
                        // so it is never drawn on top of the settled full-screen timeline.
                        if (bodyAlpha > 0.01f && frame.needsClip) {
                            val rimStrength = bodyAlpha * GlassColors.RimStrength
                            val rimBrush = Brush.verticalGradient(
                                0f to GlassColors.RimLight.copy(alpha = 0.78f * rimStrength),
                                0.22f to GlassColors.RimLight.copy(alpha = 0.14f * rimStrength),
                                0.65f to GlassColors.RimLight.copy(alpha = 0.06f * rimStrength),
                                1f to GlassColors.RimLight.copy(alpha = 0.85f * rimStrength),
                                startY = frame.rect.top,
                                endY = frame.rect.bottom,
                            )
                            drawPath(
                                path = clipPath,
                                brush = rimBrush,
                                style = Stroke(width = 1.2.dp.toPx()),
                                blendMode = BlendMode.SrcOver,
                            )
                        }
                    }

                    if (frame.needsClip) {
                        clipPath.rewind()
                        clipPath.addRoundRect(
                            RoundRect(
                                rect = frame.rect,
                                cornerRadius = CornerRadius(frame.cornerRadiusPx, frame.cornerRadiusPx),
                            ),
                        )
                        clipPath(clipPath) {
                            drawContainerBodyAndChildren()
                        }
                    } else {
                        drawContainerBodyAndChildren()
                    }
                },
        ) {
            // Card face: kept at slot dimensions, translated to the growing card's top-left,
            // and skipped completely once p >= 0.20.
            if (thread != null && phase != CardTransitionPhase.OPEN) {
                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val slot = resolveSlotRect()
                            val width = slot?.width?.roundToInt()?.coerceAtLeast(1) ?: constraints.maxWidth
                            val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                            layout(0, 0) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            if (!isPresenting()) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            val p = progress().coerceIn(0f, 1f)
                            val back = predictiveBack()
                            val faceAlpha = if (back.progress > 0.0001f || resolveSlotRect() == null) {
                                0f
                            } else {
                                cardFaceAlpha(p)
                            }
                            alpha = faceAlpha
                            if (faceAlpha <= 0f) return@graphicsLayer
                            val slot = resolveSlotRect() ?: return@graphicsLayer
                            val viewport = if (overlayViewportSize.width > 0f && overlayViewportSize.height > 0f) {
                                Rect(Offset.Zero, overlayViewportSize)
                            } else {
                                slot
                            }
                            val startRect = if (isClosing && collapseStartRect != null && !collapseStartRect.isEmpty) {
                                collapseStartRect
                            } else {
                                viewport
                            }
                            val rawRect = lerp(slot, startRect, p)
                            val effectivePress = if (isClosing) 0f else cardPressDecay(initialPress, p)
                            val rect = applyPressDeformation(rawRect, effectivePress, pressSinkPx)
                            val scaleXFactor = if (slot.width > 0f) rect.width / slot.width else 1f
                            // Match GlassPress's exact asymmetric vertical squash at handoff
                            // so the avatar and text never jump vertically on frame 1.
                            val pressYRatio = (1f - 0.09f * effectivePress) / (1f - 0.055f * effectivePress)
                            transformOrigin = TransformOrigin(0f, 0f)
                            translationX = rect.left
                            translationY = rect.top
                            scaleX = scaleXFactor
                            scaleY = scaleXFactor * pressYRatio
                        },
                ) {
                    ThreadCapsuleContent(thread = thread, userNickname = userNickname)
                }
            }

            // Timeline page: laid out once at viewport size, recorded into a GPU GraphicsLayer
            // snapshot before flight so 4 live Haze blurs stay idle during 0 < p < 1.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (phase == CardTransitionPhase.PREPARING_OPEN) {
                            // Keep alpha = 1f here so drawWithContent always executes to record
                            // the snapshot; drawWithContent withholds painting it onto the screen.
                            alpha = 1f
                            translationX = 0f
                            translationY = 0f
                            scaleX = 1f
                            scaleY = 1f
                            return@graphicsLayer
                        }
                        val frame = activeGeometry(size)
                        val timelineAlpha = if (frame.isFallbackDismiss) {
                            frame.fallbackAlpha
                        } else if (predictiveBack().progress > 0.0001f && !isClosing) {
                            1f
                        } else {
                            cardTimelineAlpha(frame.progress)
                        }
                        alpha = timelineAlpha
                        if (!frame.needsClip) {
                            translationX = 0f
                            translationY = 0f
                            scaleX = 1f
                            scaleY = 1f
                            return@graphicsLayer
                        }
                        val scale = if (size.width > 0f) frame.rect.width / size.width else 1f
                        val leadOffset = if (frame.slotRect != null && !frame.isFallbackDismiss && predictiveBack().progress <= 0.0001f) {
                            cardHeaderLeadOffsetPx(frame.progress, frame.slotRect.top, statusBarTopPx)
                        } else {
                            0f
                        }
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = frame.rect.left
                        translationY = frame.rect.top + leadOffset * scale
                        scaleX = scale
                        scaleY = scale
                    }
                    .drawWithContent {
                        // Read captureTick so pre-warm / pre-close frames trigger a clean record pass.
                        @Suppress("UNUSED_VARIABLE")
                        val tick = captureTick
                        val currentSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        val validSize = currentSize.width > 0 && currentSize.height > 0
                        val snapshotMatchesSize = snapshotSizeRef() == currentSize && validSize
                        when (phase) {
                            CardTransitionPhase.PREPARING_OPEN -> {
                                if (validSize) {
                                    snapshotLayer.record(size = currentSize) {
                                        this@drawWithContent.drawContent()
                                    }
                                    onSnapshotRecorded(currentSize)
                                }
                                // Offscreen pre-warm: intentionally do not draw onto screen yet.
                            }
                            CardTransitionPhase.PREPARING_CLOSE -> {
                                if (validSize) {
                                    snapshotLayer.record(size = currentSize) {
                                        this@drawWithContent.drawContent()
                                    }
                                    onSnapshotRecorded(currentSize)
                                    drawLayer(snapshotLayer)
                                } else {
                                    drawContent()
                                }
                            }
                            CardTransitionPhase.ANIMATING -> {
                                if (canUseSnapshot && snapshotMatchesSize) {
                                    drawLayer(snapshotLayer)
                                } else {
                                    drawContent()
                                }
                            }
                            CardTransitionPhase.OPEN,
                            CardTransitionPhase.IDLE,
                            -> {
                                drawContent()
                            }
                        }
                    },
            ) {
                content()
            }

            // While collapsing, tapping anywhere on the shrinking card reverses the spring back to open,
            // while taps outside the collapsing card still reach other inbox cards immediately.
            if (onReopen != null) {
                Box(
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val viewport = Rect(Offset.Zero, Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
                            val frame = activeGeometry(viewport.size)
                            val w = frame.rect.width.roundToInt().coerceIn(0, constraints.maxWidth)
                            val h = frame.rect.height.roundToInt().coerceIn(0, constraints.maxHeight)
                            val placeable = measurable.measure(Constraints.fixed(w, h))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(frame.rect.left.roundToInt(), frame.rect.top.roundToInt())
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = thread?.name,
                            onClick = onReopen,
                        ),
                )
            }
        }
    }
}

private data class OverlayFrame(
    val rect: Rect,
    val slotRect: Rect?,
    val cornerRadiusPx: Float,
    val veilAlpha: Float,
    val effectivePress: Float,
    val isFallbackDismiss: Boolean,
    val needsClip: Boolean,
    val progress: Float,
    val fallbackAlpha: Float,
)
