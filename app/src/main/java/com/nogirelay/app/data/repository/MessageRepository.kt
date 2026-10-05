package com.nogirelay.app.data.repository

import androidx.compose.runtime.Immutable
import com.nogirelay.app.data.DataInvalidationTracker
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.db.MessageDao
import com.nogirelay.app.performance.PerformanceDispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MemberSummary(val latest: RelayMessage, val unreadCount: Int)

/**
 * The message change log as one screen sees it: [version] advances on every
 * message change, and [changedSince] names the rows touched after an earlier
 * version, or null when the screen must reload instead.
 */
@Immutable
class MessageChanges internal constructor(private val versions: DataVersions) {
    val version: Long get() = versions.messages

    fun changedSince(version: Long): Set<String>? = versions.messageIdsSince(version)
}

/** Observable message data; screens subscribe instead of polling the DAO. */
class MessageRepository(
    private val dao: MessageDao,
    private val invalidation: DataInvalidationTracker,
    private val dispatchers: PerformanceDispatchers,
) {
    val unreadCount: Flow<Int> =
        invalidation.observe(dispatchers.databaseRead, DataVersions::unread) { dao.countUnreadMessages() }

    val changes: Flow<MessageChanges> = invalidation.versions
        .distinctUntilChangedBy(DataVersions::messages)
        .map(::MessageChanges)

    /** Each member's latest message with its unread count, newest member first. */
    val memberSummaries: Flow<List<MemberSummary>> = invalidation.versions
        .distinctUntilChangedBy(DataVersions::messages)
        .conflate()
        .map(::loadSummaries)

    private val summaryMutex = Mutex()
    private var summaryVersion = -1L
    private var cachedSummaries = emptyList<MemberSummary>()

    private suspend fun loadSummaries(versions: DataVersions): List<MemberSummary> = summaryMutex.withLock {
        if (summaryVersion != versions.messages) {
            cachedSummaries = withContext(dispatchers.databaseRead) {
                val unread = dao.unreadCountsByMember()
                val changedIds = versions.messageIdsSince(summaryVersion).takeIf { summaryVersion >= 0 }
                if (changedIds == null) {
                    dao.latestMessagePerMember().map { MemberSummary(it, unread[it.memberKey] ?: 0) }
                } else {
                    // Row edits (read, played, translated) never change which
                    // message is a member's latest; refresh just those rows.
                    cachedSummaries.map { summary ->
                        val latest = if (summary.latest.id in changedIds) dao.find(summary.latest.id) ?: summary.latest else summary.latest
                        MemberSummary(latest, unread[latest.memberKey] ?: 0)
                    }
                }
            }
            summaryVersion = versions.messages
        }
        cachedSummaries
    }
}
