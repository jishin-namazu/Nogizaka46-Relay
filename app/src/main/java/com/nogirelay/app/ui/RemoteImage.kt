package com.nogirelay.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ImageView
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.viewinterop.AndroidView
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.HttpNotFoundException
import com.nogirelay.app.media.MediaDownloader
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private sealed interface ImageLoadResult {
    data class Static(val bitmap: Bitmap) : ImageLoadResult

    /**
     * 动图：BitmapFactory 只会解出第一帧，所以改由 [AnimatedImageDrawable] 播放。它不适合放进
     * Bitmap LRU（同一个 drawable 自带播放状态，不能被两个 View 共享），因此这里直接带着它。
     */
    data class Animated(val drawable: Drawable, val width: Int, val height: Int) : ImageLoadResult {
        fun aspectRatio(): Float? =
            if (width > 0 && height > 0) width.toFloat() / height.toFloat() else null
    }

    data object NotFound : ImageLoadResult
    data object Error : ImageLoadResult
}

/**
 * 博客和消息图片最高发布到约 3700x2800，解码后约 40 MB。解码这样的图片要花几十毫秒，而且会
 * 立刻被小型 LRU 淘汰，于是每次滚动都会重新解码。采样到屏幕大小的位图可让解码时间和内存保持
 * 在合理范围。
 */
private const val FALLBACK_DECODE_DIMENSION = 1024
private const val SIZE_BUCKET_PX = 64
private const val GIF_HEADER_BYTES = 6

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
    // 使用 URL 的快速路径回退：保留最近的位图，使过渡时不会出现空白帧
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

/**
 * GIF87a / GIF89a 魔数。单独提取出来，使普通的 JVM 测试无需设备即可锁定该检测逻辑。
 */
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
    var animated by remember(cacheKey) { mutableStateOf<ImageLoadResult.Animated?>(null) }

    LaunchedEffect(cacheKey, loadCachedImmediately, retryCount) {
        if (url != null && (isNotFound || MediaDownloader.isNotFound(context, url))) {
            isNotFound = true
            isError = false
            return@LaunchedEffect
        }
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
                when (val result = loadImage(context, value, messageType, message, bucketedDimension)) {
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
                        // 动图不进 Bitmap 缓存；宽高比照样记下来，preserveAspectRatio 的布局才不跳。
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
    }

    val currentRatio = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
        ?: animated?.aspectRatio()
        ?: knownAspectRatio
    val boxModifier = if (preserveAspectRatio && currentRatio != null && currentRatio > 0f) {
        modifier.fillMaxWidth().aspectRatio(currentRatio)
    } else {
        modifier
    }

    val hasContent = bitmap != null || animated != null
    val finalModifier = if (!hasContent && isError && !isNotFound) {
        boxModifier
            .clickable { retryCount++ }
            .background(placeholderColor)
    } else {
        boxModifier.background(
            if (hasContent || placeholderResId != null) Color.Transparent else placeholderColor,
        )
    }

    Box(
        finalModifier,
        contentAlignment = Alignment.Center,
    ) {
        val animatedImage = animated
        val image = bitmap
        if (animatedImage != null) {
            AnimatedRemoteImage(
                drawable = animatedImage.drawable,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (image != null) {
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

/**
 * 博客图里的 GIF 以前只会显示第一帧，因为 BitmapFactory 不解动画。这里把 [AnimatedImageDrawable]
 * 交给一个普通 ImageView：帧推进由 RenderThread / drawable 自己的 callback 驱动，不依赖按帧重组
 * Compose，所以列表滚动时也不会额外产生重组。
 */
@Composable
private fun AnimatedRemoteImage(
    drawable: Drawable,
    contentDescription: String?,
    contentScale: ContentScale,
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
                view.setImageDrawable(drawable)
                (drawable as? AnimatedImageDrawable)?.start()
            }
        },
        modifier = modifier,
    )
}

private suspend fun loadImage(
    context: Context,
    url: String,
    messageType: MessageType = MessageType.IMAGE,
    message: RelayMessage? = null,
    maxDecodeDimension: Int = FALLBACK_DECODE_DIMENSION,
): ImageLoadResult = withContext(com.nogirelay.app.data.AppGraph.dispatchers.imageDecode) {
    if (MediaDownloader.isNotFound(context, url)) {
        return@withContext ImageLoadResult.NotFound
    }
    try {
        if (messageType == MessageType.VIDEO && message != null) {
            val thumbFile = MediaDownloader.cachedVideoThumbnail(context, message)
                ?: MediaDownloader.generateVideoThumbnail(context, message)
            if (thumbFile != null && thumbFile.exists() && thumbFile.length() > 0L) {
                val bmp = decodeSampled(thumbFile, maxDecodeDimension)
                return@withContext if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
            }
            val explicitThumb = message.thumbnailUrl?.takeIf { it.isNotBlank() }
            if (explicitThumb != null) {
                if (MediaDownloader.isNotFound(context, explicitThumb)) {
                    return@withContext ImageLoadResult.NotFound
                }
                val cached = MediaDownloader.cachedFileForUrl(context, explicitThumb, MessageType.IMAGE)
                val file = cached ?: MediaDownloader.downloadUrl(context, explicitThumb, MessageType.IMAGE)
                val bmp = decodeSampled(file, maxDecodeDimension)
                return@withContext if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
            }
            return@withContext ImageLoadResult.Error
        }

        val uri = Uri.parse(url)
        if (uri.scheme in setOf("android.resource", "content", "file")) {
            if (uri.scheme == "file") {
                val file = uri.path?.takeIf { it.isNotBlank() }?.let(::File)
                if (file != null && file.exists()) return@withContext decodeFileResult(file, maxDecodeDimension)
            }
            val bmp = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
        } else if (uri.scheme?.lowercase() in setOf("https", "http")) {
            val cached = MediaDownloader.cachedFileForUrl(context, url, messageType)
            val file = cached ?: MediaDownloader.downloadUrl(context, url, messageType)
            decodeFileResult(file, maxDecodeDimension)
        } else {
            ImageLoadResult.Error
        }
    } catch (e: HttpNotFoundException) {
        ImageLoadResult.NotFound
    } catch (e: Throwable) {
        ImageLoadResult.Error
    }
}

/**
 * 磁盘上已经是本地的文件在这里分流：GIF 动图交给 ImageDecoder（API 28+，动图 / 动图 WebP 都能播），
 * 其余仍走采样解码，避免大图一次性吃满内存。API 26/27 没有 ImageDecoder，退回第一帧的静态图。
 */
private fun decodeFileResult(file: File, maxDecodeDimension: Int): ImageLoadResult {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && looksLikeGif(file)) {
        val drawable = decodeAnimatedDrawable(file)
        if (drawable != null) {
            return ImageLoadResult.Animated(
                drawable = drawable,
                width = drawable.intrinsicWidth,
                height = drawable.intrinsicHeight,
            )
        }
    }
    val bmp = decodeSampled(file, maxDecodeDimension)
    return if (bmp != null) ImageLoadResult.Static(bmp) else ImageLoadResult.Error
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
private fun decodeAnimatedDrawable(file: File): Drawable? = try {
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
} catch (error: Throwable) {
    null
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
