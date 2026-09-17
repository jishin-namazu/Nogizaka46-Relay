package com.nogirelay.app.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.SignalCoral
import com.nogirelay.app.ui.SignalGreen
import com.nogirelay.app.ui.settings.SettingsSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
) {
    val settings = remember(dataVersion) { AppGraph.settings.read() }
    val context = LocalContext.current
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
fun StatusBand(pushReady: Boolean) {
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

fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
