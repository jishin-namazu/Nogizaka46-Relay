package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Floating capsule tab bar — the app's primary navigation.
 *
 * A single pill of milky liquid glass hovers above the content. The active
 * tab sits inside an accent-tinted glass indicator that moves by liquid
 * stretch: the leading edge darts ahead on a stiff spring while the trailing
 * edge lags on a soft one, so the pill visibly elongates mid-flight and
 * settles with controlled damping. No crossfades — the object persists.
 */

class GlassNavItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val badgeCount: Int = 0,
)

@Composable
fun GlassNavBar(
    items: List<GlassNavItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = GlassShapes.Capsule
    Box(
        modifier = modifier
            .glassShadow(shape, GlassDepths.High)
            .clip(shape)
            .glass(shape = shape, blur = GlassOpticsPresets.BlurOverlay.dp)
            .glassEdgeLight(shape)
            .padding(5.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val density = LocalDensity.current
            val itemCount = items.size.coerceAtLeast(1)
            val itemWidthPx = constraints.maxWidth.toFloat() / itemCount

            // Liquid indicator: two edges, two different springs. Whichever
            // edge points along the direction of travel moves first.
            val leftEdge = remember { Animatable(selectedIndex.toFloat()) }
            val rightEdge = remember { Animatable(selectedIndex + 1f) }
            LaunchedEffect(selectedIndex) {
                val target = selectedIndex.toFloat()
                if (target + 1f > rightEdge.value) {
                    launch { rightEdge.animateTo(target + 1f, GlassMotion.IndicatorLeadingSpec) }
                    launch { leftEdge.animateTo(target, GlassMotion.IndicatorTrailingSpec) }
                } else if (target < leftEdge.value) {
                    launch { leftEdge.animateTo(target, GlassMotion.IndicatorLeadingSpec) }
                    launch { rightEdge.animateTo(target + 1f, GlassMotion.IndicatorTrailingSpec) }
                } else {
                    launch { leftEdge.animateTo(target, GlassMotion.IndicatorLeadingSpec) }
                    launch { rightEdge.animateTo(target + 1f, GlassMotion.IndicatorLeadingSpec) }
                }
            }

            // The outer 5.dp padding is the inset on every side of the pill.
            val indicatorLeftPx = leftEdge.value * itemWidthPx
            val indicatorWidthPx = ((rightEdge.value - leftEdge.value) * itemWidthPx)
                .coerceAtLeast(with(density) { 20.dp.toPx() })

            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorLeftPx.toInt(), 0) }
                    .width(with(density) { indicatorWidthPx.toDp() })
                    .fillMaxHeight()
                    .clip(shape)
                    .glass(
                        shape = shape,
                        tone = GlassTone.Accent,
                        fillAlpha = 0.15f,
                        blur = 12.dp,
                        specular = 0.55f,
                    )
                    .glassEdgeLight(shape, GlassTone.Accent, strength = 0.75f),
            )

            Row(
                modifier = Modifier.fillMaxSize().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    GlassNavBarItem(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { if (index != selectedIndex) onSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.GlassNavBarItem(
    item: GlassNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = rememberGlassPress(interactionSource)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .glassPress(press)
            .clip(GlassShapes.Capsule)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
    ) {
        val tint = if (selected) GlassColors.Accent else GlassColors.InkSecondary
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = if (selected) item.selectedIcon else item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(23.dp),
            )
            if (item.badgeCount > 0) {
                GlassBadge(
                    count = item.badgeCount,
                    modifier = Modifier.offset(x = 12.dp, y = (-3).dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = item.label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small unread badge. */
@Composable
fun GlassBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(16.dp)
            .widthIn(min = 16.dp)
            .background(GlassColors.Danger, GlassShapes.Capsule)
            .padding(horizontal = 4.5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            fontSize = 9.5.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}



