package com.nogirelay.app.data.transfer

import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataChange
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
        val before = AppGraph.exportDataVersions.value
        val uiBefore = AppGraph.dataVersions.value
        AppGraph.notifyDataChanged(DataChange.CONTENT, invalidateExportEstimate = false)
        assertSame(before, AppGraph.exportDataVersions.value)
        assertTrue(AppGraph.dataVersions.value.revision > uiBefore.revision)
        AppGraph.notifyDataChanged(DataChange.MESSAGES)
        assertTrue(AppGraph.exportDataVersions.value.messageStructure > before.messageStructure)
        AppGraph.notifyDataChanged(DataChange.BLOGS)
        assertTrue(AppGraph.exportDataVersions.value.blogContent > before.blogContent)
    }
}
