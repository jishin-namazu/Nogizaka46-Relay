package com.nogirelay.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Inclusive start / exclusive end epoch-millisecond bounds used to narrow messages and BLOGs by
 * time. Both bounds are null while the user has not picked a range, so unfiltered queries stay
 * unchanged. Bounds are epoch based because the two tables store ISO timestamps with different
 * offsets ("...Z" for messages, "+09:00" for BLOGs).
 */
data class TimeFilter(
    val startMillis: Long? = null,
    val endMillisExclusive: Long? = null,
) {
    val isActive: Boolean get() = startMillis != null || endMillisExclusive != null
}

enum class TimePreset { ALL, TODAY, LAST_7_DAYS, CUSTOM }

/**
 * True when the end day is before the start day. Such a range matches nothing, so the filter dialogs
 * keep 确定 disabled until the user fixes or clears one of the two dates.
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

/** Maps stored bounds back to a preset so a reopened dialog shows the current choice selected. */
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
 * Time part of a filter dialog: one row of preset chips plus a "自定义" calendar icon button that
 * matches their size, and the chosen date range below. The caller owns the draft value, so cancelling
 * the surrounding dialog discards the change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimeFilterSection(
    filter: TimeFilter,
    onFilterChange: (TimeFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    // Only initialised when the dialog opens: clearing both dates must keep the custom mode selected
    // instead of snapping the row back to 全部时间.
    var preset by remember {
        mutableStateOf(matchingPreset(filter, Instant.now(), zone))
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

    fun applyCustom(start: LocalDate?, end: LocalDate?) {
        startDay = start
        endDay = end
        preset = TimePreset.CUSTOM
        onFilterChange(customBounds(start, end, zone))
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Every item is wrapped in one fixed-height, centre-aligned slot so the preset chips and
            // the icon button line up whatever internal touch-target padding each component adds.
            TimePreset.values()
                .filter { it != TimePreset.CUSTOM }
                .forEach { option ->
                    Box(modifier = Modifier.height(FILTER_ROW_HEIGHT), contentAlignment = Alignment.Center) {
                        FilterChip(
                            selected = preset == option,
                            onClick = {
                                preset = option
                                onFilterChange(timePresetBounds(option, Instant.now(), zone))
                            },
                            label = { Text(presetLabel(option), fontSize = 12.sp) },
                        )
                    }
                }
            // The custom button is built like the chips (8dp rounded rectangle, 32dp tall, 1dp outline
            // when unselected). A plain clickable Surface is used instead of a Material button because
            // those enforce a 48dp minimum touch target that would break the row alignment.
            val customSelected = preset == TimePreset.CUSTOM
            val chipShape = RoundedCornerShape(8.dp)
            Box(modifier = Modifier.height(FILTER_ROW_HEIGHT), contentAlignment = Alignment.Center) {
                Surface(
                    shape = chipShape,
                    color = if (customSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    contentColor = if (customSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = if (customSelected) {
                        null
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    },
                    modifier = Modifier
                        .height(32.dp)
                        .clip(chipShape)
                        // Only reveals the 开始日期 / 结束日期 buttons; the picker opens from those.
                        .clickable { preset = TimePreset.CUSTOM },
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
        if (preset == TimePreset.CUSTOM) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Same 32dp height as the preset chips so both rows have the same rhythm.
                Box(modifier = Modifier.height(FILTER_ROW_HEIGHT), contentAlignment = Alignment.Center) {
                    RangeDateChip(
                        text = startDay?.let(::formatDay) ?: "开始日期",
                        onClick = { picking = PickTarget.START },
                    )
                }
                Text("至", fontSize = 12.sp)
                Box(modifier = Modifier.height(FILTER_ROW_HEIGHT), contentAlignment = Alignment.Center) {
                    RangeDateChip(
                        text = endDay?.let(::formatDay) ?: "结束日期",
                        onClick = { picking = PickTarget.END },
                    )
                }
            }
            if (startDay != null && endDay != null && endDay!!.isBefore(startDay)) {
                Text("结束日期早于开始日期", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }

    picking?.let { target ->
        DayPickerDialog(
            // No default date: an unset side opens with empty 年 / 月 / 日 selectors.
            initialDay = when (target) {
                PickTarget.START -> startDay
                PickTarget.END -> endDay
            },
            title = if (target == PickTarget.START) "选择开始日期" else "选择结束日期",
            onDismiss = { picking = null },
            onClear = {
                // Clearing one side keeps the other bound, so the filter becomes open ended.
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

/** Date field of the custom range, built with the same 8dp / 32dp shape as the preset chips. */
@Composable
private fun RangeDateChip(text: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        shape = shape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .height(32.dp)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Standalone 筛选 dialog for screens that only need a time filter. */
@Composable
fun TimeFilterDialog(
    filter: TimeFilter,
    onDismiss: () -> Unit,
    onConfirm: (TimeFilter) -> Unit,
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息时间筛选") },
        text = {
            TimeFilterSection(
                filter = draft,
                onFilterChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(draft) },
                enabled = !draft.hasInvertedRange(),
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Year / month / day picker built from the app's own Material theme components instead of the
 * platform date dialog, so the 筛选 dialog keeps one consistent look. Each part is chosen in order.
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
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClear) {
                    Icon(Icons.Rounded.ClearAll, contentDescription = "清空")
                }
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
            TextButton(
                onClick = { pickedDate?.let(onConfirm) },
                enabled = pickedDate != null,
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Shows only the unit while nothing has been picked yet.
            Text(value?.let { "$it$unit" } ?: unit, fontSize = 13.sp, maxLines = 1)
            Icon(
                Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
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

/** Shared height of the preset chips and the custom icon button so their centres line up. */
private val FILTER_ROW_HEIGHT = 34.dp

private fun formatDay(day: LocalDate): String = "%04d-%02d-%02d".format(day.year, day.monthValue, day.dayOfMonth)
