package com.nogirelay.app.ui.navigation

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nogirelay.app.blog.BlogScreen
import com.nogirelay.app.call.FullScreenPermission
import com.nogirelay.app.call.OverlayPermission
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.sync.ContentSyncManager
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.LocalRelayPageActive
import com.nogirelay.app.performance.isRelayUiStarted
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.glass.LocalGlassHazeDrawTick
import com.nogirelay.app.ui.glass.GlassBackdrop
import com.nogirelay.app.ui.glass.GlassMotion
import com.nogirelay.app.ui.glass.GlassNavBar
import com.nogirelay.app.ui.glass.GlassNavItem
import com.nogirelay.app.ui.home.HomeScreen
import com.nogirelay.app.ui.home.hasNotificationPermission
import com.nogirelay.app.ui.messages.MessagesScreen
import com.nogirelay.app.ui.glass.glassHazeSourceTick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val TAG = "RelayApp"

enum class AppTab(val label: String) { HOME("主页"), MESSAGES("消息"), BLOG("博客") }

/**
 * App shell: one continuous milky-glass world.
 *
 * A single [GlassBackdrop] at the root is the shared haze source for every
 * glass surface on every page, so content visibly scrolls behind the
 * floating navigation capsule. Pages are kept alive and transition
 * spatially (gentle rise + settle, never a bare crossfade), and the capsule
 * indicator moves with liquid stretch.
 */
@Composable
fun RelayApp(
    notificationMessageIds: StateFlow<String?>,
    notificationBlogIds: StateFlow<String?>,
    onNotificationMessageHandled: (String) -> Unit,
    onNotificationBlogHandled: (String) -> Unit,
    onOpenMedia: (RelayMessage, String) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onTestCall: () -> Unit,
    syncRequests: StateFlow<Long>,
    onManualSync: () -> Unit,
    onUpdateProximity: (VoicePlaybackState) -> Unit,
) {
    val context = LocalContext.current
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
    val versions by AppGraph.dataVersions.collectAsStateWithLifecycle()
    val uiStarted = isRelayUiStarted()
    var syncing by remember { mutableStateOf(false) }
    var syncLabel by remember { mutableStateOf("") }
    var unreadMessageCount by remember { mutableIntStateOf(0) }
    var unreadBlogCount by remember { mutableIntStateOf(0) }
    var navigatedBlogId by remember { mutableStateOf<String?>(null) }
    var navigatedMemberId by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
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

    LaunchedEffect(versions.unread, uiStarted) {
        if (!uiStarted) return@LaunchedEffect
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
            val result = runCatching { withContext(Dispatchers.IO) { ContentSyncManager.syncContent(context) } }
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
                    Log.w(TAG, "History sync failed", error)
                    error.message ?: "历史消息同步失败"
                },
            )
            AppGraph.notifyDataChanged(DataChange.CONTENT)
        }
    }

    LaunchedEffect(versions.settings) {
        withContext(Dispatchers.IO) {
            TranslationManager.enqueue(context)
            BlogTranslationManager.enqueuePending(context)
        }
    }

    LaunchedEffect(Unit) {
        VoicePlaybackService.playbackState
            .map { it.copy(positionMs = 0, durationMs = 0, sampledAtMillis = 0) }
            .distinctUntilChanged()
            .collect { onUpdateProximity(it) }
    }

    BackHandler(enabled = tab != AppTab.HOME) {
        tab = AppTab.HOME
    }

    val hazeDrawTick = remember { mutableLongStateOf(0L) }
    val hazeScrollDriver = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                hazeDrawTick.longValue++
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalGlassHazeDrawTick provides hazeDrawTick) {
        GlassBackdrop(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(hazeScrollDriver),
        ) {
            Box(Modifier.fillMaxSize()) {
                AppTab.entries.forEach { item ->
                    val isSelected = (tab == item)
                    val pageActive = isSelected && uiStarted
                    // Spatial continuity: the incoming page rises and settles
                    // on a spring; the outgoing page sinks and dims. Both stay
                    // composed so scroll positions and playback survive.
                    val presence = remember { Animatable(if (isSelected) 1f else 0f) }
                    LaunchedEffect(isSelected) {
                        presence.animateTo(
                            if (isSelected) 1f else 0f,
                            if (isSelected) GlassMotion.MorphSpec else GlassMotion.GentleSpec,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (isSelected) 1f else 0f)
                            .drawWithContent {
                                if (presence.value > 0f) drawContent()
                            }
                            .graphicsLayer {
                                val p = presence.value
                                alpha = p
                                translationY = (1f - p) * 26.dp.toPx()
                                val s = 0.975f + 0.025f * p
                                scaleX = s
                                scaleY = s
                            }
                            .then(
                                if (!isSelected) {
                                    Modifier
                                        .clearAndSetSemantics { }
                                        .pointerInput(Unit) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                        }
                                } else {
                                    Modifier
                                },
                            )
                            .glassHazeSourceTick(hazeDrawTick),
                    ) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalRelayPageActive provides pageActive) {
                            when (item) {
                                AppTab.HOME -> HomeScreen(
                                    notificationGranted = notificationGranted,
                                    fullScreenGranted = fullScreenGranted,
                                    overlayGranted = overlayGranted,
                                    versions = versions,
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
                                    onSettingsChanged = { AppGraph.notifyDataChanged(DataChange.SETTINGS) },
                                )

                                AppTab.MESSAGES -> MessagesScreen(
                                    isActive = isSelected,
                                    versions = versions,
                                    initialMessageId = initialMessageId,
                                    initialMemberId = navigatedMemberId,
                                    onInitialMemberHandled = { navigatedMemberId = null },
                                    onInitialMessageHandled = onNotificationMessageHandled,
                                    onUnreadChanged = { ids -> AppGraph.notifyDataChanged(DataChange.MESSAGE_READ, ids) },
                                    onOpenMedia = onOpenMedia,
                                    onPlayVoice = onPlayVoice,
                                )

                                AppTab.BLOG -> BlogScreen(
                                    isActive = isSelected,
                                    versions = versions,
                                    initialBlogId = navigatedBlogId ?: initialBlogId,
                                    onInitialBlogHandled = {
                                        navigatedBlogId = null
                                        onNotificationBlogHandled(it)
                                    },
                                    onUnreadChanged = { AppGraph.notifyDataChanged(DataChange.BLOG_READ) },
                                )
                            }
                        }
                    }
                }

                GlassNavBar(
                    items = listOf(
                        GlassNavItem(
                            label = AppTab.HOME.label,
                            icon = RelayNavigationIcons.homeOutline,
                            selectedIcon = RelayNavigationIcons.homeFilled,
                        ),
                        GlassNavItem(
                            label = AppTab.MESSAGES.label,
                            icon = RelayNavigationIcons.inboxOutline,
                            selectedIcon = RelayNavigationIcons.inboxFilled,
                            badgeCount = unreadMessageCount,
                        ),
                        GlassNavItem(
                            label = AppTab.BLOG.label,
                            icon = RelayNavigationIcons.blogOutline,
                            selectedIcon = RelayNavigationIcons.blogFilled,
                            badgeCount = unreadBlogCount,
                        ),
                    ),
                    selectedIndex = AppTab.entries.indexOf(tab),
                    onSelected = { tab = AppTab.entries[it] },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(2f)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 6.dp, bottom = 14.dp)
                        .fillMaxWidth(0.78f)
                        .widthIn(max = 330.dp),
                )
            }
        }
    }
}




