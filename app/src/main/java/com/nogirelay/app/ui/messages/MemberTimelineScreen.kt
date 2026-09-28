package com.nogirelay.app.ui.messages

import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.readDatabase
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.rememberSearchQuery
import com.nogirelay.app.ui.RelayMirrorGlassBackground
import com.nogirelay.app.ui.RelayNavigationBarShape
import com.nogirelay.app.ui.RelayMirrorGlassIconButton
import com.nogirelay.app.ui.RelaySearchField
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.clearSelectionOnTap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
internal fun MemberTimelineScreen(
    entry: MemberMessageEntry,
    active: Boolean,
    versions: DataVersions,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    userNickname: String,
    backdropState: RelaySheetBackdropState,
    onBack: () -> Unit,
    onInitialMessageHandled: (String) -> Unit,
    onViewingLatest: (Boolean) -> Unit,
    onUnreadChanged: (Set<String>) -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
) {
    var query by remember(entry) { mutableStateOf("") }
    val workActive = active && com.nogirelay.app.performance.isRelayUiStarted() && !backdropState.isAttached
    val effectiveQuery = rememberSearchQuery(query, workActive)
    var timeFilter by remember(entry) { mutableStateOf(TimeFilter()) }
    var showFilter by remember(entry) { mutableStateOf(false) }
    var initialTargetConsumed by remember(entry) { mutableStateOf(false) }
    var sessionUnreadIds by remember(entry) { mutableStateOf(emptySet<String>()) }
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current

    fun back() {
        focusManager.clearFocus()
        textToolbar.hide()
        if (query.isNotEmpty()) query = "" else onBack()
    }
    BackHandler(enabled = active, onBack = ::back)
    LaunchedEffect(active) { if (!active) showFilter = false }

    FloatingTimelineLayout(
        header = {
            MemberTimelineHeader(
                query = query,
                active = active,
                filterActive = timeFilter.isActive,
                onBack = ::back,
                onQueryChange = { if (active) query = it },
                onFilterClick = {
                    focusManager.clearFocus()
                    textToolbar.hide()
                    showFilter = true
                },
            )
        },
    ) { headerHeight ->
        key(entry, effectiveQuery, timeFilter, userNickname) {
            MemberTimelineContent(
                memberKey = entry.memberKey,
                query = effectiveQuery,
                filter = timeFilter,
                initialTargetId = entry.targetMessageId.takeIf {
                    !initialTargetConsumed && effectiveQuery.isBlank() && !timeFilter.isActive
                },
                active = workActive,
                versions = versions,
                playbackState = playbackState,
                translationEnabled = translationEnabled,
                userNickname = userNickname,
                sessionUnreadIds = sessionUnreadIds,
                onInitialLoaded = {
                    if (!initialTargetConsumed) {
                        initialTargetConsumed = true
                        entry.notificationMessageId?.let(onInitialMessageHandled)
                    }
                },
                onViewingLatest = { onViewingLatest(it && query.isBlank() && !timeFilter.isActive) },
                onRead = { sessionUnreadIds = sessionUnreadIds + it; onUnreadChanged(it) },
                onOpenMedia = onOpenMedia,
                onPlayVoice = onPlayVoice,
                onDownload = onDownload,
                onRetranslate = onRetranslate,
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
        )
    }
}

/** Measure the overlay first so the very first timeline layout already has its true inset. */
@Composable
private fun FloatingTimelineLayout(
    header: @Composable () -> Unit,
    content: @Composable (headerHeight: Dp) -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val headerPlaceable = subcompose("header", header).single()
            .measure(constraints.copy(minHeight = 0))
        val contentPlaceable = subcompose("timeline") { content(headerPlaceable.height.toDp()) }
            .single().measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) {
            contentPlaceable.placeRelative(0, 0)
            headerPlaceable.placeRelative(0, 0)
        }
    }
}

@Composable
private fun MemberTimelineHeader(
    query: String,
    active: Boolean,
    filterActive: Boolean,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
) {
    // Insets are outside the floating card; it is a sibling of the scrolling timeline.
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Box(
            Modifier.fillMaxWidth().pointerInput(Unit) {
                // Empty card space also intercepts hits, without replacing child controls or
                // allowing taps/long presses to reach messages underneath the overlay.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
                    }
                }
            },
        ) {
            // The same shell as navigation, sampled from the page's independent empty backdrop.
            RelayMirrorGlassBackground(
                shape = RelayNavigationBarShape,
                modifier = Modifier.matchParentSize(),
            )
            Row(
                // Match the navigation shell's 7dp inset: the 24dp button corners sit
                // concentrically inside the card's 31dp outer corners.
                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RelayMirrorGlassIconButton(
                    onClick = onBack,
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = if (query.isNotEmpty()) "清空搜索" else "返回成员列表",
                    enabled = active,
                )
                RelaySearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    placeholder = "搜索消息内容或日期",
                    modifier = Modifier.weight(1f),
                    enabled = active,
                )
                RelayMirrorGlassIconButton(
                    onClick = onFilterClick,
                    imageVector = Icons.Rounded.FilterList,
                    contentDescription = if (filterActive) "筛选时间，已启用" else "筛选时间",
                    active = filterActive,
                    enabled = active,
                )
            }
        }
    }
}

private enum class TimelineEdge { NEWER, OLDER }

@Composable
private fun MemberTimelineContent(
    memberKey: String,
    query: String,
    filter: TimeFilter,
    initialTargetId: String?,
    active: Boolean,
    versions: DataVersions,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    userNickname: String,
    sessionUnreadIds: Set<String>,
    onInitialLoaded: () -> Unit,
    onViewingLatest: (Boolean) -> Unit,
    onRead: (Set<String>) -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
    topContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val loader = remember(memberKey, query, filter, userNickname) {
        { cancellation: CancellationSignal -> MemberTimelineLoader(object : MemberTimelineSource {
            override fun count() = AppGraph.database.countMessagesForMember(
                memberKey, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun indexOf(messageId: String) = AppGraph.database.messageIndexForMember(
                memberKey, messageId, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun messages(offset: Int, limit: Int) = AppGraph.database.messagesForMember(
                memberKey, query, filter.startMillis, filter.endMillisExclusive, limit, offset, userNickname, cancellation,
            )
            override fun at(id: String) = byIds(setOf(id)).firstOrNull()
            override fun byIds(ids: Set<String>) = AppGraph.database.memberMessagesByIds(
                memberKey, ids, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun olderThan(id: String, limit: Int) = AppGraph.database.memberMessagesRelativeTo(
                memberKey, id, false, limit, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
            override fun newerThan(id: String, limit: Int) = AppGraph.database.memberMessagesRelativeTo(
                memberKey, id, true, limit, query, filter.startMillis, filter.endMillisExclusive, userNickname, cancellation,
            )
        }) }
    }
    val mutex = remember { Mutex() }
    val listState = rememberLazyListState()
    val bottomContentPadding = 128.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
    val currentActive by rememberUpdatedState(active)
    val latestInitialLoaded by rememberUpdatedState(onInitialLoaded)
    val rows by remember { derivedStateOf { messageTimelineRows(window.messages) } }
    val atLatest by remember {
        derivedStateOf {
            initialized && !window.hasNewer && listState.firstVisibleItemIndex <= 1 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(loader, versions.messages, active, retryKey) {
        if (!active) return@LaunchedEffect
        mutex.withLock {
            busy = true
            try {
                val firstLoad = !initialized
                val followLatest = atLatest && !listState.isScrollInProgress
                val previous = window
                val loaded = readDatabase { cancellation ->
                    val reader = loader(cancellation)
                    val changedIds = versions.messageIdsSince(loadedVersion)
                    when {
                        firstLoad -> reader.initial(initialTargetId)
                        query.isBlank() && changedIds != null -> reader.patch(previous, changedIds)
                        else -> reader.refresh(previous)
                    }
                }
                if (!currentActive) return@withLock
                val keepFollowing = followLatest && atLatest && !listState.isScrollInProgress
                window = loaded
                loadedVersion = versions.messages
                initialized = true
                loadError = false
                if (firstLoad) {
                    val target = initialTargetId?.let { timelineListIndex(messageTimelineRows(loaded.messages), it) }
                    listState.requestScrollToItem(target ?: 0)
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
    LaunchedEffect(visibleUnreadIds, active) {
        if (!active || visibleUnreadIds.isEmpty()) return@LaunchedEffect
        val ids = visibleUnreadIds
        // Once a visible batch is marked, deliver its badge update even if navigation closes it.
        withContext(NonCancellable) {
            val updated = withContext(AppGraph.dispatchers.databaseWrite) { AppGraph.database.markMessagesReadByIds(ids) }
            if (updated > 0) onRead(ids)
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topContentPadding, bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Always reserve this item index; locating a message never depends on loading state.
            item(key = "timeline-newer") {
                TimelineBoundary(
                    visible = initialized && window.hasNewer,
                    loading = loadingEdge == TimelineEdge.NEWER,
                    error = loadError,
                    label = "加载较新消息",
                    onRetry = { retryKey++ },
                )
            }
            items(rows, key = { it.key }, contentType = { if (it is MessageTimelineRow.Day) "day" else "message" }) { row ->
                when (row) {
                    is MessageTimelineRow.Day -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(row.label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is MessageTimelineRow.Message -> MessageCard(
                        message = row.message,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(180),
                            placementSpec = null,
                            fadeOutSpec = tween(120),
                        ),
                        isUnread = row.message.isUnread || row.message.id in sessionUnreadIds,
                        audioState = playbackState.takeIf { it.messageId == row.message.id },
                        translationEnabled = translationEnabled,
                        userNickname = userNickname,
                        searchQuery = query,
                        enabled = active,
                        onOpenMedia = { onOpenMedia(row.message) },
                        onPlayVoice = { onPlayVoice(row.message) },
                        onDownload = { onDownload(row.message) },
                        onRetranslate = { onRetranslate(row.message) },
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
                    loadError -> TextButton(onClick = { retryKey++ }) { Text("加载失败，点击重试") }
                    !initialized -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else -> Text(
                        if (query.isBlank() && !filter.isActive) "暂无消息" else "没有找到相关消息",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                error -> TextButton(onClick = onRetry) { Text("加载失败，点击重试") }
                loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
