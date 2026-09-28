package com.nogirelay.app.ui.transfer

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.GraduatedTag
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogMemberCategories
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleDark
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.LocalRelayMirrorStyle
import com.nogirelay.app.ui.RelayHomeCardShape
import com.nogirelay.app.ui.RelaySelectionSurface
import com.nogirelay.app.ui.RelayDialogButton

/**
 * BLOG 筛选器和两个数据传输成员选择器共用的固定分类顺序。
 * 周期顺序本身位于 [BlogMemberCategories.STANDARD_CATEGORIES]，因此它不会
 * 偏离读取和导入成员时所做的归一化；我们不认识的标签
 * 仍然排在 其他 之后。
 */
val MemberCategoryOrder = BlogMemberCategories.STANDARD_CATEGORIES + "其他"

/** 按 [MemberCategoryOrder] 中的分类对成员分组；未知分类排在最后。 */
fun memberGroups(members: List<BlogMember>): List<Pair<String, List<BlogMember>>> =
    members.groupBy(BlogMember::category)
        .toList()
        .sortedBy { (category, _) ->
            val index = MemberCategoryOrder.indexOf(category)
            if (index >= 0) index else MemberCategoryOrder.size
        }

/**
 * 带分类标题的成员头像网格，从 BLOG 筛选对话框中逐字提取，使
 * 筛选器和两个导出选择器共用同一套视觉语言。选择状态由调用方持有，
 * 正因如此，导出选择器能在不含时间范围区块的情况下复用它。
 */
@Composable
fun MemberPickerGrid(
    members: List<BlogMember>,
    selectedIds: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(members) { memberGroups(members) }
    val mirrorStyle = LocalRelayMirrorStyle.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().heightIn(max = 430.dp),
    ) {
        groups.forEach { (category, groupMembers) ->
            item(key = "category-$category", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    category,
                    fontWeight = FontWeight.Bold,
                    color = BrandPurple,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
            }
            gridItems(groupMembers, key = BlogMember::id) { member ->
                val isSelected = member.id in selectedIds
                RelaySelectionSurface(
                    onClick = {
                        onSelectedChange(
                            if (isSelected) selectedIds - member.id else selectedIds + member.id,
                        )
                    },
                    selected = isSelected,
                    emphasizeEdges = mirrorStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(94.dp),
                ) { selectionProgress ->
                    Box(Modifier.fillMaxSize()) {
                        // 卒業标记只出现在成员卡片的右上角：其余位置（博客列表、首页轮播）不再显示。
                        if (member.graduated) {
                            GraduatedTag(
                                compact = true,
                                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp),
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                        ) {
                            RemoteImage(
                                url = member.avatarUrl,
                                contentDescription = member.name,
                                loadCachedImmediately = false,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .border(
                                        1.5.dp,
                                        BrandPurple.copy(alpha = selectionProgress),
                                        CircleShape,
                                    ),
                            )
                            Text(
                                member.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                fontWeight = if (mirrorStyle) FontWeight.Medium else if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = lerp(MaterialTheme.colorScheme.onSurface, BrandPurpleDark, selectionProgress),
                                modifier = Modifier.padding(top = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 导出与导入区块共用的成员选择器。与 BLOG 筛选对话框相同，只是去掉了
 * 时间范围区块，因此"选择成员"在整个 App 中始终是同一种交互。
 */
@Composable
fun TransferMemberPickerDialog(
    title: String,
    members: List<BlogMember>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var draft by remember(members, selectedIds) { mutableStateOf(selectedIds.toSet()) }
    val allMemberIds = remember(members) { members.mapTo(linkedSetOf(), BlogMember::id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = if (LocalRelayMirrorStyle.current) RelayHomeCardShape else RoundedCornerShape(20.dp),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            if (members.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "正在加载成员...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            } else {
                MemberPickerGrid(
                    members = members,
                    selectedIds = draft,
                    onSelectedChange = { draft = it },
                )
            }
        },
        confirmButton = {
            if (LocalRelayMirrorStyle.current) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RelayDialogButton(
                            onClick = { draft = allMemberIds },
                            enabled = members.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.DoneAll, contentDescription = "全选", modifier = Modifier.size(22.dp))
                        }
                        RelayDialogButton(
                            onClick = { draft = emptySet() },
                            enabled = draft.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.ClearAll, contentDescription = "全不选", modifier = Modifier.size(22.dp))
                        }
                    }
                    Text(
                        "已选 ${draft.size} 位",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RelayDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                        RelayDialogButton(
                            onClick = { onConfirm(draft) },
                            enabled = draft.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        ) { Text("确定", fontWeight = FontWeight.Bold) }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = { draft = members.mapTo(linkedSetOf(), BlogMember::id) },
                        enabled = members.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.DoneAll, contentDescription = "全部选择", tint = BrandPurple)
                    }
                    IconButton(
                        onClick = { draft = emptySet() },
                        enabled = draft.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Rounded.ClearAll,
                            contentDescription = "全部清除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(
                        onClick = { onConfirm(draft) },
                        enabled = draft.isNotEmpty(),
                        colors = ButtonDefaults.textButtonColors(contentColor = BrandPurple),
                    ) { Text("确定", fontWeight = FontWeight.Bold) }
                }
            }
        },
        dismissButton = {},
    )
}
