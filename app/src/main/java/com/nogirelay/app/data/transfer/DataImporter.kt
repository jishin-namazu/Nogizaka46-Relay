package com.nogirelay.app.data.transfer

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.nogirelay.app.blog.BlogContentParser
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogMemberCategories
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.BlogImportMerge
import com.nogirelay.app.data.MessageType
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

    val memberIds: Set<String>? = null,
)

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

    val mediaAdopted: Int,
    val mediaFailed: Int,
    val mediaBytes: Long,
    val errors: List<String>,
)

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
        var mediaAdopted = 0
        var mediaFailed = 0
        var mediaBytes = 0L
        var processed = 0
        val errors = mutableListOf<String>()

        fun reportError(message: String) {
            if (errors.size < MAX_REPORTED_ERRORS) errors += message
        }

        fun memberSelected(key: String): Boolean {
            val filter = options.memberIds ?: return true
            return key in filter
        }

        fun collectMediaRefs(json: JSONObject) {
            ExportFormat.mediaRefsFrom(json).forEach { ref ->
                pathToUrls.getOrPut(ref.path) { mutableListOf() }.add(ref.url)
            }
        }

        fun writeMessage(message: RelayMessage, links: Map<String, String>) {
            AppGraph.database.transaction {
                if (AppGraph.messages.insertImported(message)) {
                    inserted += 1
                } else {
                    duplicates += 1
                    if (AppGraph.messages.refreshImportedLinks(message.id, links)) linkRefreshed += 1
                    val translation = message.translation
                    if (!translation.isNullOrBlank() &&
                        AppGraph.messages.backfillMessageTranslation(message.id, translation)
                    ) {
                        backfilled += 1
                    }
                }
            }
        }

        fun writeBlog(post: BlogPost, links: Map<String, String>) {
            AppGraph.database.transaction {
                if (AppGraph.blogs.insertBlogIfAbsent(post)) {
                    inserted += 1
                } else {
                    duplicates += 1

                    val existing = AppGraph.blogs.findBlog(post.id)
                    if (AppGraph.blogs.refreshImportedBlogLinks(post.id, links)) linkRefreshed += 1
                    val merged = AppGraph.blogs.findBlog(post.id)
                    if (existing != null && merged != null) {
                        mediaAdopted += adoptBlogMediaCache(context, existing, merged)
                    }
                    val translation = post.translation
                    if (!translation.isNullOrBlank() && merged != null &&
                        BlogImportMerge.sameTranslationSource(merged, post) &&
                        AppGraph.blogs.backfillBlogTranslation(post.id, translation)
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

                            when (entryKind) {
                                ExportKind.MESSAGES -> {
                                    val message = ExportFormat.jsonToMessage(json)
                                    if (memberSelected(ExportFormat.messageMemberKey(message))) {
                                        collectMediaRefs(json)
                                        writeMessage(message, ExportFormat.messageLinksFrom(json))
                                    }
                                }
                                ExportKind.BLOGS -> {
                                    val post = ExportFormat.jsonToBlog(json)
                                    if (memberSelected(ExportFormat.blogMemberKey(post))) {
                                        collectMediaRefs(json)
                                        writeBlog(post, ExportFormat.blogLinksFrom(json))
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            invalid += 1
                            reportError("记录解析失败：" + error.message)
                        }
                        processed += 1

                        onProgress("导入记录", processed, 0)
                    }
                }

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

                    var stored = 0
                    missing.forEach { (url, target) ->
                        if (runCatching { copyInto(context, url, temp, target) }.isSuccess) {
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
                                manifest.members
                                    .filter { it.directory && memberSelected(it.id) }
                                    .forEach { member ->
                                    val merged = AppGraph.blogMembers.insertMemberIfAbsent(
                                        BlogMember(
                                            id = member.id,
                                            name = member.name,

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

                    if (urls.isNullOrEmpty()) return@forEach
                    urls.forEach { url ->
                        val target = MediaDownloader.cacheFileForUrl(context, url, extension)
                        if (target.isFile && target.length() > 0L) {
                            mediaReused += 1
                        } else if (runCatching { copyInto(context, url, staged, target) }.isSuccess) {
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
            mediaAdopted = mediaAdopted,
            mediaFailed = mediaFailed,
            mediaBytes = mediaBytes,
            errors = errors,
        )
    }

    private fun copyInto(context: Context, url: String, source: File, target: File) {
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
        MediaDownloader.notifyCacheChanged(context, url)
    }

    private fun adoptBlogMediaCache(context: Context, before: BlogPost, after: BlogPost): Int {
        val oldUrls = BlogContentParser.imageUrlsInOrder(before.bodyHtml)
        val newUrls = BlogContentParser.imageUrlsInOrder(after.bodyHtml)
        var adopted = 0
        for (index in 0 until minOf(oldUrls.size, newUrls.size)) {
            val from = oldUrls[index] ?: continue
            val to = newUrls[index] ?: continue
            if (adoptPair(context, from, to)) adopted += 1
        }
        val oldCover = before.imageUrl?.takeIf(String::isNotBlank)
        val newCover = after.imageUrl?.takeIf(String::isNotBlank)
        if (oldCover != null && newCover != null && adoptPair(context, oldCover, newCover)) adopted += 1
        return adopted
    }

    private fun adoptPair(context: Context, from: String, to: String): Boolean {
        if (isOfficialCdnHost(from) || !isOfficialCdnHost(to)) return false
        return MediaDownloader.adoptCachedBytes(context, from, to, MessageType.IMAGE)
    }

    private fun isOfficialCdnHost(url: String): Boolean {
        val host = runCatching { url.toUri().host?.lowercase() }.getOrNull() ?: return false
        return host == "nogizaka46.com" || host.endsWith(".nogizaka46.com")
    }

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
