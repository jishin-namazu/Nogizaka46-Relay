package com.nogirelay.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.HttpNotFoundException
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.ImageMemoryStore
import com.nogirelay.app.performance.LocalRelayPageActive
import com.nogirelay.app.performance.LocalRelayPageWorkPaused
import com.nogirelay.app.performance.imageSampleSize
import com.nogirelay.app.performance.imageScaledDensities
import com.nogirelay.app.performance.isRelayUiStarted
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

private sealed interface ImageLoadResult {
    data class Static(val bitmap: Bitmap) : ImageLoadResult

    data class Animated(val drawable: Drawable, val width: Int, val height: Int) : ImageLoadResult {
        fun aspectRatio(): Float? =
            if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
    }

    data object NotFound : ImageLoadResult
    data object Error : ImageLoadResult
}

private const val FALLBACK_DECODE_DIMENSION = 1024
private const val SIZE_BUCKET_PX = 64
private const val GIF_HEADER_BYTES = 6

object ImageAspectRatioCache {
    private val cache = LruCache<String, Float>(500)

    fun get(url: String?): Float? = if (url.isNullOrBlank()) null else cache.get(url)

    fun remove(url: String?) {
        if (!url.isNullOrBlank()) cache.remove(url)
    }

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
    private val cache = ImageMemoryStore<Bitmap>(limitKb * 1024L) { it.allocationByteCount.toLong() }

    @Synchronized
    fun get(key: String): Bitmap? = cache.get(key)

    @Synchronized
    fun getForUrl(url: String): Bitmap? = cache.getForUrl(url)

    @Synchronized
    fun removeForUrl(url: String) = cache.removeForUrl(url)

    @Synchronized
    fun put(key: String, url: String, bitmap: Bitmap) {
        cache.put(key, url, bitmap)
    }

    fun trim(clear: Boolean) = cache.trimTo(if (clear) 0 else limitKb * 512L)
}

/** Downloads and decodes one image into the same cache used by [RemoteImage]. */
internal suspend fun preloadRemoteImage(
    context: Context,
    url: String,
    messageType: MessageType = MessageType.IMAGE,
    targetWidth: Int = FALLBACK_DECODE_DIMENSION,
): Boolean {
    if (url.isBlank() || RemoteImageMemoryCache.getForUrl(url) != null) return true
    return try {
        val appContext = context.applicationContext
        if (MediaDownloader.isNotFound(appContext, url)) return false
        val file = withContext(AppGraph.dispatchers.network) {
            MediaDownloader.cachedFileForUrl(appContext, url, messageType)
                ?: MediaDownloader.downloadUrl(appContext, url, messageType)
        }
        val bitmap = withContext(AppGraph.dispatchers.imageDecode) {
            decodeSampled(file, targetWidth, targetWidth)
        } ?: return false
        RemoteImageMemoryCache.put("preload@$targetWidth@$url", url, bitmap)
        ImageAspectRatioCache.put(url, bitmap.width.toFloat() / bitmap.height.toFloat())
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
}

internal fun isGifSignature(header: ByteArray): Boolean {
    if (header.size < GIF_HEADER_BYTES) return false
    val signature = String(header, 0, GIF_HEADER_BYTES, Charsets.US_ASCII)
    return signature == "GIF87a" || signature == "GIF89a"
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    loadCachedImmediately: Boolean = false,
    revalidateRemote: Boolean = false,
    preserveAspectRatio: Boolean = false,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    placeholderResId: Int? = null,
    placeholderColor: Color = Color(0xFFE7E2EA),
    onAspectRatio: ((Float) -> Unit)? = null,
    previewUrl: String? = null,
) {
    val context = LocalContext.current
    val active = LocalRelayPageActive.current && isRelayUiStarted() &&
        !LocalRelayPageWorkPaused.current
    var measuredSize by remember(url) { mutableStateOf(IntSize.Zero) }
    fun bucket(value: Int) = if (value <= 0) 0 else ((value + SIZE_BUCKET_PX - 1) / SIZE_BUCKET_PX) * SIZE_BUCKET_PX
    val targetWidth = bucket(measuredSize.width)
    val targetHeight = if (preserveAspectRatio) 0 else bucket(measuredSize.height)
    val cacheKey = "${targetWidth}x$targetHeight@$contentScale@$url"

    var retryCount by remember(url) { mutableIntStateOf(0) }
    var refreshVersion by remember(url) { mutableIntStateOf(0) }
    var isNotFound by remember(url) { mutableStateOf(MediaDownloader.isNotFound(context, url)) }
    var isError by remember(cacheKey) { mutableStateOf(false) }

    var knownAspectRatio by remember(url) {
        mutableStateOf(ImageAspectRatioCache.get(url) ?: ImageAspectRatioCache.get(previewUrl))
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(
            url?.let { RemoteImageMemoryCache.get(cacheKey) ?: RemoteImageMemoryCache.getForUrl(it) }
                ?: previewUrl?.let(RemoteImageMemoryCache::getForUrl),
        )
    }
    var animated by remember(cacheKey) { mutableStateOf<ImageLoadResult.Animated?>(null) }

    LaunchedEffect(cacheKey, active, retryCount, refreshVersion) {
        if (!active || targetWidth <= 0) return@LaunchedEffect
        if (url != null && (isNotFound || MediaDownloader.isNotFound(context, url))) {
            bitmap = null
            animated = null
            isNotFound = true
            isError = false
            return@LaunchedEffect
        }

        if (animated != null) return@LaunchedEffect
        val cachedBeforeLoad = url?.let {
            MediaDownloader.cachedFileForUrl(context, it, messageType) != null
        } == true
        val exactCached = url?.let { RemoteImageMemoryCache.get(cacheKey) }
        if (exactCached != null) {
            animated = null
            bitmap = exactCached
            val ratio = exactCached.width.toFloat() / exactCached.height.toFloat()
            ImageAspectRatioCache.put(url, ratio)
            knownAspectRatio = ratio
            isError = false
            isNotFound = false
        } else {
            url?.let { value ->
                isError = false
                when (val result = loadImage(context, value, messageType, message, targetWidth, targetHeight)) {
                    is ImageLoadResult.Static -> {
                        val loaded = result.bitmap
                        animated = null
                        RemoteImageMemoryCache.put(cacheKey, value, loaded)
                        val ratio = loaded.width.toFloat() / loaded.height.toFloat()
                        ImageAspectRatioCache.put(value, ratio)
                        knownAspectRatio = ratio
                        bitmap = loaded
                        isError = false
                        isNotFound = false
                    }
                    is ImageLoadResult.Animated -> {

                        bitmap = null
                        animated = result
                        result.aspectRatio()?.let { ratio ->
                            ImageAspectRatioCache.put(value, ratio)
                            knownAspectRatio = ratio
                        }
                        isError = false
                        isNotFound = false
                    }
                    ImageLoadResult.NotFound -> {
                        bitmap = null
                        animated = null
                        isNotFound = true
                        isError = false
                    }
                    ImageLoadResult.Error -> {
                        bitmap = null
                        animated = null
                        isNotFound = false
                        isError = true
                    }
                }
            }
        }

        val revalidationUrl = url
        if (revalidateRemote && cachedBeforeLoad &&
            revalidationUrl.startsWith("https://", ignoreCase = true)
        ) {
            val changed = withContext(AppGraph.dispatchers.network) {
                MediaDownloader.revalidateCachedUrlIfChanged(context, revalidationUrl, messageType)
            }
            if (changed) {
                RemoteImageMemoryCache.removeForUrl(revalidationUrl)
                ImageAspectRatioCache.remove(revalidationUrl)
                refreshVersion++
            }
        }
    }

    val currentRatio = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
        ?: animated?.aspectRatio()
        ?: knownAspectRatio
    val hasContent = bitmap != null || animated != null
    val contentAlpha = remember(cacheKey) {
        Animatable(if (hasContent) 1f else 0f)
    }
    LaunchedEffect(cacheKey, hasContent) {
        contentAlpha.animateTo(
            targetValue = if (hasContent) 1f else 0f,
            animationSpec = tween(if (hasContent) 140 else 0),
        )
    }
    val latestOnAspectRatio by rememberUpdatedState(onAspectRatio)
    LaunchedEffect(currentRatio) {
        currentRatio?.takeIf { it > 0f }?.let { latestOnAspectRatio?.invoke(it) }
    }
    val boxModifier = if (preserveAspectRatio && currentRatio != null && currentRatio > 0f) {
        modifier.fillMaxWidth().aspectRatio(currentRatio)
    } else {
        modifier
    }

    val finalModifier = if (!hasContent && isError && !isNotFound) {
        boxModifier
            .clickable { retryCount++ }
            .background(placeholderColor)
    } else {
        boxModifier.background(if (placeholderResId != null) Color.Transparent else placeholderColor)
    }

    Box(
        finalModifier.onSizeChanged { measuredSize = it },
        contentAlignment = Alignment.Center,
    ) {
        val animatedImage = animated
        val image = bitmap
        if (animatedImage != null) {
            AnimatedRemoteImage(
                drawable = animatedImage.drawable,
                contentDescription = contentDescription,
                contentScale = contentScale,
                active = active,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha.value },
            )
        } else if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha.value },
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

@Composable
private fun AnimatedRemoteImage(
    drawable: Drawable,
    contentDescription: String?,
    contentScale: ContentScale,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
        update = { view ->
            view.contentDescription = contentDescription
            view.scaleType = if (contentScale == ContentScale.Crop) {
                ImageView.ScaleType.CENTER_CROP
            } else {
                ImageView.ScaleType.FIT_CENTER
            }
            if (view.drawable !== drawable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    (view.drawable as? AnimatedImageDrawable)?.stop()
                }
                view.setImageDrawable(drawable)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                (drawable as? AnimatedImageDrawable)?.let { if (active) it.start() else it.stop() }
            }
        },
        onRelease = { view ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (view.drawable as? AnimatedImageDrawable)?.stop()
            view.setImageDrawable(null)
        },
        modifier = modifier,
    )
}

private suspend fun loadImage(
    context: Context,
    url: String,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    targetWidth: Int = FALLBACK_DECODE_DIMENSION,
    targetHeight: Int = FALLBACK_DECODE_DIMENSION,
): ImageLoadResult = withContext(AppGraph.dispatchers.network) {
    if (MediaDownloader.isNotFound(context, url)) {
        return@withContext ImageLoadResult.NotFound
    }
    try {
        if (messageType == MessageType.VIDEO && message != null) {
            val thumbFile = MediaDownloader.cachedVideoThumbnail(context, message)
                ?: MediaDownloader.generateVideoThumbnail(context, message)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                val bmp = withContext(AppGraph.dispatchers.imageDecode) {
                    decodeSampled(thumbFile, targetWidth, targetHeight)
                }
                return@withContext if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
            }
            val explicitThumb = message.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (explicitThumb != null) {
                if (MediaDownloader.isNotFound(context, explicitThumb)) {
                    return@withContext ImageLoadResult.NotFound
                }
                val cached = MediaDownloader.cachedFileForUrl(context, explicitThumb, MessageType.IMAGE)
                val file = cached ?: MediaDownloader.downloadUrl(context, explicitThumb, MessageType.IMAGE)
                val bmp = withContext(AppGraph.dispatchers.imageDecode) {
                    decodeSampled(file, targetWidth, targetHeight)
                }
                return@withContext if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
            }
            return@withContext ImageLoadResult.Error
        }

        val uri = url.toUri()
        if (uri.scheme in setOf("android.resource", "content", "file")) {
            if (uri.scheme == "file") {
                val file = uri.path?.takeIf { it.isNotBlank() }?.let(::File)
                if (file != null && file.exists()) return@withContext decodeFileResult(file, targetWidth, targetHeight)
            }
            val bmp = withContext(AppGraph.dispatchers.imageDecode) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val sampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    imageScaledDensities(bounds.outWidth, bounds.outHeight, sampleSize, targetWidth, targetHeight)
                        ?.let { (density, targetDensity) ->
                            inDensity = density
                            inTargetDensity = targetDensity
                            inScaled = true
                        }
                }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
            if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
        } else if (uri.scheme?.lowercase() in setOf("https", "http")) {
            val cached = MediaDownloader.cachedFileForUrl(context, url, messageType)
            val file = cached ?: MediaDownloader.downloadUrl(context, url, messageType)
            decodeFileResult(file, targetWidth, targetHeight)
        } else {
            ImageLoadResult.Error
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpNotFoundException) {
        ImageLoadResult.NotFound
    } catch (e: Throwable) {
        ImageLoadResult.Error
    }
}

private suspend fun decodeFileResult(file: File, targetWidth: Int, targetHeight: Int): ImageLoadResult =
    withContext(AppGraph.dispatchers.imageDecode) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && looksLikeGif(file)) {
        val drawable = decodeAnimatedDrawable(file, targetWidth, targetHeight)
        if (drawable != null) {
            return@withContext ImageLoadResult.Animated(
                drawable = drawable,
                width = drawable.intrinsicWidth,
                height = drawable.intrinsicHeight,
            )
        }
    }
    val bmp = decodeSampled(file, targetWidth, targetHeight)
    if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
}

private fun looksLikeGif(file: File): Boolean {
    return try {
        val header = ByteArray(GIF_HEADER_BYTES)
        val read = FileInputStream(file).use { input -> input.read(header) }
        read == header.size && isGifSignature(header)
    } catch (error: Throwable) {
        false
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun decodeAnimatedDrawable(file: File, targetWidth: Int, targetHeight: Int): Drawable? = try {
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
        decoder.setTargetSampleSize(imageSampleSize(info.size.width, info.size.height, targetWidth, targetHeight))
    }
} catch (error: Throwable) {
    null
}

private fun decodeSampled(file: java.io.File, targetWidth: Int, targetHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val sampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize

        imageScaledDensities(bounds.outWidth, bounds.outHeight, sampleSize, targetWidth, targetHeight)
            ?.let { (density, targetDensity) ->
                inDensity = density
                inTargetDensity = targetDensity
                inScaled = true
            }
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}
