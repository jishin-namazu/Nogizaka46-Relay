package com.nogirelay.app.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.nogirelay.app.MainActivity
import com.nogirelay.app.R
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.notification.NotificationChannels
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays voice messages with Media3. The [MediaSession] gives playback a
 * system presence: a media notification, lock-screen and headset controls,
 * and pausing when headphones are unplugged. Voice keeps the call-like
 * route (earpiece unless the speaker is chosen) of the original player.
 */
@OptIn(UnstableApi::class)
class VoicePlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var speakerOn = false
    private var markedPlayed = false
    private var outputRoutingJob: Job? = null
    private var progressJob: Job? = null
    private var loadJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> markCurrentPlayed()
                Player.STATE_ENDED -> {
                    sendBroadcast(Intent(ACTION_PLAYBACK_FINISHED).setPackage(packageName))
                    if (loadJob?.isActive == true) releasePlayer() else stopPlayback()
                    return
                }
            }
            publishPlaybackState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publishPlaybackState()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = publishPlaybackState()

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Voice playback failed", error)
            stopPlayback()
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppGraph.initialize(this)
        audioManager = getSystemService(AudioManager::class.java)
        player = ExoPlayer.Builder(this)
            // Voice-call usage: earpiece routing and transient focus, which
            // ExoPlayer requests and abandons around playback itself.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_VOICE_COMMUNICATION)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(listener) }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent(null))
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NotificationChannels.PLAYBACK)
                .setChannelName(R.string.channel_playback)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_message) },
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopPlayback()
            ACTION_SET_SPEAKER -> {
                speakerOn = intent.getBooleanExtra(EXTRA_SPEAKER_ON, false)
                publishPlaybackState()
                outputRoutingJob?.cancel()
                outputRoutingJob = serviceScope.launch { setAudioOutput(speakerOn, fadeOnLegacyAndroid = true) }
            }
            ACTION_SEEK -> {
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
                val positionMs = intent.getIntExtra(EXTRA_POSITION_MS, -1)
                if (messageId != null && messageId == currentMessageId && positionMs >= 0) {
                    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
                    player.seekTo(positionMs.toLong().coerceIn(0L, duration))
                    publishPlaybackState()
                }
            }
            ACTION_PLAY -> intent.getStringExtra(EXTRA_MESSAGE_ID)?.let { id ->
                // A simulated call has no pause button and routes to the
                // earpiece anyway, so unplugging headphones must not pause it.
                player.setHandleAudioBecomingNoisy(!intent.getBooleanExtra(EXTRA_CALL, false))
                togglePlayback(id)
            }
            // Media buttons and the session's own commands.
            else -> return super.onStartCommand(intent, flags, startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releasePlayer()
        session.release()
        player.removeListener(listener)
        player.release()
        super.onDestroy()
    }

    private fun togglePlayback(messageId: String) {
        loadJob?.cancel()
        loadJob = null
        if (currentMessageId == messageId && player.mediaItemCount > 0) {
            if (player.isPlaying) player.pause() else player.play()
            publishPlaybackState()
            return
        }
        loadJob = serviceScope.launch {
            val loaded = runCatching {
                withContext(Dispatchers.IO) {
                    val message = AppGraph.messages.find(messageId) ?: return@withContext null
                    if (message.mediaUrl.isNullOrBlank()) return@withContext null
                    MediaDownloader.enqueueIfNeeded(this@VoicePlaybackService, message)?.let { message to it }
                }
            }.getOrNull()
            if (!isActive) return@launch
            if (loaded == null) {
                stopPlayback()
                return@launch
            }
            play(loaded.first, loaded.second)
        }
    }

    private suspend fun play(message: RelayMessage, mediaFile: File) {
        if (currentMessageId != message.id) releasePlayer()
        currentMessageId = message.id
        speakerOn = false
        markedPlayed = false
        publishPlaybackState()

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setAudioOutput(speakerOn = false, fadeOnLegacyAndroid = false)
        player.volume = 1f
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(message.id)
                .setUri(Uri.fromFile(mediaFile))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(message.memberName)
                        .setArtist(getString(R.string.voice_message))
                        .setArtworkUri(message.memberAvatarUrl?.takeIf(String::isNotBlank)?.toUri())
                        .build(),
                )
                .build(),
        )
        session.setSessionActivity(openAppIntent(message.id))
        player.prepare()
        player.play()
    }

    private fun markCurrentPlayed() {
        val id = currentMessageId ?: return
        if (markedPlayed) return
        markedPlayed = true
        serviceScope.launch(AppGraph.dispatchers.databaseWrite) { AppGraph.messages.markPlayed(id) }
    }

    private fun stopPlayback() {
        loadJob?.cancel()
        loadJob = null
        releasePlayer()
        stopSelf()
    }

    private fun publishPlaybackState() {
        val id = currentMessageId
        if (id == null || player.mediaItemCount == 0) {
            progressJob?.cancel()
            progressJob = null
            if (_playbackState.value != VoicePlaybackState()) _playbackState.value = VoicePlaybackState()
            return
        }
        val isPlayingNow = player.isPlaying
        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        val next = VoicePlaybackState(
            messageId = id,
            isPlaying = isPlayingNow,
            positionMs = player.currentPosition.coerceAtLeast(0L).toInt(),
            durationMs = duration.toInt(),
            speakerOn = speakerOn,
            sampledAtMillis = if (isPlayingNow) SystemClock.elapsedRealtime() else 0L,
        )
        if (_playbackState.value != next) _playbackState.value = next
        if (isPlayingNow && progressJob?.isActive != true) {
            progressJob = serviceScope.launch {
                while (isActive) {
                    delay(PROGRESS_UPDATE_INTERVAL_MS)
                    publishPlaybackState()
                }
            }
        } else if (!isPlayingNow) {
            progressJob?.cancel()
            progressJob = null
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        progressJob = null
        outputRoutingJob?.cancel()
        outputRoutingJob = null
        if (::player.isInitialized) {
            player.stop()
            player.clearMediaItems()
        }
        currentMessageId = null
        _playbackState.value = VoicePlaybackState()
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            run { audioManager.isSpeakerphoneOn = false }
        }
    }

    private suspend fun setAudioOutput(speakerOn: Boolean, fadeOnLegacyAndroid: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (speakerOn) {
                val speaker = audioManager.availableCommunicationDevices
                    .find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null && !audioManager.setCommunicationDevice(speaker)) {
                    Log.d(TAG, "Failed to select built-in speaker")
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }
        // Before Android 12 the route switch clicks; fade around it.
        if (fadeOnLegacyAndroid) player.volume = 0f
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = speakerOn }
        if (fadeOnLegacyAndroid) {
            for (step in 1..10) {
                delay(LEGACY_ROUTE_FADE_STEP_MS)
                player.volume = step / 10f
            }
        }
    }

    /** Opens the app, on the member conversation when [messageId] is set. */
    private fun openAppIntent(messageId: String?): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            messageId?.let { putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, it) }
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "VoicePlayback"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val ACTION_PLAY = "com.nogirelay.app.PLAY_VOICE"
        const val ACTION_STOP = "com.nogirelay.app.STOP_VOICE"
        const val ACTION_SEEK = "com.nogirelay.app.SEEK_VOICE"
        const val ACTION_SET_SPEAKER = "com.nogirelay.app.SET_SPEAKER"
        const val ACTION_PLAYBACK_FINISHED = "com.nogirelay.app.VOICE_FINISHED"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_SPEAKER_ON = "speaker_on"
        /** Set by the incoming call screen: playback belongs to a call. */
        const val EXTRA_CALL = "call"
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L
        private const val LEGACY_ROUTE_FADE_STEP_MS = 150L
        private const val REQUEST_OPEN_APP = 4101

        private val _playbackState = MutableStateFlow(VoicePlaybackState())
        val playbackState: StateFlow<VoicePlaybackState> = _playbackState.asStateFlow()

        @Volatile
        private var currentMessageId: String? = null

        fun seek(context: Context, messageId: String, positionMs: Int) {
            context.startService(
                Intent(context, VoicePlaybackService::class.java).apply {
                    action = ACTION_SEEK
                    putExtra(EXTRA_MESSAGE_ID, messageId)
                    putExtra(EXTRA_POSITION_MS, positionMs)
                },
            )
        }
    }
}
