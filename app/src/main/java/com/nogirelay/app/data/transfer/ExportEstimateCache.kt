package com.nogirelay.app.data.transfer

import com.nogirelay.app.data.MediaCandidate
import com.nogirelay.app.data.MessageType
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

internal data class ExportEstimateKey(
    val kind: ExportKind,
    val members: Set<String>,
    val includeMedia: Boolean,
    val contentRevision: Long,
    val mediaRevision: Long,
)

/** Entries are keyed by content/media revisions and may be backed by a persistent store. */
internal class ExportEstimateCache(
    private val maxBytes: Long = 2 * 1024 * 1024,
    private val maxEntries: Int = 4,
    private val loadSerialized: (() -> String?)? = null,
    private val saveSerialized: ((String) -> Unit)? = null,
) {
    private companion object {
        const val COMPACT_PREFIX = "export-estimate-cache-v1\n"
        const val LIST_SEPARATOR = '\u001f'
        const val FIELD_SEPARATOR = '\u001e'
    }

    private data class Entry(val value: ExportEstimate, val bytes: Long)
    private val entries = LinkedHashMap<ExportEstimateKey, Entry>(4, 0.75f, true)
    private var retainedBytes = 0L
    private var loaded = false

    @Synchronized
    fun get(key: ExportEstimateKey): ExportEstimate? {
        loadPersisted()
        return entries[key]?.value
    }

    @Synchronized
    fun put(key: ExportEstimateKey, value: ExportEstimate) {
        loadPersisted()
        entries.remove(key)?.let { retainedBytes -= it.bytes }
        val bytes = estimateBytes(key, value)
        if (bytes > maxBytes || maxEntries <= 0) {
            saveSerialized?.invoke(serialize())
            return
        }
        entries[key.copy(members = key.members.toSet())] = Entry(value, bytes)
        retainedBytes += bytes
        trimToLimits()
        saveSerialized?.invoke(serialize())
    }

    private fun loadPersisted() {
        if (loaded) return
        loaded = true
        val serialized = loadSerialized?.invoke() ?: return
        if (serialized.startsWith(COMPACT_PREFIX)) {
            loadCompact(serialized)
            return
        }
        runCatching {
            val array = JSONObject(serialized).optJSONArray("entries") ?: return@runCatching
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val keyJson = item.optJSONObject("key") ?: continue
                val valueJson = item.optJSONObject("value") ?: continue
                val kind = runCatching { ExportKind.valueOf(keyJson.optString("kind")) }.getOrNull() ?: continue
                val members = keyJson.optJSONArray("members")?.let { memberArray ->
                    buildSet {
                        for (memberIndex in 0 until memberArray.length()) {
                            memberArray.optString(memberIndex).takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                } ?: emptySet()
                val phase = runCatching {
                    ExportEstimatePhase.valueOf(valueJson.optJSONObject("scanProgress")?.optString("phase").orEmpty())
                }.getOrNull() ?: continue
                val progressJson = valueJson.optJSONObject("scanProgress") ?: continue
                val scanProgress = ExportEstimateProgress(
                    phase = phase,
                    done = progressJson.optInt("done"),
                    total = progressJson.optInt("total"),
                )
                val byRole = valueJson.optJSONArray("byRole")?.let { roleArray ->
                    buildList {
                        for (roleIndex in 0 until roleArray.length()) {
                            val role = roleArray.optJSONObject(roleIndex) ?: continue
                            add(MediaRoleStat(
                                role = role.optString("role"),
                                referenced = role.optInt("referenced"),
                                cached = role.optInt("cached"),
                            ))
                        }
                    }
                }.orEmpty()
                val missing = valueJson.optJSONArray("missing")?.let { missingArray ->
                    buildList {
                        for (missingIndex in 0 until missingArray.length()) {
                            val candidate = missingArray.optJSONObject(missingIndex) ?: continue
                            val type = runCatching {
                                MessageType.valueOf(candidate.optString("type"))
                            }.getOrNull() ?: continue
                            add(MediaCandidate(
                                role = candidate.optString("role"),
                                url = candidate.optString("url"),
                                type = type,
                            ))
                        }
                    }
                }.orEmpty()
                val key = ExportEstimateKey(
                    kind = kind,
                    members = members,
                    includeMedia = keyJson.optBoolean("includeMedia"),
                    contentRevision = keyJson.optLong("contentRevision"),
                    mediaRevision = keyJson.optLong("mediaRevision"),
                )
                val value = ExportEstimate(
                    records = valueJson.optInt("records"),
                    mediaReferenced = valueJson.optInt("mediaReferenced"),
                    mediaCached = valueJson.optInt("mediaCached"),
                    mediaBytes = valueJson.optLong("mediaBytes"),
                    byRole = byRole,
                    missing = missing,
                    scanProgress = scanProgress,
                )
                val bytes = estimateBytes(key, value)
                if (bytes <= maxBytes) {
                    entries[key] = Entry(value, bytes)
                    retainedBytes += bytes
                }
            }
            trimToLimits()
        }.onFailure {
            entries.clear()
            retainedBytes = 0L
        }
    }

    private fun loadCompact(serialized: String) {
        runCatching {
            serialized.lineSequence().drop(1).forEach { line ->
                val fields = line.split('\t')
                if (fields.size != 14) return@forEach
                val values = fields.map(::decode)
                val kind = runCatching { ExportKind.valueOf(values[0]) }.getOrNull() ?: return@forEach
                val key = ExportEstimateKey(
                    kind = kind,
                    members = values[1].split(LIST_SEPARATOR).filter(String::isNotBlank).toSet(),
                    includeMedia = values[2].toBoolean(),
                    contentRevision = values[3].toLong(),
                    mediaRevision = values[4].toLong(),
                )
                val byRole = values[12].takeUnless(String::isBlank)?.split(LIST_SEPARATOR).orEmpty()
                    .mapNotNull { item ->
                        val parts = item.split(FIELD_SEPARATOR)
                        if (parts.size != 3) null else MediaRoleStat(parts[0], parts[1].toInt(), parts[2].toInt())
                    }
                val missing = values[13].takeUnless(String::isBlank)?.split(LIST_SEPARATOR).orEmpty()
                    .mapNotNull { item ->
                        val parts = item.split(FIELD_SEPARATOR)
                        if (parts.size != 3) null else runCatching {
                            MediaCandidate(parts[0], parts[1], MessageType.valueOf(parts[2]))
                        }.getOrNull()
                    }
                val value = ExportEstimate(
                    records = values[5].toInt(),
                    mediaReferenced = values[6].toInt(),
                    mediaCached = values[7].toInt(),
                    mediaBytes = values[8].toLong(),
                    byRole = byRole,
                    missing = missing,
                    scanProgress = ExportEstimateProgress(
                        phase = ExportEstimatePhase.valueOf(values[9]),
                        done = values[10].toInt(),
                        total = values[11].toInt(),
                    ),
                )
                val bytes = estimateBytes(key, value)
                if (bytes <= maxBytes) {
                    entries[key] = Entry(value, bytes)
                    retainedBytes += bytes
                }
            }
            trimToLimits()
        }.onFailure {
            entries.clear()
            retainedBytes = 0L
        }
    }

    private fun trimToLimits() {
        val iterator = entries.entries.iterator()
        while ((retainedBytes > maxBytes || entries.size > maxEntries) && iterator.hasNext()) {
            retainedBytes -= iterator.next().value.bytes
            iterator.remove()
        }
    }

    private fun estimateBytes(key: ExportEstimateKey, value: ExportEstimate): Long =
        128L + key.members.sumOf { 48L + it.length * 2L } +
            value.missing.sumOf { 96L + (it.url.length.toLong() + it.role.length) * 2L } +
            value.byRole.sumOf { 48L + it.role.length * 2L }

    private fun serialize(): String = buildString {
        append(COMPACT_PREFIX)
        entries.forEach { (key, entry) ->
            val value = entry.value
            val fields = listOf(
                key.kind.name,
                key.members.sorted().joinToString(LIST_SEPARATOR.toString()),
                key.includeMedia.toString(),
                key.contentRevision.toString(),
                key.mediaRevision.toString(),
                value.records.toString(),
                value.mediaReferenced.toString(),
                value.mediaCached.toString(),
                value.mediaBytes.toString(),
                value.scanProgress.phase.name,
                value.scanProgress.done.toString(),
                value.scanProgress.total.toString(),
                value.byRole.joinToString(LIST_SEPARATOR.toString()) {
                    listOf(it.role, it.referenced.toString(), it.cached.toString())
                        .joinToString(FIELD_SEPARATOR.toString())
                },
                value.missing.joinToString(LIST_SEPARATOR.toString()) {
                    listOf(it.role, it.url, it.type.name).joinToString(FIELD_SEPARATOR.toString())
                },
            )
            append(fields.joinToString("\t", transform = ::encode)).append('\n')
        }
    }

    private fun encode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
