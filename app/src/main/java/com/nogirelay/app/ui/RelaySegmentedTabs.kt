package com.nogirelay.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
    BoxWithConstraints(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        val tabWidth = maxWidth / labels.size
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex.coerceIn(0, labels.lastIndex),
            animationSpec = tween(durationMillis = 220),
            label = "relay-segmented-indicator",
        )
        Surface(
            shape = RoundedCornerShape(19.dp),
            color = BrandPurple,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = indicatorOffset)
                .padding(3.dp)
                .width(tabWidth)
                .fillMaxHeight(),
        ) {
            Box(Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelected(index) },
                        ),
                ) {
                    Text(
                        text = label,
                        color = if (index == selectedIndex) {
                            Color.White
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
