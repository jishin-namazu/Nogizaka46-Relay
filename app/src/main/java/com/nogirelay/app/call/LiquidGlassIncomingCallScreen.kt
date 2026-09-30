package com.nogirelay.app.call

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.R
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.isRelayUiStarted
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.glass.GlassCircleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassControlWhite
import com.nogirelay.app.ui.glass.GlassMotion
import com.nogirelay.app.ui.glass.GlassTone
import java.util.Locale

private val CallInk = Color(0xFF101116)
private val CallGreen = Color(0xFF3FB57F)
private val CallRed = Color(0xFFE2606B)
private val CallFont = FontFamily(androidx.compose.ui.text.font.Font(R.font.noto_sans_jp_regular))

/**
 * Incoming call, liquid-glass style: the member's portrait fills the screen
 * under a gradient scrim; the answer / decline / speaker controls are big
 * floating droplets of tinted glass with soft-body press and a breathing
 * ring while ringing.
 */
@Composable
internal fun LiquidGlassIncomingCallScreen(
    message: RelayMessage,
    isRinging: Boolean,
    speakerOn: Boolean,
    playbackState: VoicePlaybackState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleSpeaker: () -> Unit,
) {
    val currentPlayback = playbackState.takeIf { it.messageId == message.id }
    val portraitUrl = message.phoneImageUrl?.takeIf(String::isNotBlank)
        ?: message.memberAvatarUrl?.takeIf(String::isNotBlank)
    LiquidGlassCallContent(
        callerName = message.incomingCallFrom?.takeIf(String::isNotBlank) ?: message.memberName,
        isRinging = isRinging,
        speakerOn = speakerOn,
        status = when {
            isRinging -> "语音来电"
            currentPlayback == null -> "正在连接…"
            currentPlayback.isPlaying -> "通话中"
            else -> "播放已暂停"
        },
        elapsedSeconds = (currentPlayback?.positionMs ?: 0).coerceAtLeast(0) / 1_000,
        onAnswer = onAnswer,
        onDecline = onDecline,
        onToggleSpeaker = onToggleSpeaker,
        visualActive = isRelayUiStarted(),
        portrait = {
            if (portraitUrl != null) {
                RemoteImage(
                    url = portraitUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
                    contentScale = ContentScale.Crop,
                    loadCachedImmediately = true,
                    placeholderColor = Color.Transparent,
                )
            }
        },
    )
}

@Composable
private fun LiquidGlassCallContent(
    callerName: String,
    isRinging: Boolean,
    speakerOn: Boolean,
    status: String,
    elapsedSeconds: Int,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onToggleSpeaker: () -> Unit,
    visualActive: Boolean = true,
    portrait: @Composable BoxScope.() -> Unit,
) {
    val breathing = rememberCallBreathing(isRinging, visualActive)
    Box(Modifier.fillMaxSize().background(CallInk)) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.linearGradient(listOf(Color(0xFF585B63), Color(0xFF2E3037), CallInk)),
                ),
            )
            Icon(
                Icons.Rounded.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.18f),
                modifier = Modifier.align(Alignment.Center).size(148.dp),
            )
            portrait()
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to CallInk.copy(alpha = 0.48f),
                        0.28f to CallInk.copy(alpha = 0.08f),
                        0.46f to CallInk.copy(alpha = 0.22f),
                        0.64f to CallInk.copy(alpha = 0.52f),
                        1f to CallInk.copy(alpha = 0.58f),
                    ),
                ),
            )
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compact = maxHeight < 520.dp
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = if (compact) 14.dp else 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = callerName,
                        color = Color.White,
                        fontFamily = CallFont,
                        fontSize = 32.sp,
                        lineHeight = 42.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.25f), blurRadius = 16f)),
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                    )
                    Spacer(Modifier.height(10.dp))
                    CallStatus(status, isRinging, elapsedSeconds, breathing = { breathing.value })
                    Spacer(Modifier.height(if (compact) 22.dp else 38.dp))
                    Row(
                        modifier = Modifier.widthIn(max = 292.dp).fillMaxWidth().padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.runtime.key("decline") {
                            GlassCallAction(
                                label = "挂断",
                                icon = Icons.Rounded.CallEnd,
                                tint = CallRed,
                                onClick = onDecline,
                                controlSize = if (compact) 68.dp else 78.dp,
                            )
                        }
                        androidx.compose.runtime.key(if (isRinging) "answer" else "speaker") {
                            if (isRinging) {
                                GlassCallAction(
                                    label = "接听",
                                    icon = Icons.Rounded.Call,
                                    tint = CallGreen,
                                    onClick = onAnswer,
                                    controlSize = if (compact) 68.dp else 78.dp,
                                    breathing = { breathing.value },
                                )
                            } else {
                                GlassCallAction(
                                    label = if (speakerOn) "扬声器开" else "扬声器",
                                    icon = Icons.Rounded.VolumeUp,
                                    tint = if (speakerOn) GlassColors.Accent else GlassControlWhite,
                                    onClick = onToggleSpeaker,
                                    controlSize = if (compact) 68.dp else 78.dp,
                                    checked = speakerOn,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallStatus(status: String, isRinging: Boolean, elapsedSeconds: Int, breathing: () -> Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(100))) },
            label = "call-status",
        ) { visibleStatus ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = if (visibleStatus == status) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else {
                    Modifier.clearAndSetSemantics { }
                },
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .graphicsLayer {
                            val progress = breathing().coerceIn(0f, 1f)
                            scaleX = 1f + progress * 0.22f
                            scaleY = scaleX
                            alpha = 0.78f + progress * 0.22f
                        }
                        .background(Color(0xFFBCEAD5), androidx.compose.foundation.shape.CircleShape),
                )
                Text(
                    visibleStatus,
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = if (isRinging) "" else String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60),
            color = Color.White.copy(alpha = 0.82f),
            fontSize = 14.sp,
            lineHeight = 22.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Shared app button material with semantic colors (green answer / red decline),
 * breathing gently while ringing and squishing on press via the shared physics.
 */
@Composable
private fun GlassCallAction(
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    controlSize: Dp,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    enabled: Boolean = true,
    breathing: () -> Float = { 0f },
) {
    val breath = breathing().coerceIn(0f, 1f)
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(controlSize).graphicsLayer {
                // Move the halo and droplet together so their centers stay aligned.
                translationY = -1.5.dp.toPx() * breath
            },
            contentAlignment = Alignment.Center,
        ) {
            // The halo uses the button's bounds and stays mounted as it fades out.
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1f + breath * 0.16f
                        scaleY = scaleX
                        alpha = breath * 0.30f
                    }
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(tint.copy(alpha = 0.35f)),
            )
            GlassCircleButton(
                onClick = onClick,
                enabled = enabled,
                tone = if (checked == false) GlassTone.Neutral else GlassTone.Accent,
                tint = tint,
                size = controlSize,
                pressScale = 1f + breath * 0.028f,
                contentDescription = label,
                toggleValue = checked,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(controlSize * 0.36f),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun RingingGlassCallPreview() {
    LiquidGlassCallContent(
        callerName = "池田 瑛紗",
        isRinging = true,
        speakerOn = false,
        status = "语音来电",
        elapsedSeconds = 0,
        onAnswer = {},
        onDecline = {},
        onToggleSpeaker = {},
        portrait = {},
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun PlayingGlassCallPreview() {
    LiquidGlassCallContent(
        callerName = "池田 瑛紗",
        isRinging = false,
        speakerOn = true,
        status = "通话中",
        elapsedSeconds = 75,
        onAnswer = {},
        onDecline = {},
        onToggleSpeaker = {},
        portrait = {},
    )
}


