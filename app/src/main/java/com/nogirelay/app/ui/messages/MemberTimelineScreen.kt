package com.nogirelay.app.ui.messages

import com.nogirelay.app.ui.UiTestTags
import androidx.compose.ui.platform.testTag
import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.nogirelay.app.ui.glass.GlassMetrics
import com.nogirelay.app.ui.glass.GlassMotion
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassReducedMotion
import com.nogirelay.app.ui.glass.LocalMediaSourceScope
import com.nogirelay.app.ui.glass.glassHazeSource
import com.nogirelay.app.ui.glass.glassPress
import com.nogirelay.app.ui.glass.mediaSourceKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nogirelay.app.MESSAGE_UNREAD_DISSOLVE_CLEANUP_MILLIS
import com.nogirelay.app.call.IncomingCallNotifier
import com.nogirelay.app.resolveUnreadTagState
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.repository.MessageChanges
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.readDatabase
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.rememberSearchQuery
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.clearSelectionOnTap
import com.nogirelay.app.ui.glass.rememberGlassPress
import com.nogirelay.app.ui.preloadMessagePreview
import com.nogirelay.app.ui.primeCachedMessageAspectRatios
import com.nogirelay.app.ui.primeInitialTimelineMedia
import com.nogirelay.app.ui.glass.GlassBackButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassCircularProgressIndicator
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassSearchField
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.LocalGlassFrostedControls
import com.nogirelay.app.ui.glass.LocalGlassHazeState
import com.nogirelay.app.ui.glass.progressiveGlassHeader
import com.nogirelay.app.ui.glass.rememberGlassHazeState
import com.nogirelay.app.ui.glass.glassMediaSource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.YearMonth
import java.time.ZoneId

/**
 * Member timeline, rebuilt as liquid glass: floating back / search / filter
 * controls hover over the scrolling conversation; message bubbles are glass
 * panels; media cells publish their geometry so the viewer can lift the
 * exact thumbnail out of the page.
 */
@Composable
internal fun MemberTimelineScreen(
    entry: MemberMessageEntry,
    memberName: String,
    memberAvatarUrl: String?,
    onContentReady: () -> Unit = {},
    active: Boolean,
    transitionSettled: Boolean = true,
    messageChanges: MessageChanges,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    unreadHintEnabled: Boolean = true,
    userNickname: String,
    locateUnreadToken: Long = 0L,
    backdropState: RelaySheetBackdropState,
    onBack: () -> Unit,
    onPredictiveBackStart: () -> Unit = {},
    onPredictiveBackProgress: (PredictiveBackGesture) -> Unit = {},
    onPredictiveBackCancel: () -> Unit = {},
    onPredictiveBackCommit: () -> Unit = onBack,
    onNoUnreadToLocate: () -> Unit = onBack,
    onInitialMessageHandled: (String) -> Unit,
    onViewingLatest: (Boolean) -> Unit,
    onOpenMedia: (RelayMessage, String) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
) {
    var auxiliaryScreen by remember(entry) { mutableStateOf<MemberTimelineAuxiliary?>(null) }
    var timelineEntry by remember(entry) { mutableStateOf(entry) }
    val screenScope = rememberCoroutineScope()
    var query by remember(entry) { mutableStateOf("") }
    // The header shows who this is; search expands from its icon on demand.
    var searchOpen by remember(entry) { mutableStateOf(false) }
    val workActive = active && com.nogirelay.app.performance.isRelayUiStarted() && !backdropState.isAttached
    val effectiveQuery = rememberSearchQuery(query, workActive)
    var timeFilter by remember(entry) { mutableStateOf(TimeFilter()) }
    var showFilter by remember(entry) { mutableStateOf(false) }
    var initialTargetConsumed by remember(timelineEntry) { mutableStateOf(false) }
    var dissolvingUnreadIds by remember(entry) { mutableStateOf(emptySet<String>()) }
    var contentLocateToken by remember(entry) { mutableLongStateOf(0L) }
    val latestOnNoUnreadToLocate by rememberUpdatedState(onNoUnreadToLocate)
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current

    LaunchedEffect(unreadHintEnabled) {
        if (!unreadHintEnabled) dissolvingUnreadIds = emptySet()
    }

    LaunchedEffect(locateUnreadToken) {
        if (locateUnreadToken == 0L || !active || !unreadHintEnabled) return@LaunchedEffect
        focusManager.clearFocus()
        textToolbar.hide()
        showFilter = false
        if (auxiliaryScreen != null || query.isNotBlank() || timeFilter.isActive) {
            val earliestId = withContext(AppGraph.dispatchers.databaseRead) {
                AppGraph.messages.earliestUnreadMessageIdForMember(entry.memberKey)
            }
            if (earliestId == null) {
                latestOnNoUnreadToLocate()
                return@LaunchedEffect
            }
            timelineEntry = MemberMessageEntry(
                memberKey = entry.memberKey,
                playbackState = VoicePlaybackService.playbackState.value,
                unreadTargetMessageId = earliestId,
            )
            query = ""
            searchOpen = false
            timeFilter = TimeFilter()
            auxiliaryScreen = null
        } else {
            contentLocateToken = locateUnreadToken
        }
    }

    fun toggleFavorite(message: RelayMessage) {
        screenScope.launch {
            withContext(AppGraph.dispatchers.databaseWrite) {
                AppGraph.messages.setMessageFavorite(message.id, !message.isFavorite)
            }
        }
    }

    fun returnToTimeline() {
        val sourceScreen = auxiliaryScreen ?: return
        val playback = VoicePlaybackService.playbackState.value
        val targetId = playback.messageId.takeIf { playback.isPlaying }
        if (targetId == null) {
            auxiliaryScreen = null
            return
        }
        screenScope.launch {
            val belongsToMember = withContext(AppGraph.dispatchers.databaseRead) {
                AppGraph.messages.find(targetId)?.memberKey == entry.memberKey
            }
            if (auxiliaryScreen != sourceScreen) return@launch
            val latestPlayback = VoicePlaybackService.playbackState.value
            if (belongsToMember && latestPlayback.isPlaying && latestPlayback.messageId == targetId) {
                // Capture the target once on navigation, rather than following
                // every playback update and pulling the user back while scrolling.
                timelineEntry = MemberMessageEntry(entry.memberKey, latestPlayback)
                query = ""
                searchOpen = false
                timeFilter = TimeFilter()
            }
            auxiliaryScreen = null
        }
    }

    fun back() {
        if (auxiliaryScreen != null) {
            returnToTimeline()
            return
        }
        focusManager.clearFocus()
        textToolbar.hide()
        if (searchOpen || query.isNotEmpty()) {
            query = ""
            searchOpen = false
        } else {
            onBack()
        }
    }
    val latestOnPredictiveBackStart by rememberUpdatedState(onPredictiveBackStart)
    val latestOnPredictiveBackProgress by rememberUpdatedState(onPredictiveBackProgress)
    val latestOnPredictiveBackCancel by rememberUpdatedState(onPredictiveBackCancel)
    val latestOnPredictiveBackCommit by rememberUpdatedState(onPredictiveBackCommit)
    val canPredictiveCollapse = active && auxiliaryScreen == null && !searchOpen && query.isEmpty() && !showFilter
    BackHandler(enabled = active && !canPredictiveCollapse, onBack = ::back)
    PredictiveBackHandler(enabled = canPredictiveCollapse) { progressFlow ->
        focusManager.clearFocus()
        textToolbar.hide()
        var gestureStarted = false
        try {
            progressFlow.collect { backEvent ->
                if (!gestureStarted) {
                    gestureStarted = true
                    latestOnPredictiveBackStart()
                }
                latestOnPredictiveBackProgress(
                    PredictiveBackGesture(
                        progress = backEvent.progress,
                        swipeEdge = backEvent.swipeEdge,
                        touchY = backEvent.touchY,
                    ),
                )
            }
            latestOnPredictiveBackCommit()
        } catch (cancelled: CancellationException) {
            if (gestureStarted) latestOnPredictiveBackCancel()
            throw cancelled
        }
    }
    LaunchedEffect(active) {
        if (!active) {
            focusManager.clearFocus()
            textToolbar.hide()
        }
    }

    AnimatedContent(
        targetState = auxiliaryScreen,
        transitionSpec = {
            val slideSpring = spring<IntOffset>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
            val enteringAuxiliary = targetState != null
            val enter = fadeIn(animationSpec = tween(200)) +
                slideInHorizontally(animationSpec = slideSpring) { width ->
                    if (enteringAuxiliary) width / 5 else -width / 6
                }
            val exit = fadeOut(animationSpec = tween(160)) +
                slideOutHorizontally(animationSpec = slideSpring) { width ->
                    if (enteringAuxiliary) -width / 6 else width / 5
                }
            enter togetherWith exit
        },
        label = "member_auxiliary_transition",
        modifier = Modifier.fillMaxSize(),
    ) { screen ->
        // Outgoing and incoming pages coexist during AnimatedContent transitions.
        val sourceScope = remember { java.util.UUID.randomUUID().toString() }
        val openScopedMedia: (RelayMessage) -> Unit = { message ->
            onOpenMedia(message, mediaSourceKey(sourceScope, message.id))
        }
        CompositionLocalProvider(LocalMediaSourceScope provides sourceScope) {
            if (screen != null) {
                MemberTimelineAuxiliaryScreen(
                    memberKey = entry.memberKey,
                    screen = screen,
                    active = workActive && screen == auxiliaryScreen,
                    messageChanges = messageChanges,
                    playbackState = playbackState,
                    translationEnabled = translationEnabled,
                    unreadHintEnabled = unreadHintEnabled,
                    userNickname = userNickname,
                    onBack = ::returnToTimeline,
                    onOpenMedia = openScopedMedia,
                    onPlayVoice = onPlayVoice,
                    onDownload = onDownload,
                    onRetranslate = onRetranslate,
                    onToggleFavorite = ::toggleFavorite,
                )
            } else {
                FloatingTimelineLayout(
                    header = {
                        MemberTimelineHeader(
                            memberName = memberName,
                            memberAvatarUrl = memberAvatarUrl,
                            query = query,
                            searchOpen = searchOpen,
                            active = active,
                            filterActive = timeFilter.isActive,
                            onBack = ::back,
                            onOpenSearch = { if (active) searchOpen = true },
                            onCloseSearch = {
                                focusManager.clearFocus()
                                textToolbar.hide()
                                query = ""
                                searchOpen = false
                            },
                            onQueryChange = { if (active) query = it },
                            onFilterClick = {
                                focusManager.clearFocus()
                                textToolbar.hide()
                                showFilter = true
                            },
                        )
                    },
                ) { headerHeight ->
                    key(timelineEntry, effectiveQuery, timeFilter, userNickname) {
                        MemberTimelineContent(
                            memberKey = entry.memberKey,
                            query = effectiveQuery,
                            filter = timeFilter,
                            initialTargetId = timelineEntry.targetMessageId.takeIf {
                                !initialTargetConsumed && effectiveQuery.isBlank() && !timeFilter.isActive
                            },
                            alignInitialTargetToTop = timelineEntry.unreadTargetMessageId != null,
                            locateUnreadToken = contentLocateToken,
                            active = workActive && auxiliaryScreen == null,
                            transitionSettled = transitionSettled,
                            messageChanges = messageChanges,
                            playbackState = playbackState,
                            translationEnabled = translationEnabled,
                            unreadHintEnabled = unreadHintEnabled,
                            userNickname = userNickname,
                            dissolvingUnreadIds = dissolvingUnreadIds,
                            onInitialLoaded = {
                                onContentReady()
                                if (!initialTargetConsumed) {
                                    initialTargetConsumed = true
                                    timelineEntry.notificationMessageId?.let(onInitialMessageHandled)
                                }
                            },
                            onNoUnreadToLocate = latestOnNoUnreadToLocate,
                            onViewingLatest = { onViewingLatest(it && query.isBlank() && !timeFilter.isActive) },
                            onRead = { ids ->
                                if (unreadHintEnabled) {
                                    val added = ids - dissolvingUnreadIds
                                    if (added.isNotEmpty()) {
                                        dissolvingUnreadIds = dissolvingUnreadIds + added
                                        screenScope.launch {
                                            delay(MESSAGE_UNREAD_DISSOLVE_CLEANUP_MILLIS)
                                            dissolvingUnreadIds = dissolvingUnreadIds - added
                                        }
                                    }
                                }
                            },
                            onUnreadDissolved = { id -> dissolvingUnreadIds = dissolvingUnreadIds - id },
                            onOpenMedia = openScopedMedia,
                            onPlayVoice = onPlayVoice,
                            onDownload = onDownload,
                            onRetranslate = onRetranslate,
                            onToggleFavorite = ::toggleFavorite,
                            topContentPadding = headerHeight,
                            modifier = Modifier.fillMaxSize().clearSelectionOnTap(focusManager, textToolbar),
                        )
                    }
                }
                if (showFilter) {
                    TimeFilterDialog(
                        filter = timeFilter,
                        backdropState = backdropState,
                        onDismiss = { showFilter = false },
                        onConfirm = { timeFilter = it; showFilter = false },
                        extraContent = { dismiss ->
                            FilterDrawerEntry(
                                label = "媒体",
                                testTag = UiTestTags.TIMELINE_MEDIA_ENTRY,
                                icon = Icons.Rounded.PlayArrow,
                                onClick = {
                                    dismiss {
                                        showFilter = false
                                        auxiliaryScreen = MemberTimelineAuxiliary.MEDIA
                                    }
                                },
                            )
                            Spacer(Modifier.height(10.dp))
                            FilterDrawerEntry(
                                label = "收藏夹",
                                testTag = UiTestTags.TIMELINE_FAVORITES_ENTRY,
                                icon = Icons.Rounded.Star,
                                onClick = {
                                    dismiss {
                                        showFilter = false
                                        auxiliaryScreen = MemberTimelineAuxiliary.FAVORITES
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Row inside the filter sheet that jumps to an auxiliary page. */
@Composable
private fun FilterDrawerEntry(
    label: String,
    testTag: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    GlassPanel(
        onClick = onClick,
        onClickLabel = label,
        shape = GlassShapes.Card,
        depth = GlassDepths.None,
        fillAlpha = 0.34f,
        blur = 14.dp,
        modifier = Modifier.fillMaxWidth().height(GlassMetrics.ControlHeight).testTag(testTag),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = GlassColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = GlassColors.Accent,
                fontWeight = FontWeight.SemiBold,
                style = GlassType.Callout,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = GlassColors.InkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private const val AUXILIARY_PAGE_SIZE = 200
private const val AUXILIARY_PRELOAD_LIMIT = 24
private val mediaPreloadSlots = Semaphore(2)

private enum class MemberTimelineAuxiliary { MEDIA, FAVORITES }

private enum class MemberMediaCategory(
    val label: String,
    val type: MessageType,
) {
    IMAGES("图片", MessageType.IMAGE),
    VIDEOS("视频", MessageType.VIDEO),
    VOICE("语音", MessageType.AUDIO),
}

private const val MEDIA_GRID_COLUMNS = 4

private sealed interface MemberMediaRow {
    val key: String

    data class Media(val message: RelayMessage) : MemberMediaRow {
        override val key: String = "media:${message.id}"
    }

    data class Month(override val key: String, val label: String) : MemberMediaRow

    data class Gap(override val key: String) : MemberMediaRow
}

private data class MemberMediaMonth(
    val key: String,
    val label: String,
    val items: List<RelayMessage>,
)

private fun memberMediaMonths(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MemberMediaMonth> {
    val months = messages.map { messageLocalDateTime(it.sentAt, zone)?.let(YearMonth::from) }
    val occurrences = mutableMapOf<String, Int>()
    val result = mutableListOf<MemberMediaMonth>()
    var index = 0
    while (index < messages.size) {
        val month = months[index]
        var end = index
        while (end < messages.size && months[end] == month) end++
        val monthKey = month?.toString() ?: "unknown"
        val occurrence = occurrences.getOrDefault(monthKey, 0)
        occurrences[monthKey] = occurrence + 1
        result += MemberMediaMonth(
            key = "month:$monthKey:$occurrence",
            label = month?.let { "${it.year}年${it.monthValue.toString().padStart(2, '0')}月" }
                ?: "日期未知",
            items = messages.subList(index, end).toList(),
        )
        index = end
    }
    return result
}

private fun memberMediaRows(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MemberMediaRow> = buildList {
    memberMediaMonths(messages, zone).forEach { month ->
        month.items.forEach { add(MemberMediaRow.Media(it)) }
        add(MemberMediaRow.Month(month.key, month.label))
    }
}

private fun memberMediaGridRows(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MemberMediaRow> = buildList {
    memberMediaMonths(messages, zone).forEach { month ->
        val rows = month.items.asReversed().chunked(MEDIA_GRID_COLUMNS)
        rows.asReversed().forEach { row ->
            row.forEach { add(MemberMediaRow.Media(it)) }
            repeat(MEDIA_GRID_COLUMNS - row.size) { position ->
                add(MemberMediaRow.Gap("gap:${month.key}:${row.first().id}:$position"))
            }
        }
        add(MemberMediaRow.Month(month.key, month.label))
    }
}

private suspend fun loadAuxiliaryPage(
    memberKey: String,
    screen: MemberTimelineAuxiliary,
    mediaCategory: MemberMediaCategory,
    offset: Int,
    searchQuery: String,
    nickname: String,
    limit: Int = AUXILIARY_PAGE_SIZE,
): List<RelayMessage> = when (screen) {
    MemberTimelineAuxiliary.FAVORITES -> AppGraph.messages.favoriteMessagesForMember(
        memberKey,
        limit = limit,
        offset = offset,
        searchQuery = searchQuery,
        nickname = nickname,
    )
    MemberTimelineAuxiliary.MEDIA -> AppGraph.messages.mediaMessagesForMember(
        memberKey,
        mediaCategory.type,
        limit = limit,
        offset = offset,
    )
}

private suspend fun preloadMessageMedia(
    context: android.content.Context,
    messages: List<RelayMessage>,
    targetWidth: Int,
    maxVisualPreload: Int = AUXILIARY_PRELOAD_LIMIT,
    maxVoicePreload: Int = 12,
) {
    val visualMessages = messages.asSequence()
        .filter { it.type == MessageType.IMAGE || it.type == MessageType.VIDEO }
        .distinctBy { it.id }
        .take(maxVisualPreload)
        .toList()
    val voiceUrls = messages.asSequence()
        .filter { it.type == MessageType.AUDIO }
        .mapNotNull { it.mediaUrl?.takeIf(String::isNotBlank) }
        .distinct()
        .take(maxVoicePreload)
        .toList()

    coroutineScope {
        visualMessages.map { message ->
            async(Dispatchers.IO) {
                mediaPreloadSlots.withPermit {
                    preloadMessagePreview(context, message, targetWidth = targetWidth)
                }
            }
        }.toList().awaitAll()
        voiceUrls.map { url ->
            async(Dispatchers.IO) {
                if (MediaDownloader.cachedFileForUrl(context, url, MessageType.AUDIO) != null ||
                    MediaDownloader.isNotFound(context, url)
                ) {
                    return@async
                }
                mediaPreloadSlots.withPermit {
                    runCatching {
                        withContext(AppGraph.dispatchers.network) {
                            if (MediaDownloader.cachedFileForUrl(context, url, MessageType.AUDIO) == null) {
                                MediaDownloader.downloadUrl(context, url, MessageType.AUDIO)
                            }
                        }
                    }
                }
            }
        }.toList().awaitAll()
    }
}

@Composable
private fun MemberTimelineAuxiliaryScreen(
    memberKey: String,
    screen: MemberTimelineAuxiliary,
    active: Boolean,
    messageChanges: MessageChanges,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    unreadHintEnabled: Boolean = true,
    userNickname: String,
    onBack: () -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
    onToggleFavorite: (RelayMessage) -> Unit,
) {
    var mediaCategory by remember(memberKey) { mutableStateOf(MemberMediaCategory.IMAGES) }
    var query by remember(memberKey, screen) { mutableStateOf("") }
    val effectiveQuery = rememberSearchQuery(query, active)
    val request = remember(memberKey, screen, mediaCategory, effectiveQuery, userNickname) { Any() }
    val latestRequest by rememberUpdatedState(request)
    var reloadGeneration by remember(request) { mutableIntStateOf(0) }
    val latestReloadGeneration by rememberUpdatedState(reloadGeneration)
    var messages by remember(request) { mutableStateOf<List<RelayMessage>>(emptyList()) }
    val gridRows = remember(messages) { memberMediaGridRows(messages) }
    var loading by remember(request) { mutableStateOf(true) }
    var loadingMore by remember(request) { mutableStateOf(false) }
    var exhausted by remember(request) { mutableStateOf(false) }
    var loadedVersion by remember(request) { mutableLongStateOf(-1L) }
    var moreJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    val bottomPadding = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    fun back() {
        focusManager.clearFocus()
        textToolbar.hide()
        if (query.isNotEmpty()) query = "" else onBack()
    }
    BackHandler(enabled = active && query.isNotEmpty(), onBack = ::back)

    val preloadHead = remember(messages) { messages.take(AUXILIARY_PRELOAD_LIMIT) }
    val preloadTargetWidth = if (screen == MemberTimelineAuxiliary.MEDIA && mediaCategory != MemberMediaCategory.VOICE) {
        320
    } else {
        720
    }
    LaunchedEffect(preloadHead, active, screen, mediaCategory) {
        if (active && preloadHead.isNotEmpty()) {
            preloadMessageMedia(context, preloadHead, targetWidth = preloadTargetWidth)
        }
    }

    fun more() {
        if (!active || loading || loadingMore || exhausted) return
        loadingMore = true
        val offset = messages.size
        val pageRequest = request
        val pageGeneration = reloadGeneration
        moreJob = scope.launch {
            try {
                val next = withContext(AppGraph.dispatchers.databaseRead) {
                    loadAuxiliaryPage(memberKey, screen, mediaCategory, offset, effectiveQuery, userNickname)
                }
                if (latestRequest !== pageRequest || latestReloadGeneration != pageGeneration) return@launch
                val known = messages.mapTo(mutableSetOf()) { it.id }
                messages = messages + next.filter { known.add(it.id) }
                exhausted = next.size < AUXILIARY_PAGE_SIZE
            } finally {
                if (latestRequest === pageRequest) loadingMore = false
            }
        }
    }

    LaunchedEffect(request, messageChanges.version, active) {
        if (!active) return@LaunchedEffect
        if (loadedVersion == messageChanges.version) return@LaunchedEffect
        val changedIds = if (loadedVersion >= 0L && messages.isNotEmpty() && screen == MemberTimelineAuxiliary.MEDIA) {
            messageChanges.changedSince(loadedVersion)
        } else {
            null
        }
        if (changedIds != null) {
            val existingIds = messages.mapTo(mutableSetOf()) { it.id }
            val matchingIds = existingIds.intersect(changedIds)
            if (matchingIds.isNotEmpty()) {
                val updates = withContext(AppGraph.dispatchers.databaseRead) {
                    AppGraph.messages.memberMessagesByIds(memberKey, matchingIds)
                }.associateBy { it.id }
                if (updates.size == matchingIds.size) {
                    messages = messages.map { updates[it.id] ?: it }
                    loadedVersion = messageChanges.version
                    return@LaunchedEffect
                }
            } else {
                loadedVersion = messageChanges.version
                return@LaunchedEffect
            }
        }
        moreJob?.cancel()
        moreJob = null
        loadingMore = false
        reloadGeneration++
        loading = messages.isEmpty()
        // Keep the last known pagination state until the refreshed page arrives.
        // Resetting it here inserts a load-more row on markPlayed updates and
        // shifts a short reverse-layout voice list for a single frame.
        val reloadLimit = maxOf(AUXILIARY_PAGE_SIZE, messages.size).coerceAtMost(1000)
        val loaded = withContext(AppGraph.dispatchers.databaseRead) {
            loadAuxiliaryPage(
                memberKey,
                screen,
                mediaCategory,
                0,
                effectiveQuery,
                userNickname,
                limit = reloadLimit,
            )
        }
        messages = loaded
        exhausted = loaded.size < reloadLimit
        loadedVersion = messageChanges.version
        loading = false
    }

    FloatingTimelineLayout(
        header = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                GlassBackButton(
                    onClick = ::back,
                    contentDescription = if (query.isNotEmpty()) "清空搜索" else "返回消息流",
                )
                if (screen == MemberTimelineAuxiliary.MEDIA) {
                    GlassSegmentedTabs(
                        labels = MemberMediaCategory.entries.map(MemberMediaCategory::label),
                        selectedIndex = mediaCategory.ordinal,
                        onSelected = { mediaCategory = MemberMediaCategory.entries[it] },
                        enabled = active,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    GlassSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        enabled = active,
                        placeholder = "搜索收藏的消息",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { headerHeight ->
        val topPadding = headerHeight + 18.dp
        key(request) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    GlassCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
                }
                messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            effectiveQuery.isNotBlank() -> "没有匹配的收藏消息"
                            screen == MemberTimelineAuxiliary.FAVORITES -> "暂无收藏的消息"
                            else -> "暂无${mediaCategory.label}消息"
                        },
                        color = GlassColors.InkSecondary,
                    )
                }
                screen == MemberTimelineAuxiliary.FAVORITES -> AuxiliaryMessageList(
                    messages = messages,
                    topContentPadding = topPadding,
                    bottomContentPadding = bottomPadding,
                    searchQuery = effectiveQuery,
                    hasMore = !exhausted,
                    onLoadMore = ::more,
                    playbackState = playbackState,
                    translationEnabled = translationEnabled,
                    unreadHintEnabled = unreadHintEnabled,
                    userNickname = userNickname,
                    onOpenMedia = onOpenMedia,
                    onPlayVoice = onPlayVoice,
                    onDownload = onDownload,
                    onRetranslate = onRetranslate,
                    onToggleFavorite = onToggleFavorite,
                )
                mediaCategory == MemberMediaCategory.VOICE -> AuxiliaryMessageList(
                    messages = messages,
                    topContentPadding = topPadding,
                    bottomContentPadding = bottomPadding,
                    reverseLayout = true,
                    monthSeparators = true,
                    hasMore = !exhausted,
                    onLoadMore = ::more,
                    playbackState = playbackState,
                    translationEnabled = translationEnabled,
                    unreadHintEnabled = unreadHintEnabled,
                    userNickname = userNickname,
                    onOpenMedia = onOpenMedia,
                    onPlayVoice = onPlayVoice,
                    onDownload = onDownload,
                    onRetranslate = onRetranslate,
                    onToggleFavorite = onToggleFavorite,
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(MEDIA_GRID_COLUMNS),
                    reverseLayout = true,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPadding, bottom = bottomPadding),
                    modifier = Modifier.fillMaxSize().testTag(UiTestTags.AUXILIARY_LIST),
                ) {
                    gridItems(
                        gridRows,
                        key = MemberMediaRow::key,
                        span = { row ->
                            if (row is MemberMediaRow.Month) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        },
                        contentType = { it::class },
                    ) { row ->
                        when (row) {
                            is MemberMediaRow.Media -> MediaMessageCell(
                                message = row.message,
                                onClick = { onOpenMedia(row.message) },
                            )
                            is MemberMediaRow.Month -> MemberMediaMonthHeader(row.label)
                            is MemberMediaRow.Gap -> Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
                        }
                    }
                    if (!exhausted) {
                        item(key = "auxiliary_media_loading", span = { GridItemSpan(maxLineSpan) }) {
                            AuxiliaryLoadFooter(pageKey = messages.size, onLoadMore = ::more)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuxiliaryMessageList(
    messages: List<RelayMessage>,
    hasMore: Boolean,
    topContentPadding: Dp,
    bottomContentPadding: Dp,
    searchQuery: String = "",
    reverseLayout: Boolean = false,
    monthSeparators: Boolean = false,
    onLoadMore: () -> Unit,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    unreadHintEnabled: Boolean = true,
    userNickname: String,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
    onToggleFavorite: (RelayMessage) -> Unit,
) {
    val monthRows = if (monthSeparators) remember(messages) { memberMediaRows(messages) } else emptyList()
    LazyColumn(
        reverseLayout = reverseLayout,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topContentPadding, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag(UiTestTags.AUXILIARY_LIST),
    ) {
        if (monthSeparators) {
            items(monthRows, key = MemberMediaRow::key, contentType = { it::class }) { row ->
                when (row) {
                    is MemberMediaRow.Media -> AuxiliaryMessageCard(
                        message = row.message,
                        searchQuery = searchQuery,
                        playbackState = playbackState,
                        translationEnabled = translationEnabled,
                        unreadHintEnabled = unreadHintEnabled,
                        userNickname = userNickname,
                        onOpenMedia = onOpenMedia,
                        onPlayVoice = onPlayVoice,
                        onDownload = onDownload,
                        onRetranslate = onRetranslate,
                        onToggleFavorite = onToggleFavorite,
                    )
                    is MemberMediaRow.Month -> MemberMediaMonthHeader(row.label)
                    is MemberMediaRow.Gap -> Spacer(Modifier)
                }
            }
        } else {
            items(messages, key = { it.id }, contentType = { "message" }) { message ->
                AuxiliaryMessageCard(
                    message = message,
                    searchQuery = searchQuery,
                    playbackState = playbackState,
                    translationEnabled = translationEnabled,
                    unreadHintEnabled = unreadHintEnabled,
                    userNickname = userNickname,
                    onOpenMedia = onOpenMedia,
                    onPlayVoice = onPlayVoice,
                    onDownload = onDownload,
                    onRetranslate = onRetranslate,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
        if (hasMore) {
            item(key = "auxiliary_load_more") {
                AuxiliaryLoadFooter(pageKey = messages.size, onLoadMore = onLoadMore)
            }
        }
    }
}

@Composable
private fun AuxiliaryMessageCard(
    message: RelayMessage,
    searchQuery: String,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    unreadHintEnabled: Boolean = true,
    userNickname: String,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
    onToggleFavorite: (RelayMessage) -> Unit,
) {
    MessageCard(
        message = message,
        modifier = Modifier.fillMaxWidth(),
        isUnread = unreadHintEnabled && message.isUnread,
        audioState = playbackState.takeIf { it.messageId == message.id },
        translationEnabled = translationEnabled,
        userNickname = userNickname,
        searchQuery = searchQuery,
        onOpenMedia = { onOpenMedia(message) },
        onPlayVoice = { onPlayVoice(message) },
        onDownload = { onDownload(message) },
        onRetranslate = { onRetranslate(message) },
        onToggleFavorite = { onToggleFavorite(message) },
    )
}

@Composable
private fun MemberMediaMonthHeader(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = GlassType.Footnote,
            fontWeight = FontWeight.SemiBold,
            color = GlassColors.InkTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AuxiliaryLoadFooter(pageKey: Int, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        GlassCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
    }
    LaunchedEffect(pageKey) { onLoadMore() }
}

internal data class MediaCellPreview(
    val url: String?,
    val fallbackUrl: String?,
    val messageType: MessageType,
    val message: RelayMessage?,
)

internal fun resolveMediaCellPreview(message: RelayMessage): MediaCellPreview {
    val mediaUrl = message.mediaUrl?.takeIf(String::isNotBlank)
    val thumbnailUrl = message.thumbnailUrl?.takeIf(String::isNotBlank)
    return when (message.type) {
        MessageType.IMAGE -> {
            val primaryUrl = mediaUrl ?: thumbnailUrl
            MediaCellPreview(
                url = primaryUrl,
                fallbackUrl = thumbnailUrl?.takeIf { it != primaryUrl },
                messageType = MessageType.IMAGE,
                message = null,
            )
        }
        MessageType.VIDEO -> MediaCellPreview(
            url = thumbnailUrl ?: mediaUrl,
            fallbackUrl = null,
            messageType = MessageType.VIDEO,
            message = message,
        )
        else -> MediaCellPreview(
            url = thumbnailUrl ?: mediaUrl,
            fallbackUrl = null,
            messageType = message.type,
            message = null,
        )
    }
}

@Composable
private fun MediaMessageCell(message: RelayMessage, onClick: () -> Unit) {
    val preview = resolveMediaCellPreview(message)

    val videoHasAudioTrack by rememberVideoHasAudioTrack(message)
    val cellShape = GlassShapes.CardSmall
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(cellShape)
            .glassMediaSource(
                key = mediaSourceKey(LocalMediaSourceScope.current, message.id),
                url = preview.url,
                cornerRadiusPx = with(LocalDensity.current) { 20.dp.toPx() },
            )
            .background(Color(0x2B8E93A6))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (preview.url != null) {
            RemoteImage(
                url = preview.url,
                fallbackUrl = preview.fallbackUrl,
                contentDescription = message.text?.takeIf(String::isNotBlank) ?: "媒体消息",
                loadCachedImmediately = true,
                messageType = preview.messageType,
                message = preview.message,
                retryOnClick = message.type != MessageType.VIDEO,
                contentScale = ContentScale.Crop,
                placeholderColor = Color.Transparent,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (message.type == MessageType.VIDEO) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(GlassShapes.Circle)
                    .background(Color.Black.copy(alpha = 0.38f), GlassShapes.Circle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "播放视频",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (videoHasAudioTrack == false) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.55f), GlassShapes.Circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = "视频无声音",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}

/**
 * Layout that lets the floating header overlap the timeline while the
 * timeline content extends underneath it. Glass controls in the header
 * sample only the timeline, keeping the gradient and controls out of their
 * own backdrop capture.
 */
@Composable
private fun FloatingTimelineLayout(
    header: @Composable () -> Unit,
    content: @Composable (headerHeight: Dp) -> Unit,
) {
    val timelineHazeState = rememberGlassHazeState()
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val headerPlaceable = subcompose("header") {
            CompositionLocalProvider(
                LocalGlassHazeState provides timelineHazeState,
                LocalGlassFrostedControls provides true,
            ) { header() }
        }.single().measure(constraints.copy(minHeight = 0))
        val contentTopInset = (headerPlaceable.height.toDp() - 10.dp).coerceAtLeast(0.dp)
        val contentPlaceable = subcompose("timeline") {
            Box(Modifier.fillMaxSize().glassHazeSource(timelineHazeState)) {
                content(contentTopInset)
            }
        }.single().measure(constraints)
        val blurHeight = (headerPlaceable.height + 32.dp.roundToPx()).coerceAtMost(constraints.maxHeight)
        val backdropPlaceable = subcompose("header-backdrop") {
            Box(Modifier.fillMaxSize().progressiveGlassHeader(timelineHazeState))
        }.single().measure(constraints.copy(minHeight = blurHeight, maxHeight = blurHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceable.placeRelative(0, 0)
            backdropPlaceable.placeRelative(0, 0)
            headerPlaceable.placeRelative(0, 0)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemberTimelineHeader(
    memberName: String,
    memberAvatarUrl: String?,
    query: String,
    searchOpen: Boolean,
    active: Boolean,
    filterActive: Boolean,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
) {
    val reducedMotion = LocalGlassReducedMotion.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val morph = remember { Animatable(if (searchOpen) 1f else 0f) }
    LaunchedEffect(searchOpen) {
        val target = if (searchOpen) 1f else 0f
        // Focus first so the keyboard rises together with the expanding field.
        if (searchOpen) runCatching { focusRequester.requestFocus() }
        if (reducedMotion) morph.snapTo(target) else morph.animateTo(target, GlassMotion.MorphSpec)
    }

    // Dismissing the keyboard closes an empty search; with a query the results
    // stay and the field only loses focus, so they remain explained. The
    // animation target flips the moment the keyboard starts to slide away
    // (isImeVisible only flips once it is gone), so the field collapses
    // together with the keyboard instead of after it.
    val density = LocalDensity.current
    val imeVisible = WindowInsets.imeAnimationTarget.getBottom(density) > 0
    val currentQuery by rememberUpdatedState(query)
    var imeShownWhileOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searchOpen, imeVisible) {
        when {
            !searchOpen -> imeShownWhileOpen = false
            imeVisible -> imeShownWhileOpen = true
            imeShownWhileOpen -> {
                imeShownWhileOpen = false
                if (currentQuery.isEmpty()) onCloseSearch() else focusManager.clearFocus()
            }
        }
    }

    val openInteraction = remember { MutableInteractionSource() }
    val openPress = rememberGlassPress(openInteraction, enabled = active)
    val identityShown by remember { derivedStateOf { morph.value < 1f } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassBackButton(
            onClick = onBack,
            enabled = active,
            contentDescription = if (searchOpen) "关闭搜索" else "返回成员列表",
        )
        Box(Modifier.weight(1f).height(48.dp)) {
            if (identityShown) {
                // The name slides aside and fades while the field covers it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(end = 58.dp)
                        .graphicsLayer {
                            val p = morph.value
                            alpha = (1f - p / 0.6f).coerceIn(0f, 1f)
                            translationX = -16.dp.toPx() * p.coerceIn(0f, 1f)
                        },
                ) {
                    MemberTimelineIdentity(name = memberName, avatarUrl = memberAvatarUrl)
                }
            }
            // One body: at rest a 48dp circle with the search glyph (the
            // button), opened it stretches leftward into the full field.
            GlassSearchField(
                query = query,
                onQueryChange = onQueryChange,
                enabled = active,
                placeholder = "搜索消息",
                focusRequester = focusRequester,
                height = 48.dp,
                expansion = { morph.value },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .then(if (searchOpen) Modifier else Modifier.clearAndSetSemantics { })
                    .layout { measurable, constraints ->
                        val collapsed = 48.dp.roundToPx()
                        val p = morph.value.coerceIn(0f, 1f)
                        val width = (collapsed + (constraints.maxWidth - collapsed) * p).roundToInt()
                            .coerceIn(collapsed, constraints.maxWidth)
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .glassPress(openPress),
            )
            if (!searchOpen) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .size(48.dp)
                        .clickable(
                            interactionSource = openInteraction,
                            indication = null,
                            enabled = active,
                            role = Role.Button,
                            onClickLabel = "搜索消息",
                            onClick = onOpenSearch,
                        )
                        .semantics { contentDescription = "搜索消息" },
                )
            }
        }
        GlassIconButton(
            onClick = onFilterClick,
            imageVector = Icons.Rounded.FilterList,
            contentDescription = "筛选与更多",
            enabled = active,
            tone = if (filterActive) GlassTone.Accent else GlassTone.Neutral,
            modifier = Modifier.testTag(UiTestTags.TIMELINE_MORE),
        )
    }
}

/** Avatar and name of the open member, at the height of the header controls. */
@Composable
private fun MemberTimelineIdentity(name: String, avatarUrl: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics(mergeDescendants = true) { heading() },
    ) {
        RemoteImage(
            url = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loadCachedImmediately = true,
            modifier = Modifier.size(38.dp).clip(GlassShapes.Circle),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = GlassType.Title3,
            color = GlassColors.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private enum class TimelineEdge { NEWER, OLDER }

private const val UNREAD_NEWER_CONTEXT_MIN = 6
private const val UNREAD_ANIMATE_MAX_DISTANCE = 30

@Composable
private fun MemberTimelineContent(
    memberKey: String,
    query: String,
    filter: TimeFilter,
    initialTargetId: String?,
    alignInitialTargetToTop: Boolean,
    locateUnreadToken: Long,
    active: Boolean,
    transitionSettled: Boolean,
    messageChanges: MessageChanges,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    unreadHintEnabled: Boolean = true,
    userNickname: String,
    dissolvingUnreadIds: Set<String>,
    onInitialLoaded: () -> Unit,
    onNoUnreadToLocate: () -> Unit,
    onViewingLatest: (Boolean) -> Unit,
    onRead: (Set<String>) -> Unit,
    onUnreadDissolved: (String) -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
    onToggleFavorite: (RelayMessage) -> Unit,
    topContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val loader = remember(memberKey, query, filter, userNickname) {
        { cancellation: CancellationSignal -> MemberTimelineLoader(object : MemberTimelineSource {
            override fun count() = AppGraph.messages.countMessagesForMember(
                memberKey, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun indexOf(messageId: String) = AppGraph.messages.messageIndexForMember(
                memberKey, messageId, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun messages(offset: Int, limit: Int) = AppGraph.messages.messagesForMember(
                memberKey, query, filter.startMillis, filter.endMillisExclusive, limit, offset, userNickname, cancellation,
            )
            override fun at(id: String) = byIds(setOf(id)).firstOrNull()
            override fun byIds(ids: Set<String>) = AppGraph.messages.memberMessagesByIds(
                memberKey, ids, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun olderThan(id: String, limit: Int) = AppGraph.messages.memberMessagesRelativeTo(
                memberKey, id, false, limit, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun newerThan(id: String, limit: Int) = AppGraph.messages.memberMessagesRelativeTo(
                memberKey, id, true, limit, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
        }) }
    }
    val mutex = remember { Mutex() }
    val listState = rememberLazyListState()
    val bottomContentPadding = 88.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val currentTopInset by rememberUpdatedState(with(density) { topContentPadding.roundToPx() })
    val currentBottomInset by rememberUpdatedState(with(density) { bottomContentPadding.roundToPx() })
    val visibleTimelineItems by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.visibleItemsInfo.filter { item ->
                timelineItemIntersectsReadingArea(
                    item.offset, item.size, layout.viewportStartOffset, layout.viewportEndOffset,
                    currentTopInset, currentBottomInset,
                )
            }
        }
    }
    var window by remember(loader) { mutableStateOf(MemberTimelineWindow()) }
    var loadedVersion by remember(loader) { mutableLongStateOf(-1L) }
    var initialized by remember(loader) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loadingEdge by remember { mutableStateOf<TimelineEdge?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var retryKey by remember { mutableIntStateOf(0) }
    var locatingUnread by remember { mutableStateOf(false) }
    val currentActive by rememberUpdatedState(active)
    val currentUnreadHintEnabled by rememberUpdatedState(unreadHintEnabled)
    val latestInitialLoaded by rememberUpdatedState(onInitialLoaded)
    val latestOnNoUnreadToLocate by rememberUpdatedState(onNoUnreadToLocate)
    val reducedMotion = LocalGlassReducedMotion.current
    val rows by remember { derivedStateOf { messageTimelineRows(window.messages) } }
    val context = LocalContext.current
    val atLatest by remember {
        derivedStateOf {
            initialized && !window.hasNewer && listState.firstVisibleItemIndex <= 1 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    suspend fun alignTargetToReadingTop(targetId: String, targetListIndex: Int, animate: Boolean) {
        val targetKey = "message:$targetId"
        val topMarginPx = with(density) { 8.dp.roundToPx() }
        val alreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.key == targetKey }
        if (!alreadyVisible) {
            if (animate) {
                listState.animateScrollToItem(targetListIndex, 0)
            } else {
                listState.scrollToItem(targetListIndex, 0)
            }
            withFrameNanos { }
        }
        repeat(3) { pass ->
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == targetKey } ?: return@repeat
            val readingTopEdge = layout.viewportEndOffset - layout.afterContentPadding
            val delta = unreadTargetScrollDelta(item.offset, item.size, readingTopEdge, topMarginPx)
            if (abs(delta) <= 4f) return@repeat
            val consumed = if (pass == 0 && animate) {
                listState.animateScrollBy(delta)
            } else {
                listState.scrollBy(delta)
            }
            withFrameNanos { }
            if (abs(consumed) < 1f) return@repeat
        }
    }

    LaunchedEffect(window.messages, active, transitionSettled) {
        if (active && transitionSettled && window.messages.isNotEmpty()) {
            primeCachedMessageAspectRatios(context, window.messages)
            preloadMessageMedia(context, window.messages, targetWidth = 768)
        }
    }

    LaunchedEffect(loader, messageChanges.version, active, retryKey) {
        if (!active) return@LaunchedEffect
        mutex.withLock {
            busy = true
            try {
                val firstLoad = !initialized
                val followLatest = atLatest && !listState.isScrollInProgress
                val previous = window
                val loaded = readDatabase { cancellation ->
                    val reader = loader(cancellation)
                    val changedIds = messageChanges.changedSince(loadedVersion)
                    when {
                        firstLoad -> reader.initial(initialTargetId)
                        query.isBlank() && changedIds != null -> reader.patch(previous, changedIds)
                        else -> reader.refresh(previous)
                    }
                }
                if (firstLoad) {
                    primeInitialTimelineMedia(
                        context = context,
                        messages = loaded.messages,
                        targetMessageId = initialTargetId,
                    )
                }
                if (!currentActive) return@withLock
                val keepFollowing = followLatest && atLatest && !listState.isScrollInProgress
                window = loaded
                loadedVersion = messageChanges.version
                initialized = true
                loadError = false
                if (firstLoad) {
                    val target = initialTargetId?.let { timelineListIndex(messageTimelineRows(loaded.messages), it) }
                    if (alignInitialTargetToTop && initialTargetId != null && target != null) {
                        locatingUnread = true
                        try {
                            withFrameNanos { }
                            alignTargetToReadingTop(initialTargetId, target, animate = false)
                        } finally {
                            locatingUnread = false
                        }
                    } else {
                        listState.requestScrollToItem(target ?: 0)
                    }
                    latestInitialLoaded()
                } else if (keepFollowing && previous.messages.firstOrNull()?.id != loaded.messages.firstOrNull()?.id) {
                    listState.requestScrollToItem(0)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                loadError = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(locateUnreadToken) {
        if (locateUnreadToken == 0L || !currentActive || !currentUnreadHintEnabled) return@LaunchedEffect
        locatingUnread = true
        try {
            mutex.withLock {
                if (!currentActive) return@withLock
                val targetId = readDatabase { cancellation ->
                    AppGraph.messages.earliestUnreadMessageIdForMember(
                        memberKey, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
                    )
                }
                if (targetId == null) {
                    if (currentActive) latestOnNoUnreadToLocate()
                    return@withLock
                }
                val existingIndex = window.messages.indexOfFirst { it.id == targetId }
                val needsReload = !initialized ||
                    existingIndex < 0 ||
                    (window.hasNewer && existingIndex < UNREAD_NEWER_CONTEXT_MIN)
                val targetRows = if (needsReload) {
                    val loaded = readDatabase { cancellation -> loader(cancellation).initial(targetId) }
                    primeCachedMessageAspectRatios(context, loaded.messages)
                    if (!currentActive) return@withLock
                    window = loaded
                    loadedVersion = messageChanges.version
                    initialized = true
                    loadError = false
                    withFrameNanos { }
                    messageTimelineRows(loaded.messages)
                } else {
                    messageTimelineRows(window.messages)
                }
                val targetListIndex = timelineListIndex(targetRows, targetId) ?: return@withLock
                val shouldAnimate = !needsReload && !reducedMotion &&
                    abs(targetListIndex - listState.firstVisibleItemIndex) <= UNREAD_ANIMATE_MAX_DISTANCE
                alignTargetToReadingTop(targetId, targetListIndex, animate = shouldAnimate)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep current scroll position if interrupted.
        } finally {
            locatingUnread = false
        }
    }
    LaunchedEffect(atLatest, active) { onViewingLatest(active && atLatest) }

    // Boundary IDs make consecutive batches distinct, even when the viewport is not yet full.
    LaunchedEffect(loader, active) {
        if (!active) return@LaunchedEffect
        snapshotFlow {
            val visible = visibleTimelineItems
            when {
                !initialized || busy || loadError || visible.isEmpty() ||
                    listState.layoutInfo.totalItemsCount != rows.size + 2 -> null
                window.hasOlder && visible.maxOf { it.index } >= listState.layoutInfo.totalItemsCount - 4 ->
                    TimelineEdge.OLDER to window.messages.last().id
                window.hasNewer && visible.minOf { it.index } <= 3 ->
                    TimelineEdge.NEWER to window.messages.first().id
                else -> null
            }
        }.distinctUntilChanged().collect { request ->
            if (request == null) return@collect
            mutex.withLock {
                if (!currentActive) return@withLock
                val edge = request.first
                if (edge == TimelineEdge.OLDER && !window.hasOlder || edge == TimelineEdge.NEWER && !window.hasNewer) {
                    return@withLock
                }
                busy = true
                loadingEdge = edge
                try {
                    val previous = window
                    val loaded = readDatabase { cancellation ->
                        val reader = loader(cancellation)
                        if (edge == TimelineEdge.OLDER) reader.older(previous) else reader.newer(previous)
                    }
                    if (currentActive) window = loaded
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    loadError = true
                } finally {
                    loadingEdge = null
                    busy = false
                }
            }
        }
    }

    val visibleUnreadIds by remember {
        derivedStateOf {
            val visibleKeys = visibleTimelineItems.map { it.key }.toSet()
            window.messages.filter { it.isUnread && "message:${it.id}" in visibleKeys }.map { it.id }.toSet()
        }
    }
    LaunchedEffect(visibleUnreadIds, active, transitionSettled, locatingUnread) {
        if (!active || !transitionSettled || locatingUnread || visibleUnreadIds.isEmpty()) return@LaunchedEffect
        val ids = visibleUnreadIds
        // Once a visible batch is marked, deliver its badge update even if navigation closes it.
        withContext(NonCancellable) {
            onRead(ids)
            withContext(AppGraph.dispatchers.databaseWrite) {
                AppGraph.messages.markMessagesReadByIds(ids)
                IncomingCallNotifier.cancel(context, ids)
            }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            userScrollEnabled = active,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topContentPadding, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag(UiTestTags.MEMBER_TIMELINE),
        ) {
            item(key = "timeline-newer") {
                TimelineBoundary(
                    visible = initialized && window.hasNewer,
                    loading = loadingEdge == TimelineEdge.NEWER,
                    error = loadError,
                    label = "加载较新消息",
                    onRetry = { retryKey++ },
                )
            }
            items(rows, key = { it.key }, contentType = { "message" }) { row ->
                when (row) {
                    is MessageTimelineRow.Message -> MessageCard(
                        message = row.message,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = null,
                            fadeOutSpec = tween(120),
                        ),
                        unreadState = resolveUnreadTagState(
                            hintEnabled = unreadHintEnabled,
                            isUnread = row.message.isUnread,
                            isDissolving = row.message.id in dissolvingUnreadIds,
                        ),
                        unreadDissolveActive = active && transitionSettled && !locatingUnread,
                        onUnreadDissolved = { onUnreadDissolved(row.message.id) },
                        audioState = playbackState.takeIf { it.messageId == row.message.id },
                        translationEnabled = translationEnabled,
                        userNickname = userNickname,
                        searchQuery = query,
                        enabled = active,
                        onOpenMedia = { onOpenMedia(row.message) },
                        onPlayVoice = { onPlayVoice(row.message) },
                        onDownload = { onDownload(row.message) },
                        onRetranslate = { onRetranslate(row.message) },
                        onToggleFavorite = { onToggleFavorite(row.message) },
                    )
                }
            }
            item(key = "timeline-older") {
                TimelineBoundary(
                    visible = initialized && window.hasOlder,
                    loading = loadingEdge == TimelineEdge.OLDER,
                    error = loadError,
                    label = "加载更早消息",
                    onRetry = { retryKey++ },
                )
            }
        }
        if (!initialized || window.messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(top = topContentPadding, bottom = bottomContentPadding), contentAlignment = Alignment.Center) {
                when {
                    loadError -> TextButton(onClick = { retryKey++ }) { Text("加载失败，点击重试", color = GlassColors.Accent) }
                    !initialized -> GlassCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
                    else -> Text(
                        if (query.isBlank() && !filter.isActive) "暂无消息" else "没有找到相关消息",
                        color = GlassColors.InkSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineBoundary(visible: Boolean, loading: Boolean, error: Boolean, label: String, onRetry: () -> Unit) {
    if (visible) {
        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            when {
                error -> TextButton(onClick = onRetry) { Text("加载失败，点击重试", color = GlassColors.Accent) }
                loading -> GlassCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
                else -> Text(label, style = GlassType.Caption, color = GlassColors.InkTertiary)
            }
        }
    } else {
        Spacer(Modifier.height(0.dp))
    }
}

private fun timelineItemIntersectsReadingArea(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    topInset: Int,
    bottomInset: Int,
): Boolean {
    val readingStart = (viewportStartOffset + topInset).coerceAtMost(viewportEndOffset)
    val readingEnd = (viewportEndOffset - bottomInset).coerceAtLeast(readingStart)
    val itemEnd = itemOffset + itemSize
    return itemEnd > readingStart && itemOffset < readingEnd
}

