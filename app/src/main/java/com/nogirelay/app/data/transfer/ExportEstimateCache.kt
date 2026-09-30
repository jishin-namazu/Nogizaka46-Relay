package com.nogirelay.app.data.transfer

internal data class ExportEstimateKey(
    val kind: ExportKind,
    val members: Set<String>,
    val includeMedia: Boolean,
    val contentRevision: Long,
    val mediaRevision: Long,
)

internal class ExportEstimateCache(
    private val nowMillis: () -> Long,
    private val maxBytes: Long = 2 * 1024 * 1024,
    private val maxEntries: Int = 4,
    private val ttlMillis: Long = 60_000,
) {
    private data class Entry(val value: ExportEstimate, val bytes: Long, val createdAt: Long)
    private val entries = LinkedHashMap<ExportEstimateKey, Entry>(4, 0.75f, true)
    private var retainedBytes = 0L

    @Synchronized
    fun get(key: ExportEstimateKey): ExportEstimate? {
        removeExpired()
        return entries[key]?.value
    }

    @Synchronized
    fun put(key: ExportEstimateKey, value: ExportEstimate) {
        removeExpired()
        entries.remove(key)?.let { retainedBytes -= it.bytes }
        val bytes = 128L + key.members.sumOf { 48L + it.length * 2L } +
            value.missing.sumOf { 96L + (it.url.length.toLong() + it.role.length) * 2L } +
            value.byRole.sumOf { 48L + it.role.length * 2L }
        if (bytes > maxBytes || maxEntries <= 0) return
        entries[key.copy(members = key.members.toSet())] = Entry(value, bytes, nowMillis())
        retainedBytes += bytes
        val iterator = entries.entries.iterator()
        while ((retainedBytes > maxBytes || entries.size > maxEntries) && iterator.hasNext()) {
            retainedBytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }

    private fun removeExpired() {
        val now = nowMillis()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (now - entry.createdAt >= ttlMillis) {
                retainedBytes -= entry.bytes
                iterator.remove()
            }
        }
    }
}
