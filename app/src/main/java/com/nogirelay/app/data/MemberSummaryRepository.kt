package com.nogirelay.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class MemberSummary(val latest: RelayMessage, val unreadCount: Int)

/** Home and inbox reuse the same query result for a given message revision. */
class MemberSummaryRepository {
    private val mutex = Mutex()
    private var version = -1L
    private var cached = emptyList<MemberSummary>()

    suspend fun load(versions: DataVersions): List<MemberSummary> = mutex.withLock {
        if (version != versions.messages) {
            cached = withContext(AppGraph.dispatchers.databaseRead) {
                val unread = AppGraph.database.unreadCountsByMember()
                val changedIds = versions.messageIdsSince(version)
                if (changedIds == null) {
                    AppGraph.database.latestMessagePerMember().map { MemberSummary(it, unread[it.memberKey] ?: 0) }
                } else {
                    // Read/translation updates cannot change which message is newest. Only
                    // reload a preview if its own ID changed, and refresh badge counts.
                    cached.map { summary ->
                        val latest = if (summary.latest.id in changedIds) {
                            AppGraph.database.find(summary.latest.id) ?: summary.latest
                        } else summary.latest
                        MemberSummary(latest, unread[latest.memberKey] ?: 0)
                    }
                }
            }
            version = versions.messages
        }
        cached
    }
}
