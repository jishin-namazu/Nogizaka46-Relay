package com.nogirelay.app.blog

import com.nogirelay.app.ui.UiTestTags
import androidx.compose.ui.platform.testTag
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nogirelay.app.NameWithUnreadTag
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.BlogReadTracker
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.data.isRealBlogImageUrl
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.isRelayUiStarted
import com.nogirelay.app.performance.rememberSearchQuery
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.AutoClearSelectionOnExit
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.SearchHighlightText
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterSection
import com.nogirelay.app.ui.glass.GlassMetrics
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.highlightMatches
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.clearSelectionOnTap
import com.nogirelay.app.ui.MediaViewerActivity
import com.nogirelay.app.ui.glass.GlassBottomSheet
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassCircleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassHeader
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassSearchField
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.glassMediaSource
import com.nogirelay.app.translation.BlogTranslationLayout
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.ui.hasInvertedRange
import com.nogirelay.app.ui.transfer.MemberPickerCard
import com.nogirelay.app.ui.transfer.memberGroups
import com.nogirelay.app.ui.transfer.preloadMemberAvatars
import com.nogirelay.app.ui.primeCachedImageAspectRatios
import com.nogirelay.app.ui.withoutTextPresentationSelector
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Header and search rows above the first post. */
private const val BLOG_HEADER_ITEMS = 2

/**
 * Blog list + detail, rebuilt as liquid glass. All data behavior is
 * unchanged; only the presentation moved to the shared glass system.
 */
@Composable
fun BlogScreen(
    initialBlogId: String?,
    onInitialBlogHandled: (String) -> Unit,
    isActive: Boolean = true,
    reselected: Flow<Unit> = emptyFlow(),
    viewModel: BlogViewModel = viewModel(),
) {
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val workActive = isActive && isRelayUiStarted() && !sheetBackdrop.isAttached
    val context = LocalContext.current
    val selected = viewModel.selectedBlogId
    val listActive = workActive && selected == null
    val effectiveQuery = rememberSearchQuery(viewModel.searchQuery, workActive)
    // The filter sheet is transient; everything it edits lives in the ViewModel.
    var showMemberDialog by remember { mutableStateOf(false) }
    val blogListState = rememberLazyListState()
    val pager = viewModel.pager
    val translationEnabled by viewModel.translationEnabled.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val listRevision by viewModel.listRevision.collectAsStateWithLifecycle()

    LaunchedEffect(initialBlogId, workActive) {
        if (!workActive) return@LaunchedEffect
        initialBlogId?.let { id -> viewModel.openFromNotification(id) { onInitialBlogHandled(id) } }
    }

    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    AutoClearSelectionOnExit(isActive = isActive)

    BackHandler(enabled = isActive && (selected != null || viewModel.searchQuery.isNotEmpty())) {
        focusManager.clearFocus()
        textToolbar.hide()
        if (selected != null) viewModel.closeBlog() else viewModel.searchQuery = ""
    }
    // Tapping the Blog tab again closes the article, then scrolls to the top.
    LaunchedEffect(reselected) {
        reselected.collect {
            if (viewModel.selectedBlogId != null) viewModel.closeBlog() else blogListState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(members) { viewModel.pruneMemberSelection(members) }

    // Infinite list: counts, a month index and chunks near the viewport.
    val listQuery = BlogListQuery(
        viewModel.selectedMemberIds,
        effectiveQuery,
        viewModel.oldestFirst,
        viewModel.timeFilter,
        translationEnabled,
    )
    LaunchedEffect(listRevision, listActive, listQuery) {
        if (!listActive) return@LaunchedEffect
        val previousQuery = pager.snapshot.query
        val sameQuery = previousQuery == listQuery
        val pending = viewModel.pendingScrollIndex
        val anchor = when {
            pending != null -> pending
            // A data refresh keeps the rows around the current position.
            sameQuery -> (blogListState.firstVisibleItemIndex - BLOG_HEADER_ITEMS).coerceAtLeast(0)
            else -> 0
        }
        // A refresh (e.g. a post marked read) re-reads a wider band, so rows
        // just scrolled past don't fall back to placeholders.
        pager.load(listQuery, anchor, radius = if (sameQuery) 3 else 1, changes = viewModel.rowChanges.value)
        when {
            pending != null -> {
                blogListState.requestScrollToItem(BLOG_HEADER_ITEMS + pending)
                viewModel.pendingScrollIndex = null
            }
            // A new query keeps the search controls in place when they are
            // on screen; from further down the list it returns to the top.
            !sameQuery && previousQuery != null && blogListState.firstVisibleItemIndex >= BLOG_HEADER_ITEMS ->
                blogListState.requestScrollToItem(0)
        }
    }

    // Read marks and new translations patch the shown rows instead of reloading the list.
    val rowChanges by viewModel.rowChanges.collectAsStateWithLifecycle()
    LaunchedEffect(rowChanges, listActive) {
        if (!listActive) return@LaunchedEffect
        if (pager.applyRowChanges(rowChanges)) {
            val query = pager.snapshot.query ?: return@LaunchedEffect
            val anchor = (blogListState.firstVisibleItemIndex - BLOG_HEADER_ITEMS).coerceAtLeast(0)
            pager.load(query, anchor, radius = 3, changes = rowChanges)
        }
    }

    // Read the chunks around whatever is visible, one chunk ahead each way.
    LaunchedEffect(pager, blogListState) {
        snapshotFlow {
            // Empty while the list is not shown (an article is open).
            val visible = blogListState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@snapshotFlow null
            val first = visible.first().index - BLOG_HEADER_ITEMS
            val last = visible.last().index - BLOG_HEADER_ITEMS
            Triple(first.coerceAtLeast(0), last.coerceAtLeast(0), pager.snapshot.query)
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (first, last) -> pager.ensureLoaded(first, last) }
    }

    CompositionLocalProvider(
        com.nogirelay.app.performance.LocalRelayPageWorkPaused provides sheetBackdrop.isAttached,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            if (showMemberDialog) {
                BlogFilterDialog(
                    members = members,
                    backdropState = sheetBackdrop,
                    selectedIds = viewModel.selectedMemberIds ?: members.mapTo(linkedSetOf(), BlogMember::id),
                    timeFilter = viewModel.timeFilter,
                    oldestFirst = viewModel.oldestFirst,
                    onDismiss = { showMemberDialog = false },
                    onConfirm = { selectedIds, selectedTimeFilter, selectedOldestFirst ->
                        showMemberDialog = false
                        viewModel.applyFilter(selectedIds, selectedTimeFilter, selectedOldestFirst)
                    },
                )
            }

            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    val slideSpring = spring<IntOffset>(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    if (targetState != null) {
                        (
                            slideInHorizontally(slideSpring) { it / 4 } + fadeIn(tween(160))
                            ).togetherWith(
                            slideOutHorizontally(slideSpring) { -it / 6 } + fadeOut(tween(140)),
                        )
                    } else {
                        (
                            slideInHorizontally(slideSpring) { -it / 6 } + fadeIn(tween(160))
                            ).togetherWith(
                            slideOutHorizontally(slideSpring) { it / 4 } + fadeOut(tween(140)),
                        )
                    }
                },
                label = "blog-detail-transition",
                modifier = Modifier.fillMaxSize(),
            ) { selectedId ->
                if (selectedId != null) {
                    BlogDetail(
                        blogId = selectedId,
                        isActive = workActive && selectedId == selected,
                        onBack = viewModel::closeBlog,
                        onRetranslate = viewModel::retranslate,
                    )
                    LaunchedEffect(selectedId, initialBlogId) {
                        if (selectedId == initialBlogId) onInitialBlogHandled(selectedId)
                    }
                    return@AnimatedContent
                }

                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = blogListState,
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.fillMaxSize().testTag(UiTestTags.BLOG_LIST),
                    ) {
                        item(key = "blog-header") {
                            GlassHeader(title = "博客")
                        }
                        item(key = "blog-controls") {
                            // Search takes the row; sort order lives in the filter sheet.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 8.dp),
                            ) {
                                GlassSearchField(
                                    query = viewModel.searchQuery,
                                    onQueryChange = { viewModel.searchQuery = it },
                                    placeholder = "搜索标题、正文或日期",
                                    modifier = Modifier.weight(1f),
                                )
                                val isFilterActive = viewModel.isFilterActive
                                GlassIconButton(
                                    onClick = { showMemberDialog = true },
                                    imageVector = Icons.Rounded.FilterList,
                                    contentDescription = "排序与筛选",
                                    size = GlassMetrics.ControlHeight,
                                    tone = if (isFilterActive) GlassTone.Accent else GlassTone.Neutral,
                                )
                            }
                        }

                        val snapshot = pager.snapshot
                        if (snapshot.loaded && snapshot.matchingCount == 0) {
                            item(key = "blog-empty") {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .animateItem(
                                            fadeInSpec = tween(300, easing = FastOutSlowInEasing),
                                            placementSpec = tween(300, easing = FastOutSlowInEasing),
                                            fadeOutSpec = tween(220, easing = FastOutSlowInEasing),
                                        )
                                        .fillMaxWidth()
                                        .padding(vertical = 64.dp),
                                ) {
                                    GlassPanel(
                                        shape = GlassShapes.Circle,
                                        modifier = Modifier.size(84.dp),
                                    ) {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.AutoMirrored.Rounded.Article,
                                                contentDescription = null,
                                                modifier = Modifier.size(32.dp),
                                                tint = GlassColors.InkTertiary,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        blogEmptyMessage(
                                            totalCount = snapshot.totalCount,
                                            searching = viewModel.searchQuery.isNotBlank(),
                                            memberFiltered = viewModel.selectedMemberIds != null,
                                            timeFiltered = viewModel.timeFilter.isActive,
                                        ),
                                        color = GlassColors.InkSecondary,
                                    )
                                }
                            }
                        } else if (snapshot.matchingCount > 0) {
                            val generation = snapshot.generation
                            items(
                                count = snapshot.matchingCount,
                                key = { index ->
                                    snapshot.item(index)?.let { "$generation:${it.id}" } ?: "$generation:slot:$index"
                                },
                                contentType = { index -> if (snapshot.item(index) != null) "blog" else "blog-placeholder" },
                            ) { index ->
                                val blog = snapshot.item(index)
                                if (blog == null) {
                                    // Replaced in place by the card, which fades in over it.
                                    BlogSummaryPlaceholder(
                                        modifier = Modifier
                                            .animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                                            .padding(bottom = 10.dp),
                                    )
                                } else {
                                    BlogSummaryCard(
                                        modifier = Modifier
                                            .animateItem(
                                                fadeInSpec = tween(220, easing = FastOutSlowInEasing),
                                                placementSpec = tween(260, easing = FastOutSlowInEasing),
                                                fadeOutSpec = tween(160, easing = FastOutSlowInEasing),
                                            )
                                            .padding(bottom = 10.dp),
                                        blog = blog,
                                        searchQuery = snapshot.query?.searchQuery.orEmpty(),
                                        bodyPreviews = snapshot.previews[blog.id].orEmpty(),
                                        translationEnabled = translationEnabled,
                                        onClick = { viewModel.openBlog(blog.id) },
                                        onDownload = {
                                            context.startActivity(BlogImageDownloadActivity.intent(context, blog.id))
                                        },
                                        onRetranslate = { viewModel.retranslate(blog.id) },
                                    )
                                }
                            }
                            item(key = "blog-footer") {
                                Text(
                                    text = "共 ${snapshot.matchingCount} 篇博客",
                                    color = GlassColors.InkTertiary,
                                    style = GlassType.Footnote,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                )
                            }
                            item(key = "blog-bottom-spacer") { Spacer(Modifier.height(128.dp)) }
                        }
                    }
                    val scrollerSnapshot = pager.snapshot
                    BlogMonthScroller(
                        listState = blogListState,
                        headerItems = BLOG_HEADER_ITEMS,
                        itemCount = scrollerSnapshot.matchingCount,
                        months = scrollerSnapshot.months,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(top = 112.dp, bottom = 96.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BlogFilterDialog(
    members: List<BlogMember>,
    backdropState: RelaySheetBackdropState,
    selectedIds: Set<String>,
    timeFilter: TimeFilter,
    oldestFirst: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, TimeFilter, Boolean) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(members) {
        if (members.isNotEmpty()) preloadMemberAvatars(context, members)
    }
    var draft by remember(members, selectedIds) { mutableStateOf(selectedIds.toSet()) }
    var draftTimeFilter by remember(timeFilter) { mutableStateOf(timeFilter) }
    var draftOldestFirst by remember(oldestFirst) { mutableStateOf(oldestFirst) }
    val allMemberIds = remember(members) { members.mapTo(linkedSetOf(), BlogMember::id) }
    GlassBottomSheet(
        onDismissRequest = onDismiss,
        backdropState = backdropState,
    ) { dismiss, handle ->
        Box(modifier = Modifier.fillMaxWidth()) {
            val columnCount = 2
            val groups = remember(members) { memberGroups(members) }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                overscrollEffect = null,
            ) {
                item(key = "filter-header") {
                    handle()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "排序与筛选",
                            style = GlassType.Title2,
                            fontWeight = FontWeight.Bold,
                            color = GlassColors.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            onClick = { dismiss(onDismiss) },
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭排序与筛选",
                        )
                    }
                }
                item(key = "sort-order") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("排序", style = GlassType.Callout, fontWeight = FontWeight.SemiBold, color = GlassColors.Ink)
                        GlassSegmentedTabs(
                            labels = listOf("最新优先", "最早优先"),
                            selectedIndex = if (draftOldestFirst) 1 else 0,
                            onSelected = { draftOldestFirst = it == 1 },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item(key = "time-filter") {
                    TimeFilterSection(
                        filter = draftTimeFilter,
                        onFilterChange = { draftTimeFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "member-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "成员 · 已选 ${draft.size}",
                            fontWeight = FontWeight.SemiBold,
                            color = GlassColors.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            onClick = { draft = allMemberIds },
                            imageVector = Icons.Rounded.DoneAll,
                            contentDescription = "全选",
                            enabled = members.isNotEmpty(),
                            size = 42.dp,
                            iconSize = 20.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        GlassIconButton(
                            onClick = { draft = emptySet() },
                            imageVector = Icons.Rounded.ClearAll,
                            contentDescription = "全不选",
                            enabled = draft.isNotEmpty(),
                            size = 42.dp,
                            iconSize = 20.dp,
                        )
                    }
                }
                if (members.isNotEmpty()) {
                    groups.forEach { (category, groupMembers) ->
                        item(key = "member-category-$category") {
                            Text(
                                category,
                                fontWeight = FontWeight.Bold,
                                color = GlassColors.Accent,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(
                            items = groupMembers.chunked(columnCount),
                            key = { row -> row.joinToString("|") { it.id } },
                        ) { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                row.forEach { member ->
                                    MemberPickerCard(
                                        member = member,
                                        selected = member.id in draft,
                                        graduatedTagAtCorner = true,
                                        onClick = {
                                            draft = if (member.id in draft) {
                                                draft - member.id
                                            } else {
                                                draft + member.id
                                            }
                                        },
                                        modifier = Modifier.weight(1f).heightIn(min = 96.dp),
                                    )
                                }
                                repeat(columnCount - row.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                item(key = "filter-actions") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        GlassCapsuleButton(
                            onClick = { dismiss(onDismiss) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        }
                        GlassCapsuleButton(
                            onClick = { dismiss { onConfirm(draft, draftTimeFilter, draftOldestFirst) } },
                            enabled = !draftTimeFilter.hasInvertedRange(),
                            tone = GlassTone.Accent,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("确定", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item(key = "filter-navigation-inset") {
                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                }
            }
        }
    }
}

/**
 * Stand-in for a post whose chunk is still being read. It mirrors the card's
 * frame (avatar row, title lines, cover) so the real card lands in place.
 */
@Composable
private fun BlogSummaryPlaceholder(modifier: Modifier = Modifier) {
    val fill = GlassColors.Placeholder.copy(alpha = 0.7f)
    GlassPanel(
        shape = GlassShapes.Card,
        staticMaterial = true,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(fill))
                Spacer(Modifier.size(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.width(96.dp).height(14.dp).clip(GlassShapes.Capsule).background(fill))
                    Box(Modifier.width(132.dp).height(10.dp).clip(GlassShapes.Capsule).background(fill))
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth(0.92f).height(16.dp).clip(GlassShapes.Capsule).background(fill))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.6f).height(16.dp).clip(GlassShapes.Capsule).background(fill))
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(GlassShapes.CardSmall)
                    .background(fill),
            )
        }
    }
}

@Composable
private fun BlogSummaryCard(
    blog: BlogSummary,
    searchQuery: String,
    bodyPreviews: List<BlogSearchPreview>,
    translationEnabled: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onRetranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightBackground = MaterialTheme.colorScheme.primaryContainer
    val highlightText = MaterialTheme.colorScheme.onPrimaryContainer
    var isPreviewsExpanded by remember(blog.id, searchQuery) { mutableStateOf(false) }

    GlassPanel(
        onClick = onClick,
        onClickLabel = blog.title,
        shape = GlassShapes.Card,
        staticMaterial = true,
        modifier = modifier
            .testTag(UiTestTags.BLOG_CARD)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp)) {
                    RemoteImage(
                        url = blog.memberAvatarUrl,
                        contentDescription = blog.memberName,
                        loadCachedImmediately = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    )
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    NameWithUnreadTag(
                        name = highlightMatches(blog.memberName, searchQuery, highlightBackground, highlightText),
                        isUnread = blog.isUnread,
                        style = GlassType.Headline.copy(fontWeight = FontWeight.Bold, color = GlassColors.Ink),
                        searchQuery = searchQuery,
                        highlightBackground = highlightBackground,
                    )
                    SearchHighlightText(
                        text = formatBlogDate(blog.publishedAt),
                        query = searchQuery,
                        highlightBackground = highlightBackground,
                        highlightTextColor = highlightText,
                        style = GlassType.Footnote.copy(color = GlassColors.InkTertiary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (translationEnabled) {
                    GlassCircleButton(
                        onClick = onRetranslate,
                        size = 40.dp,
                        contentDescription = "重新翻译",
                    ) {
                        AiTranslateIcon(
                            size = 20.dp,
                            tint = GlassColors.Accent,
                            contentDescription = null,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                GlassCircleButton(
                    onClick = onDownload,
                    size = 40.dp,
                    contentDescription = "选择下载博客图片",
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = null,
                        tint = GlassColors.Accent,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SearchHighlightText(
                text = blog.title,
                query = searchQuery,
                highlightBackground = highlightBackground,
                highlightTextColor = highlightText,
                style = GlassType.Title3,
                color = GlassColors.Ink,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedContent(
                targetState = blog.translatedTitle?.takeIf { translationEnabled },
                transitionSpec = {
                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                },
                label = "blog-summary-translation",
            ) { translatedTitle ->
                translatedTitle?.let {
                    SearchHighlightText(
                        text = it,
                        query = searchQuery,
                        highlightBackground = highlightBackground,
                        highlightTextColor = highlightText,
                        style = GlassType.Callout.copy(
                            color = GlassColors.AccentInk,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            val visiblePreviews = if (isPreviewsExpanded || bodyPreviews.size <= 3) {
                bodyPreviews
            } else {
                bodyPreviews.take(3)
            }

            visiblePreviews.forEach { preview ->
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        preview.label,
                        color = GlassColors.Accent,
                        style = GlassType.Caption,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .alignByBaseline(),
                    )
                    SearchHighlightText(
                        text = preview.text,
                        query = searchQuery,
                        highlightBackground = highlightBackground,
                        highlightTextColor = highlightText,
                        style = GlassType.Subhead.copy(color = GlassColors.InkSecondary),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .alignByBaseline(),
                    )
                }
            }

            if (bodyPreviews.size > 3) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(GlassShapes.Capsule)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = { isPreviewsExpanded = !isPreviewsExpanded },
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (isPreviewsExpanded) "收起匹配项" else "展开剩余 ${bodyPreviews.size - 3} 处匹配",
                        color = GlassColors.Accent,
                        style = GlassType.Footnote,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = GlassColors.Accent,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer {
                                rotationZ = if (isPreviewsExpanded) 180f else 0f
                            },
                    )
                }
            }
            blog.imageUrl?.takeIf(::isRealBlogImageUrl)?.let {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(GlassShapes.CardSmall),
                ) {
                    RemoteImage(
                        url = it,
                        contentDescription = blog.title,
                        contentScale = ContentScale.Fit,
                        preserveAspectRatio = true,
                        loadCachedImmediately = false,
                        crossfadeDurationMillis = 220,
                        placeholderAspectRatio = 1f,
                        animateAspectRatioChanges = true,
                        placeholderColor = Color(0x228E93A6),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private data class ParsedBlogDetail(
    val blog: BlogPost,
    val translationEnabled: Boolean,
    val titleTranslation: String?,
    val displayBlocks: List<DisplayBlock>,
)

@Composable
private fun BlogDetail(
    blogId: String,
    isActive: Boolean = true,
    onBack: () -> Unit,
    onRetranslate: (String) -> Unit,
) {
    val context = LocalContext.current
    // Subscribed only while shown; the last post stays on screen meanwhile.
    val postFlow = remember(blogId, isActive) {
        if (isActive) AppGraph.blogRepository.post(blogId) else emptyFlow()
    }
    val post by postFlow.collectAsStateWithLifecycle(initialValue = null)
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()

    val detailState by produceState<ParsedBlogDetail?>(
        initialValue = null,
        post,
        settings.translationEnabled,
    ) {
        val loaded = post?.let { it to settings }

        value = loaded?.let { (blog, settings) ->
            withContext(AppGraph.dispatchers.parsing) {
                val parsed = BlogTextCache.parse(blog.id, blog.bodyHtml)
                val contentBlocks = parsed.blocks
                val bodyText = parsed.plainText
                val source = listOf(blog.title.trim(), bodyText).filter(String::isNotBlank).joinToString("\n\n\n")
                val layout = BlogTranslationLayout.from(source)
                val translations = if (settings.translationEnabled) layout.decode(blog.translation) else emptyList()
                val titleTranslation = if (blog.title.isNotBlank()) translations.firstOrNull() else null
                val bodyTranslations = if (blog.title.isNotBlank()) translations.drop(1) else translations
                val displayList = displayBlocks(contentBlocks, bodyTranslations)
                primeCachedImageAspectRatios(context, displayList.filterIsInstance<DisplayBlock.Image>().map { it.url })
                ParsedBlogDetail(
                    blog = blog,
                    translationEnabled = settings.translationEnabled,
                    titleTranslation = titleTranslation,
                    displayBlocks = displayList,
                )
            }
        }
    }

    DisposableEffect(blogId, isActive) {
        if (isActive) {
            BlogReadTracker.openBlog(blogId)
        }
        onDispose { BlogReadTracker.closeBlog(blogId) }
    }
    LaunchedEffect(blogId, isActive) {
        if (!isActive) return@LaunchedEffect

        withContext(NonCancellable + AppGraph.dispatchers.databaseWrite) {
            AppGraph.blogs.markBlogRead(blogId)
        }
    }
    LaunchedEffect(detailState?.blog?.bodyHtml, detailState?.blog?.translationDone, isActive) {
        if (!isActive) return@LaunchedEffect
        val current = detailState?.blog ?: return@LaunchedEffect
        val isTranslationEnabled = detailState?.translationEnabled ?: false
        if (current.bodyHtml.isNotBlank() && isTranslationEnabled && !current.translationDone) {
            BlogTranslationManager.enqueue(context, current.id)
        }
    }

    val detail = detailState
    if (detail == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val blog = detail.blog
    val translationEnabled = detail.translationEnabled
    val titleTranslation = detail.titleTranslation
    val displayBlocks = detail.displayBlocks
    val blogImageUrls = remember(displayBlocks) {
        displayBlocks
            .filterIsInstance<DisplayBlock.Image>()
            .map(DisplayBlock.Image::url)
            .filter(String::isNotBlank)
            .distinct()
    }
    val openImage: (String) -> Unit = { url ->
        if (MediaDownloader.isNotFound(context, url)) {
            Toast.makeText(context, "官网未保存此照片 (404)", Toast.LENGTH_SHORT).show()
        } else {
            context.startActivity(
                MediaViewerActivity.imageIntent(
                    context = context,
                    url = url,
                    title = blog.title,
                    ownerName = blog.memberName,
                    imageId = "blog-${blog.id}",
                    urls = blogImageUrls,
                    transitionKey = "blogimg:$url",
                ),
            )
        }
    }

    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    AutoClearSelectionOnExit(isActive = isActive)

    LazyColumn(
        contentPadding = PaddingValues(bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .testTag(UiTestTags.BLOG_DETAIL)
            .fillMaxSize()
            .statusBarsPadding()
            .clearSelectionOnTap(focusManager, textToolbar),
    ) {
        item(key = "blog-detail-toolbar") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                com.nogirelay.app.ui.glass.GlassBackButton(
                    onClick = {
                        focusManager.clearFocus()
                        textToolbar.hide()
                        onBack()
                    },
                    contentDescription = "返回博客列表",
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "博客详情",
                    style = GlassType.Title3,
                    fontWeight = FontWeight.Bold,
                    color = GlassColors.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item(key = "blog-detail-heading") {
            GlassPanel(
                shape = GlassShapes.Card,
                staticMaterial = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemoteImage(
                            url = blog.memberAvatarUrl,
                            contentDescription = blog.memberName,
                            loadCachedImmediately = true,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                blog.memberName,
                                fontWeight = FontWeight.Bold,
                                color = GlassColors.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatBlogDate(blog.publishedAt),
                                style = GlassType.Footnote,
                                color = GlassColors.InkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (translationEnabled && blog.bodyHtml.isNotBlank()) {
                            GlassCircleButton(
                                onClick = { onRetranslate(blog.id) },
                                contentDescription = "重新翻译",
                                size = 40.dp,
                            ) {
                                AiTranslateIcon(
                                    size = 20.dp,
                                    tint = GlassColors.Accent,
                                    contentDescription = null,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        GlassCircleButton(
                            onClick = {
                                context.startActivity(BlogImageDownloadActivity.intent(context, blog.id))
                            },
                            contentDescription = "选择下载博客图片",
                            size = 40.dp,
                        ) {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = null,
                                tint = GlassColors.Accent,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        blog.title,
                        style = GlassType.Title1,
                        color = GlassColors.Ink,
                    )
                    AnimatedContent(
                        targetState = titleTranslation?.takeIf(String::isNotBlank),
                        transitionSpec = {
                            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                        },
                        label = "blog-title-translation",
                    ) { translatedTitle ->
                        translatedTitle?.let {
                            Text(
                                it,
                                color = GlassColors.AccentInk,
                                fontWeight = FontWeight.Bold,
                                style = GlassType.Title3,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        items(displayBlocks) { block ->
            when (block) {
                is DisplayBlock.Image -> if (block.url.isNotBlank()) {
                    RemoteImage(
                        url = block.url,
                        contentDescription = blog.title,
                        contentScale = ContentScale.Fit,
                        preserveAspectRatio = true,
                        loadCachedImmediately = true,
                        crossfadeDurationMillis = 220,
                        placeholderAspectRatio = 1f,
                        animateAspectRatioChanges = true,
                        placeholderColor = Color(0x228E93A6),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(GlassShapes.CardSmall)
                            .glassMediaSource(
                                key = "blogimg:${block.url}",
                                url = block.url,
                                crop = false,
                                cornerRadiusPx = with(LocalDensity.current) { 20.dp.toPx() },
                            )
                            .clickable { openImage(block.url) },
                    )
                }
                is DisplayBlock.Paragraphs -> GlassPanel(
                    shape = GlassShapes.Card,
                    staticMaterial = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            block.paragraphs.forEach { paragraph ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        paragraph.original,
                                        style = GlassType.Article,
                                        color = GlassColors.Ink,
                                    )
                                    AnimatedContent(
                                        targetState = paragraph.translation?.takeIf(String::isNotBlank),
                                        transitionSpec = {
                                            fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                        },
                                        label = "blog-paragraph-translation",
                                    ) { translatedParagraph ->
                                        translatedParagraph?.let {
                                            Text(
                                                it,
                                                color = GlassColors.AccentInk,
                                                style = GlassType.Callout,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


private sealed interface DisplayBlock {
    data class Paragraph(val original: String, val translation: String?)
    data class Paragraphs(val paragraphs: List<Paragraph>) : DisplayBlock
    data class Image(val url: String) : DisplayBlock
}

private fun displayBlocks(blocks: List<BlogContentBlock>, translations: List<String>): List<DisplayBlock> {
    var translationIndex = 0
    return buildList {
        val paragraphs = mutableListOf<DisplayBlock.Paragraph>()
        fun flushParagraphs() {
            if (paragraphs.isNotEmpty()) {
                add(DisplayBlock.Paragraphs(paragraphs.toList()))
                paragraphs.clear()
            }
        }
        blocks.forEach { block ->
            when (block) {
                is BlogContentBlock.Image -> {
                    flushParagraphs()
                    add(DisplayBlock.Image(block.url))
                }
                is BlogContentBlock.Text -> BlogContentParser.paragraphs(block.value).forEach { paragraph ->
                    paragraphs.add(DisplayBlock.Paragraph(paragraph, translations.getOrNull(translationIndex)))
                    translationIndex += 1

                    if (paragraphs.size == 8) flushParagraphs()
                }
            }
        }
        flushParagraphs()
    }
}

internal data class BlogSearchPreview(val label: String, val text: String)

/** Why the list is empty, naming the filters actually in effect. */
private fun blogEmptyMessage(
    totalCount: Int,
    searching: Boolean,
    memberFiltered: Boolean,
    timeFiltered: Boolean,
): String = when {
    totalCount == 0 -> "正在等待博客同步"
    searching -> "没有找到相关博客"
    memberFiltered && timeFiltered -> "所选成员在这段时间内没有博客"
    timeFiltered -> "这段时间内没有博客"
    memberFiltered -> "所选成员还没有博客"
    else -> "暂无博客"
}

object BlogPrewarmer {
    @Volatile
    internal var cachedSnapshot: BlogListSnapshot? = null
    @Volatile
    internal var cachedMembers: List<BlogMember>? = null
}

internal fun translatedBlogText(serialized: String?): String {
    if (serialized.isNullOrBlank()) return ""
    return runCatching {
        val array = org.json.JSONArray(serialized)
        (0 until array.length())
            .mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
            .joinToString("\n")
    }.getOrDefault("")
}

private fun formatBlogDate(value: String): String = value
    .replace('T', ' ')
    .removeSuffix("+09:00")


