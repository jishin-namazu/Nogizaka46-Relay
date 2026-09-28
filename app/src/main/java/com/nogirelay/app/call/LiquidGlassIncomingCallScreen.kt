package com.nogirelay.app.call

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
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
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.RelayMediaGlassBackground
import com.nogirelay.app.ui.RelayNavigationSelectionShape
import com.nogirelay.app.ui.RemoteImage
import java.util.Locale

private val CallInk = Color.Black
private val CallGreen = Color(0xFF48DB96)
private val CallRed = Color(0xFFFF7582)
private val CallFont = FontFamily(Font(R.font.noto_sans_jp_regular))

/** Presentation only: ringing, audio routing and call cleanup stay in IncomingCallActivity. */
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
        // Do not display a previous message's time while the service is opening this call.
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
        // Match the media viewer: keep the portrait clear beneath transparent glass overlays.
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
            // Anchor caller controls below the portrait; scrolling keeps every action reachable
            // in landscape and at large fonts without adding a header over the photograph.
            Column(
                modifier = Modifier.align(Alignment.TopCenter)
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
                        modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        GlassCallAction(
                            label = if (isRinging) "拒接" else "挂断",
                            icon = Icons.Rounded.CallEnd,
                            tint = CallRed,
                            onClick = onDecline,
                            controlSize = if (compact) 76.dp else 88.dp,
                            modifier = Modifier.weight(1f),
                        )
                        AnimatedContent(
                            targetState = isRinging,
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.TopCenter,
                            transitionSpec = {
                                (fadeIn(tween(200)) + scaleIn(tween(240), initialScale = 0.90f))
                                    .togetherWith(fadeOut(tween(110)))
                            },
                            label = "call-answer-to-speaker",
                        ) { ringing ->
                            val active = ringing == isRinging
                            GlassCallAction(
                                label = when {
                                    ringing -> "接听"
                                    speakerOn -> "扬声器 · 开"
                                    else -> "扬声器 · 关"
                                },
                                icon = if (ringing) Icons.Rounded.Call else Icons.AutoMirrored.Rounded.VolumeUp,
                                tint = when {
                                    ringing -> CallGreen
                                    speakerOn -> Color(0xFFB7A1FF)
                                    else -> Color.White
                                },
                                onClick = if (ringing) onAnswer else onToggleSpeaker,
                                checked = if (ringing) null else speakerOn,
                                enabled = active,
                                controlSize = if (compact) 76.dp else 88.dp,
                                breathing = { if (ringing && active) breathing.value else 0f },
                                // The outgoing answer button cannot receive taps or focus.
                                modifier = Modifier.fillMaxWidth().then(
                                    if (active) Modifier else Modifier.clearAndSetSemantics { },
                                ),
                            )
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
                    Modifier.size(6.dp).graphicsLayer {
                        val progress = breathing().coerceIn(0f, 1f)
                        scaleX = 1f + progress * 0.22f
                        scaleY = scaleX
                        alpha = 0.78f + progress * 0.22f
                    }.background(Color(0xFFBCEAD5), CircleShape),
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
        // Always reserve this line, so answering never displaces the caller or the controls.
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

/** The entire label is a touch target above the media viewer's shared empty glass background. */
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
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val scale = animateFloatAsState(
        if (pressed && enabled) 0.94f else 1f,
        spring(dampingRatio = 0.75f),
        label = "call-glass-press",
    )
    val foreground by animateColorAsState(tint, tween(180), label = "call-glass-icon-tint")
    val interactionModifier = if (checked == null) {
        Modifier.clickable(
            enabled = enabled,
            role = Role.Button,
            onClickLabel = label,
            interactionSource = interactions,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier.semantics { stateDescription = if (checked) "已开启" else "已关闭" }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactions,
                indication = null,
                onValueChange = { onClick() },
            )
    }
    Column(
        modifier = modifier.then(interactionModifier).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(controlSize).padding(4.dp)
                .graphicsLayer {
                    val progress = breathing().coerceIn(0f, 1f)
                    scaleX = scale.value * (1f + progress * 0.028f)
                    scaleY = scaleX
                    translationY = -1.5.dp.toPx() * progress
                }
                .drawWithCache {
                    val radius = size.minDimension * 0.85f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val glow = Brush.radialGradient(
                        0f to CallGreen.copy(alpha = 0.22f),
                        0.55f to CallGreen.copy(alpha = 0.09f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius,
                    )
                    onDrawBehind {
                        val progress = breathing().coerceIn(0f, 1f)
                        if (progress > 0f) drawCircle(glow, radius, center, alpha = progress)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            RelayMediaGlassBackground(
                modifier = Modifier.matchParentSize(),
                shape = RelayNavigationSelectionShape,
            )
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val outline = RelayNavigationSelectionShape.createOutline(size, layoutDirection, this)
                    onDrawBehind {
                        // A faint light lift on the existing surface, without another glass rim.
                        drawOutline(outline, Color.White, alpha = breathing().coerceIn(0f, 1f) * 0.045f)
                    }
                },
            )
            if (focused) Box(Modifier.matchParentSize().border(2.dp, Color.White, RelayNavigationSelectionShape))
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(32.dp))
        }
        Text(
            label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "来电 · 写真", widthDp = 393, heightDp = 852)
@Preview(name = "来电 · 窄屏大字", widthDp = 320, heightDp = 568, fontScale = 1.5f)
@Preview(name = "来电 · 横屏", widthDp = 760, heightDp = 360)
@Composable
private fun RingingGlassCallPreview() {
    NogiRelayTheme(darkTheme = true) {
        LiquidGlassCallContent("池田 瑛紗", true, false, "语音来电", 0, {}, {}, {}) {
            Image(painterResource(R.drawable.ikeda_teresa_phone_image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Preview(name = "通话 · 扬声器 · 无写真", widthDp = 393, heightDp = 852)
@Composable
private fun PlayingGlassCallPreview() {
    NogiRelayTheme(darkTheme = true) {
        LiquidGlassCallContent("池田 瑛紗", false, true, "通话中", 42, {}, {}, {}, portrait = {})
    }
}
