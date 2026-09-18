package com.nogirelay.app.data.transfer

import android.content.Context
import android.net.Uri
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

data class ExportRequest(
    val kind: ExportKind,
    val memberKeys: Set<String>,
    val includeTranslations: Boolean,
    val outputName: String,
    /** false = 只导出记录本身，不打包媒体；manifest 的 includesMedia 也会是 false。 */
    val includeMedia: Boolean = true,
)

data class ExportReport(
    val recordCount: Int,
    val mediaCount: Int,
    val mediaBytes: Long,
    val skippedCount: Int,
    val outputName: String,
)

/** 一个归档会引用某种媒体角色的多少个文件，以及其中有多少已在本地。 */
data class MediaRoleStat(val role: String, val referenced: Int, val cached: Int)

/** 在写入任何内容之前、显示在导出按钮旁的预览。 */
data class ExportEstimate(
    val records: Int,
    val mediaReferenced: Int,
    val mediaCached: Int,
    val mediaBytes: Long,
    val byRole: List<MediaRoleStat> = emptyList(),
    /** 此选择引用、但设备尚未拥有的唯一媒体。 */
    val missing: List<MediaCandidate> = emptyList(),
)

/**
 * 为一种类型和一组成员写出 Nogi Relay 归档。
 *
 * 导出会打包设备缓存中已有的媒体，其余内容记录在
 * `data/skipped.jsonl` 中。记录始终完整写出。
 */
object DataExporter {
    private const val PAGE_SIZE = 500
    private const val BUFFER = 64 * 1024

    private class ResolvedMedia(
        val path: String,
        val file: File,
        val bytes: Long,
        val crc: Long,
    )

    suspend fun estimate(
        context: Context,
        kind: ExportKind,
        memberKeys: Set<String>,
        /** false = 导出不含媒体：只要记录条数，不遍历记录也不收集缺失列表。 */
        includeMedia: Boolean = true,
        /** 回报已扫描的记录数占所选范围总数的比例。 */
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): ExportEstimate {
        val database = AppGraph.database
        // 扫描运行在数据库游标内部，那里无法做挂起上下文检查，所以
        // 改为捕获 job 并轮询。
        val job = coroutineContext[Job]
        var records = 0
        val referenced = HashSet<String>()
        val cached = HashSet<String>()
        val referencedByRole = LinkedHashMap<String, Int>()
        val cachedByRole = LinkedHashMap<String, Int>()
        val missing = mutableListOf<MediaCandidate>()
        var bytes = 0L

        // 先计数是把“正在统计…”变成真实进度条的关键；下面的遍历恰好
        // 会访问这么多行。
        val total = when (kind) {
            ExportKind.MESSAGES -> database.countMessagesForMembers(memberKeys)
            ExportKind.BLOGS -> database.countBlogsForMembers(memberKeys)
        }
        onProgress?.invoke(0, total)

        // 不含媒体时条数已经由 count 查询给出，没必要再走一遍游标去检查每个文件的缓存状态。
        if (!includeMedia) {
            onProgress?.invoke(total, total)
            return ExportEstimate(records = total, mediaReferenced = 0, mediaCached = 0, mediaBytes = 0)
        }

        // 逐条回报：一次 StateFlow 写入的成本低于遍历游标、查缓存文件。
        fun reportProgress() {
            onProgress?.invoke(records, total)
        }

        fun checkActive() {
            if (job?.isActive != true) throw CancellationException("统计已取消")
        }

        // 在这里收集缺失列表，才能让补齐流程立即开始下载，
        // 而不必为了重新发现相同的 URL 再遍历一遍所有记录。
        fun inspect(candidates: List<MediaCandidate>) {
            candidates.forEach { candidate ->
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

        when (kind) {
            ExportKind.MESSAGES -> database.forEachMessageForMembers(memberKeys) { message ->
                checkActive()
                records += 1
                reportProgress()
                inspect(ExportFormat.mediaCandidates(message))
            }
            ExportKind.BLOGS -> database.forEachBlogForMembers(memberKeys) { post ->
                checkActive()
                records += 1
                reportProgress()
                inspect(ExportFormat.mediaCandidates(post))
            }
        }
        onProgress?.invoke(records, total)

        return ExportEstimate(
            records = records,
            mediaReferenced = referenced.size,
            mediaCached = cached.size,
            mediaBytes = bytes,
            byRole = referencedByRole.map { (role, count) ->
                MediaRoleStat(role, count, cachedByRole[role] ?: 0)
            },
            missing = missing,
        )
    }

    suspend fun export(
        context: Context,
        request: ExportRequest,
        outputUri: Uri,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
    ): ExportReport {
        val database = AppGraph.database
        val resolver = context.contentResolver
        val manifest = ExportManifest(
            formatVersion = ExportFormat.FORMAT_VERSION,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            exportedAt = ExportFormat.isoNow(),
            kind = request.kind,
            includesMedia = request.includeMedia,
            includesTranslations = request.includeTranslations,
            members = manifestMembers(database, request.kind, request.memberKeys),
        )
        val totalRecords = when (request.kind) {
            ExportKind.MESSAGES -> database.countMessagesForMembers(request.memberKeys)
            ExportKind.BLOGS -> database.countBlogsForMembers(request.memberKeys)
        }

        val mediaPaths = LinkedHashMap<String, ResolvedMedia>()
        val resolvedByKey = HashMap<String, ResolvedMedia?>()
        val skipped = mutableListOf<JSONObject>()
        var recordCount = 0

        val rawOutput = resolver.openOutputStream(outputUri) ?: error("无法写入所选文件")
        try {
            ZipOutputStream(BufferedOutputStream(rawOutput, BUFFER)).use { zip ->
                zip.putNextEntry(ZipEntry(ExportFormat.MANIFEST_ENTRY))
                zip.write(ExportFormat.manifestToJson(manifest).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(request.kind.entryName))
                var offset = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val page = page(database, request.kind, request.memberKeys, offset)
                    if (page.isEmpty()) break
                    page.forEach { item ->
                        val refs = mutableListOf<MediaRef>()
                        // 不含媒体时 refs 保持为空，记录本身照常写全，导入端会看到 includesMedia=false。
                        if (request.includeMedia) {
                            candidates(request.kind, item).forEach { candidate ->
                                val key = candidate.role + "|" + candidate.url
                                val media = if (resolvedByKey.containsKey(key)) {
                                    resolvedByKey[key]
                                } else {
                                    resolve(context, candidate).also { resolvedByKey[key] = it }
                                }
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
                        // 逐条回报，不再按页（500 条）跳。
                        onProgress("读取记录", recordCount, totalRecords)
                    }
                    offset += page.size
                    if (page.size < PAGE_SIZE) break
                }
                zip.closeEntry()

                var mediaDone = 0
                mediaPaths.values.forEach { media ->
                    coroutineContext.ensureActive()
                    addStored(zip, media)
                    mediaDone += 1
                    onProgress("打包媒体", mediaDone, mediaPaths.size)
                }

                if (skipped.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry(ExportFormat.SKIPPED_ENTRY))
                    skipped.forEach { zip.write((it.toString() + "\n").toByteArray(Charsets.UTF_8)) }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            // 被取消或失败的导出不能留下写了一半的归档。
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

    private fun page(
        database: com.nogirelay.app.data.MessageDatabase,
        kind: ExportKind,
        memberKeys: Set<String>,
        offset: Int,
    ): List<Any> = when (kind) {
        ExportKind.MESSAGES -> database.messagePageForMembers(memberKeys, PAGE_SIZE, offset)
        ExportKind.BLOGS -> database.blogPageForMembers(memberKeys, PAGE_SIZE, offset)
    }

    private fun candidates(kind: ExportKind, item: Any) = when (kind) {
        ExportKind.MESSAGES -> ExportFormat.mediaCandidates(item as RelayMessage)
        ExportKind.BLOGS -> ExportFormat.mediaCandidates(item as BlogPost)
    }

    private fun manifestMembers(
        database: com.nogirelay.app.data.MessageDatabase,
        kind: ExportKind,
        memberKeys: Set<String>,
    ): List<ManifestMember> = when (kind) {
        ExportKind.MESSAGES -> database.messageExportMembers()
            .filter { it.memberKey in memberKeys }
            .map {
                ManifestMember(it.memberKey, it.name, it.category, it.avatarUrl, it.displayOrder, it.directory)
            }
        ExportKind.BLOGS -> database.blogMembers()
            .filter { it.id in memberKeys }
            .map {
                ManifestMember(it.id, it.name, it.category, it.avatarUrl, it.displayOrder, true, it.graduated)
            }
    }

    private fun resolve(context: Context, candidate: MediaCandidate): ResolvedMedia? {
        val file = MediaDownloader.cachedFileForUrl(context, candidate.url, candidate.type) ?: return null
        val extension = file.name.substringAfterLast('.', "bin")
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var bytes = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                crc.update(buffer, 0, read)
                bytes += read
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return ResolvedMedia(
            path = ExportFormat.mediaEntryName(sha, extension),
            file = file,
            bytes = bytes,
            crc = crc.value,
        )
    }

    /**
     * 媒体本身已经压缩，因此条目按原样存储。STORED 需要事先提供大小和 CRC，
     * 这正是 [resolve] 在生成内容哈希的同一遍中收集的。
     */
    private fun addStored(zip: ZipOutputStream, media: ResolvedMedia) {
        val entry = ZipEntry(media.path).apply {
            method = ZipEntry.STORED
            size = media.bytes
            compressedSize = media.bytes
            crc = media.crc
        }
        zip.putNextEntry(entry)
        FileInputStream(media.file).use { input -> input.copyTo(zip, BUFFER) }
        zip.closeEntry()
    }
}