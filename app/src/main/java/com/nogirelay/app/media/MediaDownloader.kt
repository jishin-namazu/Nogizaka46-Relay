package com.nogirelay.app.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.api.ApiConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.net.URL
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HttpNotFoundException(
    message: String = "媒体文件不存在 (HTTP 404)",
    val url: String? = null,
) : java.io.IOException(message)

object MediaDownloader {
    private const val MAX_BYTES = 100L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 90_000
    private const val REMOTE_REVALIDATE_INTERVAL_MS = 60_000L
    private val locks = ConcurrentHashMap<String, Any>()
    private val notFoundUrls = ConcurrentHashMap.newKeySet<String>()
    private val remoteRevalidatedAt = ConcurrentHashMap<String, Long>()

    fun isNotFound(context: Context?, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (notFoundUrls.contains(url)) return true
        if (context != null) {
            val marker = notFoundMarkerFile(context.applicationContext, url)
            if (marker.exists()) {
                notFoundUrls.add(url)
                return true
            }
        }
        return false
    }

    fun markNotFound(context: Context?, url: String?) {
        if (url.isNullOrBlank()) return
        notFoundUrls.add(url)
        if (context != null) {
            runCatching {
                val marker = notFoundMarkerFile(context.applicationContext, url)
                val parent = marker.parentFile
                if (parent != null && !parent.exists()) parent.mkdirs()
                if (!marker.exists()) marker.createNewFile()
            }
        }
    }

    private fun notFoundMarkerFile(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(File(context.filesDir, "media-cache"), "$digest.notfound")
    }

    fun clearNotFound(context: Context, url: String) {
        if (url.isBlank()) return
        notFoundUrls.remove(url)
        runCatching { notFoundMarkerFile(context.applicationContext, url).delete() }
    }

    data class SavedDownload(val uri: Uri, val displayName: String)

    fun enqueueIfNeeded(context: Context, message: RelayMessage): File? {
        val appContext = context.applicationContext
        val mediaUrl = mediaUrlFor(message)
        val file = mediaUrl?.let { downloadUrl(appContext, it, message.type) }

        if (message.type == MessageType.VIDEO) {
            generateVideoThumbnail(appContext, message)
            persistVideoAudioTrack(appContext, message)
        }

        if (message.type == MessageType.AUDIO) {
            message.phoneImageUrl?.takeIf { it.isNotBlank() }?.let { photo ->
                runCatching { downloadUrl(appContext, photo, MessageType.IMAGE) }
            }
        }

        return file
    }

    fun saveToDownloads(context: Context, message: RelayMessage): SavedDownload {
        require(message.type != MessageType.TEXT) { "文字消息没有可保存的媒体" }
        if (needsLegacyWritePermission(context)) error("请先允许存储权限")

        val mediaUrl = mediaUrlFor(message) ?: error("消息没有可保存的媒体")
        val source = downloadUrl(context.applicationContext, mediaUrl, message.type)
        val extension = extensionFor(mediaUrl, message.type)
        val displayName = buildDisplayName(message, extension)
        val mimeType = mimeTypeFor(extension, message.type)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, displayName, mimeType)
        } else {
            saveLegacy(context, source, displayName, mimeType)
        }
    }

    fun saveImageUrlToDownloads(context: Context, url: String, baseName: String): SavedDownload {
        if (needsLegacyWritePermission(context)) error("请先允许存储权限")
        val source = downloadUrl(context.applicationContext, url, MessageType.IMAGE)
        val extension = extensionFor(url, MessageType.IMAGE)
        val safeBaseName = baseName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
            .trim()
            .take(80)
            .ifBlank { "NogiRelay_BLOG" }
        val displayName = "$safeBaseName.$extension"
        val mimeType = mimeTypeFor(extension, MessageType.IMAGE)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, displayName, mimeType)
        } else {
            saveLegacy(context, source, displayName, mimeType)
        }
    }

    fun needsLegacyWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun cachedFileForUrl(context: Context, url: String, type: MessageType): File? {
        val file = cacheFile(context.applicationContext, url, type)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    suspend fun resolveVideoHasAudio(context: Context, message: RelayMessage): Boolean? {
        if (message.type != MessageType.VIDEO) return null
        message.videoHasAudio?.let { return it }

        val stored = withContext(AppGraph.dispatchers.databaseRead) {
            runCatching { AppGraph.database.find(message.id)?.videoHasAudio }.getOrNull()
        }
        if (stored != null) return stored
        val detected = withContext(Dispatchers.IO) { cachedVideoHasAudioTrack(context, message) } ?: return null
        withContext(AppGraph.dispatchers.databaseWrite) {
            runCatching { AppGraph.database.setVideoHasAudio(message.id, detected) }
        }
        return detected
    }

    private fun persistVideoAudioTrack(context: Context, message: RelayMessage) {
        val existing = runCatching { AppGraph.database.find(message.id)?.videoHasAudio }.getOrNull()
        if (existing != null) return
        val hasAudio = cachedVideoHasAudioTrack(context, message) ?: return
        runCatching { AppGraph.database.setVideoHasAudio(message.id, hasAudio) }
    }

    fun cachedVideoHasAudioTrack(context: Context, message: RelayMessage): Boolean? {
        if (message.type != MessageType.VIDEO) return null
        val mediaUrl = mediaUrlFor(message) ?: return null
        val videoFile = cachedFileForUrl(context, mediaUrl, MessageType.VIDEO) ?: return null
        return runCatching {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(videoFile.absolutePath)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                        .equals("yes", ignoreCase = true)
                } finally {
                    release()
                }
            }
        }.getOrNull()
    }

    fun downloadUrl(context: Context, url: String, type: MessageType): File {
        require(url.isNotBlank()) { "媒体地址为空" }
        val appContext = context.applicationContext
        if (isNotFound(appContext, url)) {
            throw HttpNotFoundException("媒体文件不存在 (HTTP 404): $url", url)
        }
        val uri = url.toUri()
        val target = cacheFile(appContext, url, type)
        target.takeIf { it.isFile && it.length() > 0L }?.let { return it }

        val lock = locks.computeIfAbsent(url) { Any() }
        return try {
            synchronized(lock) {
                if (isNotFound(appContext, url)) {
                    throw HttpNotFoundException("媒体文件不存在 (HTTP 404): $url", url)
                }
                target.takeIf { it.isFile && it.length() > 0L }?.let { return@synchronized it }
                when (uri.scheme?.lowercase()) {
                    "android.resource", "content", "file" -> copyLocalUri(appContext, uri, target)
                    "https" -> downloadHttps(appContext, uri, target, type, url)
                    else -> error("不支持的媒体地址")
                }
            }
        } finally {
            locks.remove(url, lock)
        }
    }

    fun revalidateCachedUrlIfChanged(context: Context, url: String, type: MessageType): Boolean {
        if (url.isBlank()) return false
        val appContext = context.applicationContext
        val target = cacheFile(appContext, url, type)
        if (!target.isFile || target.length() <= 0L) return false
        val now = System.currentTimeMillis()
        if (now - (remoteRevalidatedAt[url] ?: 0L) < REMOTE_REVALIDATE_INTERVAL_MS) return false

        val lockKey = "revalidate:$url"
        val lock = locks.computeIfAbsent(lockKey) { Any() }
        var changed = false
        try {
            synchronized(lock) {
                if (System.currentTimeMillis() - (remoteRevalidatedAt[url] ?: 0L) < REMOTE_REVALIDATE_INTERVAL_MS) {
                    return@synchronized
                }

                val metadata = readRemoteMetadata(target)
                    ?.takeIf { it.contentLength <= 0L || it.contentLength == target.length() }
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    doInput = true
                    setRequestProperty("Accept", acceptType(type))
                    setRequestProperty("User-Agent", "NogiRelay/${BuildConfig.VERSION_NAME}")
                    authorizationFor(appContext, url.toUri().host)?.let { setRequestProperty("Authorization", it) }
                    metadata?.etag?.let { setRequestProperty("If-None-Match", it) }
                    metadata?.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
                }

                try {
                    when (val status = connection.responseCode) {
                        HttpURLConnection.HTTP_NOT_MODIFIED -> Unit
                        HttpURLConnection.HTTP_NOT_FOUND -> {
                            markNotFound(appContext, url)
                            target.delete()
                            metadataFile(target).delete()
                            MediaCacheRevision.changed()
                            changed = true
                        }
                        in 200..299 -> {
                            if (metadata == null || !metadata.matches(connection)) {
                                copyResponseToTarget(connection, target)
                                writeRemoteMetadata(target, connection)
                                changed = true
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                remoteRevalidatedAt[url] = System.currentTimeMillis()
            }
        } catch (error: Exception) {
            remoteRevalidatedAt[url] = System.currentTimeMillis()
            if (error is kotlinx.coroutines.CancellationException) throw error
        } finally {
            locks.remove(lockKey, lock)
        }
        return changed
    }

    private fun downloadHttps(
        context: Context,
        uri: Uri,
        target: File,
        type: MessageType,
        originalUrl: String = uri.toString(),
    ): File {
        val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            doInput = true
            setRequestProperty("Accept", acceptType(type))
            setRequestProperty("User-Agent", "NogiRelay/${BuildConfig.VERSION_NAME}")
            authorizationFor(context, uri.host)?.let { setRequestProperty("Authorization", it) }
        }
        return try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                markNotFound(context, originalUrl)
                markNotFound(context, uri.toString())
                throw HttpNotFoundException("媒体文件不存在 (HTTP 404): $uri", originalUrl)
            }
            if (status !in 200..299) error("媒体服务返回 HTTP $status")
            copyResponseToTarget(connection, target)
            writeRemoteMetadata(target, connection)
            remoteRevalidatedAt[originalUrl] = System.currentTimeMillis()
            target
        } finally {
            connection.disconnect()
        }
    }

    private fun copyResponseToTarget(connection: HttpURLConnection, target: File) {
        val parent = target.parentFile ?: error("无法创建媒体目录")
        if (!parent.exists() && !parent.mkdirs()) error("无法创建媒体目录")
        val temp = File(parent, "${target.name}.part-${System.nanoTime()}")
        try {
            val contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
            if (contentLength > MAX_BYTES) error("媒体文件超过 100 MB 限制")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) error("媒体文件超过 100 MB 限制")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            replaceAtomically(temp, target)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private data class RemoteCacheMetadata(
        val etag: String?,
        val lastModified: String?,
        val contentLength: Long,
    ) {
        fun matches(connection: HttpURLConnection): Boolean {
            val responseEtag = connection.getHeaderField("ETag")?.takeIf(String::isNotBlank)
            if (etag != null && responseEtag != null) return etag == responseEtag

            val responseLastModified = connection.getHeaderField("Last-Modified")
                ?.takeIf(String::isNotBlank)
            if (lastModified != null && responseLastModified != null) {
                return lastModified == responseLastModified
            }

            val responseLength = connection.getHeaderFieldLong("Content-Length", -1L)
            return contentLength > 0L && responseLength > 0L && contentLength == responseLength
        }
    }

    private fun metadataFile(target: File): File = File("${target.absolutePath}.meta")

    private fun readRemoteMetadata(target: File): RemoteCacheMetadata? {
        val file = metadataFile(target)
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties()
            file.inputStream().use(properties::load)
            RemoteCacheMetadata(
                etag = properties.getProperty("etag")?.takeIf(String::isNotBlank),
                lastModified = properties.getProperty("last_modified")?.takeIf(String::isNotBlank),
                contentLength = properties.getProperty("content_length")?.toLongOrNull() ?: -1L,
            )
        }.getOrNull()
    }

    private fun writeRemoteMetadata(target: File, connection: HttpURLConnection) {
        runCatching {
            val properties = Properties().apply {
                connection.getHeaderField("ETag")?.takeIf(String::isNotBlank)?.let {
                    setProperty("etag", it)
                }
                connection.getHeaderField("Last-Modified")?.takeIf(String::isNotBlank)?.let {
                    setProperty("last_modified", it)
                }
                val length = connection.getHeaderFieldLong("Content-Length", -1L)
                if (length >= 0L) setProperty("content_length", length.toString())
            }
            val file = metadataFile(target)
            file.parentFile?.mkdirs()
            file.outputStream().use { properties.store(it, "remote media validators") }
        }
    }

    private fun copyLocalUri(context: Context, uri: Uri, target: File): File {
        val parent = target.parentFile ?: error("无法创建媒体目录")
        if (!parent.exists() && !parent.mkdirs()) error("无法创建媒体目录")
        val temp = File(parent, "${target.name}.part-${System.nanoTime()}")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            } ?: error("无法读取本地媒体")
            replaceAtomically(temp, target)
            target
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): SavedDownload {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在 Download 文件夹创建文件")
        return try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入 Download 文件夹")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            SavedDownload(uri, displayName)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): SavedDownload {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists() && !downloads.mkdirs()) error("无法创建 Download 文件夹")
        val target = uniqueFile(downloads, displayName)
        source.inputStream().use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return SavedDownload(Uri.fromFile(target), target.name)
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val requested = File(directory, displayName)
        if (!requested.exists()) return requested
        val extension = displayName.substringAfterLast('.', "")
        val baseName = displayName.removeSuffix(if (extension.isEmpty()) "" else ".$extension")
        var index = 2
        while (true) {
            val candidate = File(directory, "$baseName ($index)${if (extension.isEmpty()) "" else ".$extension"}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun buildDisplayName(message: RelayMessage, extension: String): String {
        val member = message.memberName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
            .trim()
            .take(40)
            .ifBlank { "NogiRelay" }
        val id = message.id
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .takeLast(24)
            .ifBlank { System.currentTimeMillis().toString() }
        return "${member}_$id.$extension"
    }

    private fun mimeTypeFor(extension: String, type: MessageType): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        else -> acceptType(type)
    }

    private fun replaceAtomically(temp: File, target: File) {
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        MediaCacheRevision.changed()
    }

    private fun authorizationFor(context: Context, host: String?): String? {
        if (host.isNullOrBlank()) return null
        AppGraph.initialize(context)
        val settings = runCatching { AppGraph.settings.read() }.getOrNull() ?: return null
        val relayUrl = settings.relayUrl.ifBlank { ApiConfig.BASE_URL }
        val relayHost = runCatching { relayUrl.toUri().host }.getOrNull()
        val token = settings.accessToken.ifBlank { ApiConfig.ACCESS_TOKEN }
        return if (relayHost != null && relayHost.equals(host, ignoreCase = true) && token.isNotBlank()) {
            "Bearer $token"
        } else {
            null
        }
    }

    fun cacheFileForUrl(context: Context, url: String, extension: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(File(context.applicationContext.filesDir, "media-cache"), "$digest.$extension")
    }

    fun cacheFileForUrl(context: Context, url: String, type: MessageType): File =
        cacheFile(context, url, type)

    fun adoptCachedBytes(context: Context, fromUrl: String, toUrl: String, type: MessageType): Boolean {
        if (fromUrl.isBlank() || toUrl.isBlank() || fromUrl == toUrl) return false
        val appContext = context.applicationContext
        val source = cacheFile(appContext, fromUrl, type)
        if (!source.isFile || source.length() <= 0L) return false
        val target = cacheFile(appContext, toUrl, type)
        if (target.isFile && target.length() > 0L) return false
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false
        val linked = runCatching { Files.createLink(target.toPath(), source.toPath()) }.isSuccess
        if (!linked && runCatching { source.copyTo(target, overwrite = true) }.isFailure) {
            runCatching { target.delete() }
            return false
        }
        clearNotFound(appContext, toUrl)
        MediaCacheRevision.changed()
        return true
    }

    private fun cacheFile(context: Context, url: String, type: MessageType): File =
        cacheFileForUrl(context, url, extensionFor(url, type))

    private fun extensionFor(url: String, type: MessageType): String {
        val path = url.toUri().path.orEmpty()
        val extension = path.substringAfterLast('.', "").lowercase().takeIf {
            it.matches(Regex("[a-z0-9]{2,5}"))
        }
        return extension ?: when (type) {
            MessageType.IMAGE -> "jpg"
            MessageType.AUDIO -> "m4a"
            MessageType.VIDEO -> "mp4"
            MessageType.TEXT -> "bin"
        }
    }

    private fun acceptType(type: MessageType): String = when (type) {
        MessageType.IMAGE -> "image/*"
        MessageType.AUDIO -> "audio/*"
        MessageType.VIDEO -> "video/*"
        MessageType.TEXT -> "application/octet-stream"
    }

    private fun mediaUrlFor(message: RelayMessage): String? =
        message.mediaUrl?.takeIf { it.isNotBlank() }
            ?: if (message.type == MessageType.IMAGE) message.thumbnailUrl?.takeIf { it.isNotBlank() } else null

    fun generateVideoThumbnail(context: Context, message: RelayMessage): File? {
        if (message.type != MessageType.VIDEO) return null
        val mediaUrl = message.mediaUrl ?: return null

        val thumbnailFile = videoThumbnailFile(context.applicationContext, mediaUrl)
        if (thumbnailFile.exists() && thumbnailFile.length() > 0L) {
            return thumbnailFile
        }

        val videoFile = cachedFileForUrl(context, mediaUrl, MessageType.VIDEO) ?: return null

        val lock = locks.computeIfAbsent("thumbnail:$mediaUrl") { Any() }
        return try {
            synchronized(lock) {
                if (thumbnailFile.exists() && thumbnailFile.length() > 0L) {
                    return@synchronized thumbnailFile
                }

                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(videoFile.absolutePath)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: return@synchronized null

                    val parent = thumbnailFile.parentFile ?: return@synchronized null
                    if (!parent.exists() && !parent.mkdirs()) return@synchronized null

                    val temp = File(parent, "${thumbnailFile.name}.part-${System.nanoTime()}")
                    try {
                        FileOutputStream(temp).use { output ->
                            frame.compress(Bitmap.CompressFormat.JPEG, 90, output)
                            output.fd.sync()
                        }
                        replaceAtomically(temp, thumbnailFile)
                        thumbnailFile
                    } finally {
                        if (temp.exists()) temp.delete()
                        frame.recycle()
                    }
                } finally {
                    retriever.release()
                }
            }
        } catch (e: Exception) {
            null
        } finally {
            locks.remove("thumbnail:$mediaUrl", lock)
        }
    }

    fun videoThumbnailFile(context: Context, videoUrl: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(videoUrl.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(File(context.filesDir, "video-thumbnails"), "$digest.jpg")
    }

    fun cachedVideoThumbnail(context: Context, message: RelayMessage): File? {
        if (message.type != MessageType.VIDEO) return null
        val mediaUrl = message.mediaUrl ?: return null
        val file = videoThumbnailFile(context.applicationContext, mediaUrl)
        return file.takeIf { it.exists() && it.length() > 0L }
    }
}
