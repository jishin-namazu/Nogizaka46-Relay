package com.nogirelay.app.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import android.view.TextureView

/** A Fit-scaled video texture that participates in Compose clipping and transforms. */
internal class TextureVideoView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var path: String? = null
    private var prepared = false
    private var playWhenReady = false
    private var resumePosition = 0
    private var preparedListener: MediaPlayer.OnPreparedListener? = null
    private var completionListener: MediaPlayer.OnCompletionListener? = null
    private var infoListener: MediaPlayer.OnInfoListener? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()
    private var hasAudioFocus = false
    private val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { change ->
            if (change < 0) pause()
        }
        .build()

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    val isPlaying: Boolean get() = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
    val duration: Int get() = if (prepared) runCatching { player?.duration ?: 0 }.getOrDefault(0) else 0
    val currentPosition: Int get() = if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0

    fun setOnPreparedListener(listener: MediaPlayer.OnPreparedListener) { preparedListener = listener }
    fun setOnCompletionListener(listener: MediaPlayer.OnCompletionListener) { completionListener = listener }
    fun setOnInfoListener(listener: MediaPlayer.OnInfoListener) { infoListener = listener }

    fun setVideoPath(value: String) {
        if (path == value && player != null) return
        releasePlayer()
        path = value
        resumePosition = 0
        preparePlayer()
    }

    fun start() {
        playWhenReady = true
        if (!prepared) return
        if (!hasAudioFocus) {
            hasAudioFocus = audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (hasAudioFocus) {
            player?.start()
            keepScreenOn = true
        }
    }

    fun pause() {
        playWhenReady = false
        if (isPlaying) player?.pause()
        releaseAudioFocus()
    }

    fun seekTo(position: Int) {
        resumePosition = position
        if (prepared) player?.seekTo(position)
    }

    fun stopPlayback() {
        playWhenReady = false
        path = null
        releasePlayer()
    }

    private fun preparePlayer() {
        val videoPath = path ?: return
        val texture = surfaceTexture ?: return
        if (!isAvailable || player != null) return
        val videoSurface = Surface(texture).also { surface = it }
        val media = MediaPlayer().also { player = it }
        media.setAudioAttributes(audioAttributes)
        media.setSurface(videoSurface)
        media.setOnPreparedListener {
            if (player !== it) return@setOnPreparedListener
            prepared = true
            updateFitTransform(it.videoWidth, it.videoHeight)
            if (resumePosition > 0) it.seekTo(resumePosition)
            preparedListener?.onPrepared(it)
            if (playWhenReady) start()
        }
        media.setOnVideoSizeChangedListener { _, width, height -> updateFitTransform(width, height) }
        media.setOnCompletionListener {
            playWhenReady = false
            releaseAudioFocus()
            completionListener?.onCompletion(it)
        }
        media.setOnInfoListener { mp, what, extra -> infoListener?.onInfo(mp, what, extra) ?: false }
        media.setOnErrorListener { _, _, _ ->
            playWhenReady = false
            releasePlayer()
            true
        }
        try {
            media.setDataSource(videoPath)
            media.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun updateFitTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return
        val fit = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        setTransform(Matrix().apply {
            setScale(videoWidth * fit / width, videoHeight * fit / height, width / 2f, height / 2f)
        })
    }

    private fun releasePlayer() {
        releaseAudioFocus()
        prepared = false
        player?.release()
        player = null
        surface?.release()
        surface = null
    }

    private fun releaseAudioFocus() {
        keepScreenOn = false
        if (hasAudioFocus) audioManager.abandonAudioFocusRequest(audioFocus)
        hasAudioFocus = false
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = preparePlayer()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        player?.takeIf { prepared }?.let { updateFitTransform(it.videoWidth, it.videoHeight) }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        resumePosition = currentPosition
        releasePlayer()
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
}
