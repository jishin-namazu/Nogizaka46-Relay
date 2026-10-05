package com.nogirelay.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.sync.SyncStatus
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.ui.SyncGlyph
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassHeader
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassMetrics
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.settings.SettingsPage
import com.nogirelay.app.ui.settings.fullScreenPermissionHint
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive

/** Notifications, full-screen calls, background launch and server push. */
private const val HEALTH_ITEM_COUNT = 4

// Concentric nested corners: outer radius = inset + inner radius.
private val HomeCardInset = 12.dp
private val HomeTileRadius = 34.dp - HomeCardInset
private val HomeTileShape = RoundedCornerShape(HomeTileRadius)
private val HomeTileInset = 14.dp
private val HomeTileButtonShape = RoundedCornerShape(HomeTileRadius - HomeTileInset)

@Composable
fun HomeScreen(
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    syncStatus: SyncStatus,
    onSyncHistory: () -> Unit,
    onOpenSettings: (SettingsPage?) -> Unit,
    reselected: Flow<Unit> = emptyFlow(),
) {
    val context = LocalContext.current
    val firebaseConfigured = remember { PushRegistrar.isConfigured(context) }
    val pushRegistered by AppGraph.settings.pushRegistered.collectAsStateWithLifecycle()
    val pushReady = firebaseConfigured && pushRegistered
    val readyCount = listOf(notificationGranted, fullScreenGranted, overlayGranted, pushReady).count { it }

    val scrollState = rememberScrollState()
    LaunchedEffect(reselected) {
        reselected.collect { scrollState.animateScrollTo(0) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            GlassHeader(
                title = "Nogi Relay",
                actions = {
                    GlassIconButton(
                        onClick = { onOpenSettings(null) },
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = "设置",
                    )
                },
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 6.dp, bottom = 24.dp),
            ) {
                HeroStatusCard(
                    pushReady = pushReady,
                    isSyncing = syncStatus.syncing,
                    syncLabel = syncStatus.label,
                    onSyncHistory = onSyncHistory,
                    onOpenSettings = { onOpenSettings(SettingsPage.CONNECTION) },
                )

                SystemHealthPanel(
                    readyCount = readyCount,
                    notificationGranted = notificationGranted,
                    fullScreenGranted = fullScreenGranted,
                    overlayGranted = overlayGranted,
                    firebaseConfigured = firebaseConfigured,
                    pushRegistered = pushRegistered,
                    onRequestNotifications = onRequestNotifications,
                    onOpenFullScreenSettings = onOpenFullScreenSettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenSettings = { onOpenSettings(SettingsPage.CONNECTION) },
                )

                SettingsEntrancePanel(onClick = { onOpenSettings(null) })

                Spacer(Modifier.height(128.dp))
            }
        }

    }
}

@Composable
private fun HeroStatusCard(
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
    // The status light holds still when decorative motion is reduced.
    val reducedMotion = LocalGlassReducedMotion.current
    LaunchedEffect(visualActive, pushReady, reducedMotion) {
        if (reducedMotion) pulseAlpha.snapTo(0.65f)
        if (visualActive && pushReady && !reducedMotion) while (isActive) {
            pulseAlpha.animateTo(0.95f, tween(1200, easing = LinearEasing))
            pulseAlpha.animateTo(0.35f, tween(1200, easing = LinearEasing))
        }
    }

    GlassPanel(
        shape = GlassShapes.CardLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(HomeCardInset)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Bare status light: retain the pulse without a surrounding glass ring.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(HomeTileRadius * 2),
                ) {
                    if (pushReady) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .drawBehind { drawCircle(GlassColors.Success.copy(alpha = pulseAlpha.value * 0.40f)) },
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(GlassShapes.Circle)
                            .background(if (pushReady) GlassColors.Success else GlassColors.Warning),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = if (pushReady) "推送已就绪" else "推送待配置",
                        color = GlassColors.Ink,
                        fontWeight = FontWeight.Bold,
                        style = GlassType.Title3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = syncLabel.takeIf { it.isNotBlank() }
                            ?: if (pushReady) "监听中" else "请配置同步地址与访问令牌",
                        color = GlassColors.InkSecondary,
                        style = GlassType.Subhead,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                com.nogirelay.app.ui.glass.GlassCircleButton(
                    onClick = onSyncHistory,
                    enabled = !isSyncing,
                    contentDescription = if (isSyncing) "同步中" else "立即同步",
                    size = HomeTileRadius * 2,
                ) {
                    SyncGlyph(isSyncing = isSyncing, tint = GlassColors.Accent)
                }
            }

            if (!pushReady) {
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GlassCapsuleButton(
                        onClick = onOpenSettings,
                        tone = GlassTone.Accent,
                        modifier = Modifier.fillMaxWidth(),
                        height = GlassMetrics.ControlHeight,
                        shape = HomeTileShape,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("立即配置", style = GlassType.Subhead, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemHealthPanel(
    readyCount: Int,
    notificationGranted: Boolean,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    firebaseConfigured: Boolean,
    pushRegistered: Boolean,
    onRequestNotifications: () -> Unit,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val allGranted = readyCount == HEALTH_ITEM_COUNT
    GlassPanel(
        shape = GlassShapes.CardLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(HomeCardInset)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp),
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (allGranted) GlassColors.Success else GlassColors.Danger,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f).padding(vertical = 2.dp, horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "系统运行能力",
                        fontWeight = FontWeight.SemiBold,
                        style = GlassType.Headline,
                        color = GlassColors.Ink,
                    )
                    Text(
                        text = if (allGranted) {
                            "全部就绪 · $readyCount/$HEALTH_ITEM_COUNT"
                        } else {
                            "已就绪 $readyCount/$HEALTH_ITEM_COUNT，完成其余设置以确保消息和来电正常提醒"
                        },
                        style = GlassType.Footnote,
                        color = if (allGranted) GlassColors.Success else GlassColors.InkSecondary,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                ) {
                    PermissionTile(
                        title = "通知权限",
                        description = if (notificationGranted) "系统通知已启用" else "需要授权后才能接收新消息",
                        granted = notificationGranted,
                        imageVector = Icons.Rounded.Notifications,
                        action = onRequestNotifications,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    PermissionTile(
                        title = "全屏来电",
                        description = if (fullScreenGranted) "允许在锁屏上显示成员来电" else fullScreenPermissionHint(),
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
                    PermissionTile(
                        title = "后台弹出界面",
                        description = if (overlayGranted) "允许应用在后台直接弹出全屏来电" else "部分设备需要此权限才能弹出后台来电",
                        granted = overlayGranted,
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        action = onOpenOverlaySettings,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    PermissionTile(
                        title = "系统推送",
                        description = when {
                            !firebaseConfigured -> "推送服务尚未配置"
                            !pushRegistered -> "请完成设备推送注册"
                            else -> "可接收服务器推送提醒"
                        },
                        granted = firebaseConfigured && pushRegistered,
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
private fun PermissionTile(
    title: String,
    description: String,
    granted: Boolean,
    imageVector: ImageVector,
    action: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        shape = HomeTileShape,
        depth = com.nogirelay.app.ui.glass.GlassDepths.None,
        fillAlpha = 0.30f,
        blur = 14.dp,
        edgeStrength = 0.6f,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(HomeTileInset)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = imageVector,
                    contentDescription = null,
                    tint = if (granted) GlassColors.Success else GlassColors.Danger,
                    modifier = Modifier.padding(top = 2.dp).size(17.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = title,
                    style = GlassType.Callout,
                    fontWeight = FontWeight.SemiBold,
                    color = GlassColors.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = description,
                color = GlassColors.InkSecondary,
                style = GlassType.Footnote,
            )
            if (!granted) {
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(10.dp))
                GlassCapsuleButton(
                    onClick = action,
                    tone = GlassTone.Accent,
                    modifier = Modifier.fillMaxWidth(),
                    shape = HomeTileButtonShape,
                    height = GlassMetrics.CompactControlHeight,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp),
                ) {
                    Text("开启", style = GlassType.Footnote, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SettingsEntrancePanel(onClick: () -> Unit) {
    GlassPanel(
        shape = GlassShapes.Card,
        onClick = onClick,
        onClickLabel = "打开设置",
        modifier = Modifier.fillMaxWidth().height(GlassMetrics.ControlHeight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // The icon disc sits concentric with the capsule's rounded end.
            modifier = Modifier.fillMaxSize().padding(horizontal = 5.dp),
        ) {
            Box(
                modifier = Modifier.size(GlassMetrics.ControlHeight - 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                GlassPanel(shape = GlassShapes.Circle, modifier = Modifier.fillMaxSize(), depth = com.nogirelay.app.ui.glass.GlassDepths.None, fillAlpha = 0.34f, blur = 12.dp) {}
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = GlassColors.Accent,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "设置",
                style = GlassType.Callout,
                fontWeight = FontWeight.SemiBold,
                color = GlassColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = GlassColors.InkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}


fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

