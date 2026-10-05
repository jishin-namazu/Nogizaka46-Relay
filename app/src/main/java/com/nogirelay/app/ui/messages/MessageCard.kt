package com.nogirelay.app.ui.messages

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nogirelay.app.R
import com.nogirelay.app.UnreadTag
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.translation.normalizeTranslationText
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.SearchHighlightText
import com.nogirelay.app.ui.glass.GlassCircleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassPopover
import com.nogirelay.app.ui.glass.GlassPopoverItem
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalMediaSourceScope
import com.nogirelay.app.ui.glass.mediaSourceKey
import com.nogirelay.app.ui.glass.glassMediaSource
import com.nogirelay.app.ui.glass.glassPopoverAnchor
import com.nogirelay.app.ui.glass.rememberGlassPopoverState
import com.nogirelay.app.ui.withoutTextPresentationSelector
import java.util.Locale

/**
 * A message as a droplet of liquid glass. Text bubbles, media previews and
 * the voice player all share the material; the "more" button morphs into
 * the action menu in place (no crossfade), and media publishes its geometry
 * for the thumbnail-to-viewer shared element transition.
 */
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
    onToggleFavorite: () -> Unit,
    enabled: Boolean = true,
) {
    val canTranslate = remember(message.text, translationEnabled) {
        translationEnabled && TranslationManager.shouldTranslate(message.text)
    }
    val popoverState = rememberGlassPopoverState()
    val popoverScope = rememberCoroutineScope()
    LaunchedEffect(enabled) { if (!enabled) popoverState.dismiss(popoverScope) }
    val body = remember(message.text, userNickname) {
        substituteNickname(message.text, userNickname)?.takeIf { it.isNotBlank() }?.withoutTextPresentationSelector()
    }
    val translation = remember(message.text, message.translation, userNickname, translationEnabled) {
        if (translationEnabled) {
            normalizeTranslationText(
                substituteNickname(message.text, userNickname),
                substituteNickname(message.translation, userNickname),
            )?.withoutTextPresentationSelector()
        } else {
            null
        }
    }
    val sentAtLabel = remember(message.sentAt) { formatTimelineTime(message.sentAt) }

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f, fill = false).widthIn(max = 420.dp)) {
                when (message.type) {
                    MessageType.TEXT -> MessageBubble {
                        MessageTextContent(body, translation, searchQuery)
                    }
                    MessageType.IMAGE, MessageType.VIDEO -> {
                        if (body != null || translation != null) {
                            MessageBubble(
                                modifier = Modifier.fillMaxWidth(),
                                shape = GlassShapes.Card,
                                contentPadding = PaddingValues(vertical = 14.dp),
                            ) {
                                MessageMediaPreview(
                                    message, enabled, onOpenMedia,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                                Spacer(Modifier.height(8.dp))
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    MessageTextContent(body, translation, searchQuery)
                                }
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

            }
            Box(Modifier.padding(start = 8.dp)) {
                GlassCircleButton(
                    onClick = { popoverState.open() },
                    enabled = enabled,
                    size = 32.dp,
                    contentDescription = "更多消息操作",
                    modifier = Modifier.glassPopoverAnchor(popoverState),
                ) {
                    Icon(
                        Icons.Rounded.MoreHoriz,
                        contentDescription = null,
                        tint = GlassColors.InkSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                GlassPopover(state = popoverState, width = 224.dp) {
                    GlassPopoverItem(
                        label = if (message.isFavorite) "取消收藏" else "添加到收藏夹",
                        icon = if (message.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        onClick = { popoverState.dismiss(popoverScope); onToggleFavorite() },
                    )
                    if (message.type != MessageType.TEXT) {
                        GlassPopoverItem(
                            label = "保存到本地",
                            icon = Icons.Rounded.Download,
                            onClick = { popoverState.dismiss(popoverScope); onDownload() },
                        )
                    }
                    if (canTranslate) {
                        GlassPopoverItem(
                            label = "重新翻译",
                            onClick = { popoverState.dismiss(popoverScope); onRetranslate() },
                            iconTint = GlassColors.Accent,
                        customIcon = { AiTranslateIcon(size = 20.dp, tint = GlassColors.Accent, contentDescription = null) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(start = 6.dp, top = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchHighlightText(
                text = sentAtLabel,
                query = searchQuery,
                style = GlassType.Caption,
                color = GlassColors.InkTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isUnread) UnreadTag("未读")
        }
    }
}

@Composable
private fun MessageBubble(
    modifier: Modifier = Modifier,
    shape: androidx.compose.foundation.shape.RoundedCornerShape = GlassShapes.Bubble,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(
        modifier = modifier,
        shape = shape,
        blur = GlassBubbleBlur.dp,
        fillAlpha = GlassColors.NeutralFillStrongAlpha,
        staticMaterial = true,
        depth = GlassDepths.Low,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

private const val GlassBubbleBlur = 18f

@Composable
private fun MessageTextContent(body: String?, translation: String?, query: String) {
    body?.let {
        SelectionContainer {
            SearchHighlightText(
                text = it,
                query = query,
                style = GlassType.Body,
                color = GlassColors.Ink,
            )
        }
    }
    AnimatedContent(
        targetState = translation,
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
        },
        label = "message-translation",
    ) { translated ->
        translated?.let {
            if (body != null) Spacer(Modifier.height(8.dp))
            SelectionContainer {
                SearchHighlightText(
                    text = it,
                    query = query,
                    style = GlassType.Callout,
                    color = GlassColors.AccentInk,
                )
            }
        }
    }
}

@Composable
private fun MessageMediaPreview(
    message: RelayMessage,
    enabled: Boolean,
    onOpenMedia: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoHasAudioTrack by rememberVideoHasAudioTrack(message)
    val clipShape = GlassShapes.CardSmall
    Box(
        modifier.fillMaxWidth()
            .clip(clipShape)
            .glassMediaSource(
                key = mediaSourceKey(LocalMediaSourceScope.current, message.id),
                url = message.mediaUrl ?: message.thumbnailUrl,
                cornerRadiusPx = with(LocalDensity.current) { 20.dp.toPx() },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onOpenMedia),
    ) {
        RemoteImage(
            url = if (message.type == MessageType.IMAGE) message.mediaUrl ?: message.thumbnailUrl else message.thumbnailUrl ?: message.mediaUrl,
            contentDescription = if (message.type == MessageType.IMAGE) "图片消息" else "视频预览",
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 360.dp),
            contentScale = ContentScale.Crop,
            preserveAspectRatio = true,
            messageType = message.type,
            message = message,
            placeholderColor = Color(0x228E93A6),
        )
        if (message.type == MessageType.VIDEO) {
            if (videoHasAudioTrack == false) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp).size(32.dp)
                        .clip(GlassShapes.Circle)
                        .background(Color.Black.copy(alpha = 0.55f)),
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
            Box(
                modifier = Modifier.align(Alignment.Center).size(52.dp)
                    .clip(GlassShapes.Circle)
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "播放视频",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun VoiceMessagePlayer(message: RelayMessage, audioState: VoicePlaybackState?, enabled: Boolean, onPlayVoice: () -> Unit) {
    val context = LocalContext.current
    val playing = audioState?.isPlaying == true
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GlassCircleButton(
            onClick = onPlayVoice,
            enabled = enabled,
            tone = if (playing) GlassTone.Accent else GlassTone.Neutral,
            contentDescription = if (playing) "暂停语音" else "播放语音",
            size = 40.dp,
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
        VoicePlaybackTimeline(message, audioState, Modifier.weight(1f).padding(horizontal = 4.dp), enabled = enabled)

        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(
                visible = playing,
                // Fade the button and its full shadow in a padded layer. A tight
                // 40.dp alpha layer cuts the shadow into a gray rectangle.
                modifier = Modifier.requiredSize(104.dp),
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.9f),
                exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.9f),
            ) {
                Box(Modifier.padding(32.dp)) {
                    val speakerOn = audioState?.speakerOn == true
                    GlassCircleButton(
                        contentDescription = if (speakerOn) "切换到听筒" else "切换到扬声器",
                        tone = if (speakerOn) GlassTone.Accent else GlassTone.Neutral,
                        enabled = enabled && playing,
                        size = 40.dp,
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
                            modifier = Modifier.size(20.dp),
                        )
                    }
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

@Composable
internal fun rememberVideoHasAudioTrack(message: RelayMessage): State<Boolean?> {
    val context = LocalContext.current
    return produceState<Boolean?>(message.videoHasAudio, message.id, message.mediaUrl) {
        value = MediaDownloader.resolveVideoHasAudio(context, message)
    }
}


