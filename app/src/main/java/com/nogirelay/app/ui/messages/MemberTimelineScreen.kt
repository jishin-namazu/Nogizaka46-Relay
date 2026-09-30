package com.nogirelay.app.ui.messages

import android.os.CancellationSignal
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import com.nogirelay.app.ui.glass.LocalMediaSourceScope
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
import androidx.compose.ui.unit.sp
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.readDatabase
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.rememberSearchQuery
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.clearSelectionOnTap
import com.nogirelay.app.ui.preloadRemoteImage
import com.nogirelay.app.ui.glass.GlassBackButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassSearchField
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.glassMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    onOpenMedia: (RelayMessage, String) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
) {
    var auxiliaryScreen by remember(entry) { mutableStateOf<MemberTimelineAuxiliary?>(null) }
    val favoriteScope = rememberCoroutineScope()
    var query by remember(entry) { mutableStateOf("") }
    val workActive = active && com.nogirelay.app.performance.isRelayUiStarted() && !backdropState.isAttached
    val effectiveQuery = rememberSearchQuery(query, workActive)
    var timeFilter by remember(entry) { mutableStateOf(TimeFilter()) }
    var showFilter by remember(entry) { mutableStateOf(false) }
    var initialTargetConsumed by remember(entry) { mutableStateOf(false) }
    var sessionUnreadIds by remember(entry) { mutableStateOf(emptySet<String>()) }
    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current

    fun toggleFavorite(message: RelayMessage) {
        favoriteScope.launch {
            withContext(AppGraph.dispatchers.databaseWrite) {
                AppGraph.database.setMessageFavorite(message.id, !message.isFavorite)
            }
            AppGraph.notifyDataChanged(DataChange.MESSAGE_ROWS, setOf(message.id))
        }
    }

    fun back() {
        if (auxiliaryScreen != null) {
            auxiliaryScreen = null
            return
        }
        focusManager.clearFocus()
        textToolbar.hide()
        if (query.isNotEmpty()) query = "" else onBack()
    }
    BackHandler(enabled = active, onBack = ::back)
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
                    active = workActive,
                    versions = versions,
                    playbackState = playbackState,
                    translationEnabled = translationEnabled,
                    userNickname = userNickname,
                    onBack = { auxiliaryScreen = null },
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
        modifier = Modifier.fillMaxWidth().height(52.dp),
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
                fontSize = 14.sp,
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
private val mediaPreloadSlots = Semaphore(3)

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
): List<RelayMessage> = when (screen) {
    MemberTimelineAuxiliary.FAVORITES -> AppGraph.database.favoriteMessagesForMember(
        memberKey,
        limit = AUXILIARY_PAGE_SIZE,
        offset = offset,
    )
    MemberTimelineAuxiliary.MEDIA -> AppGraph.database.mediaMessagesForMember(
        memberKey,
        mediaCategory.type,
        limit = AUXILIARY_PAGE_SIZE,
        offset = offset,
    )
}

private suspend fun loadAuxiliaryCount(
    memberKey: String,
    screen: MemberTimelineAuxiliary,
    mediaCategory: MemberMediaCategory,
): Int = when (screen) {
    MemberTimelineAuxiliary.FAVORITES -> AppGraph.database.countFavoriteMessagesForMember(memberKey)
    MemberTimelineAuxiliary.MEDIA -> AppGraph.database.countMediaMessagesForMember(memberKey, mediaCategory.type)
}

private suspend fun preloadMessageMedia(
    context: android.content.Context,
    messages: List<RelayMessage>,
    targetWidth: Int,
) {
    val previewUrls = messages.mapNotNull { message ->
        when (message.type) {
            MessageType.IMAGE -> message.mediaUrl?.takeIf(String::isNotBlank)
                ?: message.thumbnailUrl?.takeIf(String::isNotBlank)
            MessageType.VIDEO -> message.thumbnailUrl?.takeIf(String::isNotBlank)
            else -> null
        }
    }.distinct()
    val voiceUrls = messages.asSequence()
        .filter { it.type == MessageType.AUDIO }
        .mapNotNull { it.mediaUrl?.takeIf(String::isNotBlank) }
        .distinct()
        .take(12)
        .toList()

    coroutineScope {
        previewUrls.map { url ->
            async(Dispatchers.IO) {
                mediaPreloadSlots.withPermit {
                    preloadRemoteImage(context, url, targetWidth = targetWidth)
                }
            }
        }.toList().awaitAll()
        voiceUrls.map { url ->
            async(Dispatchers.IO) {
                mediaPreloadSlots.withPermit {
                    runCatching {
                        if (MediaDownloader.cachedFileForUrl(context, url, MessageType.AUDIO) == null) {
                            MediaDownloader.downloadUrl(context, url, MessageType.AUDIO)
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
    versions: DataVersions,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
    userNickname: String,
    onBack: () -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onDownload: (RelayMessage) -> Unit,
    onRetranslate: (RelayMessage) -> Unit,
    onToggleFavorite: (RelayMessage) -> Unit,
) {
    var mediaCategory by remember(memberKey) { mutableStateOf(MemberMediaCategory.IMAGES) }
    var messages by remember(memberKey, screen, mediaCategory) { mutableStateOf<List<RelayMessage>>(emptyList()) }
    val gridRows = remember(messages) { memberMediaGridRows(messages) }
    var totalCount by remember(memberKey, screen, mediaCategory) { mutableIntStateOf(0) }
    var loading by remember(memberKey, screen, mediaCategory) { mutableStateOf(true) }
    var loadingMore by remember(memberKey, screen, mediaCategory) { mutableStateOf(false) }
    var exhausted by remember(memberKey, screen, mediaCategory) { mutableStateOf(false) }
    var loadedVersion by remember(memberKey, screen, mediaCategory) { mutableLongStateOf(-1L) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(messages, active, screen, mediaCategory) {
        if (active && messages.isNotEmpty()) {
            preloadMessageMedia(context, messages, targetWidth = 720)
        }
    }

    fun more() {
        if (!active || loading || loadingMore || exhausted) return
        loadingMore = true
        val offset = messages.size
        scope.launch {
            val next = withContext(AppGraph.dispatchers.databaseRead) {
                loadAuxiliaryPage(memberKey, screen, mediaCategory, offset)
            }
            val known = messages.mapTo(mutableSetOf()) { it.id }
            messages = messages + next.filter { known.add(it.id) }
            exhausted = next.size < AUXILIARY_PAGE_SIZE
            loadingMore = false
        }
    }

    LaunchedEffect(memberKey, screen, mediaCategory, versions.messages, active) {
        if (!active) return@LaunchedEffect
        if (loadedVersion == versions.messages && messages.isNotEmpty()) return@LaunchedEffect
        loading = messages.isEmpty()
        loadingMore = false
        exhausted = false
        val loaded = withContext(AppGraph.dispatchers.databaseRead) {
            loadAuxiliaryPage(memberKey, screen, mediaCategory, 0) to
                loadAuxiliaryCount(memberKey, screen, mediaCategory)
        }
        messages = loaded.first
        totalCount = loaded.second
        exhausted = loaded.first.size < AUXILIARY_PAGE_SIZE
        loadedVersion = versions.messages
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
        ) {
            GlassBackButton(onClick = onBack, contentDescription = "返回消息流")
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (screen == MemberTimelineAuxiliary.MEDIA) "媒体" else "收藏夹",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = GlassColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$totalCount",
                color = GlassColors.InkTertiary,
                fontSize = 12.5.sp,
            )
        }
        if (screen == MemberTimelineAuxiliary.MEDIA) {
            GlassSegmentedTabs(
                labels = MemberMediaCategory.entries.map(MemberMediaCategory::label),
                selectedIndex = mediaCategory.ordinal,
                onSelected = { mediaCategory = MemberMediaCategory.entries[it] },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
            }
            messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (screen == MemberTimelineAuxiliary.FAVORITES) "暂无收藏的消息" else "暂无${mediaCategory.label}消息",
                    color = GlassColors.InkSecondary,
                )
            }
            screen == MemberTimelineAuxiliary.FAVORITES -> AuxiliaryMessageList(
                messages = messages,
                hasMore = !exhausted,
                onLoadMore = ::more,
                playbackState = playbackState,
                translationEnabled = translationEnabled,
                userNickname = userNickname,
                onOpenMedia = onOpenMedia,
                onPlayVoice = onPlayVoice,
                onDownload = onDownload,
                onRetranslate = onRetranslate,
                onToggleFavorite = onToggleFavorite,
            )
            mediaCategory == MemberMediaCategory.VOICE -> AuxiliaryMessageList(
                messages = messages,
                reverseLayout = true,
                monthSeparators = true,
                hasMore = !exhausted,
                onLoadMore = ::more,
                playbackState = playbackState,
                translationEnabled = translationEnabled,
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
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
                modifier = Modifier.fillMaxSize(),
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

@Composable
private fun AuxiliaryMessageList(
    messages: List<RelayMessage>,
    hasMore: Boolean,
    reverseLayout: Boolean = false,
    monthSeparators: Boolean = false,
    onLoadMore: () -> Unit,
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
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
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (monthSeparators) {
            items(monthRows, key = MemberMediaRow::key, contentType = { it::class }) { row ->
                when (row) {
                    is MemberMediaRow.Media -> AuxiliaryMessageCard(
                        message = row.message,
                        playbackState = playbackState,
                        translationEnabled = translationEnabled,
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
                    playbackState = playbackState,
                    translationEnabled = translationEnabled,
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
    playbackState: VoicePlaybackState,
    translationEnabled: Boolean,
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
        isUnread = message.isUnread,
        audioState = playbackState.takeIf { it.messageId == message.id },
        translationEnabled = translationEnabled,
        userNickname = userNickname,
        searchQuery = "",
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
            fontSize = 12.sp,
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
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
    }
    LaunchedEffect(pageKey) { onLoadMore() }
}

@Composable
private fun MediaMessageCell(message: RelayMessage, onClick: () -> Unit) {
    val thumbnailUrl = message.thumbnailUrl?.takeIf(String::isNotBlank)
    val previewUrl = thumbnailUrl ?: message.mediaUrl?.takeIf(String::isNotBlank)
    val previewType = if (thumbnailUrl != null) MessageType.IMAGE else message.type

    val videoHasAudioTrack by rememberVideoHasAudioTrack(message)
    val cellShape = GlassShapes.CardSmall
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(cellShape)
            .glassMediaSource(
                key = mediaSourceKey(LocalMediaSourceScope.current, message.id),
                url = previewUrl,
                cornerRadiusPx = with(LocalDensity.current) { 20.dp.toPx() },
            )
            .background(Color(0x2B8E93A6))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (previewUrl != null) {
            RemoteImage(
                url = previewUrl,
                contentDescription = message.text?.takeIf(String::isNotBlank) ?: "媒体消息",
                loadCachedImmediately = true,
                messageType = previewType,
                message = message.takeIf { previewType == MessageType.VIDEO },
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
 * sample the root haze source, so messages blur as they scroll beneath.
 */
@Composable
private fun FloatingTimelineLayout(
    header: @Composable () -> Unit,
    content: @Composable (headerHeight: Dp) -> Unit,
) {
    SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val headerPlaceable = subcompose("header") { header() }.single()
            .measure(constraints.copy(minHeight = 0))
        val contentTopInset = (headerPlaceable.height.toDp() - 10.dp).coerceAtLeast(0.dp)
        val contentPlaceable = subcompose("timeline") {
            Box(Modifier.fillMaxSize()) {
                content(contentTopInset)
            }
        }.single().measure(constraints)
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
            contentDescription = if (query.isNotEmpty()) "清空搜索" else "返回成员列表",
        )
        GlassSearchField(
            query = query,
            onQueryChange = onQueryChange,
            enabled = active,
            placeholder = "搜索消息",
            modifier = Modifier.weight(1f),
        )
        GlassIconButton(
            onClick = onFilterClick,
            imageVector = Icons.Rounded.FilterList,
            contentDescription = "筛选与更多",
            enabled = active,
            tone = if (filterActive) GlassTone.Accent else GlassTone.Neutral,
        )
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
    onToggleFavorite: (RelayMessage) -> Unit,
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
    val currentActive by rememberUpdatedState(active)
    val latestInitialLoaded by rememberUpdatedState(onInitialLoaded)
    val rows by remember { derivedStateOf { messageTimelineRows(window.messages) } }
    val context = LocalContext.current
    val atLatest by remember {
        derivedStateOf {
            initialized && !window.hasNewer && listState.firstVisibleItemIndex <= 1 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }

    LaunchedEffect(window.messages, active) {
        if (active && window.messages.isNotEmpty()) {
            preloadMessageMedia(context, window.messages, targetWidth = 720)
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
                        GlassPanel(
                            shape = GlassShapes.Capsule,
                            depth = GlassDepths.None,
                            fillAlpha = 0.36f,
                            blur = 12.dp,
                            edgeStrength = 0.5f,
                        ) {
                            Text(
                                row.label,
                                fontSize = 11.sp,
                                color = GlassColors.InkSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
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
                    !initialized -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
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
                loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = GlassColors.Accent)
                else -> Text(label, fontSize = 11.sp, color = GlassColors.InkTertiary)
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

