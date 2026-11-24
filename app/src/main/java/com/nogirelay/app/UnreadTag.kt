package com.nogirelay.app

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.ui.drawSearchHighlightBoxes
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import kotlinx.coroutines.delay

private const val UNREAD_INLINE_ID = "unread-tag"

internal const val DEFAULT_MESSAGE_UNREAD_DWELL_MILLIS = 420L
internal const val DEFAULT_BLOG_UNREAD_DWELL_MILLIS = 260L
internal const val UNREAD_DISSOLVE_DURATION_MILLIS = 680
internal const val MESSAGE_UNREAD_DISSOLVE_CLEANUP_MILLIS =
    DEFAULT_MESSAGE_UNREAD_DWELL_MILLIS + UNREAD_DISSOLVE_DURATION_MILLIS + 320L
internal const val BLOG_UNREAD_DISSOLVE_CLEANUP_MILLIS =
    DEFAULT_BLOG_UNREAD_DWELL_MILLIS + UNREAD_DISSOLVE_DURATION_MILLIS + 420L

enum class UnreadTagState {
    HIDDEN,
    UNREAD,
    DISSOLVING,
}

fun resolveUnreadTagState(
    hintEnabled: Boolean,
    isUnread: Boolean,
    isDissolving: Boolean,
): UnreadTagState = when {
    !hintEnabled -> UnreadTagState.HIDDEN
    isDissolving -> UnreadTagState.DISSOLVING
    isUnread -> UnreadTagState.UNREAD
    else -> UnreadTagState.HIDDEN
}

private data class DissolveSeed(
    val x: Float,
    val y: Float,
    val startT: Float,
    val scale: Float,
    val driftX: Float,
    val liftY: Float,
    val radiusDp: Float,
    val useAccentTint: Boolean,
)

// Organic right-to-left & edge-inward erosion cells + evaporating mist droplets.
private val DissolveSeeds = listOf(
    DissolveSeed(0.94f, 0.24f, 0.00f, 0.92f, 4.2f, 8.6f, 1.45f, true),
    DissolveSeed(0.88f, 0.78f, 0.03f, 1.00f, 3.6f, 6.4f, 1.35f, false),
    DissolveSeed(0.79f, 0.42f, 0.07f, 0.96f, 2.8f, 9.2f, 1.25f, true),
    DissolveSeed(0.72f, 0.16f, 0.11f, 0.88f, 2.2f, 8.0f, 1.15f, false),
    DissolveSeed(0.67f, 0.84f, 0.13f, 1.04f, 2.6f, 5.8f, 1.40f, true),
    DissolveSeed(0.58f, 0.48f, 0.16f, 1.06f, 1.4f, 9.6f, 1.30f, true),
    DissolveSeed(0.51f, 0.20f, 0.20f, 0.94f, 0.8f, 7.8f, 1.20f, false),
    DissolveSeed(0.45f, 0.78f, 0.22f, 1.00f, 1.2f, 6.6f, 1.35f, true),
    DissolveSeed(0.37f, 0.38f, 0.26f, 1.05f, 0.4f, 8.8f, 1.25f, false),
    DissolveSeed(0.31f, 0.82f, 0.29f, 0.95f, -0.8f, 6.2f, 1.30f, true),
    DissolveSeed(0.24f, 0.24f, 0.32f, 0.96f, -1.2f, 8.4f, 1.15f, true),
    DissolveSeed(0.18f, 0.62f, 0.35f, 1.02f, -1.8f, 7.2f, 1.35f, false),
    DissolveSeed(0.11f, 0.30f, 0.38f, 0.92f, -2.4f, 8.2f, 1.20f, true),
    DissolveSeed(0.06f, 0.72f, 0.41f, 0.98f, -2.6f, 6.0f, 1.25f, false),
    DissolveSeed(0.62f, 0.56f, 0.18f, 0.90f, 2.0f, 7.4f, 1.05f, false),
    DissolveSeed(0.28f, 0.50f, 0.30f, 0.96f, -0.6f, 9.0f, 1.10f, true),
)

@Composable
fun GraduatedTag(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(if (compact) 3.dp else 4.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = if (compact) 3.dp else 4.dp),
) {
    val tagHeight = if (compact) 12.dp else 16.dp
    val textSize = (if (compact) GlassType.Micro else GlassType.Caption2).fontSize
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tagHeight)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = shape,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "卒業",
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                lineHeight = textSize,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun UnreadTag(
    text: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val cornerRadius = if (compact) 3.dp else 4.dp
    val horizontalPadding = if (compact) 3.dp else 4.dp
    val tagHeight = if (compact) 12.dp else 16.dp
    val textSize = (if (compact) GlassType.Micro else GlassType.Caption2).fontSize
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tagHeight)
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(cornerRadius),
            )
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                lineHeight = textSize,
                letterSpacing = 0.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun DissolvingUnreadTag(
    text: String,
    state: UnreadTagState,
    onDissolved: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    dissolveActive: Boolean = true,
    dwellMillis: Long = DEFAULT_MESSAGE_UNREAD_DWELL_MILLIS,
) {
    if (state == UnreadTagState.HIDDEN) return
    if (state == UnreadTagState.UNREAD) {
        UnreadTag(text = text, modifier = modifier, compact = compact)
        return
    }

    val reducedMotion = LocalGlassReducedMotion.current
    val progress = remember { Animatable(0f) }
    val latestOnDissolved by rememberUpdatedState(onDissolved)
    val accentColor = MaterialTheme.colorScheme.onErrorContainer
    val containerColor = MaterialTheme.colorScheme.errorContainer
    val particleTintA = remember(accentColor, containerColor) { lerp(containerColor, accentColor, 0.72f) }
    val particleTintB = remember(accentColor, containerColor) { lerp(containerColor, accentColor, 0.35f) }

    LaunchedEffect(state, dissolveActive, reducedMotion) {
        if (!dissolveActive) return@LaunchedEffect
        if (reducedMotion) {
            delay(dwellMillis.coerceAtMost(220L))
            progress.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
        } else {
            if (dwellMillis > 0L) delay(dwellMillis)
            progress.animateTo(1f, tween(UNREAD_DISSOLVE_DURATION_MILLIS, easing = LinearEasing))
        }
        latestOnDissolved()
    }

    val p = progress.value
    Box(
        modifier = modifier.drawWithContent {
            drawContent()
            if (p <= 0f || p >= 1f || reducedMotion || size.width <= 0f || size.height <= 0f) return@drawWithContent
            for (seed in DissolveSeeds) {
                val particleT = ((p - seed.startT) / 0.56f).coerceIn(0f, 1f)
                if (particleT <= 0f || particleT >= 1f) continue
                val rise = 1f - (1f - particleT) * (1f - particleT)
                val center = Offset(
                    x = seed.x * size.width + seed.driftX.dp.toPx() * rise,
                    y = seed.y * size.height - seed.liftY.dp.toPx() * rise,
                )
                val fade = if (particleT < 0.20f) {
                    particleT / 0.20f
                } else {
                    val tail = (1f - particleT) / 0.80f
                    tail * tail
                }
                val radius = (seed.radiusDp * (1.1f - 0.65f * particleT)).dp.toPx().coerceAtLeast(0.5f)
                val tint = if (seed.useAccentTint) particleTintA else particleTintB
                drawCircle(
                    color = tint.copy(alpha = (fade * 0.26f).coerceIn(0f, 1f)),
                    radius = radius * 2.1f,
                    center = center,
                )
                drawCircle(
                    color = tint.copy(alpha = (fade * 0.86f).coerceIn(0f, 1f)),
                    radius = radius,
                    center = center,
                )
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        UnreadTag(
            text = text,
            compact = compact,
            modifier = Modifier
                .graphicsLayer {
                    if (p <= 0f) return@graphicsLayer
                    if (reducedMotion) {
                        alpha = (1f - p).coerceIn(0f, 1f)
                        return@graphicsLayer
                    }
                    compositingStrategy = CompositingStrategy.Offscreen
                    val tailFade = 1f - ((p - 0.44f) / 0.38f).coerceIn(0f, 1f)
                    alpha = tailFade * tailFade
                    val scale = 1f - 0.08f * p
                    scaleX = scale
                    scaleY = scale
                    translationY = -2.dp.toPx() * p
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && p > 0.04f) {
                        val blurPx = (3.5.dp.toPx() * p * p).coerceAtLeast(0.5f)
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Decal)
                    }
                }
                .drawWithContent {
                    drawContent()
                    if (p <= 0f || reducedMotion || size.width <= 0f || size.height <= 0f) return@drawWithContent
                    for (seed in DissolveSeeds) {
                        val cellT = ((p - seed.startT) / 0.42f).coerceIn(0f, 1f)
                        if (cellT <= 0f) continue
                        val eased = FastOutSlowInEasing.transform(cellT)
                        val radius = (size.height * (0.26f + 0.56f * seed.scale) * eased).coerceAtLeast(1f)
                        val center = Offset(seed.x * size.width, seed.y * size.height)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.Black,
                                0.58f to Color.Black.copy(alpha = 0.88f),
                                1f to Color.Transparent,
                                center = center,
                                radius = radius,
                            ),
                            radius = radius,
                            center = center,
                            blendMode = BlendMode.DstOut,
                        )
                    }
                },
        )
    }
}

@Composable
fun NameWithUnreadTag(
    name: AnnotatedString,
    isUnread: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    searchQuery: String = "",
    highlightBackground: Color = Color.Unspecified,
    unreadState: UnreadTagState = if (isUnread) UnreadTagState.UNREAD else UnreadTagState.HIDDEN,
    dissolveActive: Boolean = true,
    dissolveDwellMillis: Long = DEFAULT_BLOG_UNREAD_DWELL_MILLIS,
    onUnreadDissolved: () -> Unit = {},
) {
    var textLayoutResult by remember(name, searchQuery) { mutableStateOf<TextLayoutResult?>(null) }
    val showInlineTag = unreadState != UnreadTagState.HIDDEN
    val text = buildAnnotatedString {
        append(name)
        if (showInlineTag) appendInlineContent(UNREAD_INLINE_ID, " 未读")
    }
    val inlineContent = buildMap {
        if (showInlineTag) {
            put(
                UNREAD_INLINE_ID,
                InlineTextContent(
                    placeholder = Placeholder(
                        width = if (compact) 30.sp else 36.sp,
                        height = if (compact) 12.sp else 16.sp,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        DissolvingUnreadTag(
                            text = "未读",
                            state = unreadState,
                            onDissolved = onUnreadDissolved,
                            compact = compact,
                            dissolveActive = dissolveActive,
                            dwellMillis = dissolveDwellMillis,
                        )
                    }
                },
            )
        }
    }
    Text(
        text = text,
        inlineContent = inlineContent,
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { textLayoutResult = it },
        modifier = modifier.then(
            if (searchQuery.isNotBlank() && highlightBackground.isSpecified) {
                Modifier.drawBehind {
                    textLayoutResult?.let { layout ->
                        drawSearchHighlightBoxes(
                            result = layout,
                            query = searchQuery,
                            text = name.text,
                            highlightColor = highlightBackground,
                        )
                    }
                }
            } else {
                Modifier
            },
        ),
    )
}
