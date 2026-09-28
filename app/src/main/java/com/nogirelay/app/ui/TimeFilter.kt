package com.nogirelay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 用于按时间筛选消息和 BLOG 的包含起点 / 排除终点的 epoch 毫秒边界。
 * 用户未选择范围时两个边界都为 null，因此未筛选的查询保持原样。
 * 边界基于 epoch，因为两张表存储的 ISO 时间戳带有不同偏移
 * （消息为 "...Z"，BLOG 为 "+09:00"）。
 */
data class TimeFilter(
    val startMillis: Long? = null,
    val endMillisExclusive: Long? = null,
) {
    val isActive: Boolean get() = startMillis != null || endMillisExclusive != null
}

enum class TimePreset { ALL, TODAY, LAST_7_DAYS, CUSTOM }

/**
 * 当结束日期早于开始日期时为 true。这样的范围匹配不到任何内容，因此筛选对话框会保持「确定」
 * 不可用，直到用户修正或清空两个日期之一。
 */
fun TimeFilter.hasInvertedRange(): Boolean =
    startMillis != null && endMillisExclusive != null && endMillisExclusive <= startMillis

private enum class PickTarget { START, END }

internal fun timePresetBounds(preset: TimePreset, now: Instant, zone: ZoneId): TimeFilter {
    val today = now.atZone(zone).toLocalDate()
    return when (preset) {
        TimePreset.ALL, TimePreset.CUSTOM -> TimeFilter()
        TimePreset.TODAY -> dayBounds(today, today, zone)
        TimePreset.LAST_7_DAYS -> dayBounds(today.minusDays(6), today, zone)
    }
}

internal fun dayBounds(startDay: LocalDate, endDay: LocalDate, zone: ZoneId): TimeFilter = TimeFilter(
    startMillis = startDay.atStartOfDay(zone).toInstant().toEpochMilli(),
    endMillisExclusive = endDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
)

internal fun customBounds(startDay: LocalDate?, endDay: LocalDate?, zone: ZoneId): TimeFilter = TimeFilter(
    startMillis = startDay?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
    endMillisExclusive = endDay?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
)

/** 把已存储的边界映射回预设，使重新打开的对话框显示当前选中的选项。 */
internal fun matchingPreset(filter: TimeFilter, now: Instant, zone: ZoneId): TimePreset {
    if (!filter.isActive) return TimePreset.ALL
    return listOf(TimePreset.TODAY, TimePreset.LAST_7_DAYS)
        .firstOrNull { timePresetBounds(it, now, zone) == filter }
        ?: TimePreset.CUSTOM
}

internal fun presetLabel(preset: TimePreset): String = when (preset) {
    TimePreset.ALL -> "全部时间"
    TimePreset.TODAY -> "今天"
    TimePreset.LAST_7_DAYS -> "近 7 天"
    TimePreset.CUSTOM -> "自定义"
}

/**
 * 筛选对话框的时间部分：一行预设 chip 加一个与它们尺寸一致的「自定义」日历图标按钮，
 * 下方是所选的日期范围。
 * 草稿值由调用方持有，因此取消外层对话框会丢弃这次修改。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeFilterSection(
    filter: TimeFilter,
    onFilterChange: (TimeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val rowHeight = if (LocalRelayMirrorStyle.current) 48.dp else FILTER_ROW_HEIGHT
    // 仅在对话框打开时初始化：清空两个日期必须保持自定义模式选中，
    // 而不是把这一行弹回「全部时间」。
    var preset by remember {
        mutableStateOf(matchingPreset(filter, Instant.now(), zone))
    }
    // 自定义按钮是开关：记住进入自定义之前选中的预设，再次点击时恢复它。
    var presetBeforeCustom by remember {
        mutableStateOf(preset.takeIf { it != TimePreset.CUSTOM } ?: TimePreset.ALL)
    }
    var startDay by remember(filter) {
        mutableStateOf(filter.startMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() })
    }
    var endDay by remember(filter) {
        mutableStateOf(
            filter.endMillisExclusive?.let {
                Instant.ofEpochMilli(it).atZone(zone).toLocalDate().minusDays(1)
            },
        )
    }
    var picking by remember { mutableStateOf<PickTarget?>(null) }
    // 收回日期行时 filter 已经切回预设，日期字段会被重置；这里留一份收起前的
    // 快照，让收起动画期间仍然显示原来的日期，而不是闪过占位符。
    var collapsedStartDay by remember { mutableStateOf<LocalDate?>(null) }
    var collapsedEndDay by remember { mutableStateOf<LocalDate?>(null) }

    fun applyCustom(start: LocalDate?, end: LocalDate?) {
        startDay = start
        endDay = end
        preset = TimePreset.CUSTOM
        onFilterChange(customBounds(start, end, zone))
    }

    val customSelected = preset == TimePreset.CUSTOM
    // 日期行收起过程中读的是收起前的日期；展开时始终读当前编辑值。
    val shownStartDay = if (customSelected) startDay else collapsedStartDay
    val shownEndDay = if (customSelected) endDay else collapsedEndDay
    // 与主页设置抽屉里各段展开/收起用的是同一套弹簧参数。
    val customRowSpring = spring<IntSize>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 每个项目都包在一个固定高度、居中对齐的槽位里，这样无论各组件内部添加多少
            // 触摸目标内边距，预设 chip 和图标按钮都能对齐。
            TimePreset.values()
                .filter { it != TimePreset.CUSTOM }
                .forEach { option ->
                    Box(modifier = Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                        TimePresetChip(
                            selected = preset == option,
                            label = presetLabel(option),
                            onClick = {
                                preset = option
                                onFilterChange(timePresetBounds(option, Instant.now(), zone))
                            },
                        )
                    }
                }
            val chipShape = RoundedCornerShape(10.dp)
            Box(modifier = Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                RelaySelectionSurface(
                    onClick = {
                        if (customSelected) {
                            // 再次点击：收回日期行，恢复进入自定义之前的选择状态。
                            collapsedStartDay = startDay
                            collapsedEndDay = endDay
                            preset = presetBeforeCustom
                            onFilterChange(timePresetBounds(presetBeforeCustom, Instant.now(), zone))
                        } else {
                            // 第一次点击：记住当前预设，展开自定义日期行。
                            presetBeforeCustom = preset
                            preset = TimePreset.CUSTOM
                        }
                    },
                    selected = customSelected,
                    useNavigationStyle = true,
                    legacyShape = chipShape,
                    modifier = Modifier
                        .height(if (LocalRelayMirrorStyle.current) 48.dp else 32.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.DateRange,
                            contentDescription = "自定义时间范围",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        // 与主页设置抽屉里各段展开/收起完全相同的过渡：弹簧展开/收起 + 淡入淡出。
        // 容器换成同款底部抽屉后，跟随内容的平滑长高由抽屉负责，不会再逐帧重排对话框窗口。
        AnimatedVisibility(
            visible = customSelected,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = customRowSpring,
            ) + fadeIn(animationSpec = tween(200)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = customRowSpring,
            ) + fadeOut(animationSpec = tween(200)),
        ) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Give both dates equal room on narrow screens and at larger font scales.
                    Box(
                        modifier = Modifier.height(rowHeight)
                            .then(if (LocalRelayMirrorStyle.current) Modifier.weight(1f) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        RangeDateChip(
                            text = shownStartDay?.let(::formatDay) ?: "开始日期",
                            onClick = { picking = PickTarget.START },
                        )
                    }
                    Text("至", fontSize = 12.sp)
                    Box(
                        modifier = Modifier.height(rowHeight)
                            .then(if (LocalRelayMirrorStyle.current) Modifier.weight(1f) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        RangeDateChip(
                            text = shownEndDay?.let(::formatDay) ?: "结束日期",
                            onClick = { picking = PickTarget.END },
                        )
                    }
                }
                if (shownStartDay != null && shownEndDay != null && shownEndDay.isBefore(shownStartDay)) {
                    Text("结束日期早于开始日期", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }

    picking?.let { target ->
        DayPickerDialog(
            // 没有默认日期：未设置的一侧打开时 年 / 月 / 日 选择器为空。
            initialDay = when (target) {
                PickTarget.START -> startDay
                PickTarget.END -> endDay
            },
            title = if (target == PickTarget.START) "选择开始日期" else "选择结束日期",
            onDismiss = { picking = null },
            onClear = {
                // 清空一侧会保留另一侧边界，因此筛选变为开放区间。
                if (target == PickTarget.START) {
                    applyCustom(null, endDay)
                } else {
                    applyCustom(startDay, null)
                }
                picking = null
            },
            onConfirm = { picked ->
                if (target == PickTarget.START) {
                    applyCustom(picked, endDay)
                } else {
                    applyCustom(startDay, picked)
                }
                picking = null
            },
        )
    }
}

/** 时间筛选的预设 chip，与应用筛选系统保持统一的样式。 */
@Composable
private fun TimePresetChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    RelaySelectionSurface(
        onClick = onClick,
        selected = selected,
        useNavigationStyle = true,
        legacyShape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(if (LocalRelayMirrorStyle.current) 48.dp else 32.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RangeDateChip(text: String, onClick: () -> Unit) {
    RelaySelectionSurface(
        onClick = onClick,
        selected = true,
        useNavigationStyle = true,
        legacyShape = RoundedCornerShape(10.dp),
        modifier = Modifier.height(if (LocalRelayMirrorStyle.current) 48.dp else 32.dp)
            .then(if (LocalRelayMirrorStyle.current) Modifier.fillMaxWidth() else Modifier),
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 独立的时间筛选抽屉，供只需要时间筛选的界面使用。
 * 与主页的设置抽屉保持一致：底部弹出，内部内容平滑展开/收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeFilterDialog(
    filter: TimeFilter,
    backdropState: RelaySheetBackdropState,
    onDismiss: () -> Unit,
    onConfirm: (TimeFilter) -> Unit,
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    RelayModalBottomSheet(
        onDismissRequest = onDismiss,
        backdropState = backdropState,
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "消息时间筛选",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RelayDialogIconButton(
                    onClick = { dismiss(onDismiss) },
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭时间筛选",
                )
            }
            Spacer(Modifier.height(14.dp))
            if (LocalRelayMirrorStyle.current) {
                RelayDialogCard {
                    TimeFilterSection(
                        filter = draft,
                        onFilterChange = { draft = it },
                        modifier = Modifier.fillMaxWidth().padding(RelayCardContentInset),
                    )
                }
            } else {
                TimeFilterSection(filter = draft, onFilterChange = { draft = it }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RelayDialogButton(
            style = RelayDialogButtonStyle.Outlined,
                    onClick = { dismiss(onDismiss) },
                    modifier = Modifier.weight(1f).heightIn(min = if (LocalRelayMirrorStyle.current) 48.dp else 44.dp),
                ) {
                    Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RelayDialogButton(
                    style = RelayDialogButtonStyle.Filled,
                    onClick = { dismiss { onConfirm(draft) } },
                    enabled = !draft.hasInvertedRange(),
                    modifier = Modifier.weight(1f).heightIn(min = if (LocalRelayMirrorStyle.current) 48.dp else 44.dp),
                ) {
                    Text("确定", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * 由应用自身的 Material 主题组件（而非平台日期对话框）构建的年 / 月 / 日选择器，使筛选对话框
 * 保持统一外观。每个部分按顺序选择。
 */
@Composable
private fun DayPickerDialog(
    initialDay: LocalDate?,
    title: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val currentYear = LocalDate.now().year
    var year by remember(initialDay) {
        mutableStateOf(initialDay?.year?.coerceIn(MIN_YEAR, currentYear))
    }
    var month by remember(initialDay) { mutableStateOf(initialDay?.monthValue) }
    var day by remember(initialDay) { mutableStateOf(initialDay?.dayOfMonth) }

    fun clampDay() {
        val selectedYear = year ?: return
        val selectedMonth = month ?: return
        val selectedDay = day ?: return
        day = selectedDay.coerceIn(1, YearMonth.of(selectedYear, selectedMonth).lengthOfMonth())
    }

    val selectedYear = year
    val selectedMonth = month
    val selectedDay = day
    val pickedDate = if (selectedYear != null && selectedMonth != null && selectedDay != null) {
        runCatching { LocalDate.of(selectedYear, selectedMonth, selectedDay) }.getOrNull()
    } else {
        null
    }
    val dayOptions = if (selectedYear != null && selectedMonth != null) {
        YearMonth.of(selectedYear, selectedMonth).lengthOfMonth()
    } else {
        31
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = if (LocalRelayMirrorStyle.current) RelayHomeCardShape else RoundedCornerShape(20.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RelayDialogIconButton(onClick = onClear, imageVector = Icons.Rounded.ClearAll, contentDescription = "清空")
            }
        },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DayPartSelector(
                    value = year,
                    unit = "年",
                    options = (MIN_YEAR..currentYear).toList(),
                    onSelect = { year = it; clampDay() },
                    modifier = Modifier.weight(1.3f),
                )
                DayPartSelector(
                    value = month,
                    unit = "月",
                    options = (1..12).toList(),
                    onSelect = { month = it; clampDay() },
                    modifier = Modifier.weight(1f),
                )
                DayPartSelector(
                    value = day,
                    unit = "日",
                    options = (1..dayOptions).toList(),
                    onSelect = { day = it },
                    modifier = Modifier.weight(1f),
                )
            }
        },
        confirmButton = {
            RelayDialogButton(
                onClick = { pickedDate?.let(onConfirm) },
                enabled = pickedDate != null,
                contentColor = BrandPurple,
            ) { Text("确定", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            RelayDialogButton(
                onClick = onDismiss,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text("取消") }
        },
    )
}

@Composable
private fun DayPartSelector(
    value: Int?,
    unit: String,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        RelayDialogButton(
            style = RelayDialogButtonStyle.Outlined,
            onClick = { expanded = true },
            contentColor = BrandPurpleDark,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 尚未选择任何值时只显示单位。
            Text(value?.let { "$it$unit" } ?: unit, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = BrandPurpleDark,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = if (LocalRelayMirrorStyle.current) RelayControlShape else MaterialTheme.shapes.extraSmall,
            modifier = Modifier.heightIn(max = 260.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("$option$unit") },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val MIN_YEAR = 2010

/** 预设 chip 与自定义图标按钮共用的高度，使它们的中心对齐。 */
private val FILTER_ROW_HEIGHT = 34.dp

private fun formatDay(day: LocalDate): String = "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)
