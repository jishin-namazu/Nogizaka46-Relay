package com.nogirelay.app

import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.nogirelay.app.blog.BlogNotifier
import com.nogirelay.app.blog.BlogPrewarmer
import com.nogirelay.app.data.BlogReadTracker
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.call.IncomingCallActivity
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.call.OfficialProximityScreenControl
import com.nogirelay.app.call.ProximityScreenControl
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.api.ApiConfig
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.notification.NotificationChannels
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.MediaViewerActivity
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.navigation.RelayApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val syncRequests = MutableStateFlow(0L)
    private val notificationMessageIds = MutableStateFlow<String?>(null)
    private val notificationBlogIds = MutableStateFlow<String?>(null)
    private lateinit var proximityControl: ProximityScreenControl
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch theme mirrors the official app splash until Compose draws its first frame.
        setTheme(R.style.Theme_NogiRelay)
        AppGraph.initialize(this)
        val current = AppGraph.settings.read()
        if (current.relayUrl.isBlank() && ApiConfig.BASE_URL.isNotBlank()) {
            AppGraph.settings.save(
                current.copy(
                    relayUrl = ApiConfig.BASE_URL,
                    accessToken = if (current.accessToken.isBlank()) ApiConfig.ACCESS_TOKEN else current.accessToken,
                ),
            )
        }
        NotificationChannels.create(this)
        AppGraph.database.deleteTestMessages().forEach { IncomingCallNotifier.cancel(this, it) }
        if (AppGraph.settings.read().relayUrl.isNotBlank()) {
            PushRegistrar.registerCurrentToken(this)
        }
        Log.d("MainActivity", "Calling TranslationManager.enqueue from onCreate")
        TranslationManager.enqueue(this)
        BlogTranslationManager.enqueuePending(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { BlogPrewarmer.prewarm() }
        }
        
        proximityControl = OfficialProximityScreenControl(this)
        audioManager = getSystemService(AudioManager::class.java)
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        notificationMessageIds.value = intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID)
        notificationBlogIds.value = intent.getStringExtra(BlogNotifier.EXTRA_BLOG_ID)

        setContent {
            NogiRelayTheme {
                RelayApp(
                    notificationMessageIds = notificationMessageIds,
                    notificationBlogIds = notificationBlogIds,
                    onNotificationMessageHandled = { handledId ->
                        notificationMessageIds.compareAndSet(handledId, null)
                    },
                    onNotificationBlogHandled = { handledId ->
                        notificationBlogIds.compareAndSet(handledId, null)
                    },
                    onOpenMedia = ::openMedia,
                    onPlayVoice = ::playVoice,
                    onTestCall = ::testCall,
                    syncRequests = syncRequests,
                    onManualSync = { syncRequests.update { it + 1 } },
                    onUpdateProximity = ::updateProximityLock,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppGraph.notifyDataChanged()
        syncRequests.update { it + 1 }
    }

    override fun onResume() {
        super.onResume()
        MessageReadTracker.setAppVisible(true)
        BlogReadTracker.setAppVisible(true)
        AppGraph.notifyDataChanged()
    }

    override fun onPause() {
        MessageReadTracker.setAppVisible(false)
        BlogReadTracker.setAppVisible(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID)?.let {
            notificationMessageIds.value = it
        }
        intent.getStringExtra(BlogNotifier.EXTRA_BLOG_ID)?.let {
            notificationBlogIds.value = it
        }
    }

    override fun onDestroy() {
        proximityControl.close()
        super.onDestroy()
    }

    private fun updateProximityLock(playback: VoicePlaybackState) {
        val speakerOn = playback.speakerOn
        val isExternalAudioConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        val shouldEnable = playback.isPlaying && !speakerOn && !isExternalAudioConnected
        proximityControl.setEnabled(shouldEnable)
    }

    private fun openMedia(message: RelayMessage) {
        startActivity(Intent(this, MediaViewerActivity::class.java).putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, message.id))
    }

    private fun playVoice(message: RelayMessage) {
        startService(
            Intent(this, VoicePlaybackService::class.java).apply {
                action = VoicePlaybackService.ACTION_PLAY
                putExtra(VoicePlaybackService.EXTRA_MESSAGE_ID, message.id)
            },
        )
    }

    private fun testCall() {
        val id = "test-call-${System.currentTimeMillis()}"
        val message = RelayMessage(
            id = id,
            memberId = "test",
            memberName = "池田 瑛紗",
            memberAvatarUrl = null,
            phoneImageUrl = Uri.parse("android.resource://$packageName/${R.drawable.ikeda_teresa_phone_image}").toString(),
            type = MessageType.AUDIO,
            text = "全屏来电测试",
            mediaUrl = Uri.parse("android.resource://$packageName/${R.raw.test_voice}").toString(),
            thumbnailUrl = null,
            durationSeconds = null,
            sentAt = Instant.now().toString(),
            incomingCallFrom = "池田 瑛紗",
            ringtoneUrl = null,
            isPlayed = false,
        )
        AppGraph.database.insert(message)
        startActivity(
            Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallNotifier.EXTRA_MESSAGE_ID, message.id)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
        )
    }
}
