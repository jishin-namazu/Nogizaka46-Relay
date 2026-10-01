package com.nogirelay.app.blog

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nogirelay.app.NameWithUnreadTag
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.BlogPost
import com.nogirelay.app.data.BlogReadTracker
import com.nogirelay.app.data.BlogSummary
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.isRealBlogImageUrl
import com.nogirelay.app.data.readDatabase
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
import com.nogirelay.app.ui.highlightMatches
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.clearSelectionOnTap
import com.nogirelay.app.ui.MediaViewerActivity
import com.nogirelay.app.ui.glass.GlassBottomSheet
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassCircleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassDialog
import com.nogirelay.app.ui.glass.GlassDialogText
import com.nogirelay.app.ui.glass.GlassDialogTitle
import com.nogirelay.app.ui.glass.GlassHeader
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassSearchField
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassTextField
import com.nogirelay.app.ui.glass.GlassTone
import com.nogirelay.app.ui.glass.glassMediaSource
import com.nogirelay.app.ui.searchSnippets
import com.nogirelay.app.translation.BlogTranslationLayout
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.ui.hasInvertedRange
import com.nogirelay.app.ui.transfer.DataTransferDrawer
import com.nogirelay.app.ui.transfer.MemberPickerCard
import com.nogirelay.app.ui.transfer.memberGroups
import com.nogirelay.app.ui.transfer.preloadMemberAvatars
import com.nogirelay.app.ui.primeCachedImageAspectRatios
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.ui.withoutTextPresentationSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BLOG_PAGE_SIZE = 10

/**
 * Blog list + detail, rebuilt as liquid glass. All data behavior is
 * unchanged; only the presentation moved to the shared glass system.
 */
@Composable
fun BlogScreen(
    versions: DataVersions,
    initialBlogId: String?,
    onInitialBlogHandled: (String) -> Unit,
    onUnreadChanged: () -> Unit,
    isActive: Boolean = true,
) {
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val workActive = isActive && isRelayUiStarted() && !sheetBackdrop.isAttached
    val context = LocalContext.current
    val retranslateScope = rememberCoroutineScope()
    var selectedBlogId by remember { mutableStateOf<String?>(null) }
    val listActive = workActive && selectedBlogId == null
    var selectedMemberIds by remember { mutableStateOf<Set<String>?>(null) }
    var oldestFirst by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val effectiveQuery = rememberSearchQuery(searchQuery, workActive)
    var timeFilter by remember { mutableStateOf(TimeFilter()) }
    var showMemberDialog by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageInput by remember { mutableStateOf("1") }
    var showPageDialog by remember { mutableStateOf(false) }
    var showDataDrawer by remember { mutableStateOf(false) }
    val blogListState = rememberLazyListState()
    var translationEnabled by remember { mutableStateOf(AppGraph.settings.read().translationEnabled) }
    var members by remember { mutableStateOf(BlogPrewarmer.cachedMembers ?: emptyList()) }

    LaunchedEffect(versions.blogStructure, versions.settings, listActive) {
        if (!listActive) return@LaunchedEffect
        val loaded = withContext(AppGraph.dispatchers.databaseRead) {
            AppGraph.settings.read().translationEnabled to AppGraph.database.blogMembers()
        }
        translationEnabled = loaded.first
        members = loaded.second
    }

    var pendingScrollBlogId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(initialBlogId, workActive) {
        if (!workActive) return@LaunchedEffect
        initialBlogId?.let { id ->
            searchQuery = ""
            selectedMemberIds = null
            oldestFirst = false
            timeFilter = TimeFilter()
            withContext(AppGraph.dispatchers.databaseRead) {
                val rank = AppGraph.database.blogRank(id)
                if (rank != null) {
                    val targetPage = rank / BLOG_PAGE_SIZE
                    withContext(Dispatchers.Main) {
                        currentPage = targetPage
                        pageInput = (targetPage + 1).toString()
                    }
                }
            }
            selectedBlogId = id
            pendingScrollBlogId = id
            onInitialBlogHandled(id)
        }
    }

    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    AutoClearSelectionOnExit(isActive = isActive)

    val selected = selectedBlogId
    BackHandler(enabled = isActive && (selected != null || searchQuery.isNotEmpty())) {
        focusManager.clearFocus()
        textToolbar.hide()
        if (selected != null) {
            selectedBlogId = null
        } else {
            searchQuery = ""
            currentPage = 0
            pageInput = "1"
        }
    }
    LaunchedEffect(members) {
        selectedMemberIds?.let { selectedIds ->
            val availableIds = members.mapTo(mutableSetOf(), BlogMember::id)
            val updated = selectedIds.intersect(availableIds)
            if (updated != selectedIds) {
                selectedMemberIds = updated.takeUnless { it.size == availableIds.size }
                currentPage = 0
                pageInput = "1"
            }
        }
    }

    var pageData by remember { mutableStateOf(BlogPrewarmer.cachedInitialData ?: BlogPageData()) }
    val pageRequest = BlogListRequest(selectedMemberIds, effectiveQuery, oldestFirst, timeFilter, currentPage)
    var appliedRequest by remember { mutableStateOf<BlogListRequest?>(null) }
    var pageGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(versions.blogs, listActive, translationEnabled, pageRequest) {
        if (!listActive) return@LaunchedEffect
        val query = effectiveQuery
        val memberIds = selectedMemberIds
        val requestedPage = currentPage
        val oldest = oldestFirst
        val bounds = timeFilter
        val loadedPage = readDatabase { cancellation ->
            val total = AppGraph.database.countBlogs(cancellationSignal = cancellation)
            val matching = if (memberIds == null && query.isBlank() && !bounds.isActive) total else AppGraph.database.countBlogs(
                memberIds = memberIds,
                searchQuery = query,
                startMillis = bounds.startMillis,
                endMillisExclusive = bounds.endMillisExclusive,
                cancellationSignal = cancellation,
            )
            val totalPages = ((matching + BLOG_PAGE_SIZE - 1) / BLOG_PAGE_SIZE).coerceAtLeast(1)
            val page = requestedPage.coerceIn(0, totalPages - 1)
            val posts = AppGraph.database.blogSummaries(
                memberIds = memberIds,
                searchQuery = query,
                oldestFirst = oldest,
                startMillis = bounds.startMillis,
                endMillisExclusive = bounds.endMillisExclusive,
                limit = BLOG_PAGE_SIZE,
                offset = page * BLOG_PAGE_SIZE,
                cancellationSignal = cancellation,
            )
            val previews = if (query.isBlank() || posts.isEmpty()) {
                emptyMap()
            } else {
                AppGraph.database.blogSearchSources(posts.map(BlogSummary::id))
                    .mapNotNull { source ->
                        cancellation.throwIfCanceled()

                        val excerpts = buildList {
                            val originalSnippets = searchSnippets(
                                BlogTextCache.parse(source.id, source.bodyHtml).plainText,
                                query,
                            )
                            originalSnippets.forEachIndexed { index, snippet ->
                                val label = if (originalSnippets.size > 1) "原文 ${index + 1}" else "原文"
                                add(BlogSearchPreview(label, snippet))
                            }
                            if (translationEnabled) {
                                val translatedSnippets = searchSnippets(translatedBlogText(source.translation), query)
                                translatedSnippets.forEachIndexed { index, snippet ->
                                    val label = if (translatedSnippets.size > 1) "译文 ${index + 1}" else "译文"
                                    add(BlogSearchPreview(label, snippet))
                                }
                            }
                        }
                        excerpts.takeIf { it.isNotEmpty() }?.let { source.id to it }
                    }
                    .toMap()
            }
            val result = BlogPageData(total, matching, totalPages, page, posts, previews, loaded = true)
            if (query.isBlank() && memberIds == null && !oldest && !bounds.isActive && requestedPage == 0) {
                BlogPrewarmer.cachedInitialData = result
            }
            result
        }
        primeCachedImageAspectRatios(context, loadedPage.posts.mapNotNull { it.imageUrl?.takeIf(::isRealBlogImageUrl) })
        val resolvedRequest = pageRequest.copy(page = loadedPage.page)
        if (appliedRequest != resolvedRequest) {
            // Treat a reordered page as new content even when it contains the
            // same IDs, so cards crossfade instead of racing across the list.
            if (appliedRequest != null) pageGeneration++
            // Keep visible list controls at the same position during sorting.
            // Pagination from further down the list still returns to the top.
            val firstIndex = blogListState.firstVisibleItemIndex
            if (firstIndex < 3) {
                blogListState.requestScrollToItem(firstIndex, blogListState.firstVisibleItemScrollOffset)
            } else {
                blogListState.requestScrollToItem(0)
            }
        }
        // Commit once. Lazy item animations retain outgoing cards while their
        // replacements fade in; the header/search/selector never fade to blank.
        appliedRequest = resolvedRequest
        currentPage = loadedPage.page
        pageInput = (loadedPage.page + 1).toString()
        pageData = loadedPage
    }
    val matchingCount = pageData.matchingCount
    val totalCount = pageData.totalCount
    val totalPages = pageData.totalPages
    val page = pageData.page
    val blogs = pageData.posts
    val bodyPreviews = pageData.previews
    val displayedGeneration = pageGeneration

    LaunchedEffect(selectedBlogId, pageData.loaded, blogs) {
        if (selectedBlogId == null) {
            val targetId = pendingScrollBlogId ?: return@LaunchedEffect
            val index = blogs.indexOfFirst { it.id == targetId }
            if (index >= 0) {
                blogListState.scrollToItem(index + 3)
                pendingScrollBlogId = null
            }
        }
    }

    LaunchedEffect(blogs, listActive) {
        if (!listActive || blogs.isEmpty()) return@LaunchedEffect
        val urls = blogs.mapNotNull { it.imageUrl?.takeIf(::isRealBlogImageUrl) }
        BlogMediaDownloader.preloadImages(context, urls, limit = urls.size)
    }

    fun goToPage(targetPage: Int) {
        val safePage = targetPage.coerceIn(0, totalPages - 1)
        if (safePage == currentPage || currentPage != page) return
        // Only the local rows gate navigation. Images load independently after
        // the page is visible, including when downloads are slow or unavailable.
        pendingScrollBlogId = null
        currentPage = safePage
        pageInput = (safePage + 1).toString()
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            selectedBlogId = null
            selectedMemberIds = null
            oldestFirst = false
            searchQuery = ""
            timeFilter = TimeFilter()
            currentPage = 0
            pageInput = "1"
            showMemberDialog = false
            showPageDialog = false
            showDataDrawer = false
            appliedRequest = null
            blogListState.scrollToItem(0)
        }
    }

    LaunchedEffect(showMemberDialog) {
        if (showMemberDialog && members.isEmpty()) {
            withContext(AppGraph.dispatchers.databaseRead) {
                val loaded = AppGraph.database.blogMembers()
                if (loaded.isNotEmpty()) {
                    members = loaded
                }
            }
        }
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
                    selectedIds = selectedMemberIds ?: members.mapTo(linkedSetOf(), BlogMember::id),
                    timeFilter = timeFilter,
                    onDismiss = { showMemberDialog = false },
                    onConfirm = { selectedIds, selectedTimeFilter ->
                        showMemberDialog = false
                        val allIds = members.mapTo(linkedSetOf(), BlogMember::id)
                        selectedMemberIds = selectedIds.takeUnless { it == allIds }
                        timeFilter = selectedTimeFilter
                        currentPage = 0
                        pageInput = "1"
                    },
                )
            }

            if (showPageDialog) {
                BlogPageDialog(
                    pageInput = pageInput,
                    totalPages = totalPages,
                    onInputChange = { pageInput = it },
                    onDismiss = { showPageDialog = false },
                    onConfirm = { requestedPage ->
                        goToPage(requestedPage - 1)
                        showPageDialog = false
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
                        dataVersion = maxOf(versions.blogContent, versions.settings),
                        onBack = { selectedBlogId = null },
                        onUnreadChanged = onUnreadChanged,
                    )
                    LaunchedEffect(selectedId, initialBlogId) {
                        if (selectedId == initialBlogId) onInitialBlogHandled(selectedId)
                    }
                    return@AnimatedContent
                }

                LazyColumn(
                    state = blogListState,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "blog-header") {
                        GlassHeader(
                            title = "博客",
                            actions = {
                                GlassIconButton(
                                    onClick = { showDataDrawer = true },
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = "数据管理",
                                )
                            },
                        )
                    }
                    item(key = "blog-controls") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        ) {
                            GlassSegmentedTabs(
                                labels = listOf("最新", "最早"),
                                selectedIndex = if (oldestFirst) 1 else 0,
                                onSelected = {
                                    // Reset the page together with the sort order so
                                    // there is no intermediate request for the old page.
                                    oldestFirst = it == 1
                                    currentPage = 0
                                    pageInput = "1"
                                    pendingScrollBlogId = null
                                },
                                modifier = Modifier.weight(1f),
                            )
                            val isMemberFilterActive = selectedMemberIds != null &&
                                (members.isEmpty() || selectedMemberIds?.size != members.size)
                            val isFilterActive = timeFilter.isActive || isMemberFilterActive
                            GlassIconButton(
                                onClick = { showMemberDialog = true },
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = "筛选成员和时间",
                                size = 44.dp,
                                tone = if (isFilterActive) GlassTone.Accent else GlassTone.Neutral,
                            )
                        }
                    }
                    item(key = "blog-search") {
                        GlassSearchField(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                currentPage = 0
                                pageInput = "1"
                            },
                            placeholder = "搜索博客标题、正文或日期",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    if (pageData.loaded && blogs.isEmpty()) {
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
                                    when {
                                        totalCount == 0 -> "正在等待博客同步"
                                        searchQuery.isNotBlank() -> "没有找到相关博客"
                                        else -> "当前成员筛选下没有博客"
                                    },
                                    color = GlassColors.InkSecondary,
                                )
                            }
                        }
                    } else if (blogs.isNotEmpty()) {
                        items(blogs, key = { "$displayedGeneration:${it.id}" }) { blog ->
                            BlogSummaryCard(
                                modifier = Modifier
                                    .animateItem(
                                        fadeInSpec = tween(300, easing = FastOutSlowInEasing),
                                        placementSpec = tween(300, easing = FastOutSlowInEasing),
                                        fadeOutSpec = tween(220, easing = FastOutSlowInEasing),
                                    )
                                    .padding(bottom = 10.dp),
                                blog = blog,
                                searchQuery = effectiveQuery,
                                bodyPreviews = bodyPreviews[blog.id].orEmpty(),
                                translationEnabled = translationEnabled,
                                onClick = {
                                    selectedBlogId = blog.id
                                    // The list state retains the exact item and pixel offset.
                                    // Only notification deep links need a new scroll target.
                                    pendingScrollBlogId = null
                                },
                                onDownload = {
                                    context.startActivity(BlogImageDownloadActivity.intent(context, blog.id))
                                },
                                onRetranslate = {
                                    retranslateScope.launch(Dispatchers.IO) {
                                        AppGraph.database.markBlogForRetranslation(blog.id)
                                        AppGraph.notifyDataChanged(DataChange.BLOG_ROWS, setOf(blog.id))
                                        BlogTranslationManager.enqueue(context, blog.id, force = true)
                                    }
                                },
                            )
                        }
                        item(key = "blog-pagination") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "共 $matchingCount 篇博客",
                                    color = GlassColors.InkTertiary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    GlassCapsuleButton(
                                        onClick = { goToPage(page - 1) },
                                        enabled = currentPage == page && page > 0,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("上一页", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium) }

                                    GlassCapsuleButton(
                                        onClick = {
                                            pageInput = (page + 1).toString()
                                            showPageDialog = true
                                        },
                                        enabled = currentPage == page && matchingCount > 0,
                                        tone = GlassTone.Accent,
                                        modifier = Modifier.weight(1.15f),
                                    ) {
                                        Text(
                                            "${page + 1} / $totalPages",
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 13.sp,
                                        )
                                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码", modifier = Modifier.size(18.dp))
                                    }

                                    GlassCapsuleButton(
                                        onClick = { goToPage(page + 1) },
                                        enabled = currentPage == page && page < totalPages - 1,
                                        modifier = Modifier.weight(1f),
                                    ) { Text("下一页", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
                                }
                            }
                        }
                        item(key = "blog-bottom-spacer") { Spacer(Modifier.height(128.dp)) }
                    }
                }
            }

            if (showDataDrawer) {
                DataTransferDrawer(
                    kind = ExportKind.BLOGS,
                    backdropState = sheetBackdrop,
                    onDismiss = { showDataDrawer = false },
                )
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
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, TimeFilter) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(members) {
        if (members.isNotEmpty()) preloadMemberAvatars(context, members)
    }
    var draft by remember(members, selectedIds) { mutableStateOf(selectedIds.toSet()) }
    var draftTimeFilter by remember(timeFilter) { mutableStateOf(timeFilter) }
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
                            "博客筛选",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlassColors.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        GlassIconButton(
                            onClick = { dismiss(onDismiss) },
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭博客筛选",
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
                            onClick = { dismiss { onConfirm(draft, draftTimeFilter) } },
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

@Composable
private fun BlogPageDialog(
    pageInput: String,
    totalPages: Int,
    onInputChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val requestedPage = pageInput.toIntOrNull()
    val canJump = requestedPage != null && requestedPage in 1..totalPages
    GlassDialog(onDismissRequest = onDismiss) {
        GlassDialogTitle("跳转到页码")
        Spacer(Modifier.height(6.dp))
        GlassDialogText("输入 1 到 $totalPages 之间的页码")
        Spacer(Modifier.height(14.dp))
        GlassTextField(
            value = pageInput,
            onValueChange = { onInputChange(it.filter(Char::isDigit).take(6)) },
            label = "页码",
            placeholder = "1 – $totalPages",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            GlassCapsuleButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            }
            GlassCapsuleButton(
                onClick = { if (canJump) onConfirm(requestedPage!!) },
                enabled = canJump,
                tone = GlassTone.Accent,
                modifier = Modifier.weight(1f),
            ) {
                Text("跳转", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
        modifier = modifier
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
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = GlassColors.Ink),
                        searchQuery = searchQuery,
                        highlightBackground = highlightBackground,
                    )
                    SearchHighlightText(
                        text = formatBlogDate(blog.publishedAt),
                        query = searchQuery,
                        highlightBackground = highlightBackground,
                        highlightTextColor = highlightText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = GlassColors.InkTertiary,
                        ),
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
                style = MaterialTheme.typography.titleMedium,
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
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = GlassColors.AccentInk,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp,
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
                        fontSize = 11.sp,
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
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = GlassColors.InkSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
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
                        fontSize = 12.sp,
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
    dataVersion: Long,
    onBack: () -> Unit,
    onUnreadChanged: () -> Unit,
) {
    val context = LocalContext.current
    val retranslateScope = rememberCoroutineScope()
    var localRefresh by remember { mutableIntStateOf(0) }

    val detailState by produceState<ParsedBlogDetail?>(
        initialValue = null,
        blogId,
        dataVersion,
        localRefresh,
        isActive,
    ) {
        if (!isActive) return@produceState
        val loaded = withContext(AppGraph.dispatchers.databaseRead) {
            val blog = AppGraph.database.findBlog(blogId) ?: return@withContext null
            blog to AppGraph.settings.read()
        }

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

        withContext(NonCancellable) {
            val updated = withContext(AppGraph.dispatchers.databaseWrite) {
                AppGraph.database.markBlogRead(blogId)
            }
            if (updated > 0) {
                localRefresh++
                onUnreadChanged()
            }
        }
    }
    LaunchedEffect(detailState?.blog?.bodyHtml, dataVersion, isActive) {
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
                    fontSize = 17.sp,
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
                                fontSize = 12.sp,
                                color = GlassColors.InkTertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (translationEnabled && blog.bodyHtml.isNotBlank()) {
                            GlassCircleButton(
                                onClick = {
                                    retranslateScope.launch(Dispatchers.IO) {
                                        AppGraph.database.markBlogForRetranslation(blog.id)
                                        AppGraph.notifyDataChanged(DataChange.BLOG_ROWS, setOf(blog.id))
                                        BlogTranslationManager.enqueue(context, blog.id, force = true)
                                    }
                                },
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
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
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
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
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
                                        lineHeight = 24.sp,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
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
                                                fontSize = 14.5.sp,
                                                lineHeight = 21.sp,
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

private data class BlogListRequest(
    val memberIds: Set<String>?,
    val query: String,
    val oldestFirst: Boolean,
    val timeFilter: TimeFilter,
    val page: Int,
)

internal data class BlogPageData(
    val totalCount: Int = 0,
    val matchingCount: Int = 0,
    val totalPages: Int = 1,
    val page: Int = 0,
    val posts: List<BlogSummary> = emptyList(),
    val previews: Map<String, List<BlogSearchPreview>> = emptyMap(),
    val loaded: Boolean = false,
)

object BlogPrewarmer {
    @Volatile
    internal var cachedInitialData: BlogPageData? = null
    @Volatile
    internal var cachedMembers: List<BlogMember>? = null
}

private fun translatedBlogText(serialized: String?): String {
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


