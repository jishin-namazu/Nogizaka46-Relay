package com.nogirelay.app.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The data layer's change log. Every DAO write publishes what it touched,
 * so callers never announce changes themselves; repositories turn the log
 * into the flows screens subscribe to.
 *
 * Writes inside a [MessageDatabase.transaction] are held back and published
 * together once the transaction commits, so no reader re-queries before the
 * rows are visible (or after a rollback).
 */
class DataInvalidationTracker(
    initialExportVersions: DataVersions = DataVersions(),
    private val persistExportVersions: (DataVersions) -> Unit = {},
) {
    private val _versions = MutableStateFlow(DataVersions())

    /** Everything screens observe. */
    val versions: StateFlow<DataVersions> = _versions.asStateFlow()

    private val _exportVersions = MutableStateFlow(initialExportVersions)

    /**
     * Content revisions that survive restarts; the export size estimate is
     * cached against them. Foreground refreshes leave them untouched.
     */
    val exportVersions: StateFlow<DataVersions> = _exportVersions.asStateFlow()

    private class Pending(val change: DataChange, val ids: Set<String>, val invalidateExport: Boolean)

    private val batch = ThreadLocal<MutableList<Pending>?>()

    fun publish(change: DataChange, ids: Set<String> = emptySet(), invalidateExport: Boolean = true) {
        val pending = batch.get()
        if (pending != null) {
            pending += Pending(change, ids, invalidateExport)
            return
        }
        apply(listOf(Pending(change, ids, invalidateExport)))
    }

    /** Runs [block] with this thread's publishes held until it returns; nested calls join the outer one. */
    internal fun <T> batched(block: (commit: () -> Unit) -> T): T {
        if (batch.get() != null) return block {}
        val pending = mutableListOf<Pending>()
        batch.set(pending)
        var committed = false
        try {
            return block { committed = true }
        } finally {
            batch.set(null)
            if (committed && pending.isNotEmpty()) apply(pending)
        }
    }

    private fun apply(changes: List<Pending>) {
        val exportChanges = changes.filter(Pending::invalidateExport)
        if (exportChanges.isNotEmpty()) {
            _exportVersions.update { current ->
                exportChanges.fold(current) { versions, p -> versions.changed(p.change, p.ids) }
                    .also(persistExportVersions)
            }
        }
        _versions.update { current -> changes.fold(current) { versions, p -> versions.changed(p.change, p.ids) } }
    }

    /**
     * Re-runs [query] whenever the revision chosen by [revision] advances.
     * Changes arriving while a query runs collapse into one re-run, so a
     * burst of writes (a sync, an import) costs at most one query at a time.
     */
    fun <T> observe(
        dispatcher: CoroutineDispatcher,
        revision: (DataVersions) -> Long,
        query: suspend () -> T,
    ): Flow<T> = versions
        .map(revision)
        .distinctUntilChanged()
        .conflate()
        .map { withContext(dispatcher) { query() } }
}
