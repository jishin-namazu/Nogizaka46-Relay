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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.HttpNotFoundException
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.withContext

private sealed interface BitmapLoadResult {
    data class Success(val bitmap: Bitmap) : BitmapLoadResult
    data object NotFound : BitmapLoadResult
    data object Error : BitmapLoadResult
}

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

    var retryCount by remember(url) { mutableIntStateOf(0) }
    var isNotFound by remember(url) { mutableStateOf(MediaDownloader.isNotFound(context, url)) }
    var isError by remember(cacheKey) { mutableStateOf(false) }

    var knownAspectRatio by remember(url) {
        mutableStateOf(ImageAspectRatioCache.get(url))
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(url?.let { RemoteImageMemoryCache.get(cacheKey) ?: RemoteImageMemoryCache.getForUrl(it) })
    }

    LaunchedEffect(cacheKey, loadCachedImmediately, retryCount) {
        if (url != null && (isNotFound || MediaDownloader.isNotFound(context, url))) {
            isNotFound = true
            isError = false
            return@LaunchedEffect
        }
        val exactCached = url?.let { RemoteImageMemoryCache.get(cacheKey) }
        if (exactCached != null) {
            bitmap = exactCached
            val ratio = exactCached.width.toFloat() / exactCached.height.toFloat()
            ImageAspectRatioCache.put(url, ratio)
            knownAspectRatio = ratio
            isError = false
            isNotFound = false
        } else {
            url?.let { value ->
                isError = false
                when (val result = loadBitmap(context, value, messageType, message, bucketedDimension)) {
                    is BitmapLoadResult.Success -> {
                        val loaded = result.bitmap
                        RemoteImageMemoryCache.put(cacheKey, value, loaded)
                        val ratio = loaded.width.toFloat() / loaded.height.toFloat()
                        ImageAspectRatioCache.put(value, ratio)
                        knownAspectRatio = ratio
                        bitmap = loaded
                        isError = false
                        isNotFound = false
                    }
                    BitmapLoadResult.NotFound -> {
                        bitmap = null
                        isNotFound = true
                        isError = false
                    }
                    BitmapLoadResult.Error -> {
                        bitmap = null
                        isNotFound = false
                        isError = true
                    }
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

    val finalModifier = if (bitmap == null && isError && !isNotFound) {
        boxModifier
            .clickable { retryCount++ }
            .background(placeholderColor)
    } else {
        boxModifier.background(
            if (bitmap != null || placeholderResId != null) Color.Transparent else placeholderColor,
        )
    }

    Box(
        finalModifier,
        contentAlignment = Alignment.Center,
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
        } else if (isError && !isNotFound) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "加载失败，点击重试",
                tint = Color(0xFF888888),
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
): BitmapLoadResult = withContext(com.nogirelay.app.data.AppGraph.dispatchers.imageDecode) {
    if (MediaDownloader.isNotFound(context, url)) {
        return@withContext BitmapLoadResult.NotFound
    }
    try {
        if (messageType == MessageType.VIDEO && message != null) {
            val thumbFile = MediaDownloader.cachedVideoThumbnail(context, message)
                ?: MediaDownloader.generateVideoThumbnail(context, message)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                val bmp = decodeSampled(thumbFile, maxDecodeDimension)
                return@withContext if (bmp != null) BitmapLoadResult.Success(bmp) else BitmapLoadResult.Error
            }
            val explicitThumb = message.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (explicitThumb != null) {
                if (MediaDownloader.isNotFound(context, explicitThumb)) {
                    return@withContext BitmapLoadResult.NotFound
                }
                val cached = MediaDownloader.cachedFileForUrl(context, explicitThumb, MessageType.IMAGE)
                val file = cached ?: MediaDownloader.downloadUrl(context, explicitThumb, MessageType.IMAGE)
                val bmp = decodeSampled(file, maxDecodeDimension)
                return@withContext if (bmp != null) BitmapLoadResult.Success(bmp) else BitmapLoadResult.Error
            }
            return@withContext BitmapLoadResult.Error
        }

        val uri = Uri.parse(url)
        val bmp = if (uri.scheme in setOf("android.resource", "content", "file")) {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        } else if (uri.scheme?.lowercase() in setOf("https", "http")) {
            val cached = MediaDownloader.cachedFileForUrl(context, url, messageType)
            val file = cached ?: MediaDownloader.downloadUrl(context, url, messageType)
            decodeSampled(file, maxDecodeDimension)
        } else {
            null
        }
        if (bmp != null) {
            BitmapLoadResult.Success(bmp)
        } else {
            BitmapLoadResult.Error
        }
    } catch (e: HttpNotFoundException) {
        BitmapLoadResult.NotFound
    } catch (e: Throwable) {
        BitmapLoadResult.Error
    }
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
