package com.nogirelay.app.ui.navigation

import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.semantics
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nogirelay.app.blog.BlogScreen
import com.nogirelay.app.call.FullScreenPermission
import com.nogirelay.app.call.OverlayPermission
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.LocalRelayPageActive
import com.nogirelay.app.performance.isRelayUiStarted
import com.nogirelay.app.ui.glass.GlassBackdrop
import com.nogirelay.app.ui.glass.GlassMotion
import com.nogirelay.app.ui.glass.GlassNavBar
import com.nogirelay.app.ui.glass.GlassNavItem
import com.nogirelay.app.ui.glass.LocalGlassHazeState
import com.nogirelay.app.ui.glass.LocalGlassOverlayHazeState
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.glass.glassHazeSource
import com.nogirelay.app.ui.glass.rememberGlassHazeState
import com.nogirelay.app.ui.home.HomeScreen
import com.nogirelay.app.ui.home.hasNotificationPermission
import com.nogirelay.app.ui.messages.MessagesScreen
import com.nogirelay.app.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private val SettingsSlideSpring = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/**
 * App shell: one continuous milky-glass world.
 *
 * A single [GlassBackdrop] at the root is the shared haze source for every
 * glass surface on every page, so content visibly scrolls behind the
 * floating navigation capsule. Pages are kept alive, and each keeps its own
 * place (open conversation or article, filters, scroll) while another tab
 * shows; tapping the current tab again backs out of a detail or scrolls to
 * the top. Pages transition spatially (gentle rise + settle).
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
    onUpdateProximity: (VoicePlaybackState) -> Unit,
    viewModel: RelayAppViewModel = viewModel(),
) {
    val context = LocalContext.current
    val initialMessageId by notificationMessageIds.collectAsStateWithLifecycle()
    val initialBlogId by notificationBlogIds.collectAsStateWithLifecycle()
    val shell by viewModel.shell.collectAsStateWithLifecycle()
    val unreadMessageCount by viewModel.unreadMessages.collectAsStateWithLifecycle()
    val unreadBlogCount by viewModel.unreadBlogs.collectAsStateWithLifecycle()
    val syncStatus by viewModel.sync.collectAsStateWithLifecycle()
    val uiStarted = isRelayUiStarted()
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var fullScreenGranted by remember { mutableStateOf(FullScreenPermission.canUse(context)) }
    var overlayGranted by remember { mutableStateOf(OverlayPermission.canUse(context)) }

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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A notification deep link must not land behind the settings.
    LaunchedEffect(initialMessageId) {
        if (initialMessageId != null) viewModel.showFromNotification(AppTab.MESSAGES)
    }
    LaunchedEffect(initialBlogId) {
        if (initialBlogId != null) viewModel.showFromNotification(AppTab.BLOG)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    LaunchedEffect(Unit) {
        VoicePlaybackService.playbackState
            .map { it.copy(positionMs = 0, durationMs = 0, sampledAtMillis = 0) }
            .distinctUntilChanged()
            .collect { onUpdateProximity(it) }
    }

    BackHandler(enabled = shell.tab != AppTab.HOME && !shell.settingsOpen) {
        viewModel.backToHome()
    }

    val openFullScreenSettings: () -> Unit = {
        FullScreenPermission.settingsIntent(context)?.let(context::startActivity)
    }
    val openOverlaySettings: () -> Unit = { context.startActivity(OverlayPermission.settingsIntent(context)) }

    // Capture pages separately from their own glass materials and the nav overlay.
    val navigationHazeState = rememberGlassHazeState()

    CompositionLocalProvider(LocalGlassOverlayHazeState provides navigationHazeState) {
        // Test tags double as resource ids for UI automation (baseline profiles).
        GlassBackdrop(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().glassHazeSource(navigationHazeState)) {
                    val reducedMotion = LocalGlassReducedMotion.current
                    AppTab.entries.forEach { item ->
                        val isSelected = shell.tab == item
                        val pageActive = isSelected && uiStarted && !shell.settingsOpen
                        val reselected = remember(item) { viewModel.reselected.filter { it == item }.map { } }
                        // Spatial continuity: the incoming page rises and settles
                        // on a spring; the outgoing page sinks and dims. Both stay
                        // composed so scroll positions and playback survive.
                        val presence = remember { Animatable(if (isSelected) 1f else 0f) }
                        LaunchedEffect(isSelected) {
                            val target = if (isSelected) 1f else 0f
                            if (reducedMotion) {
                                presence.snapTo(target)
                            } else {
                                presence.animateTo(
                                    target,
                                    if (isSelected) GlassMotion.MorphSpec else GlassMotion.GentleSpec,
                                )
                            }
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
                                    if (!isSelected || shell.settingsOpen) {
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
                                        notificationGranted = notificationGranted,
                                        fullScreenGranted = fullScreenGranted,
                                        overlayGranted = overlayGranted,
                                        onRequestNotifications = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        },
                                        onOpenFullScreenSettings = openFullScreenSettings,
                                        onOpenOverlaySettings = openOverlaySettings,
                                        syncStatus = syncStatus,
                                        onSyncHistory = viewModel::requestSync,
                                        onOpenSettings = viewModel::openSettings,
                                        reselected = reselected,
                                    )

                                    AppTab.MESSAGES -> MessagesScreen(
                                        isActive = isSelected,
                                        initialMessageId = initialMessageId,
                                        onInitialMessageHandled = onNotificationMessageHandled,
                                        onOpenMedia = onOpenMedia,
                                        onPlayVoice = onPlayVoice,
                                        onOpenSettings = viewModel::openSettings,
                                        reselected = reselected,
                                    )

                                    AppTab.BLOG -> BlogScreen(
                                        isActive = isSelected,
                                        initialBlogId = initialBlogId,
                                        onInitialBlogHandled = onNotificationBlogHandled,
                                        reselected = reselected,
                                    )
                                }
                            }
                        }
                    }
                }

                CompositionLocalProvider(LocalGlassHazeState provides navigationHazeState) {
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
                        selectedIndex = AppTab.entries.indexOf(shell.tab),
                        onSelected = { viewModel.selectTab(AppTab.entries[it]) },
                        onReselected = { viewModel.reselectTab(AppTab.entries[it]) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .zIndex(2f)
                            .then(if (shell.settingsOpen) Modifier.clearAndSetSemantics { } else Modifier)
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp)
                            .padding(top = 6.dp, bottom = 16.dp)
                            .width(192.dp),
                    )
                }

                AnimatedVisibility(
                    visible = shell.settingsOpen,
                    enter = fadeIn(tween(200)) + slideInHorizontally(SettingsSlideSpring) { it / 4 },
                    exit = fadeOut(tween(160)) + slideOutHorizontally(SettingsSlideSpring) { it / 4 },
                    modifier = Modifier.zIndex(3f),
                ) {
                    SettingsScreen(
                        initialPage = shell.settingsStartPage,
                        fullScreenGranted = fullScreenGranted,
                        overlayGranted = overlayGranted,
                        syncStatus = syncStatus,
                        onOpenFullScreenSettings = openFullScreenSettings,
                        onOpenOverlaySettings = openOverlaySettings,
                        onTestCall = onTestCall,
                        onSync = viewModel::requestSync,
                        onClose = viewModel::closeSettings,
                    )
                }
            }
        }
    }
}
