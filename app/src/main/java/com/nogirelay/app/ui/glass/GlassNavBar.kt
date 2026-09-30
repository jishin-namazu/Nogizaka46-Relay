package com.nogirelay.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Floating capsule tab bar — the app's primary navigation.
 *
 * A single pill of milky liquid glass hovers above the content. The whole
 * capsule compresses on selection, while each icon crossfades between its
 * outline and filled vector. There is no selected background slider.
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
    val pulse = remember { Animatable(1f) }
    var pulseToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(pulseToken) {
        if (pulseToken == 0) return@LaunchedEffect
        pulse.animateTo(0.93f, tween(70))
        pulse.animateTo(1f, GlassMotion.ReleaseSpec)
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                // Scale the material, edge light, contents and shadow as one
                // capsule. Children no longer perform the visible container
                // deformation on their own.
                scaleX = pulse.value
                scaleY = pulse.value
            }
            .glassShadow(shape, GlassDepths.High)
            .clip(shape)
            .glass(shape = shape, blur = GlassOpticsPresets.BlurOverlay.dp)
            .glassEdgeLight(shape)
            .padding(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                GlassNavBarItem(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = {
                        pulseToken++
                        if (index != selectedIndex) onSelected(index)
                    },
                )
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
    val selectedProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = GlassMotion.GentleSpec,
        label = "nav_icon_fill",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
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
        val tint = lerp(GlassColors.InkSecondary, GlassColors.Accent, selectedProgress)
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = lerp(GlassColors.Accent, GlassColors.InkSecondary, selectedProgress),
                modifier = Modifier.size(23.dp).graphicsLayer { alpha = 1f - selectedProgress },
            )
            Icon(
                imageVector = item.selectedIcon,
                contentDescription = null,
                tint = GlassColors.Accent,
                modifier = Modifier.size(23.dp).graphicsLayer { alpha = selectedProgress },
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



