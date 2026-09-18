package com.nogirelay.app.data.transfer

import android.content.Context
import android.net.Uri
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogMemberCategories
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class ImportOptions(
    val importMedia: Boolean = true,
    val importMembers: Boolean = true,
)

/** 确认对话框所需的一切，从最前面的 manifest 读取，不触碰媒体。 */
data class ImportPreview(
    val kind: ExportKind,
    val formatVersion: Int,
    val exportedAt: String,
    val appVersionName: String,
    val includesMedia: Boolean,
    val includesTranslations: Boolean,
    val members: List<ManifestMember>,
)

data class ImportReport(
    val kind: ExportKind,
    val inserted: Int,
    val duplicates: Int,
    val invalid: Int,
    val translationsBackfilled: Int,
    val linksRefreshed: Int,
    val membersMerged: Int,
    val mediaStored: Int,
    val mediaReused: Int,
    val mediaFailed: Int,
    val mediaBytes: Long,
    val errors: List<String>,
)

/**
 * 将一个 Nogi Relay 归档合并到本地数据库中。
 *
 * 合并按主键增量且幂等：已存在的行绝不会被重写，因此
 * 第二次导入同一归档会把一切都报告为重复。唯一的例外是
 * 归档带有译文而本地行没有——参见
 * [com.nogirelay.app.data.MessageDatabase.backfillMessageTranslation]。
 */
object DataImporter {
    private const val BUFFER = 64 * 1024
    private const val MAX_REPORTED_ERRORS = 10

    suspend fun preview(context: Context, uri: Uri): ImportPreview {
        val raw = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        ZipInputStream(BufferedInputStream(raw, BUFFER)).use { zip ->
            var entry = zip.nextEntry
            var inspected = 0
            while (entry != null && inspected < 64) {
                if (entry.name == ExportFormat.MANIFEST_ENTRY) {
                    val manifest = ExportFormat.manifestFromJson(JSONObject(readText(zip)))
                    return ImportPreview(
                        kind = manifest.kind,
                        formatVersion = manifest.formatVersion,
                        exportedAt = manifest.exportedAt,
                        appVersionName = manifest.appVersionName,
                        includesMedia = manifest.includesMedia,
                        includesTranslations = manifest.includesTranslations,
                        members = manifest.members,
                    )
                }
                zip.closeEntry()
                entry = zip.nextEntry
                inspected += 1
            }
        }
        error("归档缺少 manifest.json，可能不是 Nogi Relay 导出的文件")
    }

    suspend fun importArchive(
        context: Context,
        uri: Uri,
        options: ImportOptions,
        onProgress: (phase: String, done: Int, total: Int) -> Unit,
    ): ImportReport {
        val database = AppGraph.database
        val staging = File(context.cacheDir, "transfer-import")
        val raw = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")

        var kind: ExportKind? = null
        val pathToUrls = HashMap<String, MutableList<String>>()
        val deferredPaths = LinkedHashSet<String>()

        var inserted = 0
        var duplicates = 0
        var invalid = 0
        var backfilled = 0
        var linkRefreshed = 0
        var membersMerged = 0
        var mediaStored = 0
        var mediaReused = 0
        var mediaFailed = 0
        var mediaBytes = 0L
        var processed = 0
        val errors = mutableListOf<String>()

        fun reportError(message: String) {
            if (errors.size < MAX_REPORTED_ERRORS) errors += message
        }

        /**
         * 逐条回写：每条记录读完就立刻在它自己的事务里落库，不做攒批，进程中断最多丢当前这一条。
         * 重复 id 时归档"显式写出的链接列"（含空值）覆盖本地列：同 id 不同地址时以归档为准，
         * 显式空值也会清掉本地值。正文、已读状态和译文不动，媒体也不碰。
         */
        fun writeMessage(message: RelayMessage, links: Map<String, String>) {
            database.transaction {
                if (database.insertImported(message)) {
                    inserted += 1
                } else {
                    duplicates += 1
                    if (database.refreshImportedLinks(message.id, links)) linkRefreshed += 1
                    val translation = message.translation
                    if (!translation.isNullOrBlank() &&
                        database.backfillMessageTranslation(message.id, translation)
                    ) {
                        backfilled += 1
                    }
                }
            }
        }

        fun writeBlog(post: BlogPost, links: Map<String, String>) {
            database.transaction {
                if (database.insertBlogIfAbsent(post)) {
                    inserted += 1
                } else {
                    duplicates += 1
                    if (database.refreshImportedBlogLinks(post.id, links)) linkRefreshed += 1
                    val translation = post.translation
                    if (!translation.isNullOrBlank() &&
                        database.backfillBlogTranslation(post.id, translation)
                    ) {
                        backfilled += 1
                    }
                }
            }
        }

        try {
            ZipInputStream(BufferedInputStream(raw, BUFFER)).use { zip ->
                fun consumeJsonl(entryKind: ExportKind) {
                    val reader = BufferedReader(InputStreamReader(zip, Charsets.UTF_8), BUFFER)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        try {
                            val json = JSONObject(line)
                            ExportFormat.mediaRefsFrom(json).forEach { ref ->
                                pathToUrls.getOrPut(ref.path) { mutableListOf() }.add(ref.url)
                            }
                            when (entryKind) {
                                ExportKind.MESSAGES -> writeMessage(
                                    ExportFormat.jsonToMessage(json),
                                    ExportFormat.messageLinksFrom(json),
                                )
                                ExportKind.BLOGS -> writeBlog(
                                    ExportFormat.jsonToBlog(json),
                                    ExportFormat.blogLinksFrom(json),
                                )
                            }
                        } catch (error: Exception) {
                            invalid += 1
                            reportError("记录解析失败：" + error.message)
                        }
                        processed += 1
                        // 按记录上报：写入 StateFlow 值开销很小，而且 Compose
                        // 会合并帧，所以没有理由让计数器跳着增长。
                        onProgress("导入记录", processed, 0)
                    }
                }

                /** 校验一个媒体条目，并在每一个引用它的缓存路径上把它实体化。 */
                fun storeMedia(path: String, urls: List<String>) {
                    val bare = path.removePrefix(ExportFormat.MEDIA_PREFIX)
                    val dot = bare.lastIndexOf('.')
                    if (dot <= 0) {
                        mediaFailed += 1
                        reportError("媒体条目名无效：" + path)
                        return
                    }
                    val expectedSha = bare.substring(0, dot)
                    val extension = bare.substring(dot + 1)
                    val targets = urls
                        .map { it to MediaDownloader.cacheFileForUrl(context, it, extension) }
                        .distinctBy { it.second.absolutePath }
                    val missing = targets.filterNot { it.second.isFile && it.second.length() > 0L }
                    if (missing.isEmpty()) {
                        mediaReused += targets.size
                        targets.forEach { MediaDownloader.clearNotFound(context, it.first) }
                        return
                    }
                    if (!staging.exists() && !staging.mkdirs()) {
                        mediaFailed += missing.size
                        reportError("无法创建导入临时目录")
                        return
                    }
                    val temp = File(staging, bare)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var overflow = false
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > ExportFormat.MAX_ENTRY_BYTES) {
                                overflow = true
                                break
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    if (overflow) {
                        temp.delete()
                        mediaFailed += missing.size
                        reportError("媒体超过单文件上限，已跳过：" + path)
                        return
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                        temp.delete()
                        mediaFailed += missing.size
                        reportError("媒体内容校验失败，已跳过：" + path)
                        return
                    }
                    // 单个不可写目标绝不能中止整个合并；记录已经
                    // 存好，而且可以重新运行幂等导入来补齐这些字节。
                    var stored = 0
                    missing.forEach { (url, target) ->
                        if (runCatching { copyInto(temp, target) }.isSuccess) {
                            MediaDownloader.clearNotFound(context, url)
                            stored += 1
                        } else {
                            mediaFailed += 1
                            reportError("媒体写入失败：" + url)
                        }
                    }
                    mediaStored += stored
                    mediaReused += targets.size - missing.size
                    mediaBytes += total
                    temp.delete()
                }

                /** 针对媒体位于记录之前的归档的回退方案。 */
                fun stageMedia(path: String) {
                    val bare = path.removePrefix(ExportFormat.MEDIA_PREFIX)
                    val dot = bare.lastIndexOf('.')
                    if (dot <= 0) {
                        mediaFailed += 1
                        return
                    }
                    val expectedSha = bare.substring(0, dot)
                    if (!staging.exists() && !staging.mkdirs()) return
                    val temp = File(staging, bare)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var overflow = false
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > ExportFormat.MAX_ENTRY_BYTES) {
                                overflow = true
                                break
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!overflow && actualSha.equals(expectedSha, ignoreCase = true)) {
                        deferredPaths += path
                    } else {
                        temp.delete()
                        mediaFailed += 1
                    }
                }

                var entry = zip.nextEntry
                var entryCount = 0
                var mediaProcessed = 0
                while (entry != null) {
                    coroutineContext.ensureActive()
                    entryCount += 1
                    if (entryCount > ExportFormat.MAX_ENTRIES) error("归档条目过多，已中止导入")
                    val name = entry.name
                    if (!isAllowedEntryName(name)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
                    when {
                        name == ExportFormat.MANIFEST_ENTRY -> {
                            val manifest = ExportFormat.manifestFromJson(JSONObject(readText(zip)))
                            kind = manifest.kind
                            if (options.importMembers) {
                                manifest.members.filter(ManifestMember::directory).forEach { member ->
                                    val merged = database.insertMemberIfAbsent(
                                        BlogMember(
                                            id = member.id,
                                            name = member.name,
                                            // 归档已带正确分类；旧归档仍写着「研究生」，这里折回 2期生。
                                            category = BlogMemberCategories.normalizeCategory(
                                                member.id,
                                                member.name,
                                                member.category,
                                            ),
                                            avatarUrl = member.avatarUrl,
                                            displayOrder = member.displayOrder,
                                            graduated = member.graduated,
                                        ),
                                    )
                                    if (merged) membersMerged += 1
                                }
                            }
                            onProgress("读取清单", 0, 0)
                        }
                        name == ExportKind.MESSAGES.entryName -> consumeJsonl(ExportKind.MESSAGES)
                        name == ExportKind.BLOGS.entryName -> consumeJsonl(ExportKind.BLOGS)
                        name.startsWith(ExportFormat.MEDIA_PREFIX) -> {
                            if (!options.importMedia) {
                                // 媒体总是跟在记录之后，所以已经没有可合并的内容了。
                                break
                            }
                            val urls = pathToUrls[name]
                            if (urls.isNullOrEmpty()) stageMedia(name) else storeMedia(name, urls)
                            mediaProcessed += 1
                            onProgress("导入媒体", mediaProcessed, 0)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }

                if (kind == null) error("归档缺少 manifest.json，可能不是 Nogi Relay 导出的文件")

                deferredPaths.forEach { path ->
                    val urls = pathToUrls[path]
                    val bare = path.removePrefix(ExportFormat.MEDIA_PREFIX)
                    val extension = bare.substringAfterLast('.', "bin")
                    val staged = File(staging, bare)
                    if (!staged.isFile) return@forEach
                    // 归档带了媒体文件，却没有任何记录引用它的 URL（url 为空或缺失）：
                    // 本地缓存按 sha256(url) 命名，没有 url 就没有落盘位置，因此静默跳过这一个
                    // 文件即可——它不算失败，记录本身照常导入。
                    if (urls.isNullOrEmpty()) return@forEach
                    urls.forEach { url ->
                        val target = MediaDownloader.cacheFileForUrl(context, url, extension)
                        if (target.isFile && target.length() > 0L) {
                            mediaReused += 1
                        } else if (runCatching { copyInto(staged, target) }.isSuccess) {
                            mediaStored += 1
                        } else {
                            mediaFailed += 1
                            reportError("媒体写入失败：" + url)
                        }
                        MediaDownloader.clearNotFound(context, url)
                    }
                }
            }
        } finally {
            staging.deleteRecursively()
        }

        return ImportReport(
            kind = kind ?: ExportKind.MESSAGES,
            inserted = inserted,
            duplicates = duplicates,
            invalid = invalid,
            translationsBackfilled = backfilled,
            linksRefreshed = linkRefreshed,
            membersMerged = membersMerged,
            mediaStored = mediaStored,
            mediaReused = mediaReused,
            mediaFailed = mediaFailed,
            mediaBytes = mediaBytes,
            errors = errors,
        )
    }

    /** 将 [source] 复制到 [target] 旁边并重命名，因此读取方绝不会看到不完整的文件。 */
    private fun copyInto(source: File, target: File) {
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            error("无法创建媒体缓存目录")
        }
        val temporary = File(parent, target.name + ".part-" + System.nanoTime())
        source.copyTo(temporary, overwrite = true)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
    }

    /** Zip Slip 防护：只允许本格式写入的前缀，绝不允许遍历片段。 */
    private fun isAllowedEntryName(name: String): Boolean {
        if (name.contains("..") || name.contains('\\') || name.startsWith("/") || name.contains(':')) {
            return false
        }
        return name == ExportFormat.MANIFEST_ENTRY ||
            name == ExportKind.MESSAGES.entryName ||
            name == ExportKind.BLOGS.entryName ||
            name == ExportFormat.SKIPPED_ENTRY ||
            name.startsWith(ExportFormat.MEDIA_PREFIX)
    }

    private fun readText(input: InputStream): String {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER)
        val builder = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            builder.appendRange(buffer, 0, read)
        }
        return builder.toString()
    }
}
