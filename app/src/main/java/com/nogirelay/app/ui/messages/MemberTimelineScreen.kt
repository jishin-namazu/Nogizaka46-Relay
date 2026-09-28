package com.nogirelay.app.ui.messages

import android.os.CancellationSignal
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.MessageType
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.readDatabase
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.performance.rememberSearchQuery
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.RelayCardContentInset
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.LocalRelayMirrorStyle
import com.nogirelay.app.ui.RelayHomeCardShape
import com.nogirelay.app.ui.RelayLightBackdrop
import com.nogirelay.app.ui.RelayMirrorGlassBackground
import com.nogirelay.app.ui.RelayMirrorGlassCard
import com.nogirelay.app.ui.RelayMirrorGlassIconButton
import com.nogirelay.app.ui.RelayNavigationBarShape
import com.nogirelay.app.ui.RelaySearchField
import com.nogirelay.app.ui.RelaySelectionSurface
import com.nogirelay.app.ui.RelaySegmentedTabs
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.clearSelectionOnTap
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
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
            showFilter = false
            auxiliaryScreen = null
        }
    }

    AnimatedContent(
        targetState = auxiliaryScreen,
        transitionSpec = {
            // 进入二级页面时新页从右侧推入，返回时消息流从左侧回到位置。
            val enteringAuxiliary = targetState != null
            val enter = fadeIn(animationSpec = tween(220)) +
                slideInHorizontally(animationSpec = tween(260)) { width ->
                    if (enteringAuxiliary) width / 12 else -width / 12
                }
            val exit = fadeOut(animationSpec = tween(180)) +
                slideOutHorizontally(animationSpec = tween(220)) { width ->
                    if (enteringAuxiliary) -width / 16 else width / 16
                }
            enter togetherWith exit
        },
        label = "member_auxiliary_transition",
        modifier = Modifier.fillMaxSize(),
    ) { screen ->
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
                onOpenMedia = onOpenMedia,
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
                        onOpenMedia = onOpenMedia,
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

/**
 * 筛选抽屉里的二级入口：与时间 / 成员筛选按钮一致的 40dp 可见玻璃面与 48dp 触摸区域。
 */
@Composable
private fun FilterDrawerEntry(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    val mirrorStyle = LocalRelayMirrorStyle.current
    RelaySelectionSurface(
        onClick = onClick,
        selected = false,
        useNavigationStyle = true,
        visualHeight = if (mirrorStyle) 40.dp else null,
        // 二级页面入口不是选中项，不向读屏软件宣告“未选中”。
        announceSelected = false,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (mirrorStyle) 48.dp else 44.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = BrandPurple, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 二级页面每次读取的行数，滚动到底部时继续补齐。 */
private const val AUXILIARY_PAGE_SIZE = 200

private enum class MemberTimelineAuxiliary { MEDIA, FAVORITES }

private enum class MemberMediaCategory(
    val label: String,
    val type: MessageType,
) {
    IMAGES("图片", MessageType.IMAGE),
    VIDEOS("视频", MessageType.VIDEO),
    VOICE("语音", MessageType.AUDIO),
}

/** 图片 / 视频网格的列数，排版与 `GridCells.Fixed` 必须一致。 */
private const val MEDIA_GRID_COLUMNS = 4

private sealed interface MemberMediaRow {
    val key: String

    data class Media(val message: RelayMessage) : MemberMediaRow {
        override val key: String = "media:${message.id}"
    }

    data class Month(override val key: String, val label: String) : MemberMediaRow

    /** 网格里的行尾空位：保持每行对齐，内容从左往右排。 */
    data class Gap(override val key: String) : MemberMediaRow
}

/** 一个月的媒体分组，`items` 为新 → 旧。 */
private data class MemberMediaMonth(
    val key: String,
    val label: String,
    val items: List<RelayMessage>,
)

private fun memberMediaMonths(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MemberMediaMonth> {
    // 列表已按发送时间降序，同一个月的条目一定连续。
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

/** 语音列表：月份隔离符跟在每个降序分组之后，在反向布局里显示在该组上方。 */
private fun memberMediaRows(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MemberMediaRow> = buildList {
    memberMediaMonths(messages, zone).forEach { month ->
        month.items.forEach { add(MemberMediaRow.Media(it)) }
        add(MemberMediaRow.Month(month.key, month.label))
    }
}

/**
 * 四列网格的排版：每个月从上往下、每行从左到右都由旧到新，
 * 且每个月从上端第一行开始左对齐排满（剩下不足一行的缺口在行尾）。
 */
private fun memberMediaGridRows(
    messages: List<RelayMessage>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MemberMediaRow> = buildList {
    memberMediaMonths(messages, zone).forEach { month ->
        // 从最老的一张开始分行，保证月份上端第一行排满。
        val rows = month.items.asReversed().chunked(MEDIA_GRID_COLUMNS)
        rows.asReversed().forEach { row ->
            row.forEach { add(MemberMediaRow.Media(it)) }
            // 行尾补齐空位，让每一行都占满四格，避免不足一行时后续行错位。
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

    fun more() {
        if (!active || loading || loadingMore || exhausted) return
        loadingMore = true
        val offset = messages.size
        scope.launch {
            val next = withContext(AppGraph.dispatchers.databaseRead) {
                loadAuxiliaryPage(memberKey, screen, mediaCategory, offset)
            }
            // 分页之间可能有新消息插入，按 id 去重后再顺序追加。
            val known = messages.mapTo(mutableSetOf()) { it.id }
            messages = messages + next.filter { known.add(it.id) }
            exhausted = next.size < AUXILIARY_PAGE_SIZE
            loadingMore = false
        }
    }

    LaunchedEffect(memberKey, screen, mediaCategory, versions.messages, active) {
        if (!active) return@LaunchedEffect
        // 熄屏或回到前台时数据没有变化，直接保留现有分页和滚动位置；
        // 只有已经没有任何内容时才显示整页加载态。
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

    Column(Modifier.fillMaxSize().background(RelayLightBackdrop).statusBarsPadding()) {
        RelayMirrorGlassCard(
            shape = RelayHomeCardShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 顶栏压缩：图标按钮保留 48dp 触摸区，卡片高度只留最小内边距。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            ) {
                RelayMirrorGlassIconButton(
                    onClick = onBack,
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回消息流",
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (screen == MemberTimelineAuxiliary.MEDIA) "媒体" else "收藏夹",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$totalCount",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        if (screen == MemberTimelineAuxiliary.MEDIA) {
            RelaySegmentedTabs(
                labels = MemberMediaCategory.entries.map(MemberMediaCategory::label),
                selectedIndex = mediaCategory.ordinal,
                onSelected = { mediaCategory = MemberMediaCategory.entries[it] },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            messages.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (screen == MemberTimelineAuxiliary.FAVORITES) "暂无收藏的消息" else "暂无${mediaCategory.label}消息",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 128.dp),
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
                        // 行尾空位：与预览图同尺寸，保持每行左对齐。
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
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 128.dp),
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
                    // 单列列表不会出现网格的行尾空位。
                    is MemberMediaRow.Gap -> Unit
                }
            }
        } else {
            items(messages, key = RelayMessage::id) { message ->
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
            item(key = "auxiliary_message_loading") {
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

/** 媒体二级页的月份隔离标识：反向布局里显示在该月内容上方。 */
@Composable
private fun MemberMediaMonthHeader(label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 列表底部提前预取下一页；走到底部时逐页补齐历史。 */
@Composable
private fun AuxiliaryLoadFooter(pageKey: Int, onLoadMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
    LaunchedEffect(pageKey) { onLoadMore() }
}

@Composable
private fun MediaMessageCell(message: RelayMessage, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnailUrl = message.thumbnailUrl?.takeIf(String::isNotBlank)
    val previewUrl = thumbnailUrl ?: message.mediaUrl?.takeIf(String::isNotBlank)
    val previewType = if (thumbnailUrl != null) MessageType.IMAGE else message.type
    // 与消息流一致：静音状态优先读数据库，只在从未记录时检测一次并写回。
    val videoHasAudioTrack by produceState<Boolean?>(message.videoHasAudio, message.id, message.mediaUrl) {
        value = MediaDownloader.resolveVideoHasAudio(context, message)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RelayControlShape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
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
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.18f)))
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "播放视频",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
            if (videoHasAudioTrack == false) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(Color.Black.copy(alpha = 0.62f), CircleShape),
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
                // Keep the card border flush with the controls' layout edges.
                Modifier.fillMaxWidth(),
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
