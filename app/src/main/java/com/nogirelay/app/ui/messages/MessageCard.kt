package com.nogirelay.app.ui.messages

import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Download
import com.nogirelay.app.UnreadTag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.R
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.translation.normalizeTranslationText
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleDark
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RelayGlassDropdownMenu
import com.nogirelay.app.ui.RelayMirrorGlassBackground
import com.nogirelay.app.ui.RelayMirrorGlassIconButton
import com.nogirelay.app.ui.RelayMediaPlaybackButton
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SearchHighlightText
import com.nogirelay.app.ui.withoutTextPresentationSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MessageCard(
    message: RelayMessage,
    modifier: Modifier = Modifier,
    isUnread: Boolean = false,
    audioState: VoicePlaybackState?,
    translationEnabled: Boolean,
    userNickname: String,
    searchQuery: String,
    onOpenMedia: () -> Unit,
    onPlayVoice: () -> Unit,
    onDownload: () -> Unit,
    onRetranslate: () -> Unit,
    enabled: Boolean = true,
) {
    val canTranslate = remember(message.text, translationEnabled) {
        translationEnabled && TranslationManager.shouldTranslate(message.text)
    }
    val hasActions = message.type != MessageType.TEXT || canTranslate
    var showActions by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) showActions = false }
    val body = remember(message.text, userNickname) {
        substituteNickname(message.text, userNickname)?.takeIf { it.isNotBlank() }?.withoutTextPresentationSelector()
    }
    val translation = remember(message.text, message.translation, userNickname, translationEnabled) {
        if (translationEnabled) {
            normalizeTranslationText(
                substituteNickname(message.text, userNickname),
                substituteNickname(message.translation, userNickname),
            )?.withoutTextPresentationSelector()
        } else null
    }

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f, fill = false).widthIn(max = 420.dp)) {
            when (message.type) {
                MessageType.TEXT -> MessageBubble {
                    MessageTextContent(body, translation, searchQuery)
                }
                MessageType.IMAGE, MessageType.VIDEO -> {
                    if (body != null || translation != null) {
                        MessageBubble(Modifier.fillMaxWidth()) {
                            MessageMediaPreview(message, enabled, onOpenMedia)
                            Spacer(Modifier.height(8.dp))
                            MessageTextContent(body, translation, searchQuery)
                        }
                    } else {
                        MessageMediaPreview(message, enabled, onOpenMedia)
                    }
                }
                MessageType.AUDIO -> MessageBubble(Modifier.fillMaxWidth()) {
                    if (body != null || translation != null) {
                        MessageTextContent(body, translation, searchQuery)
                        Spacer(Modifier.height(8.dp))
                    }
                    VoiceMessagePlayer(message, audioState, enabled, onPlayVoice)
                }
            }
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SearchHighlightText(
                    text = formatTimelineTime(message.sentAt),
                    query = searchQuery,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isUnread) UnreadTag("未读")
            }
        }
        if (hasActions) {
            Box {
                IconButton(onClick = { showActions = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = "更多消息操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                RelayGlassDropdownMenu(
                    expanded = showActions && enabled,
                    onDismissRequest = { showActions = false },
                ) {
                    if (message.type != MessageType.TEXT) {
                        DropdownMenuItem(
                            text = { Text("保存到本地", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null, tint = BrandPurple) },
                            modifier = Modifier.clip(RelayControlShape),
                            onClick = { showActions = false; onDownload() },
                        )
                    }
                    if (canTranslate) {
                        DropdownMenuItem(
                            text = { Text("重新翻译", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { AiTranslateIcon(tint = BrandPurple, size = 22.dp, contentDescription = null) },
                            modifier = Modifier.clip(RelayControlShape),
                            onClick = { showActions = false; onRetranslate() },
                        )
                    }
                }
            }
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun MessageBubble(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier, propagateMinConstraints = true) {
        RelayMirrorGlassBackground(
            shape = RelayControlShape,
            modifier = Modifier.matchParentSize(),
            tint = Color.White.copy(alpha = 0.04f),
            preserveSourceColors = true,
            emphasizeEdges = true,
        )
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun MessageTextContent(body: String?, translation: String?, query: String) {
    body?.let {
        SelectionContainer {
            SearchHighlightText(
                text = it,
                query = query,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    translation?.let {
        if (body != null) Spacer(Modifier.height(8.dp))
        SelectionContainer {
            SearchHighlightText(
                text = it,
                query = query,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = BrandPurpleDark,
            )
        }
    }
}

@Composable
private fun MessageMediaPreview(message: RelayMessage, enabled: Boolean, onOpenMedia: () -> Unit) {
    val context = LocalContext.current
    val videoHasAudioTrack by produceState<Boolean?>(null, message.id, message.mediaUrl) {
        if (message.type == MessageType.VIDEO) {
            value = withContext(Dispatchers.IO) { MediaDownloader.cachedVideoHasAudioTrack(context, message) }
        }
    }
    Box(
        Modifier.fillMaxWidth()
            .clip(RelayControlShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpenMedia),
    ) {
        RemoteImage(
            url = if (message.type == MessageType.IMAGE) message.mediaUrl ?: message.thumbnailUrl else message.thumbnailUrl ?: message.mediaUrl,
            contentDescription = if (message.type == MessageType.IMAGE) "图片消息" else "视频预览",
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
            contentScale = ContentScale.Fit,
            preserveAspectRatio = true,
            messageType = message.type,
            message = message,
            placeholderColor = Color.Transparent,
        )
        if (message.type == MessageType.VIDEO) {
            if (videoHasAudioTrack == false) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(32.dp)
                        .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = "视频无声音",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            RelayMediaPlaybackButton(
                onClick = onOpenMedia,
                contentDescription = "播放视频",
                enabled = enabled,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun VoiceMessagePlayer(message: RelayMessage, audioState: VoicePlaybackState?, enabled: Boolean, onPlayVoice: () -> Unit) {
    val context = LocalContext.current
    val playing = audioState?.isPlaying == true
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RelayMirrorGlassIconButton(
            onClick = onPlayVoice,
            enabled = enabled,
            contentDescription = if (playing) "暂停语音" else "播放语音",
        ) {
            Crossfade(
                targetState = playing,
                animationSpec = tween(160),
                modifier = Modifier.size(22.dp),
                label = "voice_play_pause",
            ) { isPlaying ->
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        VoicePlaybackTimeline(message, audioState, Modifier.weight(1f), enabled = enabled)
        // A permanent slot prevents the timeline from resizing when the speaker fades out.
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                visible = playing,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.9f),
                exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.9f),
            ) {
                val speakerOn = audioState?.speakerOn == true
                RelayMirrorGlassIconButton(
                    contentDescription = if (speakerOn) "切换到听筒" else "切换到扬声器",
                    active = speakerOn,
                    enabled = enabled && playing,
                    modifier = if (playing) Modifier else Modifier.clearAndSetSemantics {},
                    onClick = {
                        context.startService(Intent(context, VoicePlaybackService::class.java).apply {
                            action = VoicePlaybackService.ACTION_SET_SPEAKER
                            putExtra(VoicePlaybackService.EXTRA_SPEAKER_ON, !speakerOn)
                        })
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_audio_speaker_official),
                        contentDescription = null,
                        tint = if (speakerOn) BrandPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

fun formatAudioTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.getDefault(), totalSeconds / 60, totalSeconds % 60)
}

fun formatAudioDuration(milliseconds: Int): String =
    if (milliseconds > 0) formatAudioTime(milliseconds) else "--:--"

private val messageDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

fun formatMessageDateTime(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return input

    runCatching { Instant.parse(input) }.getOrNull()?.let {
        return messageDateFormatter.withZone(ZoneId.systemDefault()).format(it)
    }
    runCatching { OffsetDateTime.parse(input).toInstant() }.getOrNull()?.let {
        return messageDateFormatter.withZone(ZoneId.systemDefault()).format(it)
    }

    val local = runCatching {
        LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(input.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull()
    return local?.format(messageDateFormatter) ?: input
}
