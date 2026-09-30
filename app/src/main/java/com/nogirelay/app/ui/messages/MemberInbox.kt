package com.nogirelay.app.ui.messages

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.glass.GlassBadge
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.glassPress
import com.nogirelay.app.ui.glass.rememberGlassPress
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.withoutTextPresentationSelector

/**
 * Member inbox, rebuilt as liquid glass: a horizontal constellation of
 * recent-contact droplets on top, then thread rows as floating glass
 * capsules over the shared milky backdrop.
 */
@Composable
fun MemberInbox(
    threads: List<MemberThread>,
    userNickname: String,
    onSelect: (MemberThread) -> Unit,
    state: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
) {
    LazyColumn(
        state = state,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
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
                    fontSize = 15.sp,
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
                            onClick = { onSelect(thread) },
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Ink,
                )
                Text(
                    text = "共 ${threads.size} 位",
                    fontSize = 12.sp,
                    color = GlassColors.InkTertiary,
                )
            }
        }

        items(threads, key = { it.id }) { thread ->
            ThreadCapsule(
                thread = thread,
                userNickname = userNickname,
                onClick = { onSelect(thread) },
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
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
        modifier = modifier.width(74.dp),
    ) {
        // Clip only the photo; the badge must remain outside the circular clip.
        Box(
            modifier = Modifier.size(64.dp).glassPress(press).clickable(
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
            fontSize = 11.5.sp,
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
) {
    GlassPanel(
        onClick = onClick,
        onClickLabel = thread.name,
        shape = GlassShapes.Card,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
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
                    fontSize = 15.sp,
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
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                val time = formatThreadTime(thread.latest.sentAt)
                if (time.isNotBlank()) {
                    Text(
                        text = time,
                        fontSize = 11.sp,
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
}

fun unreadBadgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

fun threadPreview(message: RelayMessage, userNickname: String): String = when (message.type) {
    MessageType.TEXT -> message.text.orEmpty()
    MessageType.IMAGE -> "图片消息"
    MessageType.AUDIO -> "语音消息"
    MessageType.VIDEO -> "视频消息"
}.let { fallback -> substituteNickname(message.text?.trim()?.takeIf { it.isNotEmpty() }, userNickname) ?: fallback }
    .withoutTextPresentationSelector()

private fun formatThreadTime(sentAt: String): String {
    return runCatching {
        val trimmed = sentAt.trim()
        if (trimmed.length >= 16) {
            trimmed.substring(11, 16)
        } else {
            trimmed
        }
    }.getOrDefault("")
}

