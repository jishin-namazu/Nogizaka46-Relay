package com.nogirelay.app.data.transfer

import android.content.Context
import android.net.Uri
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.MediaRefIndex
import com.nogirelay.app.data.MediaRefKind
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaContentDigests
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

data class ExportRequest(
    val kind: ExportKind,
    val memberKeys: Set<String>,
    val includeTranslations: Boolean,
    val outputName: String,

    val includeMedia: Boolean = true,
)

data class ExportReport(
    val recordCount: Int,
    val mediaCount: Int,
    val mediaBytes: Long,
    val skippedCount: Int,
    val outputName: String,
)

data class MediaRoleStat(val role: String, val referenced: Int, val cached: Int)

data class ExportEstimate(
    val records: Int,
    val mediaReferenced: Int,
    val mediaCached: Int,
    val mediaBytes: Long,
    val byRole: List<MediaRoleStat> = emptyList(),

    val missing: List<MediaCandidate> = emptyList(),
    val scanProgress: ExportEstimateProgress,
)

object DataExporter {
    private const val BUFFER = 64 * 1024

    private class ResolvedMedia(
        val path: String,
        val file: File,
        val bytes: Long,
    )

    suspend fun estimate(
        context: Context,
        kind: ExportKind,
        memberKeys: Set<String>,

        includeMedia: Boolean = true,

        onProgress: ((ExportEstimateProgress) -> Unit)? = null,
    ): ExportEstimate {

        val job = coroutineContext[Job]
        val throttle = com.nogirelay.app.performance.ProgressThrottle(android.os.SystemClock::elapsedRealtime)
        fun publishProgress(phase: ExportEstimatePhase, done: Int, total: Int) {
            if (throttle.shouldPublish(done, total)) {
                onProgress?.invoke(ExportEstimateProgress(phase, done, total))
            }
        }
        var records = 0
        val referenced = HashSet<String>()
        val cached = HashSet<String>()
        val referencedByRole = LinkedHashMap<String, Int>()
        val cachedByRole = LinkedHashMap<String, Int>()
        val missing = mutableListOf<MediaCandidate>()
        var bytes = 0L

        val total = when (kind) {
            ExportKind.MESSAGES -> AppGraph.messages.countMessagesForMembers(memberKeys)
            ExportKind.BLOGS -> AppGraph.blogs.countBlogsForMembers(memberKeys)
        }
        if (!includeMedia) {
            publishProgress(ExportEstimatePhase.RECORDS, total, total)
            return ExportEstimate(
                records = total, mediaReferenced = 0, mediaCached = 0, mediaBytes = 0,
                scanProgress = ExportEstimateProgress(ExportEstimatePhase.RECORDS, total, total),
            )
        }

        fun checkActive() {
            if (job?.isActive != true) throw CancellationException("统计已取消")
        }

        fun inspect(candidates: List<MediaCandidate>) {
            candidates.forEach { candidate ->
                checkActive()
                val key = candidate.role + "|" + candidate.url
                if (!referenced.add(key)) return@forEach
                referencedByRole[candidate.role] = (referencedByRole[candidate.role] ?: 0) + 1
                val file = MediaDownloader.cachedFileForUrl(context, candidate.url, candidate.type)
                if (file != null) {
                    cached += key
                    cachedByRole[candidate.role] = (cachedByRole[candidate.role] ?: 0) + 1
                    bytes += file.length()
                } else {
                    missing += candidate
                }
            }
        }

        val refRows = if (AppGraph.mediaRefs.mediaRefsReady()) {
            AppGraph.mediaRefs.mediaRefsFor(kind.toMediaRefKind(), memberKeys)
        } else {
            MediaRefIndex.ensureBuilt(AppGraph.mediaRefs)
            null
        }
        if (refRows != null) {
            records = total
            publishProgress(ExportEstimatePhase.MEDIA, 0, refRows.size)
            refRows.forEachIndexed { index, row ->
                checkActive()
                inspect(listOf(MediaCandidate(row.role, row.url, row.type)))
                // Time-based throttling keeps updates regular even when individual file checks are slow.
                publishProgress(ExportEstimatePhase.MEDIA, index + 1, refRows.size)
            }
            publishProgress(ExportEstimatePhase.MEDIA, refRows.size, refRows.size)
        } else {
            publishProgress(ExportEstimatePhase.RECORDS, 0, total)
            when (kind) {
                ExportKind.MESSAGES -> AppGraph.messages.forEachMessageForMembers(memberKeys) { message ->
                    checkActive()
                    inspect(ExportFormat.mediaCandidates(message))
                    records += 1
                    publishProgress(ExportEstimatePhase.RECORDS, records, total)
                }
                ExportKind.BLOGS -> AppGraph.blogs.forEachBlogForMembers(memberKeys) { post ->
                    checkActive()
                    inspect(ExportFormat.mediaCandidates(post))
                    records += 1
                    publishProgress(ExportEstimatePhase.RECORDS, records, total)
                }
            }
            publishProgress(ExportEstimatePhase.RECORDS, total, total)
        }

        return ExportEstimate(
            records = records,
            mediaReferenced = referenced.size,
            mediaCached = cached.size,
            mediaBytes = bytes,
            byRole = referencedByRole.map { (role, count) ->
                MediaRoleStat(role, count, cachedByRole[role] ?: 0)
            },
            missing = missing,
            scanProgress = if (refRows != null) {
                ExportEstimateProgress(ExportEstimatePhase.MEDIA, refRows.size, refRows.size)
            } else {
                ExportEstimateProgress(ExportEstimatePhase.RECORDS, total, total)
            },
        )
    }

    suspend fun export(
        context: Context,
        request: ExportRequest,
        outputUri: Uri,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
    ): ExportReport {
        val resolver = context.contentResolver
        val manifest = ExportManifest(
            formatVersion = ExportFormat.FORMAT_VERSION,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            exportedAt = ExportFormat.isoNow(),
            kind = request.kind,
            includesMedia = request.includeMedia,
            includesTranslations = request.includeTranslations,
            members = manifestMembers(request.kind, request.memberKeys),
        )
        val totalRecords = when (request.kind) {
            ExportKind.MESSAGES -> AppGraph.messages.countMessagesForMembers(request.memberKeys)
            ExportKind.BLOGS -> AppGraph.blogs.countBlogsForMembers(request.memberKeys)
        }

        val mediaPaths = LinkedHashMap<String, ResolvedMedia>()
        val resolvedByKey = HashMap<String, ResolvedMedia?>()
        val skipped = mutableListOf<JSONObject>()
        var recordCount = 0

        val refsByRecord: Map<String, List<MediaCandidate>>? = if (request.includeMedia) {
            if (AppGraph.mediaRefs.mediaRefsReady()) {
                AppGraph.mediaRefs.mediaRefsFor(request.kind.toMediaRefKind(), request.memberKeys)
                    .groupBy({ it.recordId }, { MediaCandidate(it.role, it.url, it.type) })
            } else {
                MediaRefIndex.ensureBuilt(AppGraph.mediaRefs)
                null
            }
        } else {
            null
        }

        val rawOutput = resolver.openOutputStream(outputUri) ?: error("无法写入所选文件")

        val job = coroutineContext[Job]

        fun resolveCached(candidate: MediaCandidate): ResolvedMedia? {
            val key = candidate.role + "|" + candidate.url
            return if (resolvedByKey.containsKey(key)) {
                resolvedByKey[key]
            } else {
                resolve(context, candidate).also { resolvedByKey[key] = it }
            }
        }

        try {
            ZipOutputStream(BufferedOutputStream(rawOutput, BUFFER)).use { zip ->
                // With the media-ref index available, resolve every media file up front so each one reports progress.
                if (refsByRecord != null) {
                    val unique = refsByRecord.values.asSequence().flatten()
                        .distinctBy { it.role + "|" + it.url }
                        .toList()
                    onProgress("校验媒体", 0, unique.size)
                    unique.forEachIndexed { index, candidate ->
                        if (job?.isActive != true) throw CancellationException("导出已取消")
                        resolveCached(candidate)
                        onProgress("校验媒体", index + 1, unique.size)
                    }
                }

                zip.putNextEntry(ZipEntry(ExportFormat.MANIFEST_ENTRY))
                zip.write(ExportFormat.manifestToJson(manifest).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(request.kind.entryName))

                fun writeRecord(item: Any) {

                    if (job?.isActive != true) throw CancellationException("导出已取消")
                    val refs = mutableListOf<MediaRef>()

                    if (request.includeMedia) {

                        val recordRefs = refsByRecord?.get(recordId(request.kind, item))
                            ?: if (refsByRecord == null) candidates(request.kind, item) else emptyList()
                        recordRefs.forEach { candidate ->
                            val media = resolveCached(candidate)
                            if (media == null) {
                                skipped += JSONObject().apply {
                                    put("kind", "media")
                                    put("role", candidate.role)
                                    put("url", candidate.url)
                                    put("reason", "not-cached")
                                }
                            } else {
                                mediaPaths[media.path] = media
                                refs += MediaRef(candidate.role, candidate.url, media.path)
                            }
                        }
                    }
                    val json = when (request.kind) {
                        ExportKind.MESSAGES -> ExportFormat.messageToJson(
                            item as RelayMessage,
                            refs,
                            request.includeTranslations,
                        )
                        ExportKind.BLOGS -> ExportFormat.blogToJson(
                            item as BlogPost,
                            refs,
                            request.includeTranslations,
                        )
                    }
                    zip.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
                    recordCount += 1

                    onProgress("读取记录", recordCount, totalRecords)
                }
                onProgress("读取记录", 0, totalRecords)
                when (request.kind) {
                    ExportKind.MESSAGES -> AppGraph.messages.forEachMessageForMembers(request.memberKeys) { writeRecord(it) }
                    ExportKind.BLOGS -> AppGraph.blogs.forEachBlogForMembers(request.memberKeys) { writeRecord(it) }
                }
                zip.closeEntry()

                // Media is already compressed; level 0 DEFLATE lets the zip stream compute CRC while copying.
                zip.setLevel(Deflater.NO_COMPRESSION)
                var mediaDone = 0
                onProgress("打包媒体", 0, mediaPaths.size)
                mediaPaths.values.forEach { media ->
                    coroutineContext.ensureActive()
                    addMedia(zip, media)
                    mediaDone += 1
                    onProgress("打包媒体", mediaDone, mediaPaths.size)
                }

                if (skipped.isNotEmpty()) {
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                    zip.putNextEntry(ZipEntry(ExportFormat.SKIPPED_ENTRY))
                    skipped.forEach { zip.write((it.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {

            runCatching { resolver.delete(outputUri, null, null) }
            throw error
        }

        return ExportReport(
            recordCount = recordCount,
            mediaCount = mediaPaths.size,
            mediaBytes = mediaPaths.values.sumOf { it.bytes },
            skippedCount = skipped.size,
            outputName = request.outputName,
        )
    }

    private fun candidates(kind: ExportKind, item: Any) = when (kind) {
        ExportKind.MESSAGES -> ExportFormat.mediaCandidates(item as RelayMessage)
        ExportKind.BLOGS -> ExportFormat.mediaCandidates(item as BlogPost)
    }

    private fun ExportKind.toMediaRefKind(): MediaRefKind = when (this) {
        ExportKind.MESSAGES -> MediaRefKind.MESSAGES
        ExportKind.BLOGS -> MediaRefKind.BLOGS
    }

    private fun recordId(kind: ExportKind, item: Any): String = when (kind) {
        ExportKind.MESSAGES -> (item as RelayMessage).id
        ExportKind.BLOGS -> (item as BlogPost).id
    }

    private fun manifestMembers(
        kind: ExportKind,
        memberKeys: Set<String>,
    ): List<ManifestMember> = when (kind) {
        ExportKind.MESSAGES -> AppGraph.messages.messageExportMembers()
            .filter { it.memberKey in memberKeys }
            .map {
                ManifestMember(it.memberKey, it.name, it.category, it.avatarUrl, it.displayOrder, it.directory)
            }
        ExportKind.BLOGS -> AppGraph.blogMembers.blogMembers()
            .filter { it.id in memberKeys }
            .map {
                ManifestMember(it.id, it.name, it.category, it.avatarUrl, it.displayOrder, true, it.graduated)
            }
    }

    private fun resolve(context: Context, candidate: MediaCandidate): ResolvedMedia? {
        val file = MediaDownloader.cachedFileForUrl(context, candidate.url, candidate.type) ?: return null
        val extension = file.name.substringAfterLast('.', "bin")
        // Usually recorded at download time; only files cached before that need hashing here.
        val sha = MediaContentDigests.getOrCompute(file)
        return ResolvedMedia(
            path = ExportFormat.mediaEntryName(sha, extension),
            file = file,
            bytes = file.length(),
        )
    }

    private fun addMedia(zip: ZipOutputStream, media: ResolvedMedia) {
        zip.putNextEntry(ZipEntry(media.path))
        FileInputStream(media.file).use { input -> input.copyTo(zip, BUFFER) }
        zip.closeEntry()
    }
}
