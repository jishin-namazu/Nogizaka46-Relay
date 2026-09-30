package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Glass controls: switch, viscous slider, segmented tabs, search field and
 * text field — all built from the same liquid-glass material and physics.
 */

// ---------------------------------------------------------------------------
// Switch
// ---------------------------------------------------------------------------

@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource, enabled)
    val trackShape = GlassShapes.Capsule

    // Short taps in a scrolling parent can emit press/release in the same frame.
    // A state-change pulse guarantees visible feedback even for those taps.
    val toggleScale = remember { Animatable(1f) }
    var previousChecked by remember { mutableStateOf(checked) }
    LaunchedEffect(checked) {
        if (previousChecked != checked) {
            previousChecked = checked
            toggleScale.animateTo(0.9f, tween(90))
            toggleScale.animateTo(1f, GlassMotion.ReleaseSpec)
        }
    }

    // Stretch during travel, then restore a concentric circle at either end.
    val thumbAnim = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) { thumbAnim.animateTo(if (checked) 1f else 0f, GlassMotion.SnapSpec) }
    val thumbTravel = thumbAnim.value.coerceIn(0f, 1f)
    val thumbStretch = 1f + (kotlin.math.abs(thumbAnim.velocity) * 0.05f).coerceAtMost(0.22f) *
        (4f * thumbTravel * (1f - thumbTravel))

    val tintProgress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = GlassMotion.GentleSpec,
        label = "glass_switch_tint",
    )

    Box(
        modifier = modifier
            .size(width = 56.dp, height = 48.dp)
            .semantics {
                if (label != null) contentDescription = label
            }
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null,
                onValueChange = onCheckedChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .graphicsLayer {
                    val scale = minOf(1f - 0.1f * press.progress, toggleScale.value)
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .clip(trackShape)
                    .glass(
                        shape = trackShape,
                        tone = if (tintProgress > 0.5f) GlassTone.Accent else GlassTone.Neutral,
                        fillAlpha = if (tintProgress > 0.5f) {
                            GlassColors.AccentFillAlpha
                        } else {
                            GlassColors.NeutralFillStrongAlpha
                        },
                        blur = GlassOpticsPresets.BlurControl.dp,
                        interactionSource = interactionSource,
                    )
                    .glassEdgeLight(trackShape, if (tintProgress > 0.5f) GlassTone.Accent else GlassTone.Neutral),
            )
            // Equal inset keeps the thumb concentric with either rounded end.
            val thumbInset = 2.dp
            val thumbSize = maxHeight - thumbInset * 2
            val travelPx = with(LocalDensity.current) { (maxWidth - thumbSize - thumbInset * 2).toPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset((thumbInset.toPx() + travelPx * thumbTravel).roundToInt(), 0) }
                    .align(Alignment.CenterStart)
                    .size(thumbSize)
                    .graphicsLayer {
                        scaleX = thumbStretch
                        scaleY = 1f / thumbStretch
                    }
                    .glassShadow(GlassShapes.Circle, GlassDepths.Low)
                    .clip(GlassShapes.Circle)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.White, Color(0xFFE8E9F0)),
                        ),
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Viscous slider (playback / seek)
// ---------------------------------------------------------------------------

@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accent: Color = GlassColors.Accent,
    thumbContentDescription: String? = null,
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .height(44.dp)
            .semantics {
                if (thumbContentDescription != null) contentDescription = thumbContentDescription
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val density = LocalDensity.current
        val thumbRadiusPx = with(density) { 11.dp.toPx() }
        val usablePx = (widthPx - thumbRadiusPx * 2).coerceAtLeast(1f)

        val drag = rememberGlassViscousDrag(
            initial = fraction,
            range = 0f..1f,
            onValueChange = { f -> onValueChange(valueRange.start + f * span) },
        )

        fun fractionFromX(x: Float): Float = ((x - thumbRadiusPx) / usablePx).coerceIn(0f, 1f)

        val gestureModifier = if (enabled) {
            Modifier.pointerInput(valueRange, enabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    drag.beginDrag()
                    drag.dragTo(fractionFromX(down.position.x))
                    var pointer = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer }
                            ?: event.changes.firstOrNull { it.pressed }
                        if (change == null || !change.pressed) break
                        if (change.id != pointer) pointer = change.id
                        drag.dragTo(fractionFromX(change.position.x))
                        change.consume()
                    }
                    drag.release(onSettled = { onValueChangeFinished() })
                }
            }
        } else {
            Modifier
        }

        val displayFraction = if (drag.dragging) drag.position else fraction
        val stretch = if (drag.dragging) drag.stretch else 1f

        Box(gestureModifier.fillMaxSize()) {
            // Track: neutral glass capsule.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(GlassShapes.Capsule)
                    .glass(
                        shape = GlassShapes.Capsule,
                        fillAlpha = GlassColors.NeutralFillStrongAlpha,
                        blur = 10.dp,
                        specular = 0.4f,
                    ),
            )
            // Active track: accent liquid.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(displayFraction)
                    .height(7.dp)
                    .clip(GlassShapes.Capsule)
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.75f), accent.copy(alpha = 0.95f)),
                        ),
                    ),
            )
            // Thumb: white glass droplet that stretches with drag velocity.
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((usablePx * displayFraction).toInt(), 0) }
                    .size(22.dp)
                    .graphicsLayer {
                        scaleX = stretch
                        scaleY = 1f + (1f - stretch) * 0.5f
                    }
                    .glassShadow(GlassShapes.Circle, GlassDepths.Low)
                    .clip(GlassShapes.Circle)
                    .background(
                        Brush.verticalGradient(listOf(Color.White, Color(0xFFE6E7EE))),
                    )
                    .glassEdgeLight(GlassShapes.Circle, strength = 0.9f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Segmented tabs (capsule track + liquid accent pill)
// ---------------------------------------------------------------------------

@Composable
fun GlassSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = GlassShapes.Capsule
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .glass(shape = shape, fillAlpha = GlassColors.NeutralFillAlpha, blur = GlassOpticsPresets.BlurControl.dp)
            .glassEdgeLight(shape, strength = 0.6f)
            .padding(3.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val count = labels.size.coerceAtLeast(1)
            val itemWidthPx = constraints.maxWidth.toFloat() / count

            val leftEdge = remember { Animatable(selectedIndex.toFloat()) }
            val rightEdge = remember { Animatable(selectedIndex + 1f) }
            LaunchedEffect(selectedIndex) {
                val t = selectedIndex.toFloat()
                if (t + 1f > rightEdge.value) {
                    launch { rightEdge.animateTo(t + 1f, GlassMotion.IndicatorLeadingSpec) }
                    launch { leftEdge.animateTo(t, GlassMotion.IndicatorTrailingSpec) }
                } else {
                    launch { leftEdge.animateTo(t, GlassMotion.IndicatorLeadingSpec) }
                    launch { rightEdge.animateTo(t + 1f, GlassMotion.IndicatorTrailingSpec) }
                }
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset((leftEdge.value * itemWidthPx).toInt(), 0) }
                    .width(with(density) { ((rightEdge.value - leftEdge.value) * itemWidthPx).coerceAtLeast(1f).toDp() })
                    .fillMaxHeight()
                    .clip(shape)
                    .glass(
                        shape = shape,
                        tone = GlassTone.Accent,
                        fillAlpha = GlassColors.AccentFillAlpha,
                        blur = GlassOpticsPresets.BlurControl.dp,
                    )
                    .glassEdgeLight(shape, GlassTone.Accent, strength = 0.85f),
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val interactionSource = remember { MutableInteractionSource() }
                    val press = rememberGlassPress(interactionSource, enabled)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .glassPress(press)
                            .clip(shape)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = interactionSource,
                                indication = null,
                                enabled = enabled,
                                onClick = { if (!selected) onSelected(index) },
                            ),
                    ) {
                        Text(
                            text = label,
                            color = if (selected) GlassColors.OnAccent else GlassColors.InkSecondary,
                            fontSize = 13.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Search field
// ---------------------------------------------------------------------------

@Composable
fun GlassSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "搜索",
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = GlassShapes.Field
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .glass(
                shape = shape,
                fillAlpha = if (focused) GlassColors.NeutralFillStrongAlpha else GlassColors.NeutralFillAlpha,
                blur = GlassOpticsPresets.BlurControl.dp,
            )
            .glassEdgeLight(shape, if (focused) GlassTone.Accent else GlassTone.Neutral, strength = if (focused) 1.2f else 0.7f)
            .padding(start = 14.dp, end = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = (if (focused) GlassColors.Accent else GlassColors.InkTertiary).copy(
                alpha = if (enabled) 1f else 0.4f,
            ),
            modifier = Modifier.size(20.dp),
        )
        Box(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    color = GlassColors.InkTertiary.copy(alpha = if (enabled) 1f else 0.4f),
                    fontSize = 14.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
                textStyle = TextStyle(
                    color = GlassColors.Ink.copy(alpha = if (enabled) 1f else 0.4f),
                    fontSize = 14.5.sp,
                ),
                cursorBrush = SolidColor(GlassColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (query.isNotEmpty()) {
            GlassIconButton(
                onClick = { onQueryChange("") },
                imageVector = Icons.Rounded.Close,
                contentDescription = "清除搜索",
                enabled = enabled,
                size = 38.dp,
                iconSize = 18.dp,
            )
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Text field (settings)
// ---------------------------------------------------------------------------

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    readOnly: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    trailingContent: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = GlassShapes.CardSmall
    Box(
        modifier = modifier.heightIn(min = 56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .glass(
                    shape = shape,
                    fillAlpha = GlassColors.NeutralFillStrongAlpha,
                    blur = GlassOpticsPresets.BlurControl.dp,
                )
                .glassEdgeLight(shape, if (focused) GlassTone.Accent else GlassTone.Neutral, strength = if (focused) 1.2f else 0.7f)
                .padding(start = 16.dp, end = 8.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 7.dp),
            ) {
                Text(
                    text = label,
                    color = if (focused) GlassColors.Accent else GlassColors.InkTertiary,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isBlank() && placeholder.isNotBlank()) {
                        Text(
                            text = placeholder,
                            color = GlassColors.InkTertiary.copy(alpha = if (enabled) 1f else 0.4f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = enabled,
                        readOnly = readOnly,
                        singleLine = true,
                        visualTransformation = visualTransformation,
                        interactionSource = interactionSource,
                        cursorBrush = SolidColor(GlassColors.Accent),
                        textStyle = TextStyle(fontSize = 14.sp, color = GlassColors.Ink.copy(alpha = if (enabled) 1f else 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            trailingContent?.invoke()
        }
    }
}

