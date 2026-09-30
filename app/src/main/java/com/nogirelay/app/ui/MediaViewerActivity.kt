package com.nogirelay.app.ui

import android.Manifest
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.MutableFloatState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.RefreshRatePolicy
import com.nogirelay.app.performance.RefreshRatePolicyOwner
import com.nogirelay.app.ui.glass.GlassCircleButton
import com.nogirelay.app.ui.glass.GlassCircularProgressIndicator
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassMediaTransition
import com.nogirelay.app.ui.glass.GlassSlider
import com.nogirelay.app.ui.glass.GlassTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

/**
 * Media viewer with a true thumbnail-to-fullscreen shared element
 * transition: the tapped thumbnail keeps its identity and expands out of
 * its on-screen rect to fill the window; closing reverses the flight back
 * into the page (or falls back to a soft fade+scale when the source has
 * scrolled away). Zoom / pan / pager / seek behavior is unchanged.
 */
class MediaViewerActivity : ComponentActivity(), RefreshRatePolicyOwner {
    private var viewerType: MessageType = MessageType.IMAGE

    override fun refreshRatePolicy(): RefreshRatePolicy = RefreshRatePolicy.FollowSystem

    companion object {
        private const val EXTRA_IMAGE_URL = "image_url"
        private const val EXTRA_IMAGE_TITLE = "image_title"
        private const val EXTRA_IMAGE_OWNER = "image_owner"
        private const val EXTRA_IMAGE_ID = "image_id"
        private const val EXTRA_IMAGE_URLS = "image_urls"
        private const val EXTRA_IMAGE_INDEX = "image_index"
        private const val EXTRA_TRANSITION_KEY = "transition_key"

        fun messageIntent(context: android.content.Context, messageId: String, transitionKey: String) =
            android.content.Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_TRANSITION_KEY, transitionKey)
            }

        fun imageIntent(
            context: android.content.Context,
            url: String,
            title: String,
            ownerName: String,
            imageId: String,
            urls: List<String> = listOf(url),
            transitionKey: String? = null,
        ): android.content.Intent = android.content.Intent(context, MediaViewerActivity::class.java).apply {
            val orderedUrls = urls.filter(String::isNotBlank).distinct().ifEmpty { listOf(url) }
            putExtra(EXTRA_IMAGE_URL, url)
            putExtra(EXTRA_IMAGE_TITLE, title)
            putExtra(EXTRA_IMAGE_OWNER, ownerName)
            putExtra(EXTRA_IMAGE_ID, imageId)
            putStringArrayListExtra(EXTRA_IMAGE_URLS, ArrayList(orderedUrls))
            putExtra(EXTRA_IMAGE_INDEX, orderedUrls.indexOf(url).coerceAtLeast(0))
            putExtra(EXTRA_TRANSITION_KEY, transitionKey)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 8.0 rejects a fixed orientation on translucent activities.
        if (android.os.Build.VERSION.SDK_INT != android.os.Build.VERSION_CODES.O) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // Transparent window: the shared element flies in over the source page.
        overridePendingTransition(0, 0)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppGraph.initialize(this)
        val messageId = intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID)
        val storedMessage = messageId?.let(AppGraph.database::find)
        val directImageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)
        val directImageUrls = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
            .ifEmpty { listOfNotNull(directImageUrl) }
        val imageTitle = intent.getStringExtra(EXTRA_IMAGE_TITLE)
        val imageOwner = intent.getStringExtra(EXTRA_IMAGE_OWNER).orEmpty().ifBlank { "BLOG" }
        val imageId = intent.getStringExtra(EXTRA_IMAGE_ID).orEmpty()
        val messages = storedMessage?.let(::memberMediaViewerPages) ?: directImageUrls.mapIndexed { index, imageUrl ->
            RelayMessage(
                id = imageId.ifBlank { imageUrl.hashCode().toString() } + "-$index",
                memberId = "",
                memberName = imageOwner,
                memberAvatarUrl = null,
                phoneImageUrl = null,
                type = MessageType.IMAGE,
                text = imageTitle,
                mediaUrl = imageUrl,
                thumbnailUrl = null,
                durationSeconds = null,
                sentAt = "",
                incomingCallFrom = null,
                ringtoneUrl = null,
                isPlayed = false,
            )
        }
        if (messages.isEmpty()) {
            finish()
            return
        }
        val initialPage = if (storedMessage == null) {
            intent.getIntExtra(EXTRA_IMAGE_INDEX, 0).coerceIn(messages.indices)
        } else {
            messages.indexOfFirst { it.id == storedMessage.id }.takeIf { it >= 0 } ?: 0
        }
        viewerType = messages[initialPage].type
        val transitionKey = intent.getStringExtra(EXTRA_TRANSITION_KEY)
            ?: storedMessage?.id

        setContent {
            NogiRelayTheme(darkTheme = true) {
                MediaViewerTransitionRoot(
                    messages = messages,
                    initialPage = initialPage,
                    transitionKey = transitionKey,
                    onFinished = {
                        finish()
                        overridePendingTransition(0, 0)
                    },
                )
            }
        }
    }
}

private fun memberMediaViewerPages(message: RelayMessage): List<RelayMessage> {
    if (message.type != MessageType.IMAGE && message.type != MessageType.VIDEO) return listOf(message)
    val ordered = runCatching {
        AppGraph.database.mediaMessagesForMember(
            message.memberKey,
            message.type,
            limit = VIEWER_MEDIA_LIMIT,
        )
    }.getOrDefault(emptyList())
    val ascending = ordered.asReversed()
    return if (ascending.any { it.id == message.id }) ascending else listOf(message)
}

private const val VIEWER_MEDIA_LIMIT = 500

/**
 * Shared-element transition shell.
 *
 * Entry: the image starts exactly at the source thumbnail rect (position,
 * size, crop, corner radius) over a transparent window and expands to
 * fullscreen while the scrim darkens; viewer controls arrive late.
 * Exit: the current page's image flies back to the (live) source rect.
 * Fallback when the source is gone: centered fade + gentle scale.
 */
@Composable
private fun MediaViewerTransitionRoot(
    messages: List<RelayMessage>,
    initialPage: Int,
    transitionKey: String?,
    onFinished: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(messages.indices),
        pageCount = { messages.size },
    )

    var windowOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { windowOrigin = it.positionInWindow() }) {
        val fullW = constraints.maxWidth.toFloat()
        val fullH = constraints.maxHeight.toFloat()
        val initialMessage = messages[initialPage]
        fun previewUrl(message: RelayMessage) = if (message.type == MessageType.IMAGE) {
            message.mediaUrl ?: message.thumbnailUrl
        } else message.thumbnailUrl ?: message.mediaUrl
        val entrySource = remember(transitionKey) { transitionKey?.let(GlassMediaTransition::visibleSource) }
        val entryRatio = remember {
            ImageAspectRatioCache.get(previewUrl(initialMessage))
                ?: ImageAspectRatioCache.get(initialMessage.mediaUrl)
                ?: ImageAspectRatioCache.get(entrySource?.url)
                ?: ImageAspectRatioCache.get(initialMessage.thumbnailUrl)
        }
        val hasEntryFlight = entrySource != null && entryRatio != null
        val progress = remember { Animatable(if (hasEntryFlight) 0f else 1f) }
        var entryDone by remember { mutableStateOf(!hasEntryFlight) }
        var exiting by remember { mutableStateOf(false) }
        var exitTarget by remember { mutableStateOf<GlassMediaTransition.Source?>(null) }
        val currentMessage = messages[pagerState.currentPage]
        var currentRatio by remember(currentMessage.id) {
            mutableStateOf(
                ImageAspectRatioCache.get(previewUrl(currentMessage))
                    ?: ImageAspectRatioCache.get(currentMessage.thumbnailUrl),
            )
        }
        val flightSpec = tween<Float>(300, easing = FastOutSlowInEasing)

        LaunchedEffect(Unit) {
            if (hasEntryFlight) progress.animateTo(1f, flightSpec)
            entryDone = true
        }

        fun requestClose() {
            if (exiting) return
            exiting = true
            val key = if (transitionKey?.startsWith("blogimg:") == true) {
                "blogimg:${currentMessage.mediaUrl}"
            } else transitionKey?.removeSuffix(initialMessage.id)?.plus(currentMessage.id) ?: currentMessage.id
            exitTarget = GlassMediaTransition.visibleSource(key)
                .takeIf { currentRatio != null || (pagerState.currentPage == initialPage && entryRatio != null) }
            scope.launch {
                progress.animateTo(0f, if (exitTarget != null) flightSpec else tween(180))
                onFinished()
            }
        }
        BackHandler { requestClose() }

        val p = progress.value.coerceIn(0f, 1f)
        val source = if (exiting) exitTarget else entrySource.takeIf { hasEntryFlight && !entryDone }
        fun IntRect.localRect() = MediaRect(
            left - windowOrigin.x, top - windowOrigin.y,
            right - windowOrigin.x, bottom - windowOrigin.y,
        )
        val geometry = source?.let {
            mediaFlightGeometry(
                source = it.bounds.localRect(),
                visibleSource = it.visibleBounds.localRect(),
                viewportWidth = fullW,
                viewportHeight = fullH,
                aspectRatio = if (exiting) currentRatio ?: entryRatio ?: 1f else entryRatio ?: 1f,
                cornerRadius = it.cornerRadiusPx,
                progress = p,
                crop = it.crop,
            )
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = p }.background(Color.Black))

        // Keep one full-size viewer (and one image decode / video surface) alive
        // throughout. Only the crop and transform change, so there is no handoff.
        Box(Modifier.fillMaxSize().drawWithContent {
            val flight = geometry
            if (flight == null) drawContent() else {
                val rect = flight.clip
                val path = Path().apply {
                    addRoundRect(RoundRect(rect.left, rect.top, rect.right, rect.bottom, CornerRadius(flight.radius)))
                }
                clipPath(path) { this@drawWithContent.drawContent() }
            }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                scaleX = geometry?.scale ?: if (exiting) 0.94f + 0.06f * p else 1f
                scaleY = scaleX
                translationX = geometry?.translationX ?: 0f
                translationY = geometry?.translationY ?: 0f
                alpha = if (exiting && exitTarget == null) p else 1f
            }) {
                MediaViewer(
                    messages = messages,
                    pagerState = pagerState,
                    controlsEnabled = entryDone && !exiting,
                    transformProgress = if (exiting) p else 1f,
                    onAspectRatio = { currentRatio = it },
                    onClose = ::requestClose,
                )
            }
        }
    }
}


@Composable
private fun MediaViewer(
    messages: List<RelayMessage>,
    pagerState: PagerState,
    controlsEnabled: Boolean,
    transformProgress: Float,
    onAspectRatio: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val downloadScope = rememberCoroutineScope()
    var waitingForStoragePermission by remember { mutableStateOf<RelayMessage?>(null) }
    var downloadingMessageId by remember { mutableStateOf<String?>(null) }
    var downloadedMessageId by remember { mutableStateOf<String?>(null) }

    val saveDownload: (RelayMessage) -> Unit = { message ->
        downloadingMessageId = message.id
        downloadScope.launch(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching { MediaDownloader.saveToDownloads(context, message) }
            withContext(Dispatchers.Main) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed < MIN_DOWNLOAD_FEEDBACK_MILLIS) {
                    delay(MIN_DOWNLOAD_FEEDBACK_MILLIS - elapsed)
                }
                downloadingMessageId = null
                if (result.isSuccess) {
                    downloadedMessageId = message.id
                    downloadScope.launch {
                        delay(2000)
                        if (downloadedMessageId == message.id) {
                            downloadedMessageId = null
                        }
                    }
                }
                Toast.makeText(
                    context,
                    result.fold(
                        onSuccess = { "已保存到 Download/${it.displayName}" },
                        onFailure = { it.message ?: "无法保存媒体" },
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingMessage = waitingForStoragePermission
        waitingForStoragePermission = null
        if (granted && pendingMessage != null) {
            saveDownload(pendingMessage)
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存到 Download 文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    val requestSave: (RelayMessage) -> Unit = { message ->
        if (MediaDownloader.needsLegacyWritePermission(context)) {
            waitingForStoragePermission = message
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveDownload(message)
        }
    }

    val message = messages[pagerState.currentPage.coerceIn(messages.indices)]
    when (message.type) {
        MessageType.IMAGE -> ImageViewer(
            messages = messages,
            pagerState = pagerState,
            controlsEnabled = controlsEnabled,
            transformProgress = transformProgress,
            onAspectRatio = onAspectRatio,
            isDownloading = { it.id == downloadingMessageId },
            isDownloaded = { it.id == downloadedMessageId },
            onClose = onClose,
            onSaveDownload = requestSave,
        )

        MessageType.VIDEO -> VideoViewer(
            messages = messages,
            pagerState = pagerState,
            controlsEnabled = controlsEnabled,
            transformProgress = transformProgress,
            onAspectRatio = onAspectRatio,
            isDownloading = { it.id == downloadingMessageId },
            isDownloaded = { it.id == downloadedMessageId },
            onClose = onClose,
            onSaveDownload = requestSave,
        )

        else -> Unit
    }
}

private enum class DownloadButtonState { IDLE, DOWNLOADING, DONE }

private const val MIN_DOWNLOAD_FEEDBACK_MILLIS = 650L

/** Floating glass controls over dark media: back, title, download. */
@Composable
private fun MediaViewerTopBar(
    title: String,
    modifier: Modifier = Modifier,
    pageIndicator: String? = null,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    onClose: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCircleButton(
            onClick = onClose,
            tone = GlassTone.Neutral,
            contentDescription = "返回",
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title.ifBlank { "乃木坂46" },
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (pageIndicator != null) {
                Text(
                    text = pageIndicator,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        GlassCircleButton(
            onClick = onDownload,
            enabled = !isDownloading,
            tone = GlassTone.Neutral,
            contentDescription = when {
                isDownloading -> "保存中"
                isDownloaded -> "已保存"
                else -> "保存到本地"
            },
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = when {
                        isDownloading -> DownloadButtonState.DOWNLOADING
                        isDownloaded -> DownloadButtonState.DONE
                        else -> DownloadButtonState.IDLE
                    },
                    transitionSpec = {
                        (
                            fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                                scaleIn(
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                    initialScale = 0.6f,
                                )
                            ).togetherWith(
                            fadeOut(animationSpec = tween(120)) +
                                scaleOut(targetScale = 0.6f, animationSpec = tween(120)),
                        )
                    },
                    contentAlignment = Alignment.Center,
                    label = "download_button_state",
                ) { state ->
                    when (state) {
                        DownloadButtonState.DOWNLOADING -> GlassCircularProgressIndicator(
                            color = GlassColors.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        DownloadButtonState.DONE -> Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = GlassColors.Success,
                            modifier = Modifier.size(24.dp),
                        )
                        DownloadButtonState.IDLE -> Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(
    messages: List<RelayMessage>,
    pagerState: PagerState,
    controlsEnabled: Boolean,
    transformProgress: Float,
    onAspectRatio: (Float) -> Unit,
    isDownloading: (RelayMessage) -> Boolean,
    isDownloaded: (RelayMessage) -> Boolean,
    onClose: () -> Unit,
    onSaveDownload: (RelayMessage) -> Unit,
) {
    var zoomedPage by remember { mutableIntStateOf(-1) }
    var controlsVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = controlsEnabled && zoomedPage != pagerState.currentPage,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                message = messages[page],
                transformProgress = transformProgress,
                gesturesEnabled = controlsEnabled,
                onAspectRatio = { if (page == pagerState.currentPage) onAspectRatio(it) },
                onZoomedChange = { zoomed ->
                    if (zoomed) {
                        zoomedPage = page
                    } else if (zoomedPage == page) {
                        zoomedPage = -1
                    }
                },
                onTap = {
                    if (controlsEnabled) controlsVisible = !controlsVisible
                },
            )
        }

        val currentMessage = messages[pagerState.currentPage]

        AnimatedVisibility(
            visible = controlsVisible && controlsEnabled,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            MediaViewerTopBar(
                title = currentMessage.memberName,
                pageIndicator = if (messages.size > 1) "${pagerState.currentPage + 1} / ${messages.size}" else null,
                isDownloading = isDownloading(currentMessage),
                isDownloaded = isDownloaded(currentMessage),
                onClose = onClose,
                onDownload = { onSaveDownload(currentMessage) },
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    message: RelayMessage,
    transformProgress: Float,
    gesturesEnabled: Boolean,
    onAspectRatio: (Float) -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    onTap: () -> Unit = {},
) {
    var imageScale by remember(message.id) { mutableFloatStateOf(1f) }
    var imageOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    var imageViewport by remember(message.id) { mutableStateOf(IntSize.Zero) }

    fun applyImageTransform(zoomChange: Float, panChange: Offset) {
        val newScale = (imageScale * zoomChange).coerceIn(1f, 5f)
        val screenPan = contentPanToScreen(panChange.x, panChange.y, newScale)
        val constrained = constrainMediaOffset(
            x = imageOffset.x + screenPan.x,
            y = imageOffset.y + screenPan.y,
            scale = newScale,
            viewportWidth = imageViewport.width.toFloat(),
            viewportHeight = imageViewport.height.toFloat(),
        )
        imageOffset = Offset(constrained.x, constrained.y)
        imageScale = newScale
        onZoomedChange(newScale > 1.01f)
    }

    RemoteImage(
        url = message.mediaUrl,
        previewUrl = message.thumbnailUrl,
        contentDescription = message.text,
        onAspectRatio = onAspectRatio,
        placeholderColor = Color.Black,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                imageViewport = size
                val constrained = constrainMediaOffset(
                    x = imageOffset.x,
                    y = imageOffset.y,
                    scale = imageScale,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                )
                imageOffset = Offset(constrained.x, constrained.y)
            }
            .graphicsLayer {
                scaleX = 1f + (imageScale - 1f) * transformProgress
                scaleY = scaleX
                translationX = imageOffset.x * transformProgress
                translationY = imageOffset.y * transformProgress
            }
            .pointerInput(message.id, gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        if (pressedPointers >= 2 || imageScale > 1.01f) {
                            applyImageTransform(
                                zoomChange = event.calculateZoom(),
                                panChange = event.calculatePan(),
                            )
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
            .pointerInput(message.id, gesturesEnabled) {
                if (!gesturesEnabled) return@pointerInput
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (imageScale > 1f) {
                            imageScale = 1f
                            imageOffset = Offset.Zero
                            onZoomedChange(false)
                        } else {
                            imageScale = 2.5f
                            onZoomedChange(true)
                        }
                    },
                )
            },
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun VideoViewer(
    messages: List<RelayMessage>,
    pagerState: PagerState,
    controlsEnabled: Boolean,
    transformProgress: Float,
    onAspectRatio: (Float) -> Unit,
    isDownloading: (RelayMessage) -> Boolean,
    isDownloaded: (RelayMessage) -> Boolean,
    onClose: () -> Unit,
    onSaveDownload: (RelayMessage) -> Unit,
) {
    var zoomedPage by remember { mutableIntStateOf(-1) }

    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = controlsEnabled && zoomedPage != pagerState.currentPage,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageMessage = messages[page]
            VideoPlayer(
                message = pageMessage,
                transformProgress = transformProgress,
                onAspectRatio = { if (page == pagerState.currentPage) onAspectRatio(it) },
                active = pagerState.currentPage == page,
                controlsEnabled = controlsEnabled,
                pageIndicator = if (messages.size > 1) "${page + 1} / ${messages.size}" else null,
                isDownloading = isDownloading(pageMessage),
                isDownloaded = isDownloaded(pageMessage),
                onClose = onClose,
                onSaveDownload = { onSaveDownload(pageMessage) },
                onZoomedChange = { zoomed ->
                    if (zoomed) {
                        zoomedPage = page
                    } else if (zoomedPage == page) {
                        zoomedPage = -1
                    }
                },
            )
        }
    }
}

@Composable
private fun VideoPlayer(
    message: RelayMessage,
    transformProgress: Float,
    onAspectRatio: (Float) -> Unit,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onClose: () -> Unit,
    onSaveDownload: () -> Unit,
    active: Boolean = true,
    controlsEnabled: Boolean = true,
    pageIndicator: String? = null,
    onZoomedChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    var videoView by remember { mutableStateOf<TextureVideoView?>(null) }
    var videoPath by remember(message.id) { mutableStateOf<String?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var videoFrameReady by remember(message.id) { mutableStateOf(false) }
    var videoPlaying by remember { mutableStateOf(false) }
    var isCompleted by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    val coroutineScope = rememberCoroutineScope()
    var wasPlayingBeforeDrag by remember { mutableStateOf(false) }
    val playbackPositionState = remember(message.id) { mutableFloatStateOf(0f) }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var duration by remember { mutableIntStateOf(0) }

    var videoScale by remember(message.id) { mutableFloatStateOf(1f) }
    var videoOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    var videoViewport by remember(message.id) { mutableStateOf(IntSize.Zero) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                videoView?.let { vv ->
                    if (vv.isPlaying) {
                        vv.pause()
                        videoPlaying = false
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            videoView?.stopPlayback()
        }
    }

    LaunchedEffect(active, controlsEnabled, isPrepared) {
        if (active && controlsEnabled && isPrepared && !isCompleted) {
            videoView?.start()
            videoPlaying = true
        } else if (!active || !controlsEnabled) {
            videoView?.let { vv ->
                if (vv.isPlaying) {
                    vv.pause()
                    videoPlaying = false
                }
            }
        }
    }

    LaunchedEffect(message.id, message.mediaUrl) {
        val path = withContext(Dispatchers.IO) {
            runCatching {
                MediaDownloader.enqueueIfNeeded(context, message)?.absolutePath
            }.getOrNull()
        }
        videoPath = path
    }

    LaunchedEffect(controlsVisible, videoPlaying, lastInteractionTime) {
        if (controlsVisible && videoPlaying) {
            delay(2000)
            controlsVisible = false
        }
    }

    LaunchedEffect(isPrepared) {
        while (isPrepared && duration <= 0) {
            videoView?.let { vv ->
                if (vv.duration > 0) {
                    duration = vv.duration
                }
            }
            delay(100)
        }
    }

    val togglePlayPause = {
        videoView?.let { vv ->
            if (isCompleted) {
                playbackPositionState.floatValue = 0f
                vv.seekTo(0)
                vv.start()
                videoPlaying = true
                isCompleted = false
            } else if (vv.isPlaying) {
                vv.pause()
                videoPlaying = false
            } else {
                vv.start()
                videoPlaying = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        val path = videoPath

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    videoViewport = size
                    val constrained = constrainMediaOffset(
                        x = videoOffset.x,
                        y = videoOffset.y,
                        scale = videoScale,
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                    )
                    videoOffset = Offset(constrained.x, constrained.y)
                }
                .graphicsLayer {
                    scaleX = 1f + (videoScale - 1f) * transformProgress
                    scaleY = scaleX
                    translationX = videoOffset.x * transformProgress
                    translationY = videoOffset.y * transformProgress
                }
                .pointerInput(message.id, controlsEnabled) {
                    if (!controlsEnabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressedPointers = event.changes.count { it.pressed }

                            if (pressedPointers >= 2 || videoScale > 1.01f) {
                                val newScale = (videoScale * event.calculateZoom()).coerceIn(1f, 5f)
                                val pan = event.calculatePan()
                                val screenPan = contentPanToScreen(pan.x, pan.y, newScale)
                                val constrained = constrainMediaOffset(
                                    x = videoOffset.x + screenPan.x,
                                    y = videoOffset.y + screenPan.y,
                                    scale = newScale,
                                    viewportWidth = videoViewport.width.toFloat(),
                                    viewportHeight = videoViewport.height.toFloat(),
                                )
                                videoOffset = Offset(constrained.x, constrained.y)
                                videoScale = newScale
                                onZoomedChange(newScale > 1.01f)
                                event.changes.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
        ) {
            if (path != null) {
                AndroidView(
                    factory = { viewContext ->
                        TextureVideoView(viewContext).apply {
                            videoView = this
                            layoutParams = android.view.ViewGroup.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setOnPreparedListener { mp ->
                                mediaPlayer = mp
                                if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                    onAspectRatio(mp.videoWidth.toFloat() / mp.videoHeight)
                                }
                                isPrepared = true
                                duration = this.duration.coerceAtLeast(0)
                                playbackPositionState.floatValue = 0f
                                if (controlsEnabled && active) start()
                                videoPlaying = controlsEnabled && active
                                isCompleted = false
                            }
                            setOnCompletionListener {
                                videoPlaying = false
                                isCompleted = true
                                controlsVisible = true
                                playbackPositionState.floatValue = duration.toFloat().coerceAtLeast(0f)
                            }
                            setOnInfoListener { _, what, _ ->
                                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoFrameReady = true
                                false
                            }
                        }
                    },
                    update = { view ->
                        if (view.tag != path) {
                            view.tag = path
                            isPrepared = false
                            videoFrameReady = false
                            view.setVideoPath(path)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (!videoFrameReady) {
                RemoteImage(
                    url = message.thumbnailUrl ?: message.mediaUrl,
                    contentDescription = message.text,
                    onAspectRatio = onAspectRatio,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    messageType = MessageType.VIDEO,
                    message = message,
                    placeholderColor = Color.Black,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onTap = {
                                if (!controlsEnabled) return@detectTapGestures
                                controlsVisible = !controlsVisible
                                if (controlsVisible) {
                                    lastInteractionTime = System.currentTimeMillis()
                                }
                            },
                        )
                    },
            )
        }

        if (!isPrepared) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clip(GlassViewerShapes.Circle)
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                GlassCircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(30.dp),
                    strokeWidth = 2.5.dp,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && isPrepared && controlsEnabled,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                MediaViewerTopBar(
                    title = message.memberName,
                    pageIndicator = pageIndicator,
                    isDownloading = isDownloading,
                    isDownloaded = isDownloaded,
                    onClose = onClose,
                    onDownload = {
                        onSaveDownload()
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                GlassCircleButton(
                    onClick = {
                        togglePlayPause()
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    tone = GlassTone.Neutral,
                    size = 64.dp,
                    contentDescription = when {
                        isCompleted -> "重播"
                        videoPlaying -> "暂停"
                        else -> "播放"
                    },
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Icon(
                        imageVector = when {
                            isCompleted -> Icons.Rounded.Replay
                            videoPlaying -> Icons.Rounded.Pause
                            else -> Icons.Rounded.PlayArrow
                        },
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }

                VideoBottomBar(
                    videoView = videoView,
                    videoPlaying = videoPlaying,
                    isCompleted = isCompleted,
                    duration = duration,
                    playbackPositionState = playbackPositionState,
                    onTogglePlayPause = {
                        togglePlayPause()
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    onDragStart = {
                        wasPlayingBeforeDrag = videoPlaying
                        if (videoPlaying) {
                            videoView?.pause()
                            videoPlaying = false
                        }
                    },
                    onSeek = { targetMs, onComplete ->
                        val mp = mediaPlayer
                        var completed = false
                        val finishSeek = {
                            if (!completed) {
                                completed = true
                                onComplete()
                                if (wasPlayingBeforeDrag) {
                                    videoView?.start()
                                    videoPlaying = true
                                    wasPlayingBeforeDrag = false
                                }
                            }
                        }

                        val timeoutJob = coroutineScope.launch {
                            delay(800)
                            finishSeek()
                        }

                        if (mp != null) {
                            mp.setOnSeekCompleteListener {
                                timeoutJob.cancel()
                                finishSeek()
                            }
                            mp.seekTo(targetMs.toLong(), MediaPlayer.SEEK_CLOSEST)
                        } else {
                            videoView?.seekTo(targetMs)
                            timeoutJob.cancel()
                            finishSeek()
                        }

                        if (isCompleted && targetMs < duration) {
                            isCompleted = false
                        }
                    },
                    onInteraction = {
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

private object GlassViewerShapes {
    val Circle = androidx.compose.foundation.shape.CircleShape
}


/** Glass capsule transport bar: play/pause droplet + viscous seek slider. */
@Composable
private fun VideoBottomBar(
    videoView: TextureVideoView?,
    videoPlaying: Boolean,
    isCompleted: Boolean,
    duration: Int,
    playbackPositionState: MutableFloatState,
    onTogglePlayPause: () -> Unit,
    onDragStart: () -> Unit,
    onSeek: (targetMs: Int, onComplete: () -> Unit) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var isSeeking by remember { mutableStateOf(false) }

    LaunchedEffect(videoPlaying, isCompleted, isDraggingSlider, isSeeking) {
        if (isCompleted) {
            playbackPositionState.floatValue = duration.toFloat()
            return@LaunchedEffect
        }
        if (!videoPlaying || isDraggingSlider || isSeeking) {
            return@LaunchedEffect
        }

        val vv = videoView ?: return@LaunchedEffect
        var wasPlaying = vv.isPlaying
        val realPos = vv.currentPosition.toFloat().coerceAtLeast(0f)
        var lastMediaPos = if (playbackPositionState.floatValue > 0f && abs(realPos - playbackPositionState.floatValue) < 350f) {
            playbackPositionState.floatValue
        } else {
            realPos
        }
        playbackPositionState.floatValue = lastMediaPos
        var lastSyncTime = SystemClock.elapsedRealtime()

        while (isActive) {
            withFrameMillis {
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastSyncTime).coerceAtLeast(0)

                if (vv.isPlaying) {
                    if (!wasPlaying) {
                        wasPlaying = true
                        lastMediaPos = vv.currentPosition.toFloat().coerceAtLeast(0f)
                        lastSyncTime = now
                    } else if (now - lastSyncTime >= 250) {
                        val real = vv.currentPosition.toFloat().coerceAtLeast(0f)
                        val expected = lastMediaPos + elapsed
                        val drift = abs(real - expected)
                        val isEofGlitch = real == 0f && duration > 2000 && expected > duration * 0.7f
                        if (drift > 350f && !isEofGlitch) {
                            lastMediaPos = real
                            lastSyncTime = now
                        }
                    }
                } else {
                    if (wasPlaying) {
                        wasPlaying = false
                        lastMediaPos = (lastMediaPos + elapsed).coerceIn(0f, duration.toFloat().coerceAtLeast(1f))
                        lastSyncTime = now
                    }
                }

                val currentElapsed = (now - lastSyncTime).coerceAtLeast(0)
                val estimated = if (vv.isPlaying) {
                    (lastMediaPos + currentElapsed).coerceIn(0f, duration.toFloat().coerceAtLeast(1f))
                } else {
                    lastMediaPos
                }
                playbackPositionState.floatValue = estimated
            }
        }
    }

    val currentPos = playbackPositionState.floatValue
    val displayPosition = when {
        isDraggingSlider -> dragPosition.toInt()
        isCompleted -> duration
        else -> currentPos.toInt()
    }
    val progressFraction = if (duration > 0) {
        when {
            isDraggingSlider -> (dragPosition / duration).coerceIn(0f, 1f)
            isCompleted -> 1f
            else -> (currentPos / duration).coerceIn(0f, 1f)
        }
    } else {
        0f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCircleButton(
            onClick = onTogglePlayPause,
            tone = GlassTone.Neutral,
            size = 46.dp,
            contentDescription = when {
                isCompleted -> "重播"
                videoPlaying -> "暂停"
                else -> "播放"
            },
        ) {
            Icon(
                imageVector = when {
                    isCompleted -> Icons.Rounded.Replay
                    videoPlaying -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                },
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = formatTimeMs(displayPosition),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Color.White,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTimeMs(duration),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            GlassSlider(
                value = progressFraction,
                onValueChange = { frac ->
                    if (!isDraggingSlider) {
                        isDraggingSlider = true
                        onDragStart()
                    }
                    dragPosition = frac * duration
                    onInteraction()
                },
                onValueChangeFinished = {
                    val targetMs = dragPosition.toInt()
                    playbackPositionState.floatValue = targetMs.toFloat()
                    isSeeking = true
                    isDraggingSlider = false
                    onSeek(targetMs) {
                        isSeeking = false
                    }
                    onInteraction()
                },
                enabled = duration > 0,
                accent = GlassColors.Accent,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatTimeMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}


