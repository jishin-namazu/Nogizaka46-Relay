package com.nogirelay.app.ui.messages

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.AutoClearSelectionOnExit
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassHeader
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MessagesScreen(
    versions: DataVersions,
    initialMessageId: String?,
    initialMemberId: String? = null,
    onInitialMemberHandled: ((String) -> Unit)? = null,
    onInitialMessageHandled: (String) -> Unit,
    onUnreadChanged: (Set<String>) -> Unit,
    onOpenMedia: (RelayMessage, String) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    isActive: Boolean = true,
    viewModel: MessagesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val workActive = isActive && com.nogirelay.app.performance.isRelayUiStarted() && !sheetBackdrop.isAttached
    val downloadScope = rememberCoroutineScope()
    val retranslateScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(versions.messages, versions.settings, workActive) {
        if (workActive) viewModel.load(versions)
        onDispose { viewModel.stopLoading() }
    }

    val threads = uiState.threads
    val translationEnabled = uiState.translationEnabled
    val userNickname = uiState.userNickname
    val playbackFlow = remember(workActive) {
        if (workActive) VoicePlaybackService.playbackState else flowOf(VoicePlaybackService.playbackState.value)
    }
    val playbackState by playbackFlow.collectAsStateWithLifecycle(initialValue = VoicePlaybackState())
    var selectedEntry by remember { mutableStateOf<MemberMessageEntry?>(null) }
    // Set when the member was opened from its inbox card, which then expands.
    var openedCardThreadId by remember { mutableStateOf<String?>(null) }
    val selectedMemberId = selectedEntry?.memberKey
    var viewingLatest by remember(selectedEntry) { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RelayMessage?>(null) }
    val inboxListState = rememberLazyListState()
    var transitionContainerBounds by remember { mutableStateOf(Rect.Zero) }
    // The timeline stays composed while it collapses back into its card.
    var shownEntry by remember { mutableStateOf<MemberMessageEntry?>(null) }
    val containerProgress = remember { Animatable(0f, visibilityThreshold = 0.0002f) }
    // The entry whose first page of messages has loaded (see the open below).
    var contentReadyFor by remember { mutableStateOf<MemberMessageEntry?>(null) }
    val reducedMotion = LocalGlassReducedMotion.current

    fun openMember(memberKey: String, notificationMessageId: String? = null, fromCard: Boolean = false) {
        openedCardThreadId = memberKey.takeIf { fromCard }
        selectedEntry = MemberMessageEntry(
            memberKey = memberKey,
            playbackState = VoicePlaybackService.playbackState.value,
            notificationMessageId = notificationMessageId,
        )
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            selectedEntry = null
            inboxListState.scrollToItem(0)
        }
    }

    LaunchedEffect(selectedEntry) {
        val target = selectedEntry
        if (target != null) {
            val fromClosed = shownEntry == null
            shownEntry = target
            if (fromClosed) {
                // The timeline is still invisible inside the card. Wait (briefly)
                // for its first messages, then let them compose and lay out, so
                // no content lands mid-flight and the heavy frames happen before
                // anything moves.
                withTimeoutOrNull(OPEN_CONTENT_WAIT_MILLIS) {
                    snapshotFlow { contentReadyFor === target }.first { it }
                }
                withFrameNanos { }
                withFrameNanos { }
            }
            if (reducedMotion) containerProgress.snapTo(1f) else containerProgress.animateTo(1f, CardContainerSpring)
        } else if (shownEntry != null) {
            if (isActive) {
                if (!reducedMotion) containerProgress.animateTo(0f, CardContainerSpring)
                // Settle exactly on the card before it returns to its list state.
                containerProgress.snapTo(0f)
                withFrameNanos { }
            } else {
                containerProgress.snapTo(0f)
            }
            shownEntry = null
            openedCardThreadId = null
        }
    }

    DisposableEffect(selectedEntry, isActive, viewingLatest) {
        val memberKey = selectedMemberId
        if (isActive && memberKey != null) {
            MessageReadTracker.openMember(memberKey, viewingLatest = viewingLatest)
        }
        onDispose {
            if (memberKey != null) MessageReadTracker.closeMember(memberKey)
        }
    }

    LaunchedEffect(initialMessageId, isActive) {
        if (!isActive) return@LaunchedEffect
        val targetId = initialMessageId ?: return@LaunchedEffect
        val message = withContext(AppGraph.dispatchers.databaseRead) { AppGraph.database.find(targetId) }
        if (message == null) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        openMember(message.memberKey, notificationMessageId = targetId)
        initialMemberId?.let { onInitialMemberHandled?.invoke(it) }
    }

    LaunchedEffect(initialMemberId, initialMessageId, isActive) {
        if (!isActive || initialMessageId != null) return@LaunchedEffect
        val targetMember = initialMemberId?.ifBlank { null } ?: return@LaunchedEffect
        openMember(targetMember)
        onInitialMemberHandled?.invoke(targetMember)
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

    fun download(message: RelayMessage) {
        if (MediaDownloader.needsLegacyWritePermission(context)) {
            pendingDownload = message
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveDownload(message)
        }
    }

    AutoClearSelectionOnExit(isActive = isActive)
    CompositionLocalProvider(
        com.nogirelay.app.performance.LocalRelayPageWorkPaused provides sheetBackdrop.isAttached,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            if (uiState.loading && threads.isEmpty()) {
                Box(Modifier.fillMaxSize())
            } else if (threads.isEmpty()) {
                // Empty state floats as a calm glass droplet.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GlassPanel(
                            shape = GlassShapes.Circle,
                            modifier = Modifier.size(88.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Forum,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp),
                                    tint = GlassColors.InkTertiary,
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "还没有同步消息",
                            fontWeight = FontWeight.SemiBold,
                            style = GlassType.Title3,
                            color = GlassColors.Ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "保存同步设置后，新消息会出现在这里",
                            color = GlassColors.InkSecondary,
                            style = GlassType.Subhead,
                        )
                    }
                }
            } else {
                // A member card opens as a container transform: the card itself
                // grows into the page, hosting the timeline scaled to its width,
                // while one veil settles over the rest of the inbox.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { transitionContainerBounds = it.boundsInRoot() },
                ) {
                    val progress = containerProgress::value
                    val entry = shownEntry
                    val timeline: @Composable () -> Unit = {
                        if (entry != null) {
                            key(entry) {
                                val member = threads.firstOrNull { it.id == entry.memberKey }
                                MemberTimelineScreen(
                                    entry = entry,
                                    onContentReady = { contentReadyFor = entry },
                                    memberName = member?.name.orEmpty(),
                                    memberAvatarUrl = member?.avatarUrl,
                                    active = isActive && selectedEntry === entry,
                                    versions = versions,
                                    playbackState = playbackState,
                                    translationEnabled = translationEnabled,
                                    userNickname = userNickname,
                                    backdropState = sheetBackdrop,
                                    onBack = {
                                        if (selectedEntry === entry) {
                                            if (initialMessageId != null && initialMessageId == entry.notificationMessageId) {
                                                onInitialMessageHandled(initialMessageId)
                                            }
                                            selectedEntry = null
                                        }
                                    },
                                    onInitialMessageHandled = { if (selectedEntry === entry) onInitialMessageHandled(it) },
                                    onViewingLatest = { if (selectedEntry === entry) viewingLatest = it },
                                    onUnreadChanged = onUnreadChanged,
                                    onOpenMedia = onOpenMedia,
                                    onPlayVoice = onPlayVoice,
                                    onDownload = ::download,
                                    onRetranslate = { message ->
                                        retranslateScope.launch {
                                            withContext(AppGraph.dispatchers.databaseWrite) {
                                                AppGraph.database.markForRetranslation(message.id)
                                            }
                                            AppGraph.notifyDataChanged(DataChange.MESSAGE_ROWS, setOf(message.id))
                                            TranslationManager.enqueueIds(context, listOf(message.id))
                                        }
                                    },
                                )
                            }
                        }
                    }
                    val cardThreadId = openedCardThreadId?.takeIf { entry != null }
                    MemberInbox(
                        threads = threads,
                        userNickname = userNickname,
                        state = inboxListState,
                        recedeProgress = progress.takeIf { entry != null },
                        expansion = cardThreadId?.let {
                            MemberCardExpansion(
                                threadId = it,
                                progress = progress,
                                viewportInRoot = { transitionContainerBounds },
                                content = timeline,
                                // Restoring the same entry retargets the running
                                // spring, so the collapse turns into an expansion
                                // with its current velocity and the page keeps its state.
                                onReopen = if (selectedEntry == null) {
                                    { selectedEntry = entry }
                                } else {
                                    null
                                },
                            )
                        },
                        header = {
                            GlassHeader(title = "消息")
                        },
                        onSelect = { thread, fromCard -> openMember(thread.id, fromCard = fromCard) },
                    )
                    if (entry != null && cardThreadId == null) {
                        MemberSlideContainer(progress) { timeline() }
                    }
                }
            }
        }
    }
}

/** Longest the open waits for the first messages before moving anyway. */
private const val OPEN_CONTENT_WAIT_MILLIS = 150L

// Critically damped, so the container settles without overshoot. The stop
// threshold keeps the final snap under half a pixel even for a card ~2000px
// from the top of the screen.
private val CardContainerSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 180f,
    visibilityThreshold = 0.0002f,
)

/**
 * Without a card to grow from (notification, recent contacts), the page
 * slides in from the trailing edge over the dimming inbox.
 *
 * The page is always fully opaque and only moves; fading it would show the
 * inbox and the timeline through each other as a double image. The inbox
 * stays put under one veil, the same one the card transition uses, and a
 * soft shadow marks the page's leading edge. At both ends every layer is
 * exactly at rest (page off screen / page covering everything).
 */
@Composable
private fun MemberSlideContainer(progress: () -> Float, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            // The dimmed inbox beside the moving page takes no taps; the
            // events are only observed, so the page itself is unaffected.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            }
            .drawBehind {
                val p = progress().coerceIn(0f, 1f)
                if (p <= 0f) return@drawBehind
                drawRect(GlassColors.BackdropMid, alpha = 0.55f * p)
                drawRect(Color.Black, alpha = 0.08f * p)
                val edge = (1f - p) * size.width
                val reach = SlideEdgeShadowWidth.toPx()
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.10f * p),
                        startX = edge - reach,
                        endX = edge,
                    ),
                    topLeft = Offset(edge - reach, 0f),
                    size = Size(reach, size.height),
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // A layer offset, not a layout one: it moves sub-pixel, so
                    // the slow end of the spring glides.
                    translationX = (1f - progress().coerceIn(0f, 1f)) * size.width
                }
                // Same brush and bounds as the screen backdrop, so the page's
                // own copy is indistinguishable from it once it covers it.
                .drawBehind { drawRect(GlassColors.Backdrop) },
        ) { content() }
    }
}

private val SlideEdgeShadowWidth = 20.dp
