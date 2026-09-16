package com.nogirelay.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.withContext

/**
 * Blog and message photos are published up to ~3700x2800, which decodes to ~40 MB. Decoding that
 * costs tens of milliseconds and is evicted from the small LRU immediately, so every scroll pass
 * decodes again. Sampling down to a screen-sized bitmap keeps decode time and memory sane.
 */
private const val FALLBACK_DECODE_DIMENSION = 1024
private const val SIZE_BUCKET_PX = 64

object ImageAspectRatioCache {
    private val cache = LruCache<String, Float>(500)

    fun get(url: String?): Float? = if (url.isNullOrBlank()) null else cache.get(url)

    fun put(url: String?, ratio: Float) {
        if (!url.isNullOrBlank() && ratio > 0f) {
            cache.put(url, ratio)
        }
    }
}

object RemoteImageMemoryCache {
    private val limitKb = (Runtime.getRuntime().maxMemory() / 8 / 1024)
        .coerceIn(24L * 1024L, 64L * 1024L)
        .toInt()
    private val cache = object : LruCache<String, Bitmap>(limitKb) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    // Fast-path fallback using URL: retains recent bitmaps so transitions never show blank frames
    private val urlFallback = LruCache<String, Bitmap>(64)

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)

    @Synchronized
    fun getForUrl(url: String): Bitmap? = urlFallback.get(url)

    @Synchronized
    fun put(key: String, url: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
        urlFallback.put(url, bitmap)
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loadCachedImmediately: Boolean = false,
    preserveAspectRatio: Boolean = false,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    placeholderResId: Int? = null,
    placeholderColor: Color = Color(0xFFE7E2EA),
    maxDecodeDimension: Int? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val screenMaxDimension = maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels)
    val decodeDimension = maxDecodeDimension ?: screenMaxDimension
    val bucketedDimension = ((decodeDimension + SIZE_BUCKET_PX - 1) / SIZE_BUCKET_PX) * SIZE_BUCKET_PX
    val cacheKey = "$bucketedDimension@$contentScale@$url"

    var knownAspectRatio by remember(url) {
        mutableStateOf(ImageAspectRatioCache.get(url))
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(url?.let { RemoteImageMemoryCache.get(cacheKey) ?: RemoteImageMemoryCache.getForUrl(it) })
    }

    LaunchedEffect(cacheKey, loadCachedImmediately) {
        val exactCached = url?.let { RemoteImageMemoryCache.get(cacheKey) }
        if (exactCached != null) {
            bitmap = exactCached
            val ratio = exactCached.width.toFloat() / exactCached.height.toFloat()
            ImageAspectRatioCache.put(url, ratio)
            knownAspectRatio = ratio
        } else {
            url?.let { value ->
                val loaded = loadBitmap(context, value, messageType, message, bucketedDimension)
                if (loaded != null) {
                    RemoteImageMemoryCache.put(cacheKey, value, loaded)
                    val ratio = loaded.width.toFloat() / loaded.height.toFloat()
                    ImageAspectRatioCache.put(value, ratio)
                    knownAspectRatio = ratio
                    bitmap = loaded
                }
            }
        }
    }

    val currentRatio = bitmap?.let { it.width.toFloat() / it.height.toFloat() } ?: knownAspectRatio
    val boxModifier = if (preserveAspectRatio && currentRatio != null && currentRatio > 0f) {
        modifier.fillMaxWidth().aspectRatio(currentRatio)
    } else {
        modifier
    }

    Box(
        boxModifier.background(
            if (bitmap != null || placeholderResId != null) Color.Transparent else placeholderColor,
        ),
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (placeholderResId != null) {
            Image(
                painter = painterResource(placeholderResId),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private suspend fun loadBitmap(
    context: Context,
    url: String,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    maxDecodeDimension: Int = FALLBACK_DECODE_DIMENSION,
): Bitmap? = withContext(com.nogirelay.app.data.AppGraph.dispatchers.imageDecode) {
    runCatching {
        if (messageType == MessageType.VIDEO && message != null) {
            val thumbFile = MediaDownloader.cachedVideoThumbnail(context, message)
                ?: MediaDownloader.generateVideoThumbnail(context, message)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                return@withContext decodeSampled(thumbFile, maxDecodeDimension)
            }
            val explicitThumb = message.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (explicitThumb != null) {
                val cached = MediaDownloader.cachedFileForUrl(context, explicitThumb, MessageType.IMAGE)
                val file = cached ?: MediaDownloader.downloadUrl(context, explicitThumb, MessageType.IMAGE)
                return@withContext decodeSampled(file, maxDecodeDimension)
            }
            return@withContext null
        }

        val uri = Uri.parse(url)
        if (uri.scheme in setOf("android.resource", "content", "file")) {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        } else if (uri.scheme == "https") {
            val cached = MediaDownloader.cachedFileForUrl(context, url, messageType)
            val file = cached ?: MediaDownloader.downloadUrl(context, url, messageType)
            decodeSampled(file, maxDecodeDimension)
        } else {
            null
        }
    }.getOrNull()
}

private fun decodeSampled(file: java.io.File, maxDecodeDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDecodeDimension)
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
        sample *= 2
    }
    return sample
}
