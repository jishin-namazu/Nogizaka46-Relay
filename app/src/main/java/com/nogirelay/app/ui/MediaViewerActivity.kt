package com.nogirelay.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.RefreshRatePolicy
import com.nogirelay.app.performance.RefreshRatePolicyOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

class MediaViewerActivity : ComponentActivity(), RefreshRatePolicyOwner {
    private var viewerType: MessageType = MessageType.IMAGE

    override fun refreshRatePolicy(): RefreshRatePolicy = RefreshRatePolicy.Maximum

    companion object {
        private const val EXTRA_IMAGE_URL = "image_url"
        private const val EXTRA_IMAGE_TITLE = "image_title"
        private const val EXTRA_IMAGE_OWNER = "image_owner"
        private const val EXTRA_IMAGE_ID = "image_id"
        private const val EXTRA_IMAGE_URLS = "image_urls"
        private const val EXTRA_IMAGE_INDEX = "image_index"

        fun imageIntent(
            context: Context,
            url: String,
            title: String,
            ownerName: String,
            imageId: String,
            urls: List<String> = listOf(url),
        ): Intent = Intent(context, MediaViewerActivity::class.java).apply {
            val orderedUrls = urls.filter(String::isNotBlank).distinct().ifEmpty { listOf(url) }
            putExtra(EXTRA_IMAGE_URL, url)
            putExtra(EXTRA_IMAGE_TITLE, title)
            putExtra(EXTRA_IMAGE_OWNER, ownerName)
            putExtra(EXTRA_IMAGE_ID, imageId)
            putStringArrayListExtra(EXTRA_IMAGE_URLS, ArrayList(orderedUrls))
            putExtra(EXTRA_IMAGE_INDEX, orderedUrls.indexOf(url).coerceAtLeast(0))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        val messages = storedMessage?.let(::listOf) ?: directImageUrls.mapIndexed { index, imageUrl ->
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
            0
        }
        viewerType = messages[initialPage].type

        setContent {
            NogiRelayTheme(darkTheme = true) {
                MediaViewer(messages = messages, initialPage = initialPage, onClose = ::finish)
            }
        }
    }
}

@Composable
private fun MediaViewer(
    messages: List<RelayMessage>,
    initialPage: Int,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadScope = rememberCoroutineScope()
    var waitingForStoragePermission by remember { mutableStateOf<RelayMessage?>(null) }
    var downloadingMessageId by remember { mutableStateOf<String?>(null) }
    var downloadedMessageId by remember { mutableStateOf<String?>(null) }

    val saveDownload: (RelayMessage) -> Unit = { message ->
        downloadingMessageId = message.id
        downloadScope.launch(Dispatchers.IO) {
            val result = runCatching { MediaDownloader.saveToDownloads(context, message) }
            withContext(Dispatchers.Main) {
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

    val message = messages[initialPage]
    when (message.type) {
        MessageType.IMAGE -> ImageViewer(
            messages = messages,
            initialPage = initialPage,
            isDownloading = { it.id == downloadingMessageId },
            isDownloaded = { it.id == downloadedMessageId },
            onClose = onClose,
            onSaveDownload = requestSave,
        )

        MessageType.VIDEO -> VideoPlayer(
            message = message,
            isDownloading = message.id == downloadingMessageId,
            isDownloaded = message.id == downloadedMessageId,
            onClose = onClose,
            onSaveDownload = { requestSave(message) },
        )

        else -> Unit
    }
}

@Composable
private fun MediaViewerTopBar(
    title: String,
    pageIndicator: String? = null,
    isDownloading: Boolean = false,
    isDownloaded: Boolean = false,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.65f),
                        Color.Black.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = title.ifBlank { "乃木坂46" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pageIndicator != null) {
                Text(
                    text = pageIndicator,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }

        IconButton(
            onClick = onDownload,
            modifier = Modifier.size(44.dp),
        ) {
            when {
                isDownloading -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
                }
                isDownloaded -> {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = "已保存",
                        tint = Color(0xFF4ADE80),
                        modifier = Modifier.size(24.dp),
                    )
                }
                else -> {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "保存到本地",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageViewer(
    messages: List<RelayMessage>,
    initialPage: Int,
    isDownloading: (RelayMessage) -> Boolean,
    isDownloaded: (RelayMessage) -> Boolean,
    onClose: () -> Unit,
    onSaveDownload: (RelayMessage) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(messages.indices),
        pageCount = { messages.size },
    )
    var zoomedPage by remember { mutableIntStateOf(-1) }
    var controlsVisible by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = zoomedPage != pagerState.currentPage,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomableImage(
                message = messages[page],
                onZoomedChange = { zoomed ->
                    if (zoomed) {
                        zoomedPage = page
                    } else if (zoomedPage == page) {
                        zoomedPage = -1
                    }
                },
                onTap = {
                    controlsVisible = !controlsVisible
                },
            )
        }

        val currentMessage = messages[pagerState.currentPage]

        // Top bar
        AnimatedVisibility(
            visible = controlsVisible,
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
        contentDescription = message.text,
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
                scaleX = imageScale
                scaleY = imageScale
                translationX = imageOffset.x
                translationY = imageOffset.y
            }
            .pointerInput(message.id) {
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
            .pointerInput(message.id) {
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
private fun VideoPlayer(
    message: RelayMessage,
    isDownloading: Boolean,
    isDownloaded: Boolean,
    onClose: () -> Unit,
    onSaveDownload: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var videoPath by remember(message.id) { mutableStateOf<String?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
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
    val videoTransformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (videoScale * zoomChange).coerceIn(1f, 5f)
        val screenPan = contentPanToScreen(panChange.x, panChange.y, newScale)
        val constrained = constrainMediaOffset(
            x = videoOffset.x + screenPan.x,
            y = videoOffset.y + screenPan.y,
            scale = newScale,
            viewportWidth = videoViewport.width.toFloat(),
            viewportHeight = videoViewport.height.toFloat(),
        )
        videoOffset = Offset(constrained.x, constrained.y)
        videoScale = newScale
    }

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

    // Load / cache local video file
    LaunchedEffect(message.id, message.mediaUrl) {
        val path = withContext(Dispatchers.IO) {
            runCatching {
                MediaDownloader.enqueueIfNeeded(context, message)?.absolutePath
            }.getOrNull()
        }
        videoPath = path
    }

    // Auto-hide controls after 2 seconds when playing
    LaunchedEffect(controlsVisible, videoPlaying, lastInteractionTime) {
        if (controlsVisible && videoPlaying) {
            delay(2000)
            controlsVisible = false
        }
    }

    // Keep duration updated if not populated initially
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
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val path = videoPath

        // Scalable Video Content Layer
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
                    scaleX = videoScale
                    scaleY = videoScale
                    translationX = videoOffset.x
                    translationY = videoOffset.y
                }
                .transformable(videoTransformState),
        ) {
            // Video View Layer
            if (path != null) {
                AndroidView(
                    factory = { viewContext ->
                        VideoView(viewContext).apply {
                            videoView = this
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setMediaController(null)
                            setOnPreparedListener { mp ->
                                mediaPlayer = mp
                                isPrepared = true
                                duration = this.duration.coerceAtLeast(0)
                                playbackPositionState.floatValue = 0f
                                start()
                                videoPlaying = true
                                isCompleted = false
                            }
                            setOnCompletionListener {
                                videoPlaying = false
                                isCompleted = true
                                controlsVisible = true
                                playbackPositionState.floatValue = duration.toFloat().coerceAtLeast(0f)
                            }
                        }
                    },
                    update = { view ->
                        if (view.tag != path) {
                            view.tag = path
                            isPrepared = false
                            view.setVideoPath(path)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Thumbnail cover shown only before prepared
            if (!isPrepared) {
                RemoteImage(
                    url = message.thumbnailUrl ?: message.mediaUrl,
                    contentDescription = message.text,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    messageType = MessageType.VIDEO,
                    message = message,
                    placeholderColor = Color.Black,
                )
            }

            // Transparent Gesture Layer over VideoView inside the transformed container
            // Single tap: toggle controls
            // Double tap: reset zoom if zoomed in, otherwise toggle play / pause
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onTap = {
                                controlsVisible = !controlsVisible
                                if (controlsVisible) {
                                    lastInteractionTime = System.currentTimeMillis()
                                    if (isCompleted) {
                                        playbackPositionState.floatValue = duration.toFloat().coerceAtLeast(0f)
                                    } else {
                                        videoView?.let { vv ->
                                            val pos = vv.currentPosition.toFloat().coerceAtLeast(0f)
                                            if (pos > 0f) {
                                                playbackPositionState.floatValue = pos
                                            }
                                        }
                                    }
                                }
                            },
                            onDoubleTap = {
                                if (videoScale > 1f) {
                                    videoScale = 1f
                                    videoOffset = Offset.Zero
                                } else {
                                    togglePlayPause()
                                }
                                lastInteractionTime = System.currentTimeMillis()
                            },
                        )
                    },
            )
        }

        // Loading spinner when video is preparing or downloading
        if (path == null || !isPrepared) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Controls Overlay Layer (Top Bar, Center Play/Pause/Replay, Bottom Bar)
        AnimatedVisibility(
            visible = controlsVisible && isPrepared,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top Bar
                MediaViewerTopBar(
                    title = message.memberName,
                    pageIndicator = null,
                    isDownloading = isDownloading,
                    isDownloaded = isDownloaded,
                    onClose = onClose,
                    onDownload = {
                        onSaveDownload()
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                // Center Play/Pause/Replay Button
                IconButton(
                    onClick = {
                        togglePlayPause()
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                ) {
                    val icon = when {
                        isCompleted -> Icons.Rounded.Replay
                        videoPlaying -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    }
                    Icon(
                        icon,
                        contentDescription = if (videoPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }

                // Bottom Bar (Play/Pause, Time, Slider, Duration)
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
                                    if (mp != null) mp.start() else videoView?.start()
                                    videoPlaying = true
                                    wasPlayingBeforeDrag = false
                                }
                            }
                        }

                        val timeoutJob = coroutineScope.launch {
                            delay(800)
                            finishSeek()
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mp != null) {
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

@Composable
private fun VideoBottomBar(
    videoView: VideoView?,
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
    } else 0f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                ),
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = when {
                    isCompleted -> Icons.Rounded.Replay
                    videoPlaying -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                },
                contentDescription = if (videoPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }

        Text(
            text = formatTimeMs(displayPosition),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum",
            ),
            color = Color.White,
            fontSize = 12.sp,
        )

        Slider(
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
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = BrandPurple,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )

        Text(
            text = formatTimeMs(duration),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum",
            ),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
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
