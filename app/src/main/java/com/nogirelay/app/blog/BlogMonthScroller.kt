package com.nogirelay.app.blog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassMotion
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.roundToInt

private val ThumbTouchWidth = 36.dp
private val ThumbTouchHeight = 56.dp
private val ThumbVisualHeight = 40.dp

/**
 * Fast scroller for the blog list. It fades in while the list scrolls; its
 * thumb tracks the position smoothly and, when dragged, jumps month by month
 * with a label bubble and a light tick per month. Hidden, it takes no touches,
 * so the list's own scrolling is never intercepted.
 */
@Composable
internal fun BlogMonthScroller(
    listState: LazyListState,
    headerItems: Int,
    itemCount: Int,
    months: List<BlogMonthSlot>,
    modifier: Modifier = Modifier,
) {
    if (itemCount < MIN_ITEMS || months.size < 2) return
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val latestMonths by rememberUpdatedState(months)
    val latestCount by rememberUpdatedState(itemCount)

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragMonth by remember { mutableStateOf<BlogMonthSlot?>(null) }
    // Where the list should be while dragging: its very top, its very end,
    // or the start of [dragMonth] in between.
    var dragTarget by remember { mutableStateOf<ScrollTarget?>(null) }
    // After release the thumb glides from the finger to the list's position.
    val release = remember { Animatable(1f) }
    var releaseFrom by remember { mutableFloatStateOf(0f) }

    // Scroll position over the posts, 0..1, with sub-item precision.
    val listFraction by remember(listState, headerItems) {
        derivedStateOf {
            // The ends are exact: the thumb rests at the track's ends when
            // the list cannot scroll any further that way.
            if (!listState.canScrollBackward) return@derivedStateOf 0f
            if (!listState.canScrollForward) return@derivedStateOf 1f
            val info = listState.layoutInfo
            val posts = info.visibleItemsInfo.filter { it.index >= headerItems && it.index < headerItems + latestCount }
            val first = posts.firstOrNull() ?: return@derivedStateOf if (listState.firstVisibleItemIndex >= headerItems) 1f else 0f
            val hidden = (info.viewportStartOffset - first.offset).coerceAtLeast(0)
            val position = (first.index - headerItems) + hidden / first.size.coerceAtLeast(1).toFloat()
            val span = (latestCount - posts.size).coerceAtLeast(1)
            (position / span).coerceIn(0f, 1f)
        }
    }

    val shown = dragging || listState.isScrollInProgress
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) {
            visible = true
        } else {
            delay(HIDE_DELAY_MILLIS)
            visible = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 150 else 320),
        label = "blog_scroller_alpha",
    )

    // Dragging moves the list once per target change: the page top, a month
    // start, or the end of the list with the last post above the footer.
    LaunchedEffect(listState, headerItems) {
        snapshotFlow { if (dragging) dragTarget else null }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { target ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                when (target) {
                    ScrollTarget.Top -> listState.scrollToItem(0)
                    ScrollTarget.End -> listState.scrollToItem(
                        (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                    )
                    is ScrollTarget.Month -> listState.scrollToItem(headerItems + target.startIndex)
                }
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(ThumbTouchWidth + 140.dp)
            .clearAndSetSemantics { }
            .graphicsLayer { this.alpha = alpha },
    ) {
        val travel = with(density) { (maxHeight - ThumbTouchHeight).toPx() }.coerceAtLeast(1f)
        fun displayed(): Float = when {
            dragging -> dragFraction
            release.value < 1f -> releaseFrom + (listFraction - releaseFrom) * release.value
            else -> listFraction
        }

        // Faint track, so the thumb reads as a position along the list.
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = (ThumbTouchWidth - 3.dp) / 2)
                .width(3.dp)
                .fillMaxHeight()
                .padding(vertical = (ThumbTouchHeight - ThumbVisualHeight) / 2)
                .background(GlassColors.InkTertiary.copy(alpha = 0.16f), GlassShapes.Capsule),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (displayed() * travel).roundToInt()) },
        ) {
            AnimatedVisibility(
                visible = dragging && dragMonth != null,
                enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, 0.5f)),
                exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f, transformOrigin = TransformOrigin(1f, 0.5f)),
            ) {
                GlassPanel(
                    shape = GlassShapes.Capsule,
                    depth = GlassDepths.Low,
                ) {
                    Text(
                        text = dragMonth?.label.orEmpty(),
                        style = GlassType.Callout,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Ink,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ThumbTouchWidth, ThumbTouchHeight)
                    .then(
                        if (visible) {
                            Modifier.pointerInput(travel) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragFraction = listFraction
                                        dragging = true
                                    },
                                    onDragEnd = { dragging = false },
                                    onDragCancel = { dragging = false },
                                    onVerticalDrag = { change, delta ->
                                        change.consume()
                                        dragFraction = (dragFraction + delta / travel).coerceIn(0f, 1f)
                                        val count = latestCount
                                        val index = (dragFraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
                                        val month = latestMonths.slotAt(index)
                                        dragMonth = month
                                        dragTarget = when {
                                            dragFraction <= EDGE -> ScrollTarget.Top
                                            dragFraction >= 1f - EDGE -> ScrollTarget.End
                                            month != null -> ScrollTarget.Month(month.startIndex)
                                            else -> null
                                        }
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val thumbWidth by animateFloatAsState(
                    targetValue = if (dragging) 8f else 5f,
                    animationSpec = tween(160),
                    label = "blog_scroller_thumb",
                )
                Box(
                    Modifier
                        .width(thumbWidth.dp)
                        .height(ThumbVisualHeight)
                        .background(
                            if (dragging) GlassColors.Accent else GlassColors.InkTertiary.copy(alpha = 0.62f),
                            GlassShapes.Capsule,
                        ),
                )
            }
        }

        LaunchedEffect(dragging) {
            if (!dragging && dragMonth != null) {
                releaseFrom = dragFraction
                release.snapTo(0f)
                release.animateTo(1f, GlassMotion.GentleSpec)
                dragMonth = null
                dragTarget = null
            }
        }
    }
}

private sealed interface ScrollTarget {
    data object Top : ScrollTarget
    data object End : ScrollTarget
    data class Month(val startIndex: Int) : ScrollTarget
}

/** Track fraction within which the thumb counts as at an end. */
private const val EDGE = 0.002f

private const val MIN_ITEMS = 40
private const val HIDE_DELAY_MILLIS = 1_200L
