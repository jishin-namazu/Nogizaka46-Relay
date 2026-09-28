package com.nogirelay.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 应用的胶囊式分段控件：圆角轨道配上在选项之间滑动的紫色指示器。从 BLOG 排序
 * 切换器中提取出来，让数据传输抽屉的 导出 / 导入 选择器共享同一份实现，
 * 而不是近乎重复的副本。
 */
@Composable
fun RelaySegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val mirrorStyle = LocalRelayMirrorStyle.current
    // Keep the same compact geometry for every segmented selector: 48dp layout
    // and touch target, with a 42dp visible glass track and selection.
    val trackHeight = 48.dp
    val visualTrackHeight = if (mirrorStyle) 42.dp else trackHeight
    val trackShape = if (mirrorStyle) RelayNavigationBarShape else RoundedCornerShape(21.dp)
    val indicatorShape = if (mirrorStyle) RelayNavigationSelectionShape else RoundedCornerShape(19.dp)
    val trackInset = 0.dp
    BoxWithConstraints(
        modifier = modifier
            .height(trackHeight)
            .then(
                if (mirrorStyle) Modifier else Modifier
                    .clip(trackShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                    .relayGlass(shape = trackShape),
            ),
    ) {
        if (mirrorStyle) {
            RelayMirrorGlassBackground(
                shape = trackShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(visualTrackHeight),
            )
        }
        val contentWidth = (maxWidth - trackInset * 2).coerceAtLeast(0.dp)
        val tabWidth = contentWidth / labels.size
        val maxIndicatorOffset = (contentWidth - tabWidth).coerceAtLeast(0.dp)
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex.coerceIn(0, labels.lastIndex),
            animationSpec = if (mirrorStyle) {
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
            } else {
                tween(durationMillis = 220)
            },
            label = "relay-segmented-indicator",
        )
        val indicatorModifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        (trackInset + indicatorOffset.coerceIn(0.dp, maxIndicatorOffset)).roundToPx(),
                        0,
                    )
                }
                .width(tabWidth)
        if (mirrorStyle) {
            RelayMirrorGlassSelection(
                shape = indicatorShape,
                modifier = indicatorModifier.height(visualTrackHeight - trackInset * 2),
            )
        } else {
            Surface(
                shape = indicatorShape,
                color = BrandPurple,
                // Keep the padding inside the measured tab slot, including the final tab.
                modifier = indicatorModifier.fillMaxHeight().padding(3.dp),
            ) { Box(Modifier.fillMaxSize()) }
        }
        Row(Modifier.fillMaxSize().padding(trackInset).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(indicatorShape)
                        .selectable(
                            selected = index == selectedIndex,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelected(index) },
                        ),
                ) {
                    Text(
                        text = label,
                        color = if (index == selectedIndex) {
                            if (mirrorStyle) MaterialTheme.colorScheme.primary else Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (index == selectedIndex) {
                            if (mirrorStyle) FontWeight.SemiBold else FontWeight.Bold
                        } else {
                            FontWeight.Medium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
