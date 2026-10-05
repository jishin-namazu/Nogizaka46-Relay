package com.nogirelay.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.MediaSession
import java.io.File

/**
 * A Fit-scaled video texture that participates in Compose clipping and
 * transforms, played by ExoPlayer.
 *
 * Its [MediaSession] gives the video the same system presence as voice
 * messages: headset and Bluetooth buttons, the assistant, audio focus and
 * pausing when headphones are unplugged. Pauses and resumes that come from
 * the system reach the screen through [onPlayingChanged].
 */
@OptIn(UnstableApi::class)
internal class TextureVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var surface: Surface? = null
    private var path: String? = null
    private var prepared = false
    private var videoSize = VideoSize.UNKNOWN
    private var pendingSeekDone: (() -> Unit)? = null

    /** First time the player is ready to play. */
    var onPrepared: () -> Unit = {}

    /** The decoded video size, for the page's aspect ratio. */
    var onVideoSize: (width: Int, height: Int) -> Unit = { _, _ -> }

    /** The first frame reached the screen. */
    var onFirstFrame: () -> Unit = {}

    /** Whether playback is wanted, including pauses from headset buttons, focus loss or unplugging. */
    var onPlayingChanged: (Boolean) -> Unit = {}

    var onCompletion: () -> Unit = {}

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    if (!prepared) {
                        prepared = true
                        onPrepared()
                    }
                    pendingSeekDone?.let {
                        pendingSeekDone = null
                        it()
                    }
                }
                Player.STATE_ENDED -> {
                    player?.pause()
                    onCompletion()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            onPlayingChanged(playWhenReady && player?.playbackState != Player.STATE_ENDED)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            keepScreenOn = isPlaying
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoSize = size
            if (size.width > 0 && size.height > 0) onVideoSize(displayWidth(size), size.height)
            updateFitTransform()
        }

        override fun onRenderedFirstFrame() = onFirstFrame()

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            pendingSeekDone = null
            onPlayingChanged(false)
        }
    }

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    val isPlaying: Boolean get() = player?.isPlaying == true
    val duration: Int get() = player?.duration?.takeIf { prepared && it != C.TIME_UNSET }?.toInt() ?: 0
    val currentPosition: Int get() = if (prepared) player?.currentPosition?.toInt() ?: 0 else 0

    /** Loads a local video; [title] names it to the system (headset controls, assistant). */
    fun setVideoPath(value: String, title: String? = null) {
        if (path == value && player != null) return
        releasePlayer()
        path = value
        val exo = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exo.addListener(listener)
        exo.setSeekParameters(SeekParameters.EXACT)
        surface?.let(exo::setVideoSurface)
        exo.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.fromFile(File(value)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .build(),
        )
        exo.prepare()
        player = exo
        // Several viewer pages may hold a player at once; each needs its own session id.
        session = MediaSession.Builder(context, exo)
            .setId("video-${System.identityHashCode(this)}-${value.hashCode()}")
            .build()
    }

    fun start() {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
    }

    fun pause() {
        player?.pause()
    }

    /** Seeks exactly; [onComplete] runs once the frame at [position] is ready (or at once without a player). */
    fun seekTo(position: Int, onComplete: () -> Unit = {}) {
        val exo = player
        if (exo == null || !prepared) {
            onComplete()
            return
        }
        pendingSeekDone = onComplete
        exo.seekTo(position.toLong().coerceAtLeast(0L))
    }

    fun stopPlayback() {
        path = null
        releasePlayer()
    }

    private fun displayWidth(size: VideoSize): Int = (size.width * size.pixelWidthHeightRatio).toInt()

    private fun updateFitTransform() {
        val videoWidth = displayWidth(videoSize)
        val videoHeight = videoSize.height
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val fit = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * fit / width, videoHeight * fit / height, width / 2f, height / 2f)
        })
    }

    private fun releasePlayer() {
        pendingSeekDone = null
        prepared = false
        videoSize = VideoSize.UNKNOWN
        keepScreenOn = false
        session?.release()
        session = null
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        val videoSurface = Surface(texture).also { surface = it }
        player?.setVideoSurface(videoSurface)
        updateFitTransform()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = updateFitTransform()

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // The player keeps its position; a new surface resumes the picture.
        player?.clearVideoSurface()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
