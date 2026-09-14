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
import androidx.compose.ui.res.painterResource
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Blog and message photos are published up to ~3700x2800, which decodes to ~40 MB. Decoding that
 * costs tens of milliseconds and is evicted from the small LRU immediately, so every scroll pass
 * decodes again. Sampling down to a screen-sized bitmap keeps decode time and memory sane.
 */
private const val MAX_DECODE_DIMENSION = 2048

private object RemoteImageMemoryCache {
    // Sampled covers are a few megabytes each, so the previous 16 MB held barely one image and every
    // scroll pass decoded again. 48 MB keeps a screenful of covers ready.
    private val cache = object : LruCache<String, Bitmap>(48 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
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
    maxDecodeDimension: Int = MAX_DECODE_DIMENSION,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cacheKey = "$maxDecodeDimension@$url"
    var bitmap by remember(cacheKey) {
        mutableStateOf(
            url?.let {
                RemoteImageMemoryCache.get(cacheKey)
                    ?: if (loadCachedImmediately) {
                        loadCachedBitmap(context, it, messageType, message, maxDecodeDimension)
                    } else {
                        null
                    }
            },
        )
    }
    LaunchedEffect(cacheKey) {
        if (bitmap == null) {
            bitmap = url?.let { value ->
                loadBitmap(context, value, messageType, message, maxDecodeDimension)
                    ?.also { loaded -> RemoteImageMemoryCache.put(cacheKey, loaded) }
            }
        }
    }

    Box(
        modifier.background(
            if (bitmap == null && placeholderResId != null) Color.Transparent else Color(0xFFE7E2EA),
        ),
    ) {
        val image = bitmap
        if (image != null) {
            val imageModifier = if (preserveAspectRatio && image.height > 0) {
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height.toFloat())
            } else {
                Modifier.fillMaxSize()
            }
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = imageModifier,
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

private fun loadCachedBitmap(
    context: Context,
    url: String,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    maxDecodeDimension: Int = MAX_DECODE_DIMENSION,
): Bitmap? = runCatching {
    val cacheKey = "$maxDecodeDimension@$url"
    if (messageType == MessageType.VIDEO && message != null) {
        MediaDownloader.cachedVideoThumbnail(context, message)
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
            ?.also { RemoteImageMemoryCache.put(cacheKey, it); return@runCatching it }
    }

    val uri = Uri.parse(url)
    val bitmap = if (uri.scheme in setOf("android.resource", "content", "file")) {
        context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    } else if (uri.scheme == "https") {
        MediaDownloader.cachedFileForUrl(context, url, messageType)
            ?.let { decodeSampled(it, maxDecodeDimension) }
    } else {
        null
    }
    bitmap?.also { RemoteImageMemoryCache.put(cacheKey, it) }
}.getOrNull()

private suspend fun loadBitmap(
    context: Context,
    url: String,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    maxDecodeDimension: Int = MAX_DECODE_DIMENSION,
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (messageType == MessageType.VIDEO && message != null) {
            MediaDownloader.cachedVideoThumbnail(context, message)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.let { return@withContext it }
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
