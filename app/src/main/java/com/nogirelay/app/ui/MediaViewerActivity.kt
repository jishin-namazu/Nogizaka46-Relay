package com.nogirelay.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.media.MediaMetadataRetriever
import android.view.Surface
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.RefreshRatePolicy
import com.nogirelay.app.performance.RefreshRatePolicyOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var videoPlaying by remember { mutableStateOf(false) }
    var videoPath by remember(message.id) { mutableStateOf<String?>(null) }
    var videoFps by remember(message.id) { mutableFloatStateOf(0f) }
    var imageScale by remember(message.id) { mutableFloatStateOf(1f) }
    var imageOffset by remember(message.id) { mutableStateOf(Offset.Zero) }
    var waitingForStoragePermission by remember { mutableStateOf(false) }
    val imageTransformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (imageScale * zoomChange).coerceIn(1f, 5f)
        // Pointer deltas arrive in the image's transformed coordinate space.
        // Scaling the pan delta keeps the image under the user's fingers after zooming.
        imageOffset += panChange * newScale
        imageScale = newScale
        if (newScale <= 1f) imageOffset = Offset.Zero
    }

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

    if (message.type == MessageType.VIDEO) {
        LaunchedEffect(message.id, message.mediaUrl) {
            val video = runCatching {
                withContext(Dispatchers.IO) {
                    val path = MediaDownloader.enqueueIfNeeded(context, message)?.absolutePath
                    val fps = path?.let { localPath ->
                        MediaMetadataRetriever().run {
                            try {
                                setDataSource(localPath)
                                extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                            } finally {
                                release()
                            }
                        }
                    } ?: 0f
                    path to fps
                }
            }.getOrNull()
            videoPath = video?.first
            videoFps = video?.second ?: 0f
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (message.type) {
            MessageType.IMAGE -> RemoteImage(
                url = message.mediaUrl,
                contentDescription = message.text,
                placeholderColor = Color.Black,
                modifier = Modifier
                    .fillMaxSize()
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

            MessageType.VIDEO -> {
                val path = videoPath
                Box(Modifier.fillMaxSize()) {
                    if (path != null) {
                        AndroidView(
                            factory = { viewContext ->
                                VideoView(viewContext).apply {
                                    videoView = this
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                    val controller = MediaController(viewContext)
                                    controller.setAnchorView(this)
                                    setMediaController(controller)
                                    setOnPreparedListener {
                                        it.isLooping = false
                                        start()
                                        videoPlaying = true
                                    }
                                    setOnCompletionListener { videoPlaying = false }
                                }
                            },
                            update = { view ->
                                if (view.tag != path) {
                                    view.tag = path
                                    view.setVideoPath(path)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (!videoPlaying) {
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

                    if (path == null) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            else -> Unit
        }

        if (message.type == MessageType.VIDEO && !videoPlaying) {
            IconButton(
                onClick = {
                    videoView?.let { view ->
                        if (view.isPlaying) {
                            view.pause()
                            videoPlaying = false
                        } else {
                            view.start()
                            videoPlaying = true
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.62f), androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(
                    imageVector = if (videoPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (videoPlaying) "暂停视频" else "播放视频",
                    tint = Color.White,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
        ) {
            IconButton(
                onClick = {
                    if (MediaDownloader.needsLegacyWritePermission(context)) {
                        waitingForStoragePermission = true
                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        saveDownload()
                    }
                },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = "保存到本地", tint = Color.White)
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}
