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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.glass.GlassBadge
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.glass.glassPress
import com.nogirelay.app.ui.glass.rememberGlassPress
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.withoutTextPresentationSelector

private val MemberCardSpacing = 10.dp

/**
 * Member inbox, rebuilt as liquid glass: a horizontal constellation of
 * recent-contact droplets on top, then thread rows as floating glass
 * capsules over the shared milky backdrop.
 *
 * When a conversation overlay is open above the inbox, the entire list recedes
 * as one unified layer (subtle depth scale + skip draw once fully covered), and
 * visible cards continuously publish their unclipped coordinates to [slotRegistry]
 * so the sibling overlay can lift and land without hosting pages inside LazyColumn.
 */
@Composable
fun MemberInbox(
    threads: List<MemberThread>,
    userNickname: String,
    onSelect: (MemberThread, fromCard: Boolean) -> Unit,
    state: LazyListState = rememberLazyListState(),
    recedeProgress: (() -> Float)? = null,
    scrollEnabled: Boolean = true,
    inboxCovered: Boolean = false,
    hiddenThreadId: (() -> String?)? = null,
    slotRegistry: MemberCardSlotRegistry? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val reducedMotion = LocalGlassReducedMotion.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = recedeProgress?.invoke()?.coerceIn(0f, 1f) ?: 0f
                if (p >= CARD_INBOX_COVERED_PROGRESS) {
                    alpha = 0f
                    scaleX = 1f
                    scaleY = 1f
                } else {
                    alpha = 1f
                    val s = if (reducedMotion || p <= 0f) 1f else lerp(1f, 0.965f, p)
                    scaleX = s
                    scaleY = s
                }
            }
            // Capture inbox coordinates AFTER the receding graphicsLayer so
            // localBoundingBoxOf(cardCoords, clipBounds = false) always stays in
            // the unscaled resting coordinate space.
            .onGloballyPositioned { slotRegistry?.updateInboxCoordinates(it) }
            .then(if (inboxCovered) Modifier.clearAndSetSemantics { } else Modifier),
    ) {
        LazyColumn(
            state = state,
            userScrollEnabled = scrollEnabled,
            verticalArrangement = Arrangement.spacedBy(MemberCardSpacing),
            modifier = Modifier.fillMaxSize().testTag(UiTestTags.MEMBER_INBOX),
            contentPadding = PaddingValues(bottom = 128.dp),
        ) {
            header?.let { content ->
                item(key = "member-inbox-header") {
                    content()
                }
            }
            if (threads.isNotEmpty()) {
                item(key = "member-inbox-recent-title") {
                    Text(
                        text = "最近消息",
                        style = GlassType.Headline,
                        fontWeight = FontWeight.SemiBold,
                        color = GlassColors.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                }
                item(key = "member-inbox-recent") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier
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
                ThreadCapsule(
                    thread = thread,
                    userNickname = userNickname,
                    onClick = { onSelect(thread, true) },
                    hidden = { hiddenThreadId?.invoke() == thread.id },
                    slotRegistry = slotRegistry,
                    clipShadowBelow = thread.id != threads.last().id,
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
        }
    }
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
    hidden: () -> Boolean,
    slotRegistry: MemberCardSlotRegistry?,
    clipShadowBelow: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled = true)
    val lastCoords = remember(thread.id) { arrayOfNulls<LayoutCoordinates>(1) }
    DisposableEffect(thread.id, slotRegistry) {
        onDispose {
            lastCoords[0]?.let { coords -> slotRegistry?.unregisterSlot(thread.id, coords) }
            lastCoords[0] = null
        }
    }
    GlassPanel(
        onClick = onClick,
        onClickLabel = thread.name,
        shape = GlassShapes.Card,
        staticMaterial = true,
        interactionSource = interactionSource,
        press = press,
        modifier = modifier
            .testTag(UiTestTags.MEMBER_THREAD)
            .onGloballyPositioned { coords ->
                lastCoords[0] = coords
                slotRegistry?.registerSlot(
                    threadId = thread.id,
                    coords = coords,
                    pressProgress = press::progress,
                    clipShadowBelow = clipShadowBelow,
                )
            }
            .graphicsLayer { alpha = if (hidden()) 0f else 1f },
    ) {
        ThreadCapsuleContent(
            thread = thread,
            userNickname = userNickname,
        )
    }
}

@Composable
internal fun ThreadCapsuleContent(
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
