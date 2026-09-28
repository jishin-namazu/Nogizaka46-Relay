package com.nogirelay.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Badge
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nogirelay.app.NameWithUnreadTag
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.RelayCardContentInset
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RelayGlassBackdrop
import com.nogirelay.app.ui.RelayHomeCardShape
import com.nogirelay.app.ui.RelayMirrorGlassButton
import com.nogirelay.app.ui.RelayMirrorGlassCard
import com.nogirelay.app.ui.RelayMirrorGlassIcon
import com.nogirelay.app.ui.RelayMirrorGlassIconButton
import com.nogirelay.app.ui.RelayModalBottomSheet
import com.nogirelay.app.ui.RelayNavigationSelectionShape
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SignalCoral
import com.nogirelay.app.ui.SignalGreen
import com.nogirelay.app.ui.messages.MemberThread
import com.nogirelay.app.ui.relaySheetBackdrop
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.settings.SettingsSection
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import androidx.compose.ui.draw.drawBehind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    versions: DataVersions,
    isActive: Boolean = true,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestCall: () -> Unit,
    isSyncing: Boolean,
    syncLabel: String,
    onSyncHistory: () -> Unit,
    onSettingsChanged: () -> Unit,
    onSelectMember: ((String) -> Unit)? = null,
    onSelectBlog: ((String) -> Unit)? = null,
) {
    val settings = remember(versions.settings) { AppGraph.settings.read() }
    val context = LocalContext.current
    val firebaseConfigured = remember(versions.settings) { PushRegistrar.isConfigured(context) }
    val tokenRegistered = remember(versions.settings) { AppGraph.settings.pushToken().isNotBlank() }
    val pushConfigured = firebaseConfigured && tokenRegistered
    val pushReady = pushConfigured && settings.relayUrl.isNotBlank() && settings.accessToken.isNotBlank()

    val allGranted = notificationGranted && fullScreenGranted && overlayGranted && pushConfigured
    var permissionsExpanded by remember(allGranted) { mutableStateOf(!allGranted) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val workActive = isActive && !sheetBackdrop.isAttached
    val scrollState = rememberScrollState()

    var recentMembers by remember { mutableStateOf<List<MemberThread>>(emptyList()) }
    var recentBlogs by remember { mutableStateOf<List<BlogSummary>>(emptyList()) }

    LaunchedEffect(versions.messages, workActive) {
        if (!workActive) return@LaunchedEffect
        recentMembers = AppGraph.memberSummaries.load(versions).take(6).map { (msg, unreadCount) ->
                MemberThread(
                    id = msg.memberKey,
                    name = msg.memberName,
                    avatarUrl = msg.memberAvatarUrl,
                    latest = msg,
                    unreadCount = unreadCount,
                )
        }
    }
    LaunchedEffect(versions.blogs, workActive) {
        if (!workActive) return@LaunchedEffect
        recentBlogs = withContext(AppGraph.dispatchers.databaseRead) { AppGraph.database.blogSummaries(limit = 8) }
    }

    val pageBackground: () -> Brush = remember(scrollState) {
        {
            val transition = (scrollState.value / 420f).coerceIn(0f, 1f)
            Brush.verticalGradient(listOf(
                blendHomeBackground(Color(0xFFF8F4FA), Color(0xFFEFF2F7), transition),
                blendHomeBackground(Color(0xFFF2F8F8), Color(0xFFE6F1F3), transition),
                blendHomeBackground(Color(0xFFF8F3F2), Color(0xFFF1EAF5), transition),
            ))
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.nogirelay.app.performance.LocalRelayPageWorkPaused provides sheetBackdrop.isAttached,
    ) {
        RelayGlassBackdrop(
            background = pageBackground,
            modifier = Modifier
                .fillMaxSize()
                .relaySheetBackdrop(sheetBackdrop),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
            ) {
                // This inset is part of the scroll content, so the toolbar and its background
                // leave the status bar together when the user scrolls.
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RelayMirrorGlassIcon(
                                imageVector = Icons.Rounded.Home,
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Nogi Relay",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    },
                    actions = {
                        RelayMirrorGlassIconButton(
                            onClick = { showSettingsSheet = true },
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "系统与翻译设置",
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 24.dp),
                ) {
                    RelayHeroCard(
                        pushReady = pushReady,
                        isSyncing = isSyncing,
                        syncLabel = syncLabel,
                        onSyncHistory = onSyncHistory,
                        onOpenSettings = { showSettingsSheet = true },
                    )

                    SystemHealthSection(
                        allGranted = allGranted,
                        expanded = permissionsExpanded,
                        onToggleExpand = { permissionsExpanded = !permissionsExpanded },
                        notificationGranted = notificationGranted,
                        fullScreenGranted = fullScreenGranted,
                        overlayGranted = overlayGranted,
                        firebaseConfigured = firebaseConfigured,
                        tokenRegistered = tokenRegistered,
                        onRequestNotifications = onRequestNotifications,
                        onOpenFullScreenSettings = onOpenFullScreenSettings,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onOpenSettings = { showSettingsSheet = true },
                    )

                    if (recentMembers.isNotEmpty()) {
                        RecentMembersSection(
                            members = recentMembers,
                            onSelectMember = { onSelectMember?.invoke(it) },
                        )
                    }

                    if (recentBlogs.isNotEmpty()) {
                        LatestBlogSection(
                            blogs = recentBlogs,
                            onSelectBlog = { onSelectBlog?.invoke(it) },
                        )
                    }

                    SettingsEntranceCard(onClick = { showSettingsSheet = true })
                    // The navigation controls float above the page, so keep the last card clear
                    // of their touch target without introducing a background strip.
                    Spacer(Modifier.height(128.dp))
                }
            }
            if (showSettingsSheet) {
                RelayModalBottomSheet(
                    onDismissRequest = { showSettingsSheet = false },
                    backdropState = sheetBackdrop,
                    borderless = true,
                    dragHandle = null,
                    contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                ) { dismiss ->
                    SettingsSection(
                        onSettingsChanged = onSettingsChanged,
                        onTestCall = onTestCall,
                        header = {
                            Column(Modifier.fillMaxWidth()) {
                                // Insets and the handle belong to the scroll content so there are no fixed
                                // strips clipping cards above or below the viewport.
                                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .size(width = 64.dp, height = 48.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClick = { dismiss { showSettingsSheet = false } },
                                        )
                                        .semantics { contentDescription = "收起设置" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BottomSheetDefaults.DragHandle(modifier = Modifier.clearAndSetSemantics {})
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text(
                                            text = "系统与翻译设置",
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    RelayMirrorGlassIconButton(
                                        onClick = { dismiss { showSettingsSheet = false } },
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "关闭设置",
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun RelayHeroCard(
    pushReady: Boolean,
    isSyncing: Boolean,
    syncLabel: String,
    onSyncHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val visualActive = com.nogirelay.app.performance.LocalRelayPageActive.current &&
        com.nogirelay.app.performance.isRelayUiStarted() &&
        !com.nogirelay.app.performance.LocalRelayPageWorkPaused.current
    val pulseAlpha = remember { Animatable(0.35f) }
    val syncRotation = remember { Animatable(0f) }
    LaunchedEffect(visualActive, pushReady) {
        if (visualActive && pushReady) while (isActive) {
            pulseAlpha.animateTo(0.95f, tween(1200, easing = LinearEasing))
            pulseAlpha.animateTo(0.35f, tween(1200, easing = LinearEasing))
        }
    }
    LaunchedEffect(visualActive, isSyncing) {
        if (!visualActive) return@LaunchedEffect
        if (isSyncing) while (isActive) {
            val remainingDuration = ((360f - syncRotation.value) / 360f * 900f).toInt().coerceAtLeast(1)
            syncRotation.animateTo(360f, tween(remainingDuration, easing = LinearEasing))
            syncRotation.snapTo(0f)
        } else if (syncRotation.value > 0f) {
            // Finish the current turn as the label fades back, avoiding a visible angle jump.
            syncRotation.animateTo(360f, tween(240, easing = FastOutSlowInEasing))
            syncRotation.snapTo(0f)
        }
    }

    RelayMirrorGlassCard(
        shape = RelayHomeCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(RelayCardContentInset)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).padding(end = 10.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(24.dp),
                    ) {
                        if (pushReady) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .drawBehind { drawCircle(SignalGreen.copy(alpha = pulseAlpha.value * 0.45f)) },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (pushReady) SignalGreen else Color(0xFFFFB300)),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (pushReady) "推送已就绪" else "推送待配置",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                RelayMirrorGlassButton(
                    onClick = onSyncHistory,
                    enabled = !isSyncing,
                    animateEnabledChanges = true,
                    shape = RelayNavigationSelectionShape,
                    modifier = Modifier.widthIn(max = 144.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = syncRotation.value },
                    )
                    Spacer(Modifier.width(6.dp))
                    AnimatedContent(
                        targetState = isSyncing,
                        transitionSpec = {
                            (
                                fadeIn(tween(180, delayMillis = 40)) +
                                    slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 4 }
                            ).togetherWith(
                                fadeOut(tween(120)) +
                                    slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { -it / 4 },
                            ).using(SizeTransform(clip = false) { _, _ -> tween(240, easing = FastOutSlowInEasing) })
                        },
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.weight(1f, fill = false),
                        label = "home_sync_label",
                    ) { syncing ->
                        Text(
                            text = if (syncing) "同步中..." else "立即同步",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (syncing == isSyncing) Modifier else Modifier.clearAndSetSemantics {},
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (syncLabel.isNotBlank()) {
                    syncLabel
                } else if (pushReady) {
                    "监听中"
                } else {
                    "尚未配置服务端同步地址与鉴权令牌，点击前往设置"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            if (!pushReady) {
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RelayMirrorGlassButton(
                        onClick = onOpenSettings,
                        shape = RelayNavigationSelectionShape,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("立即配置", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
            }
        }
    }
}
}

@Composable
fun SystemHealthSection(
    allGranted: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    firebaseConfigured: Boolean,
    tokenRegistered: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    RelayMirrorGlassCard(
        shape = RelayHomeCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (expanded) "收起" else "展开",
                    onClick = onToggleExpand,
                )
                .padding(horizontal = RelayCardContentInset),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (allGranted) SignalGreen else SignalCoral,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "系统运行能力",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (allGranted) "全部就绪 (4/4)" else "需要配置",
                    fontSize = 12.sp,
                    color = if (allGranted) SignalGreen else SignalCoral,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = BrandPurple,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(
                    start = RelayCardContentInset,
                    end = RelayCardContentInset,
                    bottom = RelayCardContentInset,
                ),
            ) {
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
                            action = onOpenSettings,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
            }
        }
    }
}

@Composable
fun RecentMembersSection(
    members: List<MemberThread>,
    onSelectMember: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                text = "最近消息",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            TextButton(
                onClick = { onSelectMember("") },
                shape = RelayNavigationSelectionShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.widthIn(max = 144.dp).heightIn(min = 48.dp),
            ) {
                Text(
                    text = "查看全部",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            members.forEach { member ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelectMember(member.id) }
                        .padding(vertical = 4.dp),
                ) {
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        RemoteImage(
                            url = member.avatarUrl,
                            contentDescription = member.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape),
                        )
                        if (member.unreadCount > 0) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                Text(
                                    text = if (member.unreadCount > 99) "99+" else member.unreadCount.toString(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = member.name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(60.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
fun LatestBlogSection(
    blogs: List<BlogSummary>,
    onSelectBlog: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                text = "最近博客",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            TextButton(
                onClick = { onSelectBlog("") },
                shape = RelayNavigationSelectionShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.widthIn(max = 144.dp).heightIn(min = 48.dp),
            ) {
                Text(
                    text = "浏览全部",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            blogs.forEach { blog ->
                RelayMirrorGlassCard(
                    shape = RelayHomeCardShape,
                    onClick = { onSelectBlog(blog.id) },
                    modifier = Modifier.width(260.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(RelayCardContentInset)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape),
                            ) {
                                RemoteImage(
                                    url = blog.memberAvatarUrl,
                                    contentDescription = blog.memberName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            NameWithUnreadTag(
                                name = androidx.compose.ui.text.AnnotatedString(blog.memberName),
                                isUnread = blog.isUnread,
                                compact = true,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = blog.publishedAt.take(10),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = blog.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!blog.imageUrl.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(135.dp)
                                    .clip(RelayControlShape),
                            ) {
                                RemoteImage(
                                    url = blog.imageUrl,
                                    contentDescription = blog.title,
                                    contentScale = ContentScale.Crop,
                                    loadCachedImmediately = false,
                                    placeholderColor = Color.Transparent,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsEntranceCard(onClick: () -> Unit) {
    RelayMirrorGlassCard(
        shape = RelayHomeCardShape,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(RelayCardContentInset),
        ) {
            RelayMirrorGlassIcon(
                imageVector = Icons.Rounded.Tune,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "系统与翻译设置",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "配置 FCM 服务、自定义昵称及大模型参数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    imageVector: ImageVector,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RelayMirrorGlassCard(
        shape = RelayHomeCardShape,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(RelayCardContentInset)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = if (granted) SignalGreen else SignalCoral,
                    modifier = Modifier.size(18.dp),
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
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                RelayMirrorGlassButton(
                    onClick = action,
                    shape = RelayNavigationSelectionShape,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) { Text("开启", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

private fun blendHomeBackground(start: Color, end: Color, fraction: Float): Color {
    val amount = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * amount,
        green = start.green + (end.green - start.green) * amount,
        blue = start.blue + (end.blue - start.blue) * amount,
        alpha = start.alpha + (end.alpha - start.alpha) * amount,
    )
}

fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
