package com.nogirelay.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import com.nogirelay.app.NameWithUnreadTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleDark
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SignalCoral
import com.nogirelay.app.ui.SignalGreen
import com.nogirelay.app.ui.messages.MemberThread
import com.nogirelay.app.ui.navigation.RelayIconButton
import com.nogirelay.app.ui.settings.SettingsSection
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
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
    onSelectMember: ((String) -> Unit)? = null,
    onSelectBlog: ((String) -> Unit)? = null,
) {
    val settings = remember(dataVersion) { AppGraph.settings.read() }
    val context = LocalContext.current
    val firebaseConfigured = remember(dataVersion) { PushRegistrar.isConfigured(context) }
    val tokenRegistered = remember(dataVersion) { AppGraph.settings.pushToken().isNotBlank() }
    val pushConfigured = firebaseConfigured && tokenRegistered
    val pushReady = pushConfigured && settings.relayUrl.isNotBlank() && settings.accessToken.isNotBlank()

    val allGranted = notificationGranted && fullScreenGranted && overlayGranted && pushConfigured
    var permissionsExpanded by remember(allGranted) { mutableStateOf(!allGranted) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    val sheetScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    fun closeSettingsSheet() {
        sheetScope.launch {
            sheetState.hide()
            showSettingsSheet = false
        }
    }

    var recentMembers by remember { mutableStateOf<List<MemberThread>>(emptyList()) }
    var recentBlogs by remember { mutableStateOf<List<BlogSummary>>(emptyList()) }

    LaunchedEffect(dataVersion) {
        withContext(AppGraph.dispatchers.databaseRead) {
            val latestPerMember = AppGraph.database.latestMessagePerMember()
            val unreadCounts = AppGraph.database.unreadCountsByMember()
            val threads = latestPerMember.map { msg ->
                MemberThread(
                    id = msg.memberKey,
                    name = msg.memberName,
                    avatarUrl = msg.memberAvatarUrl,
                    latest = msg,
                    unreadCount = unreadCounts[msg.memberKey] ?: 0,
                )
            }.sortedByDescending { it.latest.sentAt }
            recentMembers = threads.take(6)

            val blogs = AppGraph.database.blogSummaries(limit = 8)
            recentBlogs = blogs
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandPurple),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Nogi Relay",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            actions = {
                RelayIconButton(
                    onClick = { showSettingsSheet = true },
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "系统与翻译设置",
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
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
        }
    }

    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Column {
                        Text(
                            text = "系统与翻译设置",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    RelayIconButton(
                        onClick = ::closeSettingsSheet,
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭设置",
                    )
                }
                Spacer(Modifier.height(14.dp))
                SettingsSection(
                    onSettingsChanged = onSettingsChanged,
                    onTestCall = onTestCall,
                )
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
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_halo")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    val syncRotation by animateFloatAsState(
        targetValue = if (isSyncing) 360f else 0f,
        animationSpec = if (isSyncing) {
            infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            )
        } else {
            tween(0)
        },
        label = "sync_spin",
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(BrandPurple, BrandPurpleDark),
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(24.dp),
                    ) {
                        if (pushReady) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(SignalGreen.copy(alpha = pulseAlpha * 0.45f)),
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
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                }

                FilledTonalButton(
                    onClick = onSyncHistory,
                    enabled = !isSyncing,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.22f),
                        contentColor = Color.White,
                        disabledContainerColor = Color.White.copy(alpha = 0.12f),
                        disabledContentColor = Color.White.copy(alpha = 0.6f),
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = syncRotation },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isSyncing) "同步中..." else "立即同步",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
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
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            if (!pushReady) {
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("立即配置", fontSize = 12.sp)
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
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
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
                    indication = rememberRipple(color = BrandPurple.copy(alpha = 0.15f)),
                    onClick = onToggleExpand,
                )
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
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
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectMember("") },
            ) {
                Text(
                    text = "查看全部",
                    fontSize = 12.sp,
                    color = BrandPurple,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = BrandPurple,
                    modifier = Modifier.size(16.dp),
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
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelectBlog("") },
            ) {
                Text(
                    text = "浏览全部",
                    fontSize = 12.sp,
                    color = BrandPurple,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = BrandPurple,
                    modifier = Modifier.size(16.dp),
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
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .width(260.dp)
                        .clickable { onSelectBlog(blog.id) },
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
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
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!blog.imageUrl.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(135.dp)
                                    .clip(RoundedCornerShape(10.dp)),
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
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrandPurple.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = BrandPurple,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "系统与翻译设置",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "配置 FCM 服务、自定义昵称及大模型参数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            FilledTonalButton(
                onClick = action,
                shape = RelayControlShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text("开启", fontSize = 12.sp) }
        }
    }
}

fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
