package com.nogirelay.app.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Changes only when complete media bytes are published, never for download progress. */
object MediaCacheRevision {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun changed() { revision.update { it + 1 } }
}
