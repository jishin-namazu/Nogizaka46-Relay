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
import androidx.compose.animation.Crossfade
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
import com.nogirelay.app.data.MediaRefKind
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.HttpNotFoundException
import com.nogirelay.app.media.MediaCacheRevision
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.ImageMemoryStore
import com.nogirelay.app.performance.LocalRelayPageActive
import com.nogirelay.app.performance.LocalRelayPageWorkPaused
import com.nogirelay.app.performance.imageSampleSize
import com.nogirelay.app.performance.imageScaledDensities
import com.nogirelay.app.performance.isRelayUiStarted
import com.nogirelay.app.ui.glass.GlassColors
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
        val cached = withContext(Dispatchers.IO) {
            MediaDownloader.cachedFileForUrl(appContext, url, messageType)
        }
        val file = cached ?: run {
            if (withContext(Dispatchers.IO) { MediaDownloader.isNotFound(appContext, url) }) return false
            withContext(AppGraph.dispatchers.network) {
                MediaDownloader.downloadUrl(appContext, url, messageType)
            }
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

/** Preloads a message's visual preview using the same priority order as [RemoteImage]. */
internal suspend fun preloadMessagePreview(
    context: Context,
    message: RelayMessage,
    targetWidth: Int = FALLBACK_DECODE_DIMENSION,
): Boolean {
    val appContext = context.applicationContext
    return when (message.type) {
        MessageType.IMAGE -> {
            val primary = message.mediaUrl?.takeIf(String::isNotBlank)
                ?: message.thumbnailUrl?.takeIf(String::isNotBlank)
                ?: return false
            val fallback = message.thumbnailUrl?.takeIf { it.isNotBlank() && it != primary }
            preloadRemoteImage(appContext, primary, MessageType.IMAGE, targetWidth) ||
                (fallback != null && preloadRemoteImage(appContext, fallback, MessageType.IMAGE, targetWidth))
        }
        MessageType.VIDEO -> {
            val previewUrl = message.thumbnailUrl?.takeIf(String::isNotBlank)
                ?: message.mediaUrl?.takeIf(String::isNotBlank)
                ?: return false
            if (RemoteImageMemoryCache.getForUrl(previewUrl) != null) return true
            val localBitmap = try {
                withContext(AppGraph.dispatchers.imageDecode) {
                    val thumbFile = MediaDownloader.cachedVideoThumbnail(appContext, message)
                        ?: MediaDownloader.generateVideoThumbnail(appContext, message)
                    thumbFile?.takeIf { it.exists() && it.length() > 0L }?.let {
                        decodeSampled(it, targetWidth, targetWidth)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            if (localBitmap != null) {
                RemoteImageMemoryCache.put("preload@$targetWidth@$previewUrl", previewUrl, localBitmap)
                ImageAspectRatioCache.put(previewUrl, localBitmap.width.toFloat() / localBitmap.height.toFloat())
                true
            } else {
                val explicitThumb = message.thumbnailUrl?.takeIf(String::isNotBlank) ?: return false
                preloadRemoteImage(appContext, explicitThumb, MessageType.IMAGE, targetWidth)
            }
        }
        else -> false
    }
}

/** Read only local image headers before publishing a blog page, never await the network. */
internal suspend fun primeCachedImageAspectRatios(context: Context, urls: List<String>) {
    withContext(AppGraph.dispatchers.imageDecode) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        urls.distinct().forEach { url ->
            if (ImageAspectRatioCache.get(url) == null) {
                MediaDownloader.cachedFileForUrl(context, url, MessageType.IMAGE)?.let { file ->
                    bounds.outWidth = 0
                    bounds.outHeight = 0
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        ImageAspectRatioCache.put(url, bounds.outWidth.toFloat() / bounds.outHeight)
                    }
                }
            }
        }
    }
}

/**
 * Returns true when an already-cached bitmap for the same URL is sharp enough
 * for [targetWidth] x [targetHeight], avoiding redundant disk re-decodes during
 * transitions (e.g. a 52.dp inbox avatar reused in the 38.dp timeline header,
 * or a primed 768px preview reused in a message card).
 */
internal fun bitmapSatisfiesTarget(
    bitmapWidth: Int,
    bitmapHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Boolean {
    if (bitmapWidth <= 0 || bitmapHeight <= 0) return false
    val widthOk = targetWidth <= 0 || bitmapWidth >= (targetWidth * 0.85f).toInt()
    val heightOk = targetHeight <= 0 || bitmapHeight >= (targetHeight * 0.85f).toInt()
    return widthOk && heightOk
}

/** Read local image and video thumbnail headers before publishing a timeline batch so cards lay out at their final height on the first frame. */
internal suspend fun primeCachedMessageAspectRatios(context: Context, messages: List<RelayMessage>) {
    if (messages.isEmpty()) return
    withContext(AppGraph.dispatchers.imageDecode) {
        val appContext = context.applicationContext
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        messages.forEach { message ->
            val url = when (message.type) {
                MessageType.IMAGE -> message.mediaUrl ?: message.thumbnailUrl
                MessageType.VIDEO -> message.thumbnailUrl ?: message.mediaUrl
                else -> null
            }?.takeIf { it.isNotBlank() } ?: return@forEach
            if (ImageAspectRatioCache.get(url) != null) return@forEach
            val file = when (message.type) {
                MessageType.IMAGE -> MediaDownloader.cachedFileForUrl(appContext, url, MessageType.IMAGE)
                MessageType.VIDEO -> MediaDownloader.cachedVideoThumbnail(appContext, message)
                    ?: message.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                        MediaDownloader.cachedFileForUrl(appContext, it, MessageType.IMAGE)
                    }
                else -> null
            } ?: return@forEach
            bounds.outWidth = 0
            bounds.outHeight = 0
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                ImageAspectRatioCache.put(url, bounds.outWidth.toFloat() / bounds.outHeight)
            }
        }
    }
}

/**
 * Fast local-only priming before the timeline's first frame: decodes aspect
 * ratios for the priority slice near the initial target and warms up to
 * [maxBitmapDecodes] already-cached local thumbnails in parallel within a tight
 * time budget so visible images render on Frame 1 without mid-flight pop-in.
 */
internal suspend fun primeInitialTimelineMedia(
    context: Context,
    messages: List<RelayMessage>,
    targetMessageId: String? = null,
    targetWidth: Int = 768,
    maxAspectPrimes: Int = 6,
    maxBitmapDecodes: Int = 3,
    budgetMillis: Long = 55L,
) {
    if (messages.isEmpty()) return
    val anchor = targetMessageId?.let { id -> messages.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
    val start = (anchor - 2).coerceAtLeast(0)
    val priority = messages.subList(start, minOf(messages.size, start + maxAspectPrimes))
    withTimeoutOrNull(budgetMillis) {
        primeCachedMessageAspectRatios(context, priority)
        val visualTargets = priority.asSequence()
            .filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
            .take(maxBitmapDecodes)
            .toList()
        if (visualTargets.isEmpty()) return@withTimeoutOrNull
        val appContext = context.applicationContext
        coroutineScope {
            visualTargets.map { message ->
                async(AppGraph.dispatchers.imageDecode) {
                    val url = when (message.type) {
                        MessageType.IMAGE -> message.mediaUrl ?: message.thumbnailUrl
                        MessageType.VIDEO -> message.thumbnailUrl ?: message.mediaUrl
                        else -> null
                    }?.takeIf { it.isNotBlank() } ?: return@async
                    val existing = RemoteImageMemoryCache.getForUrl(url)
                    if (existing != null && bitmapSatisfiesTarget(existing.width, existing.height, targetWidth, 0)) {
                        return@async
                    }
                    val file = when (message.type) {
                        MessageType.IMAGE -> MediaDownloader.cachedFileForUrl(appContext, url, MessageType.IMAGE)
                        MessageType.VIDEO -> MediaDownloader.cachedVideoThumbnail(appContext, message)
                            ?: message.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                                MediaDownloader.cachedFileForUrl(appContext, it, MessageType.IMAGE)
                            }
                        else -> null
                    } ?: return@async
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && looksLikeGif(file)) return@async
                    val bmp = decodeSampled(file, targetWidth, 0) ?: return@async
                    RemoteImageMemoryCache.put("prime@$targetWidth@$url", url, bmp)
                    ImageAspectRatioCache.put(url, bmp.width.toFloat() / bmp.height.toFloat())
                }
            }.awaitAll()
        }
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
    placeholderColor: Color = GlassColors.Placeholder,
    onAspectRatio: ((Float) -> Unit)? = null,
    previewUrl: String? = null,
    fallbackUrl: String? = null,
    retryOnClick: Boolean = true,
    crossfadeDurationMillis: Int = 0,
    placeholderAspectRatio: Float? = null,
    animateAspectRatioChanges: Boolean = false,
) {
    val context = LocalContext.current
    val active = LocalRelayPageActive.current && isRelayUiStarted() &&
        !LocalRelayPageWorkPaused.current
    val normalizedFallback = fallbackUrl?.takeIf { it.isNotBlank() && it != url }
    val canShortCircuitNotFound = (messageType != MessageType.VIDEO || message == null) &&
        normalizedFallback == null
    var measuredSize by remember(url) { mutableStateOf(IntSize.Zero) }
    fun bucket(value: Int) = if (value <= 0) 0 else ((value + SIZE_BUCKET_PX - 1) / SIZE_BUCKET_PX) * SIZE_BUCKET_PX
    val targetWidth = bucket(measuredSize.width)
    val targetHeight = if (preserveAspectRatio) 0 else bucket(measuredSize.height)
    val cacheKey = "${targetWidth}x$targetHeight@$contentScale@$url"

    var retryCount by remember(url) { mutableIntStateOf(0) }
    var refreshVersion by remember(url) { mutableIntStateOf(0) }
    var isNotFound by remember(url, normalizedFallback, messageType, message?.id) {
        mutableStateOf(canShortCircuitNotFound && MediaDownloader.isNotFound(null, url))
    }
    var isError by remember(cacheKey) { mutableStateOf(false) }

    var knownAspectRatio by remember(url) {
        mutableStateOf(
            ImageAspectRatioCache.get(url)
                ?: ImageAspectRatioCache.get(previewUrl)
                ?: ImageAspectRatioCache.get(normalizedFallback),
        )
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(
            url?.let { RemoteImageMemoryCache.get(cacheKey) ?: RemoteImageMemoryCache.getForUrl(it) }
                ?: previewUrl?.let(RemoteImageMemoryCache::getForUrl)
                ?: normalizedFallback?.let(RemoteImageMemoryCache::getForUrl),
        )
    }
    var animated by remember(cacheKey) { mutableStateOf<ImageLoadResult.Animated?>(null) }

    if (messageType == MessageType.VIDEO && message != null && bitmap == null && animated == null) {
        LaunchedEffect(cacheKey, active) {
            if (!active) return@LaunchedEffect
            MediaCacheRevision.changesFor(MediaRefKind.MESSAGES).drop(1).collect {
                refreshVersion++
            }
        }
    }

    LaunchedEffect(cacheKey, normalizedFallback, active, retryCount, refreshVersion) {
        if (!active || targetWidth <= 0) return@LaunchedEffect
        val bypassNotFound = retryCount > 0
        if (!bypassNotFound && canShortCircuitNotFound && url != null &&
            (isNotFound || MediaDownloader.isNotFound(null, url))
        ) {
            bitmap = null
            animated = null
            isNotFound = true
            isError = false
            return@LaunchedEffect
        }

        if (animated != null) return@LaunchedEffect
        val cachedBeforeLoad = revalidateRemote && url != null && withContext(Dispatchers.IO) {
            MediaDownloader.cachedFileForUrl(context, url, messageType) != null
        }
        val exactCached = url?.let {
            RemoteImageMemoryCache.get(cacheKey)
                ?: RemoteImageMemoryCache.getForUrl(it)?.takeIf { cached ->
                    bitmapSatisfiesTarget(cached.width, cached.height, targetWidth, targetHeight)
                }
        }
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
                when (
                    val result = loadImage(
                        context = context,
                        url = value,
                        messageType = messageType,
                        message = message,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        fallbackUrl = normalizedFallback,
                        bypassNotFound = bypassNotFound,
                    )
                ) {
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
    val layoutRatio = currentRatio?.takeIf { it.isFinite() && it > 0f }
        ?: placeholderAspectRatio?.takeIf { it.isFinite() && it > 0f && !isNotFound }
    val targetHeightFraction = imageHeightFraction(layoutRatio)
    // Animate height/width rather than width/height so portrait and landscape
    // placeholders resize at the same pace. Reveal pixels only after settling.
    val heightFraction = if (preserveAspectRatio && animateAspectRatioChanges) {
        remember(url) { Animatable(targetHeightFraction ?: 0f) }
    } else {
        null
    }
    if (heightFraction != null) {
        LaunchedEffect(targetHeightFraction) {
            targetHeightFraction?.let { target ->
                if (heightFraction.value > 0f && !imageHeightSettled(heightFraction.value, target)) {
                    heightFraction.animateTo(target, tween(260))
                } else {
                    heightFraction.snapTo(target)
                }
            }
        }
    }
    val ratioSettled = heightFraction == null || targetHeightFraction == null ||
        (!heightFraction.isRunning && imageHeightSettled(heightFraction.value, targetHeightFraction))
    val hasContent = (bitmap != null || animated != null) && ratioSettled
    val contentAlpha = remember(cacheKey) {
        Animatable(if (hasContent) 1f else 0f)
    }
    LaunchedEffect(cacheKey, hasContent, crossfadeDurationMillis) {
        if (crossfadeDurationMillis <= 0) {
            contentAlpha.animateTo(
                targetValue = if (hasContent) 1f else 0f,
                animationSpec = tween(if (hasContent) 140 else 0),
            )
        }
    }
    val latestOnAspectRatio by rememberUpdatedState(onAspectRatio)
    LaunchedEffect(currentRatio) {
        currentRatio?.takeIf { it > 0f }?.let { latestOnAspectRatio?.invoke(it) }
    }
    val displayedRatio = if (heightFraction != null && targetHeightFraction != null && heightFraction.value > 0f) {
        1f / heightFraction.value
    } else {
        layoutRatio
    }
    val boxModifier = if (preserveAspectRatio && displayedRatio != null) {
        modifier.fillMaxWidth().aspectRatio(displayedRatio)
    } else {
        modifier
    }

    val finalModifier = if (retryOnClick && !hasContent && isError && !isNotFound) {
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
        val frame: ImageLoadResult? = (if (ratioSettled) animated ?: bitmap?.let { ImageLoadResult.Static(it) } else null)
            ?: when {
                isNotFound -> ImageLoadResult.NotFound
                isError -> ImageLoadResult.Error
                else -> null
            }
        if (crossfadeDurationMillis > 0) {
            // Retain the outgoing image/placeholder through its fade. Keying
            // the transition by content also survives decode-size changes.
            Crossfade(
                targetState = frame,
                animationSpec = tween(crossfadeDurationMillis),
                modifier = Modifier.fillMaxSize(),
                label = "remote-image-crossfade",
            ) { visibleFrame ->
                RemoteImageFrame(
                    visibleFrame,
                    contentDescription,
                    contentScale,
                    active,
                    placeholderResId,
                    showErrorIcon = retryOnClick,
                )
            }
        } else {
            RemoteImageFrame(
                frame = frame,
                contentDescription = contentDescription,
                contentScale = contentScale,
                active = active,
                placeholderResId = placeholderResId,
                showErrorIcon = retryOnClick,
                imageModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = contentAlpha.value },
            )
        }
    }
}

@Composable
private fun RemoteImageFrame(
    frame: ImageLoadResult?,
    contentDescription: String?,
    contentScale: ContentScale,
    active: Boolean,
    placeholderResId: Int?,
    showErrorIcon: Boolean = true,
    imageModifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (frame) {
            is ImageLoadResult.Animated -> AnimatedRemoteImage(
                drawable = frame.drawable,
                contentDescription = contentDescription,
                contentScale = contentScale,
                active = active,
                modifier = imageModifier,
            )
            is ImageLoadResult.Static -> Image(
                bitmap = frame.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = imageModifier,
            )
            else -> when {
                placeholderResId != null -> Image(
                    painter = painterResource(placeholderResId),
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = Modifier.fillMaxSize(),
                )
                showErrorIcon && frame == ImageLoadResult.Error -> Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "加载失败，点击重试",
                    tint = Color(0xFF888888),
                )
            }
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
    fallbackUrl: String? = null,
    bypassNotFound: Boolean = false,
): ImageLoadResult {
    return try {
        if (messageType == MessageType.VIDEO && message != null) {
            val localBmp = withContext(AppGraph.dispatchers.imageDecode) {
                val thumbFile = MediaDownloader.cachedVideoThumbnail(context, message)
                    ?: MediaDownloader.generateVideoThumbnail(context, message)
                thumbFile?.takeIf { it.exists() && it.length() > 0L }?.let {
                    decodeSampled(it, targetWidth, targetHeight)
                }
            }
            if (localBmp != null) {
                return ImageLoadResult.Static(localBmp)
            }
            val explicitThumb = message.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (explicitThumb != null) {
                return loadSingleUrl(
                    context = context,
                    url = explicitThumb,
                    messageType = MessageType.IMAGE,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    bypassNotFound = bypassNotFound,
                )
            }
            return ImageLoadResult.Error
        }

        val primary = loadSingleUrl(
            context = context,
            url = url,
            messageType = messageType,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            bypassNotFound = bypassNotFound,
        )
        if (primary is ImageLoadResult.Static || primary is ImageLoadResult.Animated) {
            return primary
        }
        val secondary = fallbackUrl?.takeIf { it.isNotBlank() && it != url } ?: return primary
        val fallback = loadSingleUrl(
            context = context,
            url = secondary,
            messageType = messageType,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            bypassNotFound = bypassNotFound,
        )
        if (fallback is ImageLoadResult.Static || fallback is ImageLoadResult.Animated) {
            fallback
        } else {
            primary
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: HttpNotFoundException) {
        ImageLoadResult.NotFound
    } catch (e: Throwable) {
        ImageLoadResult.Error
    }
}

private suspend fun loadSingleUrl(
    context: Context,
    url: String,
    messageType: MessageType,
    targetWidth: Int,
    targetHeight: Int,
    bypassNotFound: Boolean,
): ImageLoadResult {
    return try {
        val uri = url.toUri()
        if (uri.scheme in setOf("android.resource", "content", "file")) {
            if (uri.scheme == "file") {
                val file = uri.path?.takeIf { it.isNotBlank() }?.let(::File)
                if (file != null && file.exists()) return decodeFileResult(file, targetWidth, targetHeight)
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
            val cached = withContext(Dispatchers.IO) {
                MediaDownloader.cachedFileForUrl(context, url, messageType)
            }
            if (cached != null) {
                return decodeFileResult(cached, targetWidth, targetHeight)
            }
            if (!bypassNotFound && withContext(Dispatchers.IO) { MediaDownloader.isNotFound(context, url) }) {
                return ImageLoadResult.NotFound
            }
            val file = withContext(AppGraph.dispatchers.network) {
                MediaDownloader.downloadUrl(context, url, messageType, bypassNotFound = bypassNotFound)
            }
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
