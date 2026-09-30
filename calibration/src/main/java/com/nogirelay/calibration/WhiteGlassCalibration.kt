package com.nogirelay.calibration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.calibration.CalibrationTheme
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

@Composable
private fun rememberGlassPress(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val anim = remember { Animatable(0f) }
    LaunchedEffect(pressed) { anim.animateTo(if (pressed) 1f else 0f, spring(dampingRatio = 0.7f, stiffness = 480f)) }
    return anim.value
}

/** Opt-in material study; intentionally not the default application material. */
private val WhiteShape = RoundedCornerShape(50)
private val WhiteBody = Brush.verticalGradient(
    0f to Color(0xFFF8F9FA),
    0.48f to Color(0xFFF4F6F7),
    1f to Color(0xFFFAFBFB),
)

/** Separate broad cast shadow, near contact shadow and softly rounded inner light. */
private fun Modifier.calibratedWhiteSurface() = this
    .dropShadow(
        WhiteShape,
        Shadow(radius = 20.dp, spread = (-3).dp, offset = DpOffset(0.dp, 12.dp), color = Color(0x1C424650)),
    )
    .dropShadow(
        WhiteShape,
        Shadow(radius = 4.dp, spread = (-2).dp, offset = DpOffset(0.dp, 2.dp), color = Color(0x08424650)),
    )
    .clip(WhiteShape)
    .background(WhiteBody)
    .innerShadow(
        WhiteShape,
        Shadow(radius = 1.5.dp, offset = DpOffset(0.dp, 1.dp), color = Color.White.copy(alpha = 0.95f)),
    )
    .innerShadow(
        WhiteShape,
        Shadow(radius = 5.dp, offset = DpOffset(0.dp, (-2).dp), color = Color.White.copy(alpha = 0.8f)),
    )

@Composable
internal fun CalibratedWhiteCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interaction)
    Box(
        modifier.size(80.dp)
            .graphicsLayer {
                scaleX = 1f - 0.035f * press
                scaleY = scaleX
                translationY = press * 1.dp.toPx()
            }
            .calibratedWhiteSurface()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Settings, "设置", Modifier.size(38.dp), tint = Color(0xFF242528))
    }
}

@Composable
internal fun CalibratedWhiteCapsule(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = listOf("闹钟", "世界时钟", "秒表", "计时器")
    val icons = listOf(Icons.Outlined.Alarm, Icons.Outlined.Language, Icons.Outlined.Timer, Icons.Outlined.HourglassEmpty)
    Row(
        modifier.height(72.dp).calibratedWhiteSurface().selectableGroup().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val interaction = remember { MutableInteractionSource() }
            val press = rememberGlassPress(interaction)
            val selected = index == selectedIndex
            val ink = if (selected) Color(0xFF1C1D1F) else Color(0xFF4A4C4F)
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .graphicsLayer { scaleX = 1f - 0.04f * press; scaleY = scaleX }
                    .selectable(
                        selected, interactionSource = interaction, indication = null, role = Role.Tab,
                        onClick = { onSelected(index) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icons[index], null, Modifier.size(26.dp), tint = ink)
                Spacer(Modifier.height(4.dp))
                Text(label, color = ink, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

/** Native counterpart of the browser study, exposed by the calibration launcher. */
@Composable
internal fun WhiteGlassCalibrationScreen() {
    CalibrationTheme {
        var selected by remember { mutableIntStateOf(2) }
        var clicks by remember { mutableIntStateOf(0) }
        Column(
            Modifier.fillMaxSize().background(Color(0xFFEDEEF0)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CalibratedWhiteCircleButton(onClick = { clicks++ })
            Spacer(Modifier.height(64.dp))
            CalibratedWhiteCapsule(selected, { selected = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(32.dp))
            Text(if (clicks == 0) "轻按查看反馈" else "已点击 $clicks 次", color = Color(0xFF777A80), fontSize = 12.sp)
        }
    }
}

/** Compose preview for the same screen used by [CalibrationActivity]. */
@Preview(name = "White surface calibration", widthDp = 400, heightDp = 440, showBackground = true)
@Composable
internal fun WhiteGlassCalibrationPreview() {
    WhiteGlassCalibrationScreen()
}
