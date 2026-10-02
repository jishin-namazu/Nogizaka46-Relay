package com.nogirelay.app.media

import android.content.Context
import android.content.SharedPreferences
import com.nogirelay.app.data.MediaRefKind
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

object MediaCacheRevision {
    private val revisions = MediaRefKind.entries.associateWith { MutableStateFlow(0L) }
    private val changes = revisions.mapValues { it.value.asStateFlow() }
    private val pendingChanges = ThreadLocal<MutableSet<MediaRefKind>?>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        MediaRefKind.entries.forEach { kind ->
            revisions.getValue(kind).value = stored.getLong(keyFor(kind), 0L)
        }
        prefs = stored
    }

    fun changesFor(kind: MediaRefKind): StateFlow<Long> = changes.getValue(kind)

    fun changed(kinds: Set<MediaRefKind>) {
        pendingChanges.get()?.let {
            it.addAll(kinds)
            return
        }
        kinds.forEach { kind ->
            revisions.getValue(kind).update { current ->
                (current + 1).also { next ->
                    prefs?.edit()?.putLong(keyFor(kind), next)?.apply()
                }
            }
        }
    }

    suspend fun <T> batch(block: suspend () -> T): T {
        if (pendingChanges.get() != null) return block()
        val pending = linkedSetOf<MediaRefKind>()
        try {
            return withContext(pendingChanges.asContextElement(pending)) { block() }
        } finally {
            // Files already committed still invalidate estimates on cancellation.
            changed(pending)
        }
    }

    private fun keyFor(kind: MediaRefKind): String = "revision_${kind.name.lowercase()}"

    private const val PREFS_NAME = "media_cache_revisions"
}
