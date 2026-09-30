package com.nogirelay.app.ui.messages

import com.nogirelay.app.data.RelayMessage
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val MESSAGE_TIMELINE_BATCH_SIZE = 20

internal interface MemberTimelineSource {
    fun count(): Int
    fun indexOf(messageId: String): Int
    fun messages(offset: Int, limit: Int): List<RelayMessage>
    fun at(id: String): RelayMessage? = indexOf(id).takeIf { it >= 0 }?.let { messages(it, 1).firstOrNull() }
    fun olderThan(id: String, limit: Int): List<RelayMessage> = messages(indexOf(id) + 1, limit)
    fun newerThan(id: String, limit: Int): List<RelayMessage> {
        val index = indexOf(id)
        return if (index > 0) messages((index - limit).coerceAtLeast(0), minOf(index, limit)) else emptyList()
    }
    fun byIds(ids: Set<String>): List<RelayMessage> = ids.mapNotNull(::at)
}

internal data class MemberTimelineWindow(
    val messages: List<RelayMessage> = emptyList(),
    val totalCount: Int = 0,
    val startOffset: Int = 0,
    val hasNewer: Boolean = false,
    val hasOlder: Boolean = false,
)

internal class MemberTimelineLoader(
    private val source: MemberTimelineSource,
    private val batchSize: Int = MESSAGE_TIMELINE_BATCH_SIZE,
) {
    fun initial(targetMessageId: String?): MemberTimelineWindow {
        val count = source.count()
        val index = targetMessageId?.let(source::indexOf) ?: -1
        val target = targetMessageId?.takeIf { index >= 0 }?.let(source::at)
        if (target == null) return range(0, minOf(batchSize, count), count)
        val before = source.newerThan(target.id, batchSize / 2)
        val after = source.olderThan(target.id, batchSize - before.size - 1)
        val start = index - before.size
        val messages = (before + target + after).distinctBy { it.id }
        return MemberTimelineWindow(messages, count, start, start > 0, start + messages.size < count)
    }

    fun older(window: MemberTimelineWindow): MemberTimelineWindow {
        val anchor = window.messages.lastOrNull() ?: return initial(null)
        val anchorIndex = source.indexOf(anchor.id)
        val start = source.indexOf(window.messages.first().id)
        if (anchorIndex < 0 || start < 0) return refresh(window)
        val count = source.count()
        val next = source.olderThan(anchor.id, batchSize)
        return MemberTimelineWindow(
            messages = (window.messages + next).distinctBy { it.id },
            totalCount = count,
            startOffset = start,
            hasNewer = start > 0,
            hasOlder = next.isNotEmpty() && anchorIndex + 1 + next.size < count,
        )
    }

    fun newer(window: MemberTimelineWindow): MemberTimelineWindow {
        val anchor = window.messages.firstOrNull() ?: return initial(null)
        val anchorIndex = source.indexOf(anchor.id)
        val lastIndex = source.indexOf(window.messages.last().id)
        if (anchorIndex < 0 || lastIndex < 0) return refresh(window)
        val count = source.count()
        val start = (anchorIndex - batchSize).coerceAtLeast(0)
        val next = if (anchorIndex > 0) source.newerThan(anchor.id, batchSize) else emptyList()
        return MemberTimelineWindow(
            messages = (next + window.messages).distinctBy { it.id },
            totalCount = count,
            startOffset = start,
            hasNewer = start > 0,
            hasOlder = lastIndex + 1 < count,
        )
    }

    fun refresh(window: MemberTimelineWindow): MemberTimelineWindow {
        if (window.messages.isEmpty()) return initial(null)
        val count = source.count()
        if (count == 0) return MemberTimelineWindow()
        val firstIndex = source.indexOf(window.messages.first().id)
        val lastIndex = source.indexOf(window.messages.last().id)
        val start = if (!window.hasNewer) 0 else {
            firstIndex.takeIf { it >= 0 } ?: window.startOffset.coerceAtMost(count - 1)
        }
        val end = if (lastIndex >= start) lastIndex + 1 else start + window.messages.size
        val first = if (start == firstIndex) source.at(window.messages.first().id) else null
        return range(start, end.coerceAtMost(count), count, first)
    }

    fun patch(window: MemberTimelineWindow, changedIds: Set<String>): MemberTimelineWindow {
        val ids = window.messages.mapTo(mutableSetOf()) { it.id }.intersect(changedIds)
        if (ids.isEmpty()) return window
        val updates = source.byIds(ids).associateBy { it.id }
        if (updates.size != ids.size) return refresh(window)
        return window.copy(messages = window.messages.map { updates[it.id] ?: it })
    }

    private fun range(start: Int, end: Int, count: Int, first: RelayMessage? = null): MemberTimelineWindow {
        val messages = mutableListOf<RelayMessage>()
        var offset = start
        if (first != null && start < end) { messages += first; offset++ }
        var reachedEnd = false
        while (offset < end) {
            val limit = minOf(100, end - offset)
            val batch = messages.lastOrNull()?.let { source.olderThan(it.id, limit) } ?: source.messages(offset, limit)
            messages += batch
            offset += batch.size
            if (batch.size < limit) {
                reachedEnd = true
                break
            }
        }
        return MemberTimelineWindow(
            messages = messages.distinctBy { it.id },
            totalCount = count,
            startOffset = start,
            hasNewer = start > 0 && messages.isNotEmpty(),
            hasOlder = !reachedEnd && offset < count,
        )
    }
}

internal sealed interface MessageTimelineRow {
    val key: String

    data class Message(val message: RelayMessage) : MessageTimelineRow {
        override val key: String = "message:${message.id}"
    }

    data class Day(override val key: String, val label: String) : MessageTimelineRow
}

internal fun messageTimelineRows(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MessageTimelineRow> = buildList {
    val days = messages.map { messageLocalDateTime(it.sentAt, zone)?.toLocalDate() }
    val occurrences = mutableMapOf<String, Int>()
    messages.forEachIndexed { index, message ->
        add(MessageTimelineRow.Message(message))
        if (index == messages.lastIndex || days[index] != days[index + 1]) {
            val day = days[index]
            val dayKey = day?.toString() ?: "unknown"
            val occurrence = occurrences.getOrDefault(dayKey, 0)
            occurrences[dayKey] = occurrence + 1
            add(MessageTimelineRow.Day("day:$dayKey:$occurrence", day?.format(timelineDayFormatter) ?: "日期未知"))
        }
    }
}

internal fun timelineListIndex(rows: List<MessageTimelineRow>, messageId: String): Int? =
    rows.indexOfFirst { it.key == "message:$messageId" }.takeIf { it >= 0 }?.plus(1)

internal fun formatTimelineTime(value: String): String =
    messageLocalDateTime(value)?.format(timelineTimeFormatter) ?: value

internal fun messageLocalDateTime(value: String, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? {
    val input = value.trim()
    runCatching { Instant.parse(input) }.getOrNull()?.let { return it.atZone(zone).toLocalDateTime() }
    runCatching { OffsetDateTime.parse(input) }.getOrNull()?.let { return it.atZoneSameInstant(zone).toLocalDateTime() }
    return runCatching { LocalDateTime.parse(input.replace(' ', 'T')) }.getOrNull()
}

private val timelineDayFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
private val timelineTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
