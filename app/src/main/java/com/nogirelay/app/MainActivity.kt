package com.nogirelay.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.produceState
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nogirelay.app.call.FullScreenPermission
import com.nogirelay.app.call.IncomingCallActivity
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.call.OverlayPermission
import com.nogirelay.app.blog.BlogNotifier
import com.nogirelay.app.blog.BlogScreen
import com.nogirelay.app.blog.BlogMediaDownloader
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.AppSettings
import com.nogirelay.app.data.BlogReadTracker
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.api.ApiConfig
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.notification.NotificationChannels
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.AIModel
import com.nogirelay.app.translation.AIProviderFactory
import com.nogirelay.app.translation.AIProviderType
import com.nogirelay.app.translation.JsonOutputSupport
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.translation.normalizeTranslationText
import com.nogirelay.app.translation.substituteNickname
import com.nogirelay.app.ui.MediaViewerActivity
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SignalCoral
import com.nogirelay.app.ui.SignalGreen
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.highlightMatches
import com.nogirelay.app.ui.withoutTextPresentationSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val syncRequests = MutableStateFlow(0L)
    private val notificationMessageIds = MutableStateFlow<String?>(null)
    private val notificationBlogIds = MutableStateFlow<String?>(null)
    private lateinit var proximityControl: com.nogirelay.app.call.ProximityScreenControl
    private lateinit var audioManager: android.media.AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch theme mirrors the official app splash until Compose draws its first frame.
        setTheme(R.style.Theme_NogiRelay)
        AppGraph.initialize(this)
        if (BuildConfig.SIMPLE_UI) {
            // The simplified build hides the relay fields, so the values baked in
            // at build time stay authoritative even if older settings existed.
            val current = AppGraph.settings.read()
            AppGraph.settings.save(current.copy(relayUrl = ApiConfig.BASE_URL, accessToken = ApiConfig.ACCESS_TOKEN))
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
            runCatching { com.nogirelay.app.blog.BlogPrewarmer.prewarm() }
        }
        
        proximityControl = com.nogirelay.app.call.OfficialProximityScreenControl(this)
        audioManager = getSystemService(android.media.AudioManager::class.java)
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
        MessageReadTracker.setAppVisible(true)
        BlogReadTracker.setAppVisible(true)
        AppGraph.notifyDataChanged()
        syncRequests.update { it + 1 }
    }

    override fun onStop() {
        MessageReadTracker.setAppVisible(false)
        BlogReadTracker.setAppVisible(false)
        super.onStop()
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
        val isExternalAudioConnected = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
            .any { device ->
                device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
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

private enum class AppTab(val label: String) { HOME("主页"), MESSAGES("消息"), BLOG("博客") }

/** Corner radius of the bottom-tab indicator: raise it towards 16.dp for a pill, lower it for a sharper square. */
private val NavigationTabIndicatorShape = RoundedCornerShape(10.dp)

/**
 * Rounded-square shape shared by the app's buttons. Material 3 gives buttons a full pill by default;
 * 12.dp on a 40dp button keeps the same corner-to-height ratio as the bottom-tab indicator.
 */
private val RelayControlShape = RoundedCornerShape(12.dp)

/**
 * Bottom tab that keeps the Material 3 navigation-bar metrics but swaps the default pill-shaped
 * selection for a rounded square. The bounded ripple sits on the same clipped box, so the tap
 * feedback matches the selected highlight instead of pushing the default stadium shape back in.
 */
@Composable
private fun RowScope.RelayNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    imageVector: ImageVector,
    badgeCount: Int,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            // Whole tab stays tappable; only the indicator box below paints the ripple.
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 32.dp)
                .clip(NavigationTabIndicatorShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                )
                .indication(
                    interactionSource = interactionSource,
                    indication = rememberRipple(bounded = true),
                ),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(
                badge = {
                    if (badgeCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text(unreadBadgeLabel(badgeCount))
                        }
                    }
                },
            ) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = label,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * [IconButton] equivalent carrying the app's rounded-square shape: Material 3 locks [IconButton]
 * itself to a circle and exposes no shape parameter, so the container is drawn here instead.
 * The 48dp box matches [IconButton]'s touch target exactly.
 */
@Composable
private fun RelayIconButton(
    onClick: () -> Unit,
    imageVector: ImageVector,
    contentDescription: String?,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RelayControlShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelayApp(
    notificationMessageIds: StateFlow<String?>,
    notificationBlogIds: StateFlow<String?>,
    onNotificationMessageHandled: (String) -> Unit,
    onNotificationBlogHandled: (String) -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onTestCall: () -> Unit,
    syncRequests: StateFlow<Long>,
    onManualSync: () -> Unit,
    onUpdateProximity: (VoicePlaybackState) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val initialMessageId by notificationMessageIds.collectAsState()
    val initialBlogId by notificationBlogIds.collectAsState()
    var tab by remember {
        mutableStateOf(
            when {
                initialBlogId != null -> AppTab.BLOG
                initialMessageId != null -> AppTab.MESSAGES
                else -> AppTab.HOME
            },
        )
    }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var fullScreenGranted by remember { mutableStateOf(FullScreenPermission.canUse(context)) }
    var overlayGranted by remember { mutableStateOf(OverlayPermission.canUse(context)) }
    val dataVersion by AppGraph.dataVersion.collectAsState()
    var syncing by remember { mutableStateOf(false) }
    var syncLabel by remember { mutableStateOf("") }
    var unreadMessageCount by remember { mutableIntStateOf(0) }
    var unreadBlogCount by remember { mutableIntStateOf(0) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                notificationGranted = hasNotificationPermission(context)
                fullScreenGranted = FullScreenPermission.canUse(context)
                overlayGranted = OverlayPermission.canUse(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(dataVersion) {
        withContext(AppGraph.dispatchers.databaseRead) {
            val msgCount = AppGraph.database.countUnreadMessages()
            val blogCount = AppGraph.database.countUnreadBlogs()
            unreadMessageCount = msgCount
            unreadBlogCount = blogCount
        }
    }

    LaunchedEffect(initialMessageId) {
        if (initialMessageId != null) tab = AppTab.MESSAGES
    }
    LaunchedEffect(initialBlogId) {
        if (initialBlogId != null) tab = AppTab.BLOG
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    LaunchedEffect(syncRequests) {
        syncRequests.collectLatest {
            syncing = true
            val result = runCatching { withContext(Dispatchers.IO) { syncContent(context) } }
            syncing = false
            syncLabel = result.fold(
                onSuccess = { outcome ->
                    if (outcome.messages > 0 || outcome.blogs > 0) {
                        "已同步 ${outcome.messages} 条消息、${outcome.blogs} 篇博客"
                    } else {
                        "消息和博客已是最新"
                    }
                },
                onFailure = { error ->
                    Log.w("NogiRelay", "History sync failed", error)
                    error.message ?: "历史消息同步失败"
                },
            )
            AppGraph.notifyDataChanged()
        }
    }

    LaunchedEffect(dataVersion) {
        withContext(Dispatchers.IO) {
            TranslationManager.enqueue(context)
            BlogTranslationManager.enqueuePending(context)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                AppTab.entries.forEach { item ->
                    RelayNavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = item.label,
                        imageVector = when (item) {
                            AppTab.HOME -> Icons.Rounded.Home
                            AppTab.MESSAGES -> Icons.Rounded.Inbox
                            AppTab.BLOG -> Icons.AutoMirrored.Rounded.Article
                        },
                        badgeCount = when (item) {
                            AppTab.MESSAGES -> unreadMessageCount
                            AppTab.BLOG -> unreadBlogCount
                            else -> 0
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AppTab.entries.forEach { item ->
                val isSelected = (tab == item)
                val alpha by animateFloatAsState(
                    targetValue = if (isSelected) 1f else 0f,
                    animationSpec = tween(durationMillis = 180),
                    label = "tab_fade_${item.name}",
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (isSelected) 1f else 0f)
                        .graphicsLayer {
                            this.alpha = alpha
                        }
                        .background(MaterialTheme.colorScheme.background)
                        .then(
                            if (!isSelected && alpha == 0f) {
                                Modifier.clearAndSetSemantics { }
                            } else {
                                Modifier
                            }
                        ),
                ) {
                    when (item) {
                        AppTab.HOME -> HomeScreen(
                            notificationGranted = notificationGranted,
                            fullScreenGranted = fullScreenGranted,
                            overlayGranted = overlayGranted,
                            dataVersion = dataVersion,
                            onRequestNotifications = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onOpenFullScreenSettings = {
                                FullScreenPermission.settingsIntent(context)?.let(context::startActivity)
                            },
                            onOpenOverlaySettings = {
                                context.startActivity(OverlayPermission.settingsIntent(context))
                            },
                            onTestCall = onTestCall,
                            isSyncing = syncing,
                            syncLabel = syncLabel,
                            onSyncHistory = onManualSync,
                            onSettingsChanged = { AppGraph.notifyDataChanged() },
                        )

                        AppTab.MESSAGES -> MessagesScreen(
                            isActive = isSelected,
                            dataVersion = dataVersion,
                            initialMessageId = initialMessageId,
                            onInitialMessageHandled = onNotificationMessageHandled,
                            onUnreadChanged = { AppGraph.notifyDataChanged() },
                            onOpenMedia = onOpenMedia,
                            onPlayVoice = onPlayVoice,
                            onUpdateProximity = onUpdateProximity,
                        )

                        AppTab.BLOG -> BlogScreen(
                            isActive = isSelected,
                            dataVersion = dataVersion,
                            initialBlogId = initialBlogId,
                            onInitialBlogHandled = onNotificationBlogHandled,
                            onUnreadChanged = { AppGraph.notifyDataChanged() },
                        )
                    }
                }
            }
        }
    }
}

private data class SyncOutcome(val messages: Int, val blogs: Int)

private fun syncContent(context: Context): SyncOutcome {
    val messageResult = runCatching { syncMessagesFromServer(context) }
    val blogResult = runCatching { syncBlogsFromOfficial(context) }
    if (messageResult.isFailure && blogResult.isFailure) {
        throw IllegalStateException(
            "消息同步失败：${messageResult.exceptionOrNull()?.message}；BLOG 同步失败：${blogResult.exceptionOrNull()?.message}",
        )
    }
    messageResult.exceptionOrNull()?.let { Log.w("NogiRelay", "Message sync failed", it) }
    blogResult.exceptionOrNull()?.let { Log.w("NogiRelay", "BLOG sync failed", it) }
    return SyncOutcome(messageResult.getOrDefault(0), blogResult.getOrDefault(0))
}

private fun syncBlogsFromOfficial(context: Context): Int {
    val pageSize = 100
    runCatching {
        AppGraph.database.replaceBlogMembers(AppGraph.blogClient.fetchMembers())
    }.onFailure { error ->
        Log.w("NogiRelay", "BLOG member directory sync failed; keeping the last successful list", error)
    }
    val fullSyncComplete = AppGraph.database.isBlogFullSyncComplete()
    val syncBoundaryId = AppGraph.database.blogSyncHeadId()
    var inserted = 0

    if (fullSyncComplete && syncBoundaryId != null) {
        var offset = 0
        var newestId: String? = null
        var expectedCount: Int? = null
        var completed = false
        while (true) {
            val page = AppGraph.blogClient.fetchPage(limit = pageSize, offset = offset)
            if (expectedCount == null) expectedCount = page.total
            if (page.posts.isEmpty()) {
                if (offset >= page.total) completed = true else error("BLOG 增量分页在尾页前返回空数据")
                break
            }
            if (newestId == null) newestId = page.posts.firstOrNull()?.id
            val boundaryReached = page.posts.any { it.id == syncBoundaryId }
            page.posts.forEach { post ->
                if (AppGraph.database.upsertBlog(post)) inserted += 1
                BlogMediaDownloader.enqueue(context, post)
            }
            offset += page.posts.size
            if (boundaryReached || offset >= page.total || page.posts.size < pageSize) {
                completed = true
                break
            }
        }
        val finalCount = AppGraph.blogClient.fetchCount()
        val finalHeadId = AppGraph.blogClient.fetchPage(limit = 1, offset = 0).posts.firstOrNull()?.id
        if (!completed || finalCount != expectedCount || finalHeadId != newestId) {
            error("BLOG 增量同步期间官网列表发生变化；未移动同步边界，下次将安全重试")
        }
        AppGraph.database.markBlogSyncHead(newestId)
        Log.d("NogiRelay", "BLOG incremental sync complete: inserted=$inserted")
        return inserted
    }

    repeat(3) { attempt ->
        val expectedCount = AppGraph.blogClient.fetchCount()
        val seenIds = mutableSetOf<String>()
        var offset = 0
        var stable = true
        while (offset < expectedCount) {
            val page = AppGraph.blogClient.fetchPage(limit = pageSize, offset = offset)
            if (page.total != expectedCount || page.posts.isEmpty()) {
                stable = false
                break
            }
            page.posts.forEach { post ->
                seenIds += post.id
                if (AppGraph.database.upsertBlog(post)) inserted += 1
                BlogMediaDownloader.enqueue(context, post)
            }
            offset += page.posts.size
        }
        val finalCount = AppGraph.blogClient.fetchCount()
        val finalHead = AppGraph.blogClient.fetchPage(limit = pageSize, offset = 0)
        val headCovered = finalHead.posts.all { it.id in seenIds }
        if (stable && expectedCount == finalCount && seenIds.size == finalCount && headCovered) {
            AppGraph.database.markBlogFullSyncComplete(finalHead.posts.firstOrNull()?.id)
            Log.d("NogiRelay", "BLOG full sync verified: count=$finalCount, attempts=${attempt + 1}")
            return inserted
        }
        Log.w(
            "NogiRelay",
            "BLOG full sync snapshot changed; retrying: expected=$expectedCount, final=$finalCount, unique=${seenIds.size}, headCovered=$headCovered",
        )
    }
    error("BLOG 列表在同步期间持续变化；已保存抓到的内容，但未标记全量完成，下次会重新校验")
}

private fun syncMessagesFromServer(context: Context): Int {
    val savedSettings = AppGraph.settings.read()
    val settings = savedSettings.copy(
        relayUrl = savedSettings.relayUrl.ifBlank { ApiConfig.BASE_URL },
        accessToken = savedSettings.accessToken.ifBlank { ApiConfig.ACCESS_TOKEN },
    )
    if (settings.relayUrl.isBlank() || settings.accessToken.isBlank()) return 0

    val fullSyncComplete = AppGraph.database.isMessageFullSyncComplete()
    val syncBoundaryId = AppGraph.database.messageSyncHeadId()
    val inserted = if (fullSyncComplete && syncBoundaryId != null) {
        syncNewMessages(context, settings, syncBoundaryId)
    } else {
        syncMessageHistory(context, settings)
    }
    TranslationManager.enqueue(context)
    return inserted
}

/**
 * Message sync after the first successful backfill, built like [syncBlogsFromOfficial]: the server
 * lists newest first, so the walk starts at offset 0 and stops the moment the previous head shows
 * up. The boundary only moves once the total and the head are confirmed unchanged; if the list moved
 * under us the walk is discarded so the next sync repeats it and picks up whatever arrived.
 */
private fun syncNewMessages(context: Context, settings: AppSettings, syncBoundaryId: String): Int {
    val pageSize = 200
    val expectedCount = messageCountOrNull(settings)
    var offset = 0
    var inserted = 0
    var newestId: String? = null
    var completed = false
    while (true) {
        val page = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = offset)
        if (page.isEmpty()) {
            // Reaching the end is only trustworthy when the count agrees that we are at the end;
            // the previous head can also be gone after a server-side prune, which recovers here.
            if (expectedCount == null || offset >= expectedCount) completed = true
            break
        }
        if (newestId == null) newestId = page.firstOrNull()?.id
        val boundaryReached = page.any { it.id == syncBoundaryId }
        page.forEach { if (storeSyncedMessage(context, it)) inserted++ }
        offset += page.size
        if (boundaryReached || (expectedCount != null && offset >= expectedCount) || page.size < pageSize) {
            completed = true
            break
        }
    }
    val finalCount = messageCountOrNull(settings)
    val finalHeadId = AppGraph.relayClient.fetchMessages(settings, limit = 1, offset = 0).firstOrNull()?.id
    // A null total only drops the count half of the check; the head comparison always runs.
    val countCovered = expectedCount == null || finalCount == null || expectedCount == finalCount
    if (!completed || !countCovered || finalHeadId != newestId) {
        error("消息增量同步期间服务器列表发生变化；未移动同步边界，下次将安全重试")
    }
    AppGraph.database.markMessageSyncHead(newestId)
    Log.d("NogiRelay", "Message incremental sync complete: inserted=${inserted}")
    return inserted
}

/**
 * One-off message backfill, built like the BLOG full sync: page through everything and record the
 * full-sync marker only when the snapshot was identical from start to finish — the total must not
 * move, a stable list hands every row out exactly once, and the newest page must be covered by this
 * pass. Anything else is retried rather than leaving a hole the boundary would then hide forever.
 */
private fun syncMessageHistory(context: Context, settings: AppSettings): Int {
    val pageSize = 200
    var inserted = 0
    repeat(3) { attempt ->
        val expectedCount = messageCountOrNull(settings)
        val seenIds = mutableSetOf<String>()
        var offset = 0
        var stable = true
        while (expectedCount == null || offset < expectedCount) {
            val page = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = offset)
            if (page.isEmpty()) {
                // Reading nothing while the total still promises rows means the list moved under us.
                if (expectedCount != null && offset < expectedCount) stable = false
                break
            }
            page.forEach {
                seenIds += it.id
                if (storeSyncedMessage(context, it)) inserted++
            }
            offset += page.size
            if (page.size < pageSize) break
        }
        val finalCount = messageCountOrNull(settings)
        val finalHead = AppGraph.relayClient.fetchMessages(settings, limit = pageSize, offset = 0)
        val headCovered = finalHead.all { it.id in seenIds }
        val countCovered = if (expectedCount != null && finalCount != null) {
            expectedCount == finalCount && seenIds.size == finalCount
        } else {
            // Older relay without /v1/messages/stats/summary: fall back to "no row came back twice",
            // which is what a shifted list produces.
            seenIds.size == offset
        }
        if (stable && countCovered && headCovered) {
            AppGraph.database.markMessageFullSyncComplete(finalHead.firstOrNull()?.id)
            Log.d("NogiRelay", "Message full sync verified: count=${seenIds.size}, attempts=${attempt + 1}")
            return inserted
        }
        Log.w(
            "NogiRelay",
            "Message full sync snapshot changed; retrying: expected=$expectedCount final=$finalCount unique=${seenIds.size} walked=$offset headCovered=$headCovered",
        )
    }
    error("消息列表在同步期间持续变化；已保存抓到的内容，但未标记全量完成，下次会重新校验")
}

/**
 * Message total from the relay, or null when the server predates /v1/messages/stats/summary or is
 * briefly unreachable. Callers treat null as "total unknown" and verify with the head alone.
 */
private fun messageCountOrNull(settings: AppSettings): Int? =
    runCatching { AppGraph.relayClient.fetchMessageCount(settings) }
        .onFailure { Log.w("NogiRelay", "Message count unavailable; verifying with the head only", it) }
        .getOrNull()

private fun storeSyncedMessage(context: Context, message: RelayMessage): Boolean {
    val inserted = AppGraph.database.insert(message, isUnread = false)
    if (message.type != MessageType.TEXT) {
        runCatching { MediaDownloader.enqueueIfNeeded(context, message) }
            .onFailure { error -> Log.w("NogiRelay", "Media download enqueue failed for ${message.id}", error) }
    }
    return inserted
}

private val messageDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatMessageDateTime(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return input

    runCatching { Instant.parse(input) }.getOrNull()?.let {
        return messageDateFormatter.withZone(ZoneId.systemDefault()).format(it)
    }
    runCatching { OffsetDateTime.parse(input).toInstant() }.getOrNull()?.let {
        return messageDateFormatter.withZone(ZoneId.systemDefault()).format(it)
    }

    val local = runCatching {
        LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull() ?: runCatching {
        LocalDateTime.parse(input.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }.getOrNull()
    return local?.format(messageDateFormatter) ?: input
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    dataVersion: Long,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestCall: () -> Unit,
    isSyncing: Boolean,
    syncLabel: String,
    onSyncHistory: () -> Unit,
    onSettingsChanged: () -> Unit,
) {
    val settings = remember(dataVersion) { AppGraph.settings.read() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val firebaseConfigured = remember(dataVersion) { PushRegistrar.isConfigured(context) }
    val tokenRegistered = remember(dataVersion) { AppGraph.settings.pushToken().isNotBlank() }
    val pushReady = firebaseConfigured && tokenRegistered && settings.relayUrl.isNotBlank() && settings.accessToken.isNotBlank()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Relay and translation settings used to live in their own tab; the shortcuts below scroll to them.
    val settingsRequester = remember { BringIntoViewRequester() }
    fun scrollToSettings() {
        scope.launch { settingsRequester.bringIntoView() }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Nogi Relay", fontWeight = FontWeight.SemiBold)
                    Text("主页", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            StatusBand(pushReady = pushReady)

        Column {
            Text("系统能力", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    PermissionCard(
                        title = "通知权限",
                        description = if (notificationGranted) "系统通知已启用" else "需要授权后才能接收新消息",
                        granted = notificationGranted,
                        imageVector = Icons.Rounded.Notifications,
                        action = onRequestNotifications,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    PermissionCard(
                        title = "全屏来电",
                        description = if (fullScreenGranted) "允许在锁屏上显示成员来电" else "Android 14 需要开启特殊权限",
                        granted = fullScreenGranted,
                        imageVector = Icons.Rounded.Call,
                        action = onOpenFullScreenSettings,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    PermissionCard(
                        title = "后台弹出界面",
                        description = if (overlayGranted) "允许应用在后台直接弹出全屏来电" else "部分设备需要此权限才能弹出后台来电",
                        granted = overlayGranted,
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        action = onOpenOverlaySettings,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    PermissionCard(
                        title = "FCM 系统推送",
                        description = when {
                            !firebaseConfigured -> "缺少 Firebase google-services.json"
                            !tokenRegistered -> "设备尚未向服务器注册"
                            else -> "服务器可直接唤醒系统通知服务"
                        },
                        granted = firebaseConfigured && tokenRegistered,
                        imageVector = Icons.Rounded.Cloud,
                        action = { scrollToSettings() },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onSyncHistory,
                enabled = !isSyncing,
                shape = RelayControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Sync, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (isSyncing) "正在同步历史消息..." else "主动同步历史消息", maxLines = 1)
            }
            if (!BuildConfig.SIMPLE_UI) {
                Button(
                    onClick = onTestCall,
                    shape = RelayControlShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("测试全屏来电", maxLines = 1)
                }
            }
            FilledTonalButton(
                onClick = { scrollToSettings() },
                shape = RelayControlShape,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("推送设置", maxLines = 1)
            }
            if (syncLabel.isNotBlank()) {
                Text(syncLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(settingsRequester),
        ) {
            Text("设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            SettingsSection(onSettingsChanged = onSettingsChanged)
        }
    }
    }
}

@Composable
private fun StatusBand(pushReady: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (pushReady) Color(0xFFE7F6EF) else Color(0xFFF2EDF3),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (pushReady) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (pushReady) SignalGreen else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = if (pushReady) "FCM 系统推送已就绪" else "FCM 推送尚未完成配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * One cell of the 2x2 "系统能力" grid. Height is equalised per row by the caller via
 * [IntrinsicSize.Min], so the card only has to fill the box it is handed.
 */
@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    imageVector: ImageVector,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RelayControlShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = if (granted) SignalGreen else SignalCoral,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        if (!granted) {
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(
                onClick = action,
                shape = RelayControlShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("开启") }
        }
    }
}

data class MessagesUiState(
    val loading: Boolean = true,
    val messages: List<RelayMessage> = emptyList(),
    val threads: List<MemberThread> = emptyList(),
    val unreadCounts: Map<String, Int> = emptyMap(),
    val translationEnabled: Boolean = false,
    val userNickname: String = "",
)

class MessagesViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MessagesUiState())
    val uiState: StateFlow<MessagesUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(AppGraph.dispatchers.databaseRead) {
            val settings = AppGraph.settings.read()
            val messages = AppGraph.database.latest()
            val unreadCounts = AppGraph.database.unreadCountsByMember()
            val threads = messages
                .groupBy { it.memberKey }
                .map { (memberId, memberMessages) ->
                    MemberThread(
                        id = memberId,
                        name = memberMessages.first().memberName,
                        avatarUrl = memberMessages.firstNotNullOfOrNull { it.memberAvatarUrl },
                        latest = memberMessages.first(),
                        unreadCount = unreadCounts[memberId] ?: 0,
                    )
                }
                .sortedByDescending { it.latest.sentAt }
            _uiState.update { current ->
                current.copy(
                    loading = false,
                    messages = messages,
                    threads = threads,
                    unreadCounts = unreadCounts,
                    translationEnabled = settings.translationEnabled,
                    userNickname = settings.userNickname,
                )
            }
        }
    }
}

@Composable
private fun MessagesScreen(
    dataVersion: Long,
    initialMessageId: String?,
    onInitialMessageHandled: (String) -> Unit,
    onUnreadChanged: () -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onUpdateProximity: (VoicePlaybackState) -> Unit,
    isActive: Boolean = true,
    viewModel: MessagesViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloadScope = rememberCoroutineScope()
    val retranslateScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(dataVersion) {
        viewModel.load()
    }

    val messages = uiState.messages
    val threads = uiState.threads
    val translationEnabled = uiState.translationEnabled
    val userNickname = uiState.userNickname
    val playbackState by VoicePlaybackService.playbackState.collectAsState()
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var timeFilter by remember { mutableStateOf(TimeFilter()) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageInput by remember { mutableStateOf("1") }
    var pendingDownload by remember { mutableStateOf<RelayMessage?>(null) }
    var notificationScrollMessageId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(selectedMemberId, isActive) {
        val memberKey = selectedMemberId
        if (isActive && memberKey != null) MessageReadTracker.openMember(memberKey)
        onDispose {
            if (memberKey != null) MessageReadTracker.closeMember(memberKey)
        }
    }

    LaunchedEffect(selectedMemberId) {
        val memberKey = selectedMemberId ?: return@LaunchedEffect
        val updated = withContext(Dispatchers.IO) {
            AppGraph.database.markMessagesReadForMember(memberKey)
        }
        if (updated > 0) onUnreadChanged()
    }
    
    LaunchedEffect(playbackState) {
        onUpdateProximity(playbackState)
    }
    
    LaunchedEffect(initialMessageId) {
        val targetId = initialMessageId ?: return@LaunchedEffect
        val message = AppGraph.database.find(targetId)
        if (message == null) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        val memberKey = message.memberId.ifBlank { message.memberName }
        val messageIndex = AppGraph.database.messageIndexForMember(memberKey, targetId)
        if (messageIndex < 0) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        searchQuery = ""
        // The target index is computed without filters, so clear the time filter to stay consistent.
        timeFilter = TimeFilter()
        selectedMemberId = memberKey
        currentPage = messageIndex / MEMBER_MESSAGES_PAGE_SIZE
        pageInput = (currentPage + 1).toString()
        notificationScrollMessageId = targetId
    }

    val saveDownload: (RelayMessage) -> Unit = { message ->
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
        val message = pendingDownload
        pendingDownload = null
        if (granted && message != null) {
            saveDownload(message)
        } else if (!granted) {
            Toast.makeText(context, "需要存储权限才能保存到 Download 文件夹", Toast.LENGTH_SHORT).show()
        }
    }
    if (uiState.loading && messages.isEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Inbox, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("还没有同步消息", style = MaterialTheme.typography.titleMedium)
                Text("保存同步设置后，新消息会出现在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    // threads are precomputed in MessagesViewModel on databaseRead dispatcher

    fun download(message: RelayMessage) {
        if (MediaDownloader.needsLegacyWritePermission(context)) {
            pendingDownload = message
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveDownload(message)
        }
    }

    val selected = selectedMemberId
    BackHandler(enabled = isActive && selected != null) {
        selectedMemberId = null
    }
    Crossfade(
        targetState = selected,
        animationSpec = tween(durationMillis = 220),
        label = "member-message-transition",
    ) { selectedMember ->
        if (selectedMember == null) {
            MemberInbox(
                threads = threads,
                userNickname = userNickname,
                onSelect = {
                    searchQuery = ""
                    selectedMemberId = it.id
                    
                    // Check if there's a playing message for this member
                    val currentPlayingMessageId = playbackState.messageId
                    if (playbackState.isPlaying && currentPlayingMessageId != null) {
                        val playingMessage = AppGraph.database.find(currentPlayingMessageId)
                        val playingMemberId = playingMessage?.memberId?.ifBlank { playingMessage.memberName }
                        if (playingMemberId == it.id) {
                            // Calculate which page the playing message is on
                            val playingIndex = AppGraph.database.messageIndexForMember(
                                memberKey = it.id,
                                messageId = currentPlayingMessageId,
                                startMillis = timeFilter.startMillis,
                                endMillisExclusive = timeFilter.endMillisExclusive,
                            )
                            if (playingIndex >= 0) {
                                val targetPage = playingIndex / MEMBER_MESSAGES_PAGE_SIZE
                                currentPage = targetPage
                                pageInput = (targetPage + 1).toString()
                            } else {
                                currentPage = 0
                                pageInput = "1"
                            }
                        } else {
                            currentPage = 0
                            pageInput = "1"
                        }
                    } else {
                        currentPage = 0
                        pageInput = "1"
                    }
                },
            )
        } else {
            val thread = threads.firstOrNull { it.id == selectedMember }
            val initialForMember = remember(selectedMember) {
                uiState.messages.filter { it.memberKey == selectedMember }.take(MEMBER_MESSAGES_PAGE_SIZE)
            }
            val matchingMessageCount by produceState(
                initialValue = if (searchQuery.isBlank() && !timeFilter.isActive) initialForMember.size else 0,
                key1 = selectedMember,
                key2 = searchQuery,
                key3 = Pair(timeFilter, dataVersion),
            ) {
                value = withContext(AppGraph.dispatchers.databaseRead) {
                    AppGraph.database.countMessagesForMember(
                        memberKey = selectedMember,
                        searchQuery = searchQuery,
                        startMillis = timeFilter.startMillis,
                        endMillisExclusive = timeFilter.endMillisExclusive,
                    )
                }
            }
            val totalPages = ((matchingMessageCount + MEMBER_MESSAGES_PAGE_SIZE - 1) / MEMBER_MESSAGES_PAGE_SIZE)
                .coerceAtLeast(1)
            val page = currentPage.coerceIn(0, totalPages - 1)
            val messageListState = rememberLazyListState()
            var showPageDialog by remember(selectedMember, searchQuery, timeFilter) { mutableStateOf(false) }
            var showTimeFilterDialog by remember(selectedMember) { mutableStateOf(false) }
            val memberMessages by produceState<List<RelayMessage>>(
                initialValue = if (searchQuery.isBlank() && !timeFilter.isActive && page == 0) initialForMember else emptyList(),
                key1 = selectedMember,
                key2 = Pair(searchQuery, timeFilter),
                key3 = Pair(page, dataVersion),
            ) {
                value = withContext(AppGraph.dispatchers.databaseRead) {
                    AppGraph.database.messagesForMember(
                        memberKey = selectedMember,
                        searchQuery = searchQuery,
                        startMillis = timeFilter.startMillis,
                        endMillisExclusive = timeFilter.endMillisExclusive,
                        limit = MEMBER_MESSAGES_PAGE_SIZE,
                        offset = page * MEMBER_MESSAGES_PAGE_SIZE,
                    )
                }
            }
            
            fun goToPage(targetPage: Int) {
                val safePage = targetPage.coerceIn(0, totalPages - 1)
                currentPage = safePage
                pageInput = (safePage + 1).toString()
            }
            val requestedPage = pageInput.toIntOrNull()
            val canJump = requestedPage != null && requestedPage in 1..totalPages
            LaunchedEffect(selectedMember, searchQuery, timeFilter, page) {
                if (currentPage != page) currentPage = page
                pageInput = (page + 1).toString()
                if (memberMessages.isNotEmpty() && notificationScrollMessageId == null) {
                    // Check if playing message is in current page
                    if (playbackState.isPlaying && playbackState.messageId != null) {
                        val playingIndex = memberMessages.indexOfFirst { it.id == playbackState.messageId }
                        if (playingIndex >= 0) {
                            messageListState.scrollToItem(playingIndex + 1)
                        } else {
                            messageListState.scrollToItem(0)
                        }
                    } else {
                        messageListState.scrollToItem(0)
                    }
                }
            }
            LaunchedEffect(notificationScrollMessageId, memberMessages) {
                val targetId = notificationScrollMessageId ?: return@LaunchedEffect
                val targetIndex = memberMessages.indexOfFirst { it.id == targetId }
                if (targetIndex >= 0) {
                    // The search field occupies item 0, matching the existing voice-playback positioning.
                    messageListState.scrollToItem(targetIndex + 1)
                    notificationScrollMessageId = null
                    onInitialMessageHandled(targetId)
                }
            }
            if (showTimeFilterDialog) {
                TimeFilterDialog(
                    filter = timeFilter,
                    onDismiss = { showTimeFilterDialog = false },
                    onConfirm = { updated ->
                        timeFilter = updated
                        currentPage = 0
                        pageInput = "1"
                        showTimeFilterDialog = false
                    },
                )
            }
            if (showPageDialog) {
                AlertDialog(
                    onDismissRequest = { showPageDialog = false },
                    title = { Text("跳转") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "请输入1-${totalPages}之间的页码",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = pageInput,
                                onValueChange = { value ->
                                    pageInput = value.filter(Char::isDigit).take(6)
                                },
                                singleLine = true,
                                label = { Text("页码") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        if (canJump) {
                                            goToPage(requestedPage!! - 1)
                                            showPageDialog = false
                                        }
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                goToPage(requestedPage!! - 1)
                                showPageDialog = false
                            },
                            enabled = canJump,
                        ) { Text("跳转") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPageDialog = false }) { Text("取消") }
                    },
                )
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = { selectedMemberId = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回成员列表")
                    }
                    Text(
                        text = thread?.name ?: "成员消息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                LazyColumn(
                    state = messageListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    currentPage = 0
                                    pageInput = "1"
                                },
                                singleLine = true,
                                label = { Text("搜索") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                currentPage = 0
                                                pageInput = "1"
                                            },
                                        ) {
                                            Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            IconButton(onClick = { showTimeFilterDialog = true }) {
                                Icon(
                                    Icons.Rounded.FilterList,
                                    contentDescription = "筛选时间",
                                    tint = if (timeFilter.isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    if (memberMessages.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) "暂无消息" else "没有找到相关消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            )
                        }
                    }
                    items(memberMessages, key = { it.id }) { message ->
                        MessageCard(
                            message = message,
                            audioState = playbackState.takeIf { it.messageId == message.id },
                            translationEnabled = translationEnabled,
                            userNickname = userNickname,
                            searchQuery = searchQuery,
                            onOpenMedia = { onOpenMedia(message) },
                            onPlayVoice = { onPlayVoice(message) },
                            onDownload = { download(message) },
                            onRetranslate = {
                                retranslateScope.launch {
                                    AppGraph.database.markForRetranslation(message.id)
                                    TranslationManager.enqueue(context)
                                }
                            },
                        )
                    }
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = "$matchingMessageCount 条消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = { goToPage(page - 1) },
                                    enabled = page > 0,
                                ) { Text("上一页") }
                                OutlinedButton(
                                    onClick = {
                                        pageInput = (page + 1).toString()
                                        showPageDialog = true
                                    },
                                    enabled = matchingMessageCount > 0,
                                ) {
                                    Text("${page + 1} / $totalPages")
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码")
                                }
                                OutlinedButton(
                                    onClick = { goToPage(page + 1) },
                                    enabled = page < totalPages - 1,
                                ) { Text("下一页") }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

private const val MEMBER_MESSAGES_PAGE_SIZE = 20

data class MemberThread(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val latest: RelayMessage,
    val unreadCount: Int,
)

@Composable
private fun MemberInbox(
    threads: List<MemberThread>,
    userNickname: String,
    onSelect: (MemberThread) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Text(
                "最近收到",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                items(threads.take(6), key = { it.id }) { thread ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(92.dp).clickable { onSelect(thread) },
                    ) {
                        Box {
                            RemoteImage(
                                url = thread.avatarUrl,
                                contentDescription = thread.name,
                                modifier = Modifier.size(72.dp).clip(CircleShape),
                                loadCachedImmediately = true,
                            )
                            if (thread.unreadCount > 0) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.align(Alignment.TopEnd).size(12.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            thread.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        item {
            Text(
                "全部成员",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        items(threads, key = { it.id }) { thread ->
            Card(
                onClick = { onSelect(thread) },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) {
                    RemoteImage(
                        url = thread.avatarUrl,
                        contentDescription = thread.name,
                        modifier = Modifier.size(50.dp).clip(CircleShape),
                        loadCachedImmediately = true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(thread.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = threadPreview(thread.latest, userNickname),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    if (thread.unreadCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.error) {
                            Text(unreadBadgeLabel(thread.unreadCount))
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private fun unreadBadgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

private fun threadPreview(message: RelayMessage, userNickname: String): String = when (message.type) {
    MessageType.TEXT -> message.text.orEmpty()
    MessageType.IMAGE -> "图片消息"
    MessageType.AUDIO -> "语音消息"
    MessageType.VIDEO -> "视频消息"
}.let { fallback -> substituteNickname(message.text?.trim()?.takeIf { it.isNotEmpty() }, userNickname) ?: fallback }
    .withoutTextPresentationSelector()

@Composable
private fun MessageCard(
    message: RelayMessage,
    audioState: VoicePlaybackState?,
    translationEnabled: Boolean,
    userNickname: String,
    searchQuery: String,
    onOpenMedia: () -> Unit,
    onPlayVoice: () -> Unit,
    onDownload: () -> Unit,
    onRetranslate: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val highlightBackground = MaterialTheme.colorScheme.primaryContainer
    val highlightText = MaterialTheme.colorScheme.onPrimaryContainer
    var scrubPositionMs by remember(message.id) { mutableIntStateOf(0) }
    var scrubbing by remember(message.id) { mutableStateOf(false) }
    val audioPlaying = audioState?.isPlaying == true
    val audioDurationMs = audioState?.durationMs?.takeIf { it > 0 }
        ?: message.durationSeconds?.takeIf { it > 0 }?.times(1_000)
        ?: 0
    val audioPositionMs = audioState?.positionMs?.coerceIn(0, audioDurationMs.coerceAtLeast(0)) ?: 0
    LaunchedEffect(audioState?.positionMs, audioState?.durationMs, audioDurationMs) {
        if (!scrubbing) scrubPositionMs = audioPositionMs
    }
    val displayedPositionMs = if (scrubbing) {
        scrubPositionMs.coerceIn(0, audioDurationMs.coerceAtLeast(0))
    } else {
        audioPositionMs
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = message.memberAvatarUrl,
                    contentDescription = message.memberName,
                    modifier = Modifier.size(42.dp).clip(CircleShape),
                    loadCachedImmediately = true,
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        highlightMatches(message.memberName, searchQuery, highlightBackground, highlightText),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        highlightMatches(formatMessageDateTime(message.sentAt), searchQuery, highlightBackground, highlightText),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (translationEnabled && message.text?.isNotBlank() == true) {
                    IconButton(onClick = onRetranslate, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "重新翻译",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (message.type != MessageType.TEXT) {
                    IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.Download, contentDescription = "保存到本地")
                    }
                }
            }

            message.text?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(12.dp))
                SelectionContainer {
                    val displayedText = (substituteNickname(it, userNickname) ?: it).withoutTextPresentationSelector()
                    Text(highlightMatches(displayedText, searchQuery, highlightBackground, highlightText))
                }
            }
            if (translationEnabled) {
                normalizeTranslationText(substituteNickname(message.text, userNickname), message.translation)?.let {
                    Spacer(Modifier.height(7.dp))
                    SelectionContainer {
                        Text(
                            text = highlightMatches(
                                it.withoutTextPresentationSelector(),
                                searchQuery,
                                highlightBackground,
                                highlightText,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                        )
                    }
                }
            }

            when (message.type) {
                MessageType.IMAGE, MessageType.VIDEO -> {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .clickable(onClick = onOpenMedia),
                    ) {
                        RemoteImage(
                            url = if (message.type == MessageType.IMAGE) {
                                message.mediaUrl ?: message.thumbnailUrl
                            } else {
                                message.thumbnailUrl ?: message.mediaUrl
                            },
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            preserveAspectRatio = true,
                            messageType = message.type,
                            message = message,
                            placeholderColor = Color.Transparent,
                        )
                        if (message.type == MessageType.VIDEO) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.62f), CircleShape),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "播放视频",
                                    tint = Color.White,
                                    modifier = Modifier.size(38.dp),
                                )
                            }
                        }
                    }
                }

                MessageType.AUDIO -> {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        IconButton(onClick = onPlayVoice) {
                            Icon(
                                imageVector = if (audioPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (audioPlaying) "暂停语音" else "播放语音",
                            )
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (message.isPlayed) "语音消息" else "未播放语音",
                                    fontWeight = FontWeight.Medium,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    formatAudioTime(displayedPositionMs),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    " / ${formatAudioDuration(audioDurationMs)}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = displayedPositionMs.toFloat(),
                                onValueChange = { value ->
                                    if (audioDurationMs > 0) {
                                        scrubbing = true
                                        scrubPositionMs = value.toInt()
                                    }
                                },
                                onValueChangeFinished = {
                                    if (audioDurationMs > 0) {
                                        VoicePlaybackService.seek(
                                            context = context,
                                            messageId = message.id,
                                            positionMs = scrubPositionMs,
                                        )
                                    }
                                    scrubbing = false
                                },
                                valueRange = 0f..audioDurationMs.coerceAtLeast(1).toFloat(),
                                enabled = audioDurationMs > 0,
                                modifier = Modifier.fillMaxWidth().height(28.dp),
                            )
                        }
                        if (audioPlaying) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        context.startService(
                                            Intent(context, VoicePlaybackService::class.java).apply {
                                                action = VoicePlaybackService.ACTION_SET_SPEAKER
                                                putExtra(VoicePlaybackService.EXTRA_SPEAKER_ON, !(audioState?.speakerOn ?: false))
                                            }
                                        )
                                    }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_audio_speaker_official),
                                    contentDescription = if (audioState?.speakerOn == true) "切换到听筒" else "切换到扬声器",
                                    tint = if (audioState?.speakerOn == true) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                MessageType.TEXT -> Unit
            }
        }
    }
}

private fun formatAudioTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.getDefault(), totalSeconds / 60, totalSeconds % 60)
}

private fun formatAudioDuration(milliseconds: Int): String =
    if (milliseconds > 0) formatAudioTime(milliseconds) else "--:--"

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsSection(onSettingsChanged: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val initial = remember { AppGraph.settings.read() }
    var relayUrl by remember { mutableStateOf(initial.relayUrl) }
    var token by remember { mutableStateOf(initial.accessToken) }
    var aiProvider by remember { mutableStateOf(initial.aiProvider) }
    var aiApiKey by remember { mutableStateOf(initial.aiApiKey) }
    var aiModel by remember { mutableStateOf(initial.aiModel) }
    var modelOptions by remember { mutableStateOf(initial.cachedAiModels) }
    var translationEnabled by remember { mutableStateOf(initial.translationEnabled) }
    var userNickname by remember { mutableStateOf(initial.userNickname) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var providerFieldWidthPx by remember { mutableIntStateOf(0) }
    var modelFieldWidthPx by remember { mutableIntStateOf(0) }
    var savedLabel by remember { mutableStateOf("") }
    var nicknameLabel by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf("") }
    var validatingApiKey by remember { mutableStateOf(false) }
    // The support badge in the lists and under the model field all read the same query the
    // request builder uses, so the label always matches what is actually sent.
    val selectedProvider = remember(aiProvider) { AIProviderFactory.getProvider(aiProvider) }
    val selectedModelSupport = remember(selectedProvider, aiModel) {
        selectedProvider.jsonOutputSupport(aiModel)
    }

    // Everything being edited except the nickname: it has its own field and save button, so saving
    // any other setting here must not pick up a half-typed nickname.
    fun currentSettings() = AppGraph.settings.read().copy(
        relayUrl = relayUrl,
        accessToken = token,
        aiProvider = aiProvider,
        aiApiKey = aiApiKey,
        aiModel = aiModel,
        cachedAiModels = modelOptions,
        translationEnabled = translationEnabled,
    )

    fun saveNickname() {
        val previous = AppGraph.settings.read().userNickname
        val trimmed = userNickname.trim()
        AppGraph.settings.save(AppGraph.settings.read().copy(userNickname = trimmed))
        userNickname = trimmed
        // The nickname is substituted into the text handed to the translator, so anything translated
        // with the old name is stale once it changes. enqueue() is a no-op while 翻译 is switched off.
        if (previous != trimmed) {
            TranslationManager.resetRetries()
            BlogTranslationManager.resetRetries()
            TranslationManager.enqueue(context)
            BlogTranslationManager.enqueuePending(context)
        }
        nicknameLabel = "昵称已保存"
        onSettingsChanged()
    }

    fun saveTranslationSettings() {
        AppGraph.settings.save(currentSettings())
        TranslationManager.resetRetries()
        BlogTranslationManager.resetRetries()
        TranslationManager.enqueue(context)
        BlogTranslationManager.enqueuePending(context)
        savedLabel = "翻译设置已保存"
        onSettingsChanged()
    }

    fun validateApiKey() {
        val key = aiApiKey.trim()
        if (key.isEmpty()) {
            modelStatus = "请先填写 API Key"
            return
        }
        scope.launch {
            validatingApiKey = true
            modelStatus = "正在验证并加载模型..."
            val result = TranslationManager.fetchAvailableModels(aiProvider, key)
            validatingApiKey = false
            result.onSuccess { models ->
                modelOptions = models
                if (aiModel.isNotBlank() && models.none { it.id == aiModel }) {
                    aiModel = ""
                }
                AppGraph.settings.save(currentSettings().copy(cachedAiModels = models))
                modelStatus = "API Key 有效，已加载 ${models.size} 个可用模型"
            }.onFailure { error ->
                modelStatus = error.message ?: "API Key 无效或模型加载失败"
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("FCM 推送服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (!BuildConfig.SIMPLE_UI) {
            OutlinedTextField(
                value = relayUrl,
                onValueChange = { relayUrl = it },
                label = { Text("同步服务地址") },
                placeholder = { Text("https://relay.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("访问令牌") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = {
                AppGraph.settings.save(currentSettings())
                savedLabel = "正在注册 FCM 设备..."
                PushRegistrar.registerCurrentToken(context) { result ->
                    (context as? android.app.Activity)?.runOnUiThread {
                        savedLabel = result.fold(
                            onSuccess = { "设备已注册，系统推送已就绪" },
                            onFailure = { it.message ?: "FCM 设备注册失败" },
                        )
                    }
                }
            },
            shape = RelayControlShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (BuildConfig.SIMPLE_UI) "注册推送" else "保存并注册推送", maxLines = 1)
        }
        Text("昵称", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = userNickname,
            onValueChange = { userNickname = it },
            label = { Text("你的昵称") },
            placeholder = { Text("用于替换消息中的 %%%") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(
            onClick = ::saveNickname,
            shape = RelayControlShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("保存", maxLines = 1)
        }
        if (nicknameLabel.isNotBlank()) {
            Text(nicknameLabel, color = SignalGreen, fontSize = 13.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                Text("翻译", fontWeight = FontWeight.Medium)
            }
            Switch(
                checked = translationEnabled,
                onCheckedChange = {
                    translationEnabled = it
                    AppGraph.settings.save(currentSettings())
                    TranslationManager.resetRetries()
                    BlogTranslationManager.resetRetries()
                    if (it) {
                        TranslationManager.enqueue(context)
                        BlogTranslationManager.enqueuePending(context)
                    }
                    // The list and detail screens cache the setting behind refreshKey, so bump it to
                    // hide or restore the BLOG translations immediately instead of within the next poll.
                    onSettingsChanged()
                },
            )
        }
        Box {
            OutlinedTextField(
                value = aiProvider.displayName,
                onValueChange = {},
                readOnly = true,
                label = { Text("AI 供应商") },
                trailingIcon = {
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择供应商")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { providerFieldWidthPx = it.width },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable { providerMenuExpanded = true },
            )
            DropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false },
                modifier = if (providerFieldWidthPx > 0) {
                    Modifier.width(with(density) { providerFieldWidthPx.toDp() })
                } else {
                    Modifier
                },
            ) {
                AIProviderType.values().forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(provider.displayName) },
                        trailingIcon = {
                            if (provider.supportsStructuredOutput) SupportBadge("结构化输出")
                        },
                        onClick = {
                            // Every provider keeps its own API Key, model and cached model list, so
                            // switching back and forth restores what was configured before.
                            aiProvider = provider
                            aiApiKey = AppGraph.settings.apiKeyFor(provider)
                            aiModel = AppGraph.settings.modelFor(provider)
                            modelOptions = AppGraph.settings.cachedModelsFor(provider)
                            AppGraph.settings.save(currentSettings())
                            modelStatus = ""
                            providerMenuExpanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = aiApiKey,
            onValueChange = { aiApiKey = it },
            label = { Text("${aiProvider.displayName} API Key") },
            placeholder = { Text("sk-... 或对应供应商的 API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = ::saveTranslationSettings,
                shape = RelayControlShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text("保存", maxLines = 1)
            }
            OutlinedButton(
                onClick = ::validateApiKey,
                enabled = !validatingApiKey,
                shape = RelayControlShape,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                Spacer(Modifier.size(5.dp))
                Text(if (validatingApiKey) "校验中..." else "校验有效性", maxLines = 1)
            }
        }
        if (modelStatus.isNotBlank()) {
            Text(modelStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Box {
            OutlinedTextField(
                value = aiModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("翻译模型") },
                placeholder = { Text("请先校验 API Key 并选择模型") },
                trailingIcon = {
                    RelayIconButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = modelOptions.isNotEmpty(),
                        imageVector = Icons.Rounded.ArrowDropDown,
                        contentDescription = "选择翻译模型",
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { modelFieldWidthPx = it.width },
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(enabled = modelOptions.isNotEmpty()) {
                        modelMenuExpanded = true
                    },
            )
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
                modifier = if (modelFieldWidthPx > 0) {
                    Modifier.width(with(density) { modelFieldWidthPx.toDp() })
                } else {
                    Modifier
                },
            ) {
                modelOptions.forEach { model ->
                    val support = selectedProvider.jsonOutputSupport(model.id)
                    DropdownMenuItem(
                        text = { Text(model.displayName) },
                        trailingIcon = { if (support.isSupported) SupportBadge(support.label) },
                        onClick = {
                            aiModel = model.id
                            AppGraph.settings.save(currentSettings().copy(aiModel = model.id))
                            TranslationManager.resetRetries()
                            BlogTranslationManager.resetRetries()
                            TranslationManager.enqueue(context)
                            BlogTranslationManager.enqueuePending(context)
                            savedLabel = "翻译模型已保存"
                            modelMenuExpanded = false
                        },
                    )
                }
            }
        }
        if (aiModel.isNotBlank()) {
            Text(
                text = if (selectedModelSupport.isSupported) {
                    "结构化输出：${selectedModelSupport.label}"
                } else {
                    "结构化输出：不支持，仅用提示词约束"
                },
                color = if (selectedModelSupport.isSupported) SignalGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
        if (modelOptions.isEmpty() && aiApiKey.isNotBlank()) {
            Text(
                "请点击\"校验有效性\"加载可用模型",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }
        if (savedLabel.isNotBlank()) {
            Text(savedLabel, color = SignalGreen, fontSize = 13.sp)
        }
    }
}

/** Small green marker for a provider or model that sends an API-level structured-output request. */
@Composable
private fun SupportBadge(label: String) {
    Text(
        text = label,
        color = SignalGreen,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun hasNotificationPermission(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
