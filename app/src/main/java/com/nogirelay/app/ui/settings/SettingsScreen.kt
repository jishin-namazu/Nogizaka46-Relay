package com.nogirelay.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.IncomingCallStyle
import com.nogirelay.app.data.ThemeMode
import com.nogirelay.app.data.sync.SyncStatus
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.performance.LocalRelayPageWorkPaused
import com.nogirelay.app.push.PushRegistrar
import com.nogirelay.app.translation.TranslationHealth
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.glass.GlassBackdrop
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDetailHeader
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassSwitch
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassOverlayHazeState
import com.nogirelay.app.ui.glass.glassHazeSource
import com.nogirelay.app.ui.glass.rememberGlassHazeState
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.transfer.DataTransferDrawer

/** The settings groups, each one page. */
enum class SettingsPage(val title: String) {
    CONNECTION("连接"),
    TRANSLATION("翻译"),
    CALL("来电"),
    DATA("数据"),
}

/**
 * Full-screen settings: an index of the four groups, each opening its own
 * page. Opening with [initialPage] lands directly on that group; back then
 * returns to the index before leaving.
 */
@Composable
fun SettingsScreen(
    initialPage: SettingsPage?,
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    syncStatus: SyncStatus,
    onOpenFullScreenSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTestCall: () -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var dataKind by remember { mutableStateOf<ExportKind?>(null) }
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val hazeState = rememberGlassHazeState()

    BackHandler {
        if (page != null) page = null else onClose()
    }

    GlassBackdrop(
        modifier = Modifier
            .fillMaxSize()
            // Covers the app beneath: no touches fall through to it.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        CompositionLocalProvider(
            // Dialogs and sheets opened here frost this screen, not the app below.
            LocalGlassOverlayHazeState provides hazeState,
            LocalRelayPageWorkPaused provides sheetBackdrop.isAttached,
        ) {
            Box(Modifier.fillMaxSize().glassHazeSource(hazeState)) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        val slideSpring = spring<IntOffset>(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        )
                        val forward = targetState != null
                        (
                            fadeIn(tween(200)) + slideInHorizontally(slideSpring) { width ->
                                if (forward) width / 5 else -width / 6
                            }
                            ).togetherWith(
                            fadeOut(tween(160)) + slideOutHorizontally(slideSpring) { width ->
                                if (forward) -width / 6 else width / 5
                            },
                        )
                    },
                    label = "settings_page",
                    modifier = Modifier.fillMaxSize(),
                ) { shown ->
                    val back = { page = null }
                    when (shown) {
                        null -> SettingsIndex(
                            fullScreenGranted = fullScreenGranted,
                            overlayGranted = overlayGranted,
                            syncStatus = syncStatus,
                            onOpen = { page = it },
                            onClose = onClose,
                        )
                        SettingsPage.CONNECTION -> ConnectionSettingsPage(onBack = back)
                        SettingsPage.TRANSLATION -> TranslationSettingsPage(onBack = back)
                        SettingsPage.CALL -> CallSettingsPage(
                            fullScreenGranted = fullScreenGranted,
                            overlayGranted = overlayGranted,
                            onOpenFullScreenSettings = onOpenFullScreenSettings,
                            onOpenOverlaySettings = onOpenOverlaySettings,
                            onTestCall = onTestCall,
                            onBack = back,
                        )
                        SettingsPage.DATA -> DataSettingsPage(
                            isSyncing = syncStatus.syncing,
                            syncLabel = syncStatus.label,
                            onSync = onSync,
                            onOpenDataManagement = { dataKind = it },
                            onBack = back,
                        )
                    }
                }
            }

            dataKind?.let { kind ->
                DataTransferDrawer(
                    kind = kind,
                    backdropState = sheetBackdrop,
                    onDismiss = { dataKind = null },
                )
            }
        }
    }
}

@Composable
private fun SettingsIndex(
    fullScreenGranted: Boolean,
    overlayGranted: Boolean,
    syncStatus: SyncStatus,
    onOpen: (SettingsPage) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val pushRegistered by AppGraph.settings.pushRegistered.collectAsStateWithLifecycle()
    val translationIssue by TranslationHealth.issue.collectAsStateWithLifecycle()
    val pushReady = remember(pushRegistered) { PushRegistrar.isConfigured(context) && pushRegistered }
    val callPermissionsReady = fullScreenGranted && overlayGranted

    Column(Modifier.fillMaxSize()) {
        GlassDetailHeader(
            title = "设置",
            onBack = onClose,
            backContentDescription = "关闭设置",
        )
        // Scrolls, so large font sizes and small screens still reach every group.
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 6.dp, bottom = 24.dp),
        ) {
            SettingsGroupRow(
                icon = { Icon(Icons.Rounded.Cloud, contentDescription = null, tint = GlassColors.Accent, modifier = Modifier.size(20.dp)) },
                title = SettingsPage.CONNECTION.title,
                summary = buildString {
                    append(if (pushReady) "推送已就绪" else "推送待配置")
                    settings.userNickname.takeIf(String::isNotBlank)?.let { append(" · 昵称 ").append(it) }
                },
                needsAttention = !pushReady,
                onClick = { onOpen(SettingsPage.CONNECTION) },
            )
            val issue = translationIssue.takeIf { settings.translationEnabled }
            SettingsGroupRow(
                icon = { AiTranslateIcon(size = 20.dp, tint = GlassColors.Accent, contentDescription = null) },
                title = SettingsPage.TRANSLATION.title,
                summary = when {
                    !settings.translationEnabled -> "AI 翻译未开启"
                    issue != null -> issue.title
                    else -> "${settings.aiProvider.displayName} · ${settings.aiModel.ifBlank { "未选择模型" }}"
                },
                needsAttention = settings.translationEnabled && (settings.aiModel.isBlank() || issue != null),
                attentionIsError = issue?.blocking == true,
                onClick = { onOpen(SettingsPage.TRANSLATION) },
            )
            SettingsGroupRow(
                icon = { Icon(Icons.Rounded.Call, contentDescription = null, tint = GlassColors.Accent, modifier = Modifier.size(20.dp)) },
                title = SettingsPage.CALL.title,
                summary = buildString {
                    append(if (settings.incomingCallStyle == IncomingCallStyle.LIQUID_GLASS) "液态玻璃" else "经典")
                    append("来电页面")
                    if (!callPermissionsReady) append(" · 权限待开启")
                },
                needsAttention = !callPermissionsReady,
                onClick = { onOpen(SettingsPage.CALL) },
            )
            SettingsGroupRow(
                icon = { Icon(Icons.Rounded.Inventory2, contentDescription = null, tint = GlassColors.Accent, modifier = Modifier.size(20.dp)) },
                title = SettingsPage.DATA.title,
                summary = syncStatus.label.ifBlank { "同步、导入导出与补齐媒体" },
                needsAttention = syncStatus.failed,
                attentionIsError = syncStatus.failed,
                onClick = { onOpen(SettingsPage.DATA) },
            )
            AppearanceCard()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** Light / dark appearance and the glass material; applies at once to every screen. */
@Composable
private fun AppearanceCard() {
    val mode by AppGraph.settings.themeMode.collectAsStateWithLifecycle()
    val blurEnabled by AppGraph.settings.glassBlurEnabled.collectAsStateWithLifecycle()
    val reduceMotion by AppGraph.settings.reduceMotion.collectAsStateWithLifecycle()
    SettingsCard(title = "外观") {
        GlassSegmentedTabs(
            labels = ThemeMode.entries.map(ThemeMode::label),
            selectedIndex = mode.ordinal,
            onSelected = { AppGraph.settings.saveThemeMode(ThemeMode.entries[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
        AppearanceToggle(
            title = "玻璃模糊",
            description = "关闭后卡片改用实色底，更省电，文字也更清晰。省电模式、低内存或设备过热时会自动关闭。",
            checked = blurEnabled,
            onCheckedChange = AppGraph.settings::saveGlassBlurEnabled,
        )
        AppearanceToggle(
            title = "减少动效",
            description = "去掉页面过渡、呼吸灯等装饰动画。系统关闭动画或省电模式时会自动减少。",
            checked = reduceMotion,
            onCheckedChange = AppGraph.settings::saveReduceMotion,
        )
    }
}

@Composable
private fun AppearanceToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = GlassType.Callout, fontWeight = FontWeight.SemiBold, color = GlassColors.Ink)
            Text(description, style = GlassType.Footnote, color = GlassColors.InkSecondary)
        }
        Spacer(Modifier.width(10.dp))
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange, label = title)
    }
}

@Composable
private fun SettingsGroupRow(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    needsAttention: Boolean,
    onClick: () -> Unit,
    attentionIsError: Boolean = false,
) {
    GlassPanel(
        shape = GlassShapes.Card,
        onClick = onClick,
        onClickLabel = "打开${title}设置",
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                GlassPanel(
                    shape = GlassShapes.Circle,
                    modifier = Modifier.fillMaxSize(),
                    depth = com.nogirelay.app.ui.glass.GlassDepths.None,
                    fillAlpha = 0.34f,
                    blur = 12.dp,
                ) {}
                icon()
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = GlassType.Headline,
                        color = GlassColors.Ink,
                        maxLines = 1,
                    )
                    if (needsAttention) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .size(7.dp)
                                .clip(GlassShapes.Circle)
                                .background(if (attentionIsError) GlassColors.Danger else GlassColors.Warning),
                        )
                    }
                }
                Text(
                    text = summary,
                    style = GlassType.Footnote,
                    fontWeight = FontWeight.Normal,
                    color = GlassColors.InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = GlassColors.InkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
