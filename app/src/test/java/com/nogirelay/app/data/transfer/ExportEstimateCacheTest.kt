package com.nogirelay.app.data.transfer

import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataInvalidationTracker
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportEstimateCacheTest {
    private val key = ExportEstimateKey(ExportKind.MESSAGES, setOf("member"), true, 1L, 2L)
    private val estimate = ExportEstimate(
        1000, 600, 500, 1234L,
        scanProgress = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 600, 600),
    )

    @Test fun cachePreservesTheResultAndItsActualScanUnit() {
        val cache = ExportEstimateCache()
        cache.put(key, estimate)
        assertSame(estimate, cache.get(key))
        assertEquals(ExportEstimatePhase.MEDIA, cache.get(key)?.scanProgress?.phase)
        assertEquals(600, cache.get(key)?.scanProgress?.total)
    }

    @Test fun resultsRemainUntilRelevantVersionChanges() {
        val cache = ExportEstimateCache()
        cache.put(key, estimate)
        assertSame(estimate, cache.get(key))
        assertNull(cache.get(key.copy(contentRevision = 2L)))
        assertNull(cache.get(key.copy(mediaRevision = 3L)))
    }

    @Test fun changesToSelectionContentOrMediaDoNotReuseOldResults() {
        val cache = ExportEstimateCache()
        cache.put(key, estimate)
        listOf(
            key.copy(members = setOf("another")),
            key.copy(includeMedia = false),
            key.copy(contentRevision = 2L),
            key.copy(mediaRevision = 3L),
            key.copy(kind = ExportKind.BLOGS),
        ).forEach { assertNull(cache.get(it)) }
    }

    @Test fun serializedEntriesLoadIntoANewCacheInstance() {
        var stored: String? = null
        ExportEstimateCache(
            loadSerialized = { stored },
            saveSerialized = { stored = it },
        ).put(key, estimate)

        val restored = ExportEstimateCache(loadSerialized = { stored }).get(key)

        assertEquals(estimate, restored)
        assertEquals(ExportEstimatePhase.MEDIA, restored?.scanProgress?.phase)
    }

    @Test fun displayRefreshDoesNotInvalidateButContentChangeDoes() {
        val tracker = DataInvalidationTracker()
        val before = tracker.exportVersions.value
        val uiBefore = tracker.versions.value
        tracker.publish(DataChange.CONTENT, invalidateExport = false)
        assertSame(before, tracker.exportVersions.value)
        assertTrue(tracker.versions.value.revision > uiBefore.revision)
        tracker.publish(DataChange.MESSAGES)
        assertTrue(tracker.exportVersions.value.messageStructure > before.messageStructure)
        tracker.publish(DataChange.BLOGS)
        assertTrue(tracker.exportVersions.value.blogContent > before.blogContent)
    }

    @Test fun batchedChangesPublishOnceAfterCommitAndNotAfterRollback() {
        val tracker = DataInvalidationTracker()
        val before = tracker.versions.value
        tracker.batched { commit ->
            tracker.publish(DataChange.MESSAGE_ROWS, setOf("a"))
            tracker.publish(DataChange.MESSAGE_ROWS, setOf("b"))
            assertSame(before, tracker.versions.value)
            commit()
        }
        assertEquals(setOf("a", "b"), tracker.versions.value.messageIdsSince(before.messages))
        val committed = tracker.versions.value
        tracker.batched { tracker.publish(DataChange.MESSAGES) }
        assertSame(committed, tracker.versions.value)
    }
}
