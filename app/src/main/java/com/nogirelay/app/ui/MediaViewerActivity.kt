package com.nogirelay.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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

class MediaViewerActivity : ComponentActivity(), RefreshRatePolicyOwner {
    private var viewerType: MessageType = MessageType.IMAGE

    override fun refreshRatePolicy(): RefreshRatePolicy = RefreshRatePolicy.Maximum

    companion object {
        private const val EXTRA_IMAGE_URL = "image_url"
        private const val EXTRA_IMAGE_TITLE = "image_title"
        private const val EXTRA_IMAGE_OWNER = "image_owner"
        private const val EXTRA_IMAGE_ID = "image_id"

        fun imageIntent(
            context: Context,
            url: String,
            title: String,
            ownerName: String,
            imageId: String,
        ): Intent = Intent(context, MediaViewerActivity::class.java).apply {
            putExtra(EXTRA_IMAGE_URL, url)
            putExtra(EXTRA_IMAGE_TITLE, title)
            putExtra(EXTRA_IMAGE_OWNER, ownerName)
            putExtra(EXTRA_IMAGE_ID, imageId)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppGraph.initialize(this)
        val messageId = intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID)
        val message = messageId?.let(AppGraph.database::find) ?: intent.getStringExtra(EXTRA_IMAGE_URL)?.let { imageUrl ->
            RelayMessage(
                id = intent.getStringExtra(EXTRA_IMAGE_ID).orEmpty().ifBlank { imageUrl.hashCode().toString() },
                memberId = "",
                memberName = intent.getStringExtra(EXTRA_IMAGE_OWNER).orEmpty().ifBlank { "BLOG" },
                memberAvatarUrl = null,
                phoneImageUrl = null,
                type = MessageType.IMAGE,
                text = intent.getStringExtra(EXTRA_IMAGE_TITLE),
                mediaUrl = imageUrl,
                thumbnailUrl = null,
                durationSeconds = null,
                sentAt = "",
                incomingCallFrom = null,
                ringtoneUrl = null,
                isPlayed = false,
            )
        }
        if (message == null) {
            finish()
            return
        }
        viewerType = message.type

        setContent {
            NogiRelayTheme(darkTheme = true) {
                MediaViewer(message = message, onClose = ::finish)
            }
        }
    }
}

@Composable
private fun MediaViewer(message: RelayMessage, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadScope = rememberCoroutineScope()
    var waitingForStoragePermission by remember { mutableStateOf(false) }

    val saveDownload: () -> Unit = {
        downloadScope.launch(Dispatchers.IO) {
            val result = runCatching { MediaDownloader.saveToDownloads(context, message) }
            withContext(Dispatchers.Main) {
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
        val shouldSave = waitingForStoragePermission
        waitingForStoragePermission = false
        if (granted && shouldSave) {
            saveDownload()
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存到 Download 文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    val requestSave: () -> Unit = {
        if (MediaDownloader.needsLegacyWritePermission(context)) {
            waitingForStoragePermission = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveDownload()
        }
    }

    when (message.type) {
        MessageType.IMAGE -> ImageViewer(
            message = message,
            onClose = onClose,
            onSaveDownload = requestSave,
        )

        MessageType.VIDEO -> VideoPlayer(
            message = message,
            onClose = onClose,
            onSaveDownload = requestSave,
        )

        else -> Unit
    }
}

@Composable
private fun ImageViewer(
    message: RelayMessage,
    onClose: () -> Unit,
    onSaveDownload: () -> Unit,
) {
    var imageScale by remember(message.id) { mutableFloatStateOf(1f) }
    var imageOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    var imageViewport by remember(message.id) { mutableStateOf(IntSize.Zero) }
    val imageTransformState = rememberTransformableState { zoomChange, panChange, _ ->
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
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                .transformable(imageTransformState)
                .pointerInput(message.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (imageScale > 1f) {
                                imageScale = 1f
                                imageOffset = Offset.Zero
                            } else {
                                imageScale = 2.5f
                            }
                        },
                    )
                },
            contentScale = ContentScale.Fit,
        )

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = message.memberName.ifBlank { "乃木坂46" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(
                onClick = onSaveDownload,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .size(38.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "保存到本地",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoPlayer(
    message: RelayMessage,
    onClose: () -> Unit,
    onSaveDownload: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var videoPath by remember(message.id) { mutableStateOf<String?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var videoPlaying by remember { mutableStateOf(false) }
    var isCompleted by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var currentPosition by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

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

    // Track playback progress
    LaunchedEffect(videoPlaying, isPrepared) {
        while (isActive) {
            videoView?.let { vv ->
                if (!isDraggingSlider && vv.isPlaying) {
                    currentPosition = vv.currentPosition
                }
                val dur = vv.duration
                if (dur > 0) {
                    duration = dur
                }
            }
            delay(200)
        }
    }

    val togglePlayPause = {
        videoView?.let { vv ->
            if (isCompleted) {
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
                            setOnPreparedListener {
                                isPrepared = true
                                duration = this.duration.coerceAtLeast(0)
                                start()
                                videoPlaying = true
                                isCompleted = false
                            }
                            setOnCompletionListener {
                                videoPlaying = false
                                isCompleted = true
                                controlsVisible = true
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                            ),
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color.White.copy(alpha = 0.15f), CircleShape),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Text(
                            text = message.memberName.ifBlank { "乃木坂46" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Download Button
                    IconButton(
                        onClick = {
                            onSaveDownload()
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(38.dp)
                            .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    ) {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = "保存到本地",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

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
                val displayPosition = if (isDraggingSlider) dragPosition.toInt() else currentPosition
                val progressFraction = if (duration > 0) (displayPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
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
                        onClick = {
                            togglePlayPause()
                            lastInteractionTime = System.currentTimeMillis()
                        },
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
                            isDraggingSlider = true
                            dragPosition = frac * duration
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        onValueChangeFinished = {
                            val targetMs = dragPosition.toInt()
                            videoView?.seekTo(targetMs)
                            currentPosition = targetMs
                            if (isCompleted && targetMs < duration) {
                                isCompleted = false
                            }
                            isDraggingSlider = false
                            lastInteractionTime = System.currentTimeMillis()
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
