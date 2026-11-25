package com.nogirelay.app.ui.messages

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.AutoClearSelectionOnExit
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassHeader
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.settings.SettingsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun MessagesScreen(
    initialMessageId: String?,
    onInitialMessageHandled: (String) -> Unit,
    onOpenMedia: (RelayMessage, String) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onOpenSettings: (SettingsPage?) -> Unit,
    isActive: Boolean = true,
    reselected: Flow<Unit> = emptyFlow(),
    viewModel: MessagesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val workActive = isActive && com.nogirelay.app.performance.isRelayUiStarted() && !sheetBackdrop.isAttached
    val downloadScope = rememberCoroutineScope()
    val retranslateScope = rememberCoroutineScope()
    val transitionScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messageChanges by viewModel.messageChanges.collectAsStateWithLifecycle()

    LaunchedEffect(workActive) { viewModel.setActive(workActive) }

    val threads = uiState.threads
    val translationEnabled = uiState.translationEnabled
    val unreadHintEnabled = uiState.unreadHintEnabled
    val currentUnreadHintEnabled by rememberUpdatedState(unreadHintEnabled)
    val userNickname = uiState.userNickname
    var locateUnreadRequest by remember { mutableStateOf<Pair<MemberMessageEntry, Long>?>(null) }
    var nextLocateToken by remember { mutableLongStateOf(0L) }
    val playbackFlow = remember(workActive) {
        if (workActive) VoicePlaybackService.playbackState else flowOf(VoicePlaybackService.playbackState.value)
    }
    val playbackState by playbackFlow.collectAsStateWithLifecycle(initialValue = VoicePlaybackState())
    // The open conversation lives in the ViewModel, so it survives tab switches.
    val selectedEntry by viewModel.openEntry.collectAsStateWithLifecycle()
    // Set when the member was opened from its inbox card, which then expands.
    var openedCardThreadId by remember { mutableStateOf<String?>(null) }
    val selectedMemberId = selectedEntry?.memberKey
    var viewingLatest by remember(selectedEntry) { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RelayMessage?>(null) }
    val inboxListState = rememberLazyListState()
    val slotRegistry = remember { MemberCardSlotRegistry() }
    var containerSize by remember { mutableStateOf(Size.Zero) }
    // The timeline stays composed while it collapses back into its card.
    var shownEntry by remember { mutableStateOf<MemberMessageEntry?>(null) }
    val containerProgress = remember { Animatable(0f, visibilityThreshold = 0.0004f) }
    // The entry whose first page of messages has loaded (see the open below).
    var contentReadyFor by remember { mutableStateOf<MemberMessageEntry?>(null) }
    var transitionPhase by remember { mutableStateOf(CardTransitionPhase.IDLE) }
    var captureTick by remember { mutableIntStateOf(0) }
    var canUseSnapshot by remember { mutableStateOf(false) }
    val snapshotLayer = rememberGraphicsLayer()
    val snapshotSize = remember { arrayOf(IntSize.Zero) }
    var fallbackSlotRect by remember { mutableStateOf<Rect?>(null) }
    var collapseStartRect by remember { mutableStateOf<Rect?>(null) }
    var collapseStartCornerRadiusPx by remember { mutableStateOf<Float?>(null) }
    var collapseVeilScale by remember { mutableFloatStateOf(1f) }
    var pendingSlideCloseProgress by remember { mutableStateOf<Float?>(null) }
    var initialPressProgress by remember { mutableFloatStateOf(0f) }
    var initialClipShadowBelow by remember { mutableStateOf(true) }
    // Interactive predictive-back gesture state (Android 14+ edge swipe).
    val backProgressAnim = remember { Animatable(0f, visibilityThreshold = 0.001f) }
    var predictiveBackGesture by remember { mutableStateOf(PredictiveBackGesture()) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackJob by remember { mutableStateOf<Job?>(null) }
    val displayCornerRadiusPx = rememberDisplayCornerRadiusPx()
    val cardCornerRadiusPx = with(density) { MemberCardCornerRadius.toPx() }
    val reducedMotion = LocalGlassReducedMotion.current
    val maxBackShiftXPx = with(density) { 24.dp.toPx() }
    val maxBackShiftYPx = with(density) { 18.dp.toPx() }

    fun openMember(memberKey: String, notificationMessageId: String? = null, fromCard: Boolean = false) {
        openedCardThreadId = memberKey.takeIf { fromCard }
        // Enter PREPARING_OPEN synchronously on the tap frame (before LaunchedEffect runs)
        // so MemberCardOverlay never flashes over the real inbox card on frame 0.
        if (shownEntry == null || shownEntry?.memberKey != memberKey) {
            transitionPhase = CardTransitionPhase.PREPARING_OPEN
            canUseSnapshot = false
        }
        if (fromCard) {
            fallbackSlotRect = slotRegistry.slotRectInInbox(memberKey)
            initialPressProgress = slotRegistry.currentPressProgress(memberKey)
            initialClipShadowBelow = slotRegistry.clipShadowBelow(memberKey)
        } else {
            fallbackSlotRect = null
            initialPressProgress = 0f
        }
        viewModel.openMember(memberKey, notificationMessageId)
    }

    fun closeEntry(entry: MemberMessageEntry) {
        if (selectedEntry === entry) {
            if (initialMessageId != null && initialMessageId == entry.notificationMessageId) {
                onInitialMessageHandled(initialMessageId)
            }
            viewModel.close(entry)
        }
    }

    // Tapping Messages inside a member timeline dynamically locates its earliest
    // unread message; once everything is read (or unread hints are off), it backs
    // out to the inbox, and tapping again scrolls the inbox to top.
    LaunchedEffect(reselected) {
        reselected.collect {
            val open = viewModel.openEntry.value
            if (open == null) {
                inboxListState.animateScrollToItem(0)
            } else if (currentUnreadHintEnabled) {
                locateUnreadRequest = open to (++nextLocateToken)
            } else {
                viewModel.close(open)
            }
        }
    }

    LaunchedEffect(selectedEntry) {
        val target = selectedEntry
        if (target != null) {
            val needsInitialLoad = shouldWaitForInitialContent(shownEntry, target)
            shownEntry = target
            predictiveBackJob?.cancel()
            predictiveBackJob = null
            predictiveBackActive = false
            predictiveBackGesture = PredictiveBackGesture()
            backProgressAnim.snapTo(0f)
            collapseStartRect = null
            collapseStartCornerRadiusPx = null
            collapseVeilScale = 1f
            pendingSlideCloseProgress = null

            if (needsInitialLoad) {
                transitionPhase = CardTransitionPhase.PREPARING_OPEN
                canUseSnapshot = false
                snapshotSize[0] = IntSize.Zero
                containerProgress.snapTo(0f)
                // The timeline is still offscreen (recording into snapshotLayer).
                // Wait briefly for its first page of messages + priority images,
                // then let LazyColumn lay out (frame 1) and Haze render the frosted
                // header into the snapshot (frame 2) before anything moves.
                val ready = withTimeoutOrNull(OPEN_CONTENT_WAIT_MILLIS) {
                    snapshotFlow { contentReadyFor === target }.first { it }
                } != null
                if (ready) {
                    withFrameNanos { }
                    captureTick++
                    withFrameNanos { }
                    canUseSnapshot = snapshotSize[0].width > 0 && snapshotSize[0].height > 0
                } else {
                    withFrameNanos { }
                    canUseSnapshot = false
                }
                openedCardThreadId?.let { threadId ->
                    initialPressProgress = slotRegistry.currentPressProgress(threadId)
                    slotRegistry.slotRectInInbox(threadId)?.let { fallbackSlotRect = it }
                    initialClipShadowBelow = slotRegistry.clipShadowBelow(threadId)
                }
            }
            if (reducedMotion) {
                containerProgress.snapTo(1f)
                transitionPhase = CardTransitionPhase.OPEN
                canUseSnapshot = false
            } else {
                transitionPhase = CardTransitionPhase.ANIMATING
                containerProgress.animateTo(1f, CardOpenSpring)
                containerProgress.snapTo(1f)
                transitionPhase = CardTransitionPhase.OPEN
                canUseSnapshot = false
            }
        } else if (shownEntry != null) {
            predictiveBackJob?.cancel()
            predictiveBackJob = null
            if (isActive && !reducedMotion) {
                pendingSlideCloseProgress?.let { slideStart ->
                    containerProgress.snapTo(slideStart)
                    pendingSlideCloseProgress = null
                }
                // Capture one crisp frame of the live timeline (and let active/onReopen
                // settle) before the closing spring moves, unless predictive back
                // already froze the current frame.
                if (transitionPhase == CardTransitionPhase.OPEN || !canUseSnapshot) {
                    transitionPhase = CardTransitionPhase.PREPARING_CLOSE
                    captureTick++
                    withFrameNanos { }
                    canUseSnapshot = snapshotSize[0].width > 0 && snapshotSize[0].height > 0
                }
                transitionPhase = CardTransitionPhase.ANIMATING
                containerProgress.animateTo(0f, CardCloseSpring)
                // Settle exactly on the card so the real inbox card restores with zero flicker.
                containerProgress.snapTo(0f)
                withFrameNanos { }
            } else {
                containerProgress.snapTo(0f)
            }
            shownEntry = null
            openedCardThreadId = null
            fallbackSlotRect = null
            collapseStartRect = null
            collapseStartCornerRadiusPx = null
            collapseVeilScale = 1f
            pendingSlideCloseProgress = null
            initialPressProgress = 0f
            predictiveBackActive = false
            predictiveBackGesture = PredictiveBackGesture()
            backProgressAnim.snapTo(0f)
            canUseSnapshot = false
            transitionPhase = CardTransitionPhase.IDLE
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
        val message = withContext(AppGraph.dispatchers.databaseRead) { AppGraph.messages.find(targetId) }
        if (message == null) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        openMember(message.memberKey, notificationMessageId = targetId)
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
                // Empty state floats as a calm glass droplet, with the way forward.
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
                            if (uiState.relayConfigured) "暂无消息" else "还没有同步消息",
                            fontWeight = FontWeight.SemiBold,
                            style = GlassType.Title3,
                            color = GlassColors.Ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (uiState.relayConfigured) {
                                "成员的新消息会通过推送自动出现在这里"
                            } else {
                                "填写同步地址和访问令牌后，消息会出现在这里"
                            },
                            color = GlassColors.InkSecondary,
                            style = GlassType.Subhead,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                        if (!uiState.relayConfigured) {
                            Spacer(Modifier.height(16.dp))
                            GlassCapsuleButton(
                                onClick = { onOpenSettings(SettingsPage.CONNECTION) },
                                tone = GlassTone.Accent,
                            ) {
                                Text("前往连接设置", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            } else {
                // Sibling overlay container transform: MemberInbox stays intact in its
                // LazyColumn while the expanding card & timeline float cleanly above it.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { containerSize = it.toSize() },
                ) {
                    val progress = containerProgress::value
                    val transitionSettled by remember {
                        derivedStateOf {
                            containerProgress.value >= CARD_TRANSITION_SETTLED_PROGRESS && !predictiveBackActive
                        }
                    }
                    val inboxCovered by remember {
                        derivedStateOf {
                            selectedEntry != null &&
                                !predictiveBackActive &&
                                containerProgress.value >= CARD_INBOX_COVERED_PROGRESS
                        }
                    }
                    val inboxScrollEnabled by remember {
                        derivedStateOf {
                            selectedEntry == null &&
                                !predictiveBackActive &&
                                containerProgress.value < CARD_INBOX_SCROLL_UNLOCK_PROGRESS
                        }
                    }
                    val entry = selectedEntry ?: shownEntry
                    val cardThreadId = openedCardThreadId?.takeIf { entry != null }
                    val isClosing = selectedEntry == null && entry != null

                    val currentBackGesture: () -> PredictiveBackGesture = {
                        val p = backProgressAnim.value
                        if (p > 0.0001f) {
                            predictiveBackGesture.copy(progress = p)
                        } else {
                            PredictiveBackGesture()
                        }
                    }

                    val effectiveRecedeProgress: (() -> Float)? = if (entry != null) {
                        {
                            val backP = backProgressAnim.value.coerceIn(0f, 1f)
                            if (backP > 0.0001f && selectedEntry != null) {
                                lerp(1f, 0.42f, smoothstep(backP))
                            } else if (isClosing) {
                                (containerProgress.value.coerceIn(0f, 1f) * collapseVeilScale).coerceIn(0f, 1f)
                            } else {
                                containerProgress.value.coerceIn(0f, 1f)
                            }
                        }
                    } else {
                        null
                    }

                    val overlayPresenting: () -> Boolean = {
                        isCardOverlayPresenting(
                            hasCardThread = cardThreadId != null,
                            phase = transitionPhase,
                            progress = containerProgress.value,
                            backProgress = backProgressAnim.value,
                        )
                    }

                    val hiddenThreadId: () -> String? = {
                        if (overlayPresenting()) cardThreadId else null
                    }

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
                                    transitionSettled = transitionSettled,
                                    messageChanges = messageChanges,
                                    playbackState = playbackState,
                                    translationEnabled = translationEnabled,
                                    unreadHintEnabled = unreadHintEnabled,
                                    userNickname = userNickname,
                                    locateUnreadToken = locateUnreadRequest?.takeIf { it.first === entry }?.second ?: 0L,
                                    backdropState = sheetBackdrop,
                                    onBack = { closeEntry(entry) },
                                    onPredictiveBackStart = {
                                        if (reducedMotion || selectedEntry !== entry) return@MemberTimelineScreen
                                        predictiveBackJob?.cancel()
                                        predictiveBackActive = true
                                        if (cardThreadId != null && transitionPhase == CardTransitionPhase.OPEN) {
                                            transitionPhase = CardTransitionPhase.PREPARING_CLOSE
                                            captureTick++
                                            predictiveBackJob = transitionScope.launch {
                                                withFrameNanos { }
                                                if (predictiveBackActive && selectedEntry === entry) {
                                                    canUseSnapshot = snapshotSize[0].width > 0 && snapshotSize[0].height > 0
                                                    transitionPhase = CardTransitionPhase.ANIMATING
                                                }
                                            }
                                        }
                                    },
                                    onPredictiveBackProgress = { gesture ->
                                        if (reducedMotion || selectedEntry !== entry) return@MemberTimelineScreen
                                        predictiveBackGesture = gesture
                                        transitionScope.launch {
                                            backProgressAnim.snapTo(gesture.progress.coerceIn(0f, 1f))
                                        }
                                    },
                                    onPredictiveBackCancel = {
                                        predictiveBackJob?.cancel()
                                        predictiveBackJob = transitionScope.launch {
                                            backProgressAnim.animateTo(0f, CardBackCancelSpring)
                                            backProgressAnim.snapTo(0f)
                                            predictiveBackActive = false
                                            predictiveBackGesture = PredictiveBackGesture()
                                            if (selectedEntry === entry) {
                                                transitionPhase = CardTransitionPhase.OPEN
                                                canUseSnapshot = false
                                            }
                                        }
                                    },
                                    onPredictiveBackCommit = {
                                        predictiveBackJob?.cancel()
                                        val backP = backProgressAnim.value.coerceIn(0f, 1f)
                                        if (!reducedMotion && backP > 0.001f && containerSize.width > 0f && containerSize.height > 0f) {
                                            val viewport = Rect(Offset.Zero, containerSize)
                                            if (cardThreadId != null) {
                                                val liveSlot = slotRegistry.visibleSlotRectInInbox(cardThreadId, viewport)
                                                collapseStartRect = predictiveBackCardRect(
                                                    viewport = viewport,
                                                    gesture = predictiveBackGesture.copy(progress = backP),
                                                    maxShiftXPx = maxBackShiftXPx,
                                                    maxShiftYPx = maxBackShiftYPx,
                                                    targetCenterY = liveSlot?.center?.y,
                                                )
                                                collapseStartCornerRadiusPx = predictiveBackCornerRadiusPx(
                                                    backProgress = backP,
                                                    cardRadiusPx = cardCornerRadiusPx,
                                                    displayRadiusPx = displayCornerRadiusPx,
                                                )
                                                collapseVeilScale = lerp(1f, 0.42f, smoothstep(backP))
                                            } else {
                                                pendingSlideCloseProgress = (1f - smoothstep(backP) * 0.28f).coerceIn(0f, 1f)
                                            }
                                        }
                                        predictiveBackActive = false
                                        predictiveBackGesture = PredictiveBackGesture()
                                        transitionScope.launch { backProgressAnim.snapTo(0f) }
                                        closeEntry(entry)
                                    },
                                    onNoUnreadToLocate = { closeEntry(entry) },
                                    onInitialMessageHandled = { if (selectedEntry === entry) onInitialMessageHandled(it) },
                                    onViewingLatest = { if (selectedEntry === entry) viewingLatest = it },
                                    onOpenMedia = onOpenMedia,
                                    onPlayVoice = onPlayVoice,
                                    onDownload = ::download,
                                    onRetranslate = { message ->
                                        retranslateScope.launch {
                                            withContext(AppGraph.dispatchers.databaseWrite) {
                                                AppGraph.messages.markForRetranslation(message.id)
                                            }
                                            TranslationManager.enqueueIds(context, listOf(message.id))
                                        }
                                    },
                                )
                            }
                        }
                    }

                    MemberInbox(
                        threads = threads,
                        userNickname = userNickname,
                        state = inboxListState,
                        recedeProgress = effectiveRecedeProgress,
                        scrollEnabled = inboxScrollEnabled,
                        inboxCovered = inboxCovered,
                        hiddenThreadId = hiddenThreadId,
                        slotRegistry = slotRegistry,
                        header = {
                            GlassHeader(title = "消息")
                        },
                        onSelect = { thread, fromCard -> openMember(thread.id, fromCard = fromCard) },
                    )

                    if (entry != null && cardThreadId != null) {
                        val activeThread = threads.firstOrNull { it.id == cardThreadId }
                        MemberCardOverlay(
                            thread = activeThread,
                            userNickname = userNickname,
                            progress = progress,
                            predictiveBack = currentBackGesture,
                            isPresenting = overlayPresenting,
                            isClosing = isClosing,
                            phase = transitionPhase,
                            captureTick = captureTick,
                            canUseSnapshot = canUseSnapshot,
                            snapshotLayer = snapshotLayer,
                            snapshotSizeRef = { snapshotSize[0] },
                            onSnapshotRecorded = { snapshotSize[0] = it },
                            resolveSlotRect = {
                                val viewport = Rect(Offset.Zero, containerSize)
                                if (isClosing) {
                                    slotRegistry.visibleSlotRectInInbox(cardThreadId, viewport)
                                } else {
                                    slotRegistry.slotRectInInbox(cardThreadId) ?: fallbackSlotRect
                                }
                            },
                            collapseStartRect = collapseStartRect,
                            collapseStartCornerRadiusPx = collapseStartCornerRadiusPx,
                            collapseVeilScale = collapseVeilScale,
                            initialPress = initialPressProgress,
                            clipShadowBelow = slotRegistry.clipShadowBelow(cardThreadId).takeIf {
                                slotRegistry.slotRectInInbox(cardThreadId) != null
                            } ?: initialClipShadowBelow,
                            displayCornerRadiusPx = displayCornerRadiusPx,
                            blockOutsideTaps = selectedEntry != null && transitionPhase != CardTransitionPhase.PREPARING_OPEN,
                            onReopen = if (isClosing) {
                                { viewModel.reopen(entry) }
                            } else {
                                null
                            },
                            content = timeline,
                        )
                    } else if (entry != null) {
                        MemberSlideContainer(
                            progress = progress,
                            predictiveBackProgress = backProgressAnim::value,
                            phase = transitionPhase,
                            content = timeline,
                        )
                    }
                }
            }
        }
    }
}

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
private fun MemberSlideContainer(
    progress: () -> Float,
    predictiveBackProgress: () -> Float,
    phase: CardTransitionPhase,
    content: @Composable () -> Unit,
) {
    val effectiveProgress: () -> Float = {
        val base = progress().coerceIn(0f, 1f)
        val back = predictiveBackProgress().coerceIn(0f, 1f)
        if (back > 0.0001f) {
            (base * (1f - smoothstep(back) * 0.28f)).coerceIn(0f, 1f)
        } else {
            base
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            // The dimmed inbox beside the moving page takes no taps; the
            // events are only observed, so the page itself is unaffected.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent() }
            }
            .drawBehind {
                if (phase == CardTransitionPhase.PREPARING_OPEN) return@drawBehind
                val p = effectiveProgress()
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
                    val p = effectiveProgress()
                    // A layer offset, not a layout one: it moves sub-pixel, so
                    // the slow end of the spring glides.
                    translationX = (1f - p) * size.width
                }
                // Same brush and bounds as the screen backdrop, so the page's
                // own copy is indistinguishable from it once it covers it.
                .drawBehind { drawRect(GlassColors.Backdrop) },
        ) { content() }
    }
}

private val SlideEdgeShadowWidth = 20.dp
