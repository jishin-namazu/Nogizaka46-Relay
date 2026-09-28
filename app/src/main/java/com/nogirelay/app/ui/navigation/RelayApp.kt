package com.nogirelay.app.ui.navigation

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
import com.nogirelay.app.ui.ProvideRelayHazeState
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RelayLightBackdrop
import com.nogirelay.app.ui.RelayMirrorGlassBackground
import com.nogirelay.app.ui.RelayMirrorGlassSelection
import com.nogirelay.app.ui.RelayNavigationBarShape
import com.nogirelay.app.ui.home.HomeScreen
import com.nogirelay.app.ui.home.hasNotificationPermission
import com.nogirelay.app.ui.messages.MessagesScreen
import com.nogirelay.app.ui.messages.unreadBadgeLabel
import com.nogirelay.app.ui.relayAtmosphere
import com.nogirelay.app.ui.relayHazeSource
import com.nogirelay.app.ui.rememberRelayHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class AppTab(val label: String) { HOME("主页"), MESSAGES("消息"), BLOG("博客") }

@Composable
fun RowScope.RelayNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    imageVector: ImageVector,
    badgeCount: Int,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = tween(durationMillis = 200),
        label = "nav_icon_scale",
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 4.dp),
    ) {
        Box(
            // The moving glass indicator and icon scale provide the tab feedback.
            modifier = Modifier.size(width = 56.dp, height = 34.dp),
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
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 相当于带应用圆角方形形状的 [androidx.compose.material3.IconButton]：Material 3 把 [androidx.compose.material3.IconButton]
 * 本身固定为圆形，并且不暴露形状参数，所以这里改为自行绘制容器。
 * 48dp 的方框与 [androidx.compose.material3.IconButton] 的触摸目标完全一致。
 */
@Composable
fun RelayIconButton(
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
                indication = null,
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
fun RelayApp(
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
                    Log.w("NogiRelay", "History sync failed", error)
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

    // 语音播放期间听筒靠近熄屏由此处同步：即使界面已经停止，
    // 播放停止时也要释放锁，避免播放结束后仍然“靠近就熄屏”。
    LaunchedEffect(Unit) {
        VoicePlaybackService.playbackState
            .map { it.copy(positionMs = 0, durationMs = 0, sampledAtMillis = 0) }
            .distinctUntilChanged()
            .collect { onUpdateProximity(it) }
    }

    BackHandler(enabled = tab != AppTab.HOME) {
        tab = AppTab.HOME
    }

    val hazeState = rememberRelayHazeState()
    ProvideRelayHazeState(hazeState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RelayLightBackdrop)
                .relayAtmosphere(),
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                // 顶部状态栏 inset 由各页面自己的 TopAppBar（或详情页的 statusBarsPadding）消费；
                // 这里若沿用默认的 systemBars 内边距，会和它叠加成两倍状态栏高度的留白。
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .relayHazeSource(hazeState),
                ) {
                    AppTab.entries.forEach { item ->
                        val isSelected = (tab == item)
                        val pageActive = isSelected && uiStarted
                        val alpha by animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0f,
                            animationSpec = tween(durationMillis = 320),
                            label = "tab_fade_${item.name}",
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (isSelected) 1f else 0f)
                                .graphicsLayer {
                                    this.alpha = alpha
                                }
                                .background(Color.Transparent)
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
                                ),
                        ) {
                            CompositionLocalProvider(LocalRelayPageActive provides pageActive) {
                            when (item) {
                                AppTab.HOME -> HomeScreen(
                                    isActive = pageActive,
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
                                    onSelectMember = { memberId ->
                                        navigatedMemberId = memberId.ifBlank { null }
                                        tab = AppTab.MESSAGES
                                    },
                                    onSelectBlog = { blogId ->
                                        navigatedBlogId = blogId.ifBlank { null }
                                        tab = AppTab.BLOG
                                    },
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
                }
            }

            // The navigation overlays (and never enters) the scrolling Haze source.
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp, bottom = 12.dp),
            ) {
                val navigationInset = 0.dp
                val indicatorHeight = 74.dp
                val indicatorShape = RelayNavigationBarShape
                // The outer card hugs the selectable item row and moving indicator.
                val navigationCardShape = RelayNavigationBarShape
                val selectedIndex = AppTab.entries.indexOf(tab)
                val itemWidth = (maxWidth - navigationInset * 2) / AppTab.entries.size
                val indicatorOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.6f,
                        stiffness = Spring.StiffnessLow,
                    ),
                    label = "navigation_glass_indicator_offset",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(indicatorHeight + navigationInset * 2),
                ) {
                    // Only this empty background layer samples the page. Keeping controls
                    // and the indicator out of Haze avoids rectangular/self-sampled layers.
                    RelayMirrorGlassBackground(
                        shape = navigationCardShape,
                        modifier = Modifier.matchParentSize(),
                    )
                    RelayMirrorGlassSelection(
                        shape = indicatorShape,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            // Let the spring travel past either end and return. Only the
                            // backdrop is clipped, never this moving foreground layer.
                            .offset { IntOffset((navigationInset + indicatorOffset).roundToPx(), 0) }
                            .size(width = itemWidth, height = indicatorHeight),
                    )
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(navigationInset)
                            .selectableGroup(),
                    ) {
                        AppTab.entries.forEach { item ->
                            RelayNavigationBarItem(
                                selected = tab == item,
                                onClick = {
                                    if (tab != item) {
                                        tab = item
                                    }
                                },
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
                }
            }
        }
    }
}
