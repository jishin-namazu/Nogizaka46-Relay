package com.nogirelay.app.ui.transfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.GraduatedTag
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogMemberCategories
import com.nogirelay.app.performance.LocalRelayPageWorkPaused
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassDialog
import com.nogirelay.app.ui.glass.GlassDialogTitle
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassMotion
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone

val MemberCategoryOrder = BlogMemberCategories.STANDARD_CATEGORIES + "其他"

fun memberGroups(members: List<BlogMember>): List<Pair<String, List<BlogMember>>> =
    members.groupBy(BlogMember::category)
        .toList()
        .sortedBy { (category, _) ->
            val index = MemberCategoryOrder.indexOf(category)
            if (index >= 0) index else MemberCategoryOrder.size
        }

@Composable
fun MemberPickerGrid(
    members: List<BlogMember>,
    selectedIds: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 4.dp, bottom = 24.dp),
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    CompositionLocalProvider(LocalRelayPageWorkPaused provides false) {
        MemberPickerGridContent(members, selectedIds, onSelectedChange, modifier, contentPadding, header, footer)
    }
}

@Composable
private fun MemberPickerGridContent(
    members: List<BlogMember>,
    selectedIds: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    header: (@Composable () -> Unit)?,
    footer: (@Composable () -> Unit)?,
) {
    val groups = remember(members) { memberGroups(members) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
        overscrollEffect = null,
    ) {
        if (header != null) {
            item(key = "picker-header", span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        if (members.isEmpty()) {
            item(key = "picker-empty", span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("正在加载成员...", color = GlassColors.InkSecondary, fontSize = 13.sp)
                }
            }
        }
        groups.forEach { (category, groupMembers) ->
            item(key = "category-$category", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    category,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Accent,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
            }
            gridItems(groupMembers, key = BlogMember::id) { member ->
                MemberPickerCard(
                    member = member,
                    selected = member.id in selectedIds,
                    onClick = {
                        onSelectedChange(
                            if (member.id in selectedIds) selectedIds - member.id else selectedIds + member.id,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                )
            }
        }
        if (footer != null) {
            item(key = "picker-footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
        }
    }
}

/**
 * Member tile: neutral glass at rest; when selected the avatar ring and the
 * panel tint flow toward the accent liquid glass on a spring.
 */
@Composable
fun MemberPickerCard(
    member: BlogMember,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    graduatedTagAtCorner: Boolean = true,
) {
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = GlassMotion.GentleSpec,
        label = "member_selection",
    )
    GlassPanel(
        onClick = onClick,
        onClickLabel = member.name,
        shape = if (graduatedTagAtCorner) GlassShapes.CardSmall else GlassShapes.Card,
        tone = if (selectionProgress > 0.5f) GlassTone.Accent else GlassTone.Neutral,
        fillAlpha = 0.30f + (GlassColors.AccentFillAlpha - 0.30f) * selectionProgress,
        depth = if (selected) GlassDepths.Low else GlassDepths.None,
        blur = 14.dp,
        edgeStrength = 0.6f + 0.4f * selectionProgress,
        modifier = modifier,
    ) {
        // The corner badge is an overlay, so both member states keep the same
        // centered avatar/name block and equal space above and below it.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(
                horizontal = 10.dp,
                vertical = 12.dp,
            ),
        ) {
            Box {
                RemoteImage(
                    url = member.avatarUrl,
                    contentDescription = member.name,
                    loadCachedImmediately = false,
                    revalidateRemote = true,
                    modifier = Modifier
                        .size(if (graduatedTagAtCorner) 40.dp else 46.dp)
                        .clip(GlassShapes.Circle),
                )
                if (selectionProgress > 0.01f) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clip(GlassShapes.Circle)
                            .border(
                                2.dp,
                                GlassColors.OnAccent.copy(alpha = 0.9f * selectionProgress),
                                GlassShapes.Circle,
                            ),
                    )
                }
            }
            Text(
                member.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                fontWeight = if (selectionProgress > 0.5f) FontWeight.SemiBold else FontWeight.Medium,
                color = lerp(GlassColors.Ink, GlassColors.OnAccent, selectionProgress),
                modifier = Modifier.padding(top = if (graduatedTagAtCorner) 4.dp else 5.dp),
            )
            if (!graduatedTagAtCorner) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.heightIn(min = 14.dp), contentAlignment = Alignment.Center) {
                    if (member.graduated) GraduatedTag(compact = true)
                }
            }
        }
        if (graduatedTagAtCorner && member.graduated) {
            // The compact badge follows the 20.dp panel corner with a 12.dp inset.
            val tagShape = RoundedCornerShape(8.dp)
            GraduatedTag(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .heightIn(min = 16.dp)
                    .border(1.dp, GlassColors.Accent.copy(alpha = 0.18f), tagShape),
                compact = true,
                shape = tagShape,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
            )
        }
    }
}

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
    GlassDialog(
        onDismissRequest = onDismiss,
        frostedBackground = false,
        contentPadding = PaddingValues(0.dp),
    ) {
        MemberPickerGrid(
            members = members,
            selectedIds = draft,
            onSelectedChange = { draft = it },
            contentPadding = PaddingValues(22.dp),
            header = { GlassDialogTitle(title) },
            footer = {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        GlassIconButton(
                            onClick = { draft = allMemberIds },
                            enabled = members.isNotEmpty(),
                            imageVector = Icons.Rounded.DoneAll,
                            contentDescription = "全选",
                            size = 42.dp,
                            iconSize = 20.dp,
                        )
                        GlassIconButton(
                            onClick = { draft = emptySet() },
                            enabled = draft.isNotEmpty(),
                            imageVector = Icons.Rounded.ClearAll,
                            contentDescription = "全不选",
                            size = 42.dp,
                            iconSize = 20.dp,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "已选 ${draft.size} 位",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.5.sp,
                            color = GlassColors.InkSecondary,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        GlassCapsuleButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        }
                        GlassCapsuleButton(
                            onClick = { onConfirm(draft) },
                            enabled = draft.isNotEmpty(),
                            tone = GlassTone.Accent,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("确定", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
        )
    }
}

