package com.nogirelay.app.ui.messages

import com.nogirelay.app.ui.UiTestTags
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.glass.GlassBadge
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.glassControlCastShadow
import com.nogirelay.app.ui.glass.glassPress
import com.nogirelay.app.ui.glass.rememberGlassPress
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.withoutTextPresentationSelector
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * An opened member card. The card itself grows from its list slot to
 * [viewportInRoot] as [progress] goes 0 -> 1 and hosts [content] (the member
 * timeline) inside its own glass, so there is never a second copy of it.
 * [onReopen] is set while the card is collapsing back into the list.
 */
class MemberCardExpansion(
    val threadId: String,
    val progress: () -> Float,
    val viewportInRoot: () -> Rect,
    val content: @Composable () -> Unit,
    val onReopen: (() -> Unit)? = null,
)

private val MemberCardSpacing = 10.dp
private val CardCornerRadius = 28.dp

/**
 * Progress after which the opened page's backdrop (opaque from 0.25 on)
 * hides the card body completely, so its glass can be skipped unseen.
 */
private const val GLASS_COVERED_PROGRESS = 0.32f

/**
 * Member inbox, rebuilt as liquid glass: a horizontal constellation of
 * recent-contact droplets on top, then thread rows as floating glass
 * capsules over the shared milky backdrop.
 *
 * While a member page is open ([recedeProgress] non-null) the list is
 * frozen, and the [expansion] card veils everything behind it.
 */
@Composable
fun MemberInbox(
    threads: List<MemberThread>,
    userNickname: String,
    onSelect: (MemberThread, fromCard: Boolean) -> Unit,
    state: LazyListState = rememberLazyListState(),
    recedeProgress: (() -> Float)? = null,
    expansion: MemberCardExpansion? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val recede = recedeProgress?.let { Modifier.memberInboxRecede(it) } ?: Modifier
    LazyColumn(
        state = state,
        userScrollEnabled = recedeProgress == null,
        verticalArrangement = Arrangement.spacedBy(MemberCardSpacing),
        modifier = Modifier.fillMaxSize().testTag(UiTestTags.MEMBER_INBOX),
        contentPadding = PaddingValues(bottom = 128.dp),
    ) {
        header?.let { content ->
            item(key = "member-inbox-header") {
                Box(recede) { content() }
            }
        }
        if (threads.isNotEmpty()) {
            item(key = "member-inbox-recent-title") {
                Text(
                    text = "最近消息",
                    style = GlassType.Headline,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Ink,
                    modifier = recede
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 4.dp),
                )
            }
            item(key = "member-inbox-recent") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = recede.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                ) {
                    items(threads.take(8), key = { "recent-" + it.id }) { thread ->
                        RecentContactDroplet(
                            thread = thread,
                            onClick = { onSelect(thread, false) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }

        item(key = "member-inbox-all-title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = recede
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(top = 12.dp, bottom = 2.dp),
            ) {
                Text(
                    text = "全部成员",
                    style = GlassType.Headline,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Ink,
                )
                Text(
                    text = "共 ${threads.size} 位",
                    style = GlassType.Footnote,
                    color = GlassColors.InkTertiary,
                )
            }
        }

        items(threads, key = { it.id }) { thread ->
            val opened = expansion?.takeIf { it.threadId == thread.id }
            ThreadCapsule(
                thread = thread,
                userNickname = userNickname,
                onClick = { onSelect(thread, true) },
                expansion = opened,
                clipShadowBelow = thread.id != threads.last().id,
                modifier = Modifier
                    .animateItem()
                    .then(if (opened != null) Modifier.zIndex(1f) else recede)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * Skips drawing inbox content once the opened page fully covers it. The
 * receding look itself is a single veil drawn by the opened card; per-item
 * effects would show as separate bands at every item's bounds.
 */
private fun Modifier.memberInboxRecede(progress: () -> Float): Modifier =
    graphicsLayer { alpha = if (progress() >= 1f) 0f else 1f }

/**
 * One uniform veil over the whole viewport, drawn from the opened card's slot
 * underneath the card itself, so it sits above every other item.
 */
private fun Modifier.memberInboxVeil(expansion: MemberCardExpansion, slot: () -> Rect): Modifier =
    drawBehind {
        val p = expansion.progress().coerceIn(0f, 1f)
        if (p <= 0f) return@drawBehind
        val viewport = expansion.viewportInRoot()
        val topLeft = viewport.topLeft - slot().topLeft
        drawRect(GlassColors.BackdropMid, topLeft = topLeft, size = viewport.size, alpha = 0.55f * p)
        drawRect(Color.Black, topLeft = topLeft, size = viewport.size, alpha = 0.08f * p)
    }

@Composable
private fun RecentContactDroplet(
    thread: MemberThread,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(74.dp),
    ) {
        // Clip only the photo; the badge must remain outside the circular clip.
        Box(
            modifier = Modifier
                .size(64.dp)
                .glassPress(press).clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = thread.name,
                onClick = onClick,
            ),
        ) {
            RemoteImage(
                url = thread.avatarUrl,
                contentDescription = thread.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(GlassShapes.Circle),
                loadCachedImmediately = true,
            )
            if (thread.unreadCount > 0) {
                GlassBadge(
                    count = thread.unreadCount,
                    modifier = Modifier.align(Alignment.TopEnd).zIndex(1f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = thread.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = GlassType.Footnote,
            fontWeight = FontWeight.Medium,
            color = GlassColors.Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThreadCapsule(
    thread: MemberThread,
    userNickname: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expansion: MemberCardExpansion? = null,
    clipShadowBelow: Boolean = true,
) {
    // One composition for both states, so opening never remounts the card face.
    var slotInRoot by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    // The progress changes every frame; it is read only in layout and draw.
    // Composition sees just one flip: once the page backdrop fully covers
    // the card body, its refractive glass is invisible and is switched off.
    val covered by remember(expansion) {
        derivedStateOf { (expansion?.progress?.invoke() ?: 0f) >= GLASS_COVERED_PROGRESS }
    }
    val cardRadiusPx = with(density) { CardCornerRadius.toPx() }
    GlassPanel(
        onClick = if (expansion == null) onClick else null,
        onClickLabel = thread.name,
        shape = GlassShapes.Card,
        staticMaterial = true,
        // While opened, the shadow is drawn at the slot instead (see below).
        shadowAlpha = if (expansion == null) 1f else 0f,
        blurEnabled = !covered,
        clipShape = expansion?.let { opened ->
            {
                // Corners stay round for most of the way, then square off.
                val p = opened.progress().coerceIn(0f, 1f)
                RoundedCornerShape(cardRadiusPx * ((1f - p) / 0.3f).coerceIn(0f, 1f))
            }
        },
        // The tap's release spring finishes while the card starts to grow.
        keepPressFeedback = true,
        modifier = modifier
            .testTag(UiTestTags.MEMBER_THREAD)
            .onGloballyPositioned { slotInRoot = it.boundsInRoot() }
            .then(
                if (expansion != null) {
                    // The same shadow the panel casts at rest, pinned to the slot
                    // so it never grows or shrinks with the card; it only eases out.
                    Modifier
                        .memberInboxVeil(expansion, slot = { slotInRoot })
                        .pinnedSlotShadow(
                            alpha = {
                                val fade = (expansion.progress() / 0.35f).coerceIn(0f, 1f)
                                1f - fade * fade * (3f - 2f * fade)
                            },
                            clipBelow = clipShadowBelow,
                        )
                        .expandFromSlot(expansion, slot = { slotInRoot })
                } else {
                    Modifier
                },
            ),
    ) {
        ThreadCapsuleContent(
            thread = thread,
            userNickname = userNickname,
            modifier = if (expansion != null) {
                // Keep the face at card width while the glass grows around it.
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .layout { measurable, _ ->
                        val width = slotInRoot.width.roundToInt()
                        val placeable = measurable.measure(Constraints(minWidth = width, maxWidth = width))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .graphicsLayer { alpha = (1f - expansion.progress() / 0.2f).coerceIn(0f, 1f) }
            } else {
                Modifier
            },
        )
        if (expansion != null) {
            // The page is laid out at viewport size and scaled to the card's
            // current width from its top-left; it never sizes the card.
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .layout { measurable, _ ->
                            val viewport = expansion.viewportInRoot()
                            val placeable = measurable.measure(
                                Constraints.fixed(viewport.width.roundToInt(), viewport.height.roundToInt()),
                            )
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        .graphicsLayer {
                            val progress = expansion.progress().coerceIn(0f, 1f)
                            val viewportWidth = expansion.viewportInRoot().width
                            val width = lerp(slotInRoot.width, viewportWidth, progress)
                            val scale = if (viewportWidth > 0f) width / viewportWidth else 1f
                            transformOrigin = TransformOrigin(0f, 0f)
                            scaleX = scale
                            scaleY = scale
                            alpha = ((progress - 0.06f) / 0.3f).coerceIn(0f, 1f)
                        }
                        .drawBehind {
                            // The page backdrop covers the glass body early on.
                            drawRect(GlassColors.Backdrop, alpha = (expansion.progress() / 0.25f).coerceIn(0f, 1f))
                        },
                ) {
                    expansion.content()
                }
            }
            expansion.onReopen?.let { reopen ->
                // While collapsing, a tap anywhere on the card turns it around;
                // the half-closed page itself takes no input.
                Box(
                    Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = thread.name,
                            onClick = reopen,
                        ),
                )
            }
        }
    }
}

/**
 * The panel's resting cast shadow, pinned to the slot. The opened card is
 * lifted above its neighbours, so the part of the shadow that normally sits
 * under the next card is clipped away; otherwise it would lie on top of that
 * card and vanish the instant the card drops back into the list.
 */
@Composable
private fun Modifier.pinnedSlotShadow(alpha: () -> Float, clipBelow: Boolean): Modifier {
    val shadowContext = LocalGraphicsContext.current.shadowContext
    val painter = remember(shadowContext, GlassColors.palette) {
        shadowContext.createDropShadowPainter(GlassShapes.Card, glassControlCastShadow(GlassDepths.Medium))
    }
    return drawBehind {
        val alpha = alpha()
        if (alpha <= 0f) return@drawBehind
        // Generous finite bounds; only the bottom edge actually clips.
        val reach = size.maxDimension
        val bottom = if (clipBelow) size.height + MemberCardSpacing.toPx() else size.height + reach
        clipRect(left = -reach, top = -reach, right = size.width + reach, bottom = bottom) {
            with(painter) { draw(size, alpha = alpha) }
        }
    }
}

/**
 * Lays the card out at its slot size, but measures and places it at the rect
 * interpolated from the slot to the viewport, so the list never reflows.
 */
private fun Modifier.expandFromSlot(expansion: MemberCardExpansion, slot: () -> Rect): Modifier =
    layout { measurable, constraints ->
        val p = expansion.progress().coerceIn(0f, 1f)
        val slotRect = slot()
        if (p <= 0f || slotRect.isEmpty) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val rect = lerp(slotRect, expansion.viewportInRoot(), p)
        // Layout works in whole pixels; the remainder goes into the layer as a
        // sub-pixel offset and scale, so the slow end of the spring glides
        // instead of stepping one pixel at a time.
        val width = ceil(rect.width).toInt()
        val height = ceil(rect.height).toInt()
        val placeable = measurable.measure(Constraints.fixed(width, height))
        val x = rect.left - slotRect.left
        val y = rect.top - slotRect.top
        val baseX = floor(x).toInt()
        val baseY = floor(y).toInt()
        layout(slotRect.width.roundToInt(), slotRect.height.roundToInt()) {
            placeable.placeWithLayer(baseX, baseY) {
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = x - baseX
                translationY = y - baseY
                scaleX = rect.width / width
                scaleY = rect.height / height
            }
        }
    }

@Composable
private fun ThreadCapsuleContent(
    thread: MemberThread,
    userNickname: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(GlassShapes.Circle),
        ) {
            RemoteImage(
                url = thread.avatarUrl,
                contentDescription = thread.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loadCachedImmediately = true,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = thread.name,
                fontWeight = FontWeight.SemiBold,
                style = GlassType.Headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = GlassColors.Ink,
            )

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (thread.latest.type) {
                    MessageType.AUDIO -> {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = GlassColors.Accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    MessageType.IMAGE -> {
                        Icon(
                            imageVector = Icons.Rounded.Image,
                            contentDescription = null,
                            tint = GlassColors.Accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    MessageType.VIDEO -> {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = GlassColors.Accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    else -> {}
                }

                Text(
                    text = threadPreview(thread.latest, userNickname),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = GlassColors.InkSecondary,
                    style = GlassType.Subhead,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            val time = formatListTime(thread.latest.sentAt)
            if (time.isNotBlank()) {
                Text(
                    text = time,
                    style = GlassType.Caption,
                    color = GlassColors.InkTertiary,
                )
                if (thread.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (thread.unreadCount > 0) {
                GlassBadge(count = thread.unreadCount)
            }
        }
    }
}

fun unreadBadgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

fun threadPreview(message: RelayMessage, userNickname: String): String = when (message.type) {
    MessageType.TEXT -> message.text.orEmpty()
    MessageType.IMAGE -> "图片消息"
    MessageType.AUDIO -> "语音消息"
    MessageType.VIDEO -> "视频消息"
}.let { fallback -> substituteNickname(message.text?.trim()?.takeIf { it.isNotEmpty() }, userNickname) ?: fallback }
    .withoutTextPresentationSelector()

