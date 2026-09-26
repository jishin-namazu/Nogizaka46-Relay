package com.nogirelay.app.ui.messages

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import com.nogirelay.app.NameWithUnreadTag
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
import com.nogirelay.app.ui.BrandPurpleLight
import com.nogirelay.app.ui.RelayCardShape
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SearchHighlightText
import com.nogirelay.app.ui.highlightMatches
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
) {
    val context = LocalContext.current
    val highlightBackground = MaterialTheme.colorScheme.primaryContainer
    val highlightText = MaterialTheme.colorScheme.onPrimaryContainer
    val videoHasAudioTrack by produceState<Boolean?>(
        initialValue = null,
        key1 = message.id,
        key2 = message.mediaUrl,
    ) {
        if (message.type == MessageType.VIDEO) {
            value = withContext(Dispatchers.IO) {
                MediaDownloader.cachedVideoHasAudioTrack(context, message)
            }
        }
    }
    var scrubPositionMs by remember(message.id) { mutableIntStateOf(0) }
    var scrubbing by remember(message.id) { mutableStateOf(false) }
    val audioPlaying = audioState?.isPlaying == true
    val audioDurationMs = audioState?.durationMs?.takeIf { it > 0 }
        ?: message.durationSeconds?.takeIf { it > 0 }?.times(1_000)
        ?: 0
    val audioPositionMs = audioState?.positionMs?.coerceIn(0, audioDurationMs.coerceAtLeast(0)) ?: 0
    LaunchedEffect(audioState?.positionMs, audioState?.durationMs, audioDurationMs) {
        if (!scrubbing) scrubPositionMs = audioPositionMs
    }
    val displayedPositionMs = if (scrubbing) {
        scrubPositionMs.coerceIn(0, audioDurationMs.coerceAtLeast(0))
    } else {
        audioPositionMs
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RelayCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = message.memberAvatarUrl,
                    contentDescription = message.memberName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                    loadCachedImmediately = true,
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    NameWithUnreadTag(
                        name = highlightMatches(message.memberName, searchQuery, highlightBackground, highlightText),
                        isUnread = isUnread,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        searchQuery = searchQuery,
                        highlightBackground = highlightBackground,
                    )
                    SearchHighlightText(
                        text = formatMessageDateTime(message.sentAt),
                        query = searchQuery,
                        highlightBackground = highlightBackground,
                        highlightTextColor = highlightText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                // 与翻译器看到的一致：它现在接收原始文本（含占位符），
                // 因此对于管理器会跳过的文本，按钮不应亮起。
                val canTranslate = remember(message.text) {
                    TranslationManager.shouldTranslate(message.text)
                }
                if (translationEnabled && canTranslate) {
                    IconButton(onClick = onRetranslate, modifier = Modifier.size(38.dp)) {
                        AiTranslateIcon(
                            tint = BrandPurple,
                            size = 22.dp,
                        )
                    }
                }
                if (message.type != MessageType.TEXT) {
                    IconButton(onClick = onDownload, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = "保存到本地",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            message.text?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    val displayedText = (substituteNickname(it, userNickname) ?: it).withoutTextPresentationSelector()
                    SearchHighlightText(
                        text = displayedText,
                        query = searchQuery,
                        highlightBackground = highlightBackground,
                        highlightTextColor = highlightText,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (translationEnabled) {
                normalizeTranslationText(
                    substituteNickname(message.text, userNickname),
                    substituteNickname(message.translation, userNickname),
                )?.let { transText ->
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer {
                        SearchHighlightText(
                            text = transText.withoutTextPresentationSelector(),
                            query = searchQuery,
                            highlightBackground = highlightBackground,
                            highlightTextColor = highlightText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = BrandPurpleDark,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                            ),
                        )
                    }
                }
            }

            when (message.type) {
                MessageType.IMAGE, MessageType.VIDEO -> {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .clickable(onClick = onOpenMedia),
                    ) {
                        RemoteImage(
                            url = if (message.type == MessageType.IMAGE) {
                                message.mediaUrl ?: message.thumbnailUrl
                            } else {
                                message.thumbnailUrl ?: message.mediaUrl
                            },
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            preserveAspectRatio = true,
                            messageType = message.type,
                            message = message,
                            placeholderColor = Color.Transparent,
                        )
                        if (message.type == MessageType.VIDEO) {
                            if (videoHasAudioTrack == false) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(10.dp)
                                        .size(32.dp)
                                        .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VolumeOff,
                                        contentDescription = "视频无声音",
                                        tint = Color.White,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "播放视频",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }
                    }
                }

                MessageType.AUDIO -> {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BrandPurpleLight.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            IconButton(onClick = onPlayVoice) {
                                Icon(
                                    imageVector = if (audioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (audioPlaying) "暂停语音" else "播放语音",
                                    tint = BrandPurple,
                                )
                            }
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (message.isPlayed) "语音消息" else "未播放语音",
                                        fontWeight = FontWeight.SemiBold,
                                        color = BrandPurpleDark,
                                        fontSize = 13.sp,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        formatAudioTime(displayedPositionMs),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        " / ${formatAudioDuration(audioDurationMs)}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Slider(
                                    value = displayedPositionMs.toFloat(),
                                    onValueChange = { value ->
                                        if (audioDurationMs > 0) {
                                            scrubbing = true
                                            scrubPositionMs = value.toInt()
                                        }
                                    },
                                    onValueChangeFinished = {
                                        if (audioDurationMs > 0) {
                                            VoicePlaybackService.seek(
                                                context = context,
                                                messageId = message.id,
                                                positionMs = scrubPositionMs,
                                            )
                                        }
                                        scrubbing = false
                                    },
                                    valueRange = 0f..audioDurationMs.coerceAtLeast(1).toFloat(),
                                    enabled = audioDurationMs > 0,
                                    colors = SliderDefaults.colors(
                                        thumbColor = BrandPurple,
                                        activeTrackColor = BrandPurple,
                                        inactiveTrackColor = BrandPurple.copy(alpha = 0.25f),
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(28.dp),
                                )
                            }
                            if (audioPlaying) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .padding(horizontal = 4.dp)
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(BrandPurple.copy(alpha = 0.12f))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            context.startService(
                                                Intent(context, VoicePlaybackService::class.java).apply {
                                                    action = VoicePlaybackService.ACTION_SET_SPEAKER
                                                    putExtra(VoicePlaybackService.EXTRA_SPEAKER_ON, !(audioState?.speakerOn ?: false))
                                                }
                                            )
                                        }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_audio_speaker_official),
                                        contentDescription = if (audioState?.speakerOn == true) "切换到听筒" else "切换到扬声器",
                                        tint = if (audioState?.speakerOn == true)
                                            BrandPurple
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                MessageType.TEXT -> Unit
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

private val messageDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

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
