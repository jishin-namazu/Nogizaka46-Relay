package com.nogirelay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nogirelay.app.ui.glass.GlassBottomSheet
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassChip
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassDialog
import com.nogirelay.app.ui.glass.GlassDialogTitle
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassPopover
import com.nogirelay.app.ui.glass.GlassPopoverItem
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.glassPopoverAnchor
import com.nogirelay.app.ui.glass.rememberGlassPopoverState
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class TimeFilter(
    val startMillis: Long? = null,
    val endMillisExclusive: Long? = null,
) {
    val isActive: Boolean get() = startMillis != null || endMillisExclusive != null
}

enum class TimePreset { ALL, TODAY, LAST_7_DAYS, CUSTOM }

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
 * Time filter section: preset chips are glass capsules; the custom-range
 * chip unfolds into two glass date pills that morph into day pickers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeFilterSection(
    filter: TimeFilter,
    onFilterChange: (TimeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    var preset by remember {
        mutableStateOf(matchingPreset(filter, Instant.now(), zone))
    }
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
    var collapsedStartDay by remember { mutableStateOf<LocalDate?>(null) }
    var collapsedEndDay by remember { mutableStateOf<LocalDate?>(null) }

    fun applyCustom(start: LocalDate?, end: LocalDate?) {
        startDay = start
        endDay = end
        preset = TimePreset.CUSTOM
        onFilterChange(customBounds(start, end, zone))
    }
    val customSelected = preset == TimePreset.CUSTOM
    val shownStartDay = if (customSelected) startDay else collapsedStartDay
    val shownEndDay = if (customSelected) endDay else collapsedEndDay
    // Finish fading and resizing together; a spring tail kept resizing the
    // surrounding sheet after the date controls had already disappeared.
    val customRowMotion = tween<IntSize>(220, easing = FastOutSlowInEasing)

    Column(modifier) {
        Text("时间", style = GlassType.Callout, fontWeight = FontWeight.SemiBold, color = GlassColors.Ink)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TimePreset.values()
                .filter { it != TimePreset.CUSTOM }
                .forEach { option ->
                    GlassChip(
                        label = presetLabel(option),
                        selected = preset == option,
                        onClick = {
                            collapsedStartDay = startDay
                            collapsedEndDay = endDay
                            preset = option
                            onFilterChange(timePresetBounds(option, Instant.now(), zone))
                        },
                    )
                }
            GlassChip(
                label = "自定义",
                selected = customSelected,
                onClick = {
                    if (customSelected) {
                        collapsedStartDay = startDay
                        collapsedEndDay = endDay
                        preset = presetBeforeCustom
                        onFilterChange(timePresetBounds(presetBeforeCustom, Instant.now(), zone))
                    } else {
                        presetBeforeCustom = preset
                        preset = TimePreset.CUSTOM
                    }
                },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.DateRange,
                        contentDescription = "自定义时间范围",
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = customSelected,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = customRowMotion,
            ) + fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = customRowMotion,
            ) + fadeOut(animationSpec = tween(220)),
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RangeDateCapsule(
                        text = shownStartDay?.let(::formatDay) ?: "开始日期",
                        chosen = shownStartDay != null,
                        onClick = { picking = PickTarget.START },
                        modifier = Modifier.weight(1f),
                    )
                    Text("至", style = GlassType.Footnote, color = GlassColors.InkSecondary)
                    RangeDateCapsule(
                        text = shownEndDay?.let(::formatDay) ?: "结束日期",
                        chosen = shownEndDay != null,
                        onClick = { picking = PickTarget.END },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (shownStartDay != null && shownEndDay != null && shownEndDay.isBefore(shownStartDay)) {
                    Text("结束日期早于开始日期", color = GlassColors.Danger, style = GlassType.Footnote)
                }
            }
        }
    }
    picking?.let { target ->
        DayPickerDialog(
            initialDay = when (target) {
                PickTarget.START -> startDay
                PickTarget.END -> endDay
            },
            title = if (target == PickTarget.START) "选择开始日期" else "选择结束日期",
            onDismiss = { picking = null },
            onClear = {
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

/** Start / end date button, styled as a sibling of the time chips above it. */
@Composable
private fun RangeDateCapsule(text: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCapsuleButton(
        onClick = onClick,
        depth = GlassDepths.None,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            style = GlassType.Callout,
            fontWeight = FontWeight.Medium,
            color = if (chosen) GlassColors.Ink else GlassColors.InkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun TimeFilterDialog(
    filter: TimeFilter,
    backdropState: RelaySheetBackdropState,
    onDismiss: () -> Unit,
    onConfirm: (TimeFilter) -> Unit,
    extraContent: (@Composable (dismiss: (afterHidden: () -> Unit) -> Unit) -> Unit)? = null,
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    GlassBottomSheet(
        onDismissRequest = onDismiss,
        backdropState = backdropState,
    ) { dismiss, handle ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState(), overscrollEffect = null)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            handle()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "消息筛选",
                    style = GlassType.Title2,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(
                    onClick = { dismiss(onDismiss) },
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭时间筛选",
                )
            }
            Spacer(Modifier.height(14.dp))
            GlassPanel(shape = GlassShapes.Card, modifier = Modifier.fillMaxWidth()) {
                TimeFilterSection(
                    filter = draft,
                    onFilterChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
            if (extraContent != null) {
                Spacer(Modifier.height(14.dp))
                extraContent(dismiss)
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassCapsuleButton(
                    onClick = { dismiss(onDismiss) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                }
                GlassCapsuleButton(
                    onClick = { dismiss { onConfirm(draft) } },
                    enabled = !draft.hasInvertedRange(),
                    tone = GlassTone.Accent,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("确定", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DayPickerDialog(
    initialDay: LocalDate?,
    title: String,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val currentYear = remember { LocalDate.now().year }
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
    GlassDialog(onDismissRequest = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { GlassDialogTitle(title) }
            GlassIconButton(
                onClick = onClear,
                imageVector = Icons.Rounded.ClearAll,
                contentDescription = "清空",
                size = 40.dp,
                iconSize = 18.dp,
            )
        }
        Spacer(Modifier.height(14.dp))
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
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GlassCapsuleButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
            GlassCapsuleButton(
                onClick = { pickedDate?.let(onConfirm) },
                enabled = pickedDate != null,
                tone = GlassTone.Accent,
                modifier = Modifier.weight(1f),
            ) { Text("确定", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Year / month / day capsule whose dropdown morphs out of the capsule. */
@Composable
private fun DayPartSelector(
    value: Int?,
    unit: String,
    options: List<Int>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val popover = rememberGlassPopoverState()
    val scope = rememberCoroutineScope()
    Box(modifier) {
        GlassCapsuleButton(
            onClick = { popover.open() },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .glassPopoverAnchor(popover),
        ) {
            Text(
                value?.let { "$it$unit" } ?: unit,
                style = GlassType.Callout,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        GlassPopover(
            state = popover,
            width = 132.dp,
            modifier = Modifier.heightIn(max = 260.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 252.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    GlassPopoverItem(
                        label = "$option$unit",
                        onClick = {
                            onSelect(option)
                            popover.dismiss(scope)
                        },
                    )
                }
            }
        }
    }
}

private const val MIN_YEAR = 2010

private fun formatDay(day: LocalDate): String = "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)

