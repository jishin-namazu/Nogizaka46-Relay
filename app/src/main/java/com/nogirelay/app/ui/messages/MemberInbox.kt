package com.nogirelay.app.ui.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleLight
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.withoutTextPresentationSelector

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemberInbox(
    threads: List<MemberThread>,
    userNickname: String,
    onSelect: (MemberThread) -> Unit,
    state: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        state = state,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (threads.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "最近消息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(threads.take(8), key = { it.id }) { thread ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .animateItem()
                                .width(74.dp)
                                .clickable { onSelect(thread) },
                        ) {
                            Box(
                                modifier = Modifier.size(62.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                RemoteImage(
                                    url = thread.avatarUrl,
                                    contentDescription = thread.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape),
                                    loadCachedImmediately = true,
                                )
                                if (thread.unreadCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    ) {
                                        Text(
                                            text = unreadBadgeLabel(thread.unreadCount),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = thread.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 4.dp),
            ) {
                Text(
                    text = "全部成员",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "共 ${threads.size} 位",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(threads, key = { it.id }) { thread ->
            Card(
                onClick = { onSelect(thread) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                            .clip(CircleShape),
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
                            color = MaterialTheme.colorScheme.onSurface,
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
                                        tint = BrandPurple,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                }
                                MessageType.IMAGE -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                        tint = BrandPurple,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Spacer(Modifier.width(3.dp))
                                }
                                MessageType.VIDEO -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Videocam,
                                        contentDescription = null,
                                        tint = BrandPurple,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (thread.unreadCount > 0) {
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                        if (thread.unreadCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                            ) {
                                Text(
                                    text = unreadBadgeLabel(thread.unreadCount),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
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
