package com.nogirelay.app.blog

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
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
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.performance.isRelayUiStarted
import com.nogirelay.app.performance.rememberSearchQuery
import com.nogirelay.app.translation.BlogTranslationLayout
import com.nogirelay.app.translation.BlogTranslationManager
import com.nogirelay.app.ui.AiTranslateIcon
import com.nogirelay.app.ui.AutoClearSelectionOnExit
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleDark
import com.nogirelay.app.ui.BrandPurpleLight
import com.nogirelay.app.ui.LocalRelayMirrorStyle
import com.nogirelay.app.ui.MediaViewerActivity
import com.nogirelay.app.ui.RelayCardContentInset
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RelayDialogButton
import com.nogirelay.app.ui.RelayDialogCard
import com.nogirelay.app.ui.RelayDialogIconButton
import com.nogirelay.app.ui.RelayGlassBackdrop
import com.nogirelay.app.ui.RelayHomeCardShape
import com.nogirelay.app.ui.RelayLightBackdrop
import com.nogirelay.app.ui.RelayMirrorGlassBackground
import com.nogirelay.app.ui.RelayMirrorGlassButton
import com.nogirelay.app.ui.RelayMirrorGlassCard
import com.nogirelay.app.ui.RelayMirrorGlassIconButton
import com.nogirelay.app.ui.RelayModalBottomSheet
import com.nogirelay.app.ui.RelayNavigationSelectionShape
import com.nogirelay.app.ui.RelaySearchField
import com.nogirelay.app.ui.RelaySegmentedTabs
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.RemoteImage
import com.nogirelay.app.ui.SearchHighlightText
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterSection
import com.nogirelay.app.ui.clearSelectionOnTap
import com.nogirelay.app.ui.hasInvertedRange
import com.nogirelay.app.ui.highlightMatches
import com.nogirelay.app.ui.relaySheetBackdrop
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.searchSnippets
import com.nogirelay.app.ui.transfer.DataTransferDrawer
import com.nogirelay.app.ui.transfer.MemberPickerGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BLOG_PAGE_SIZE = 20

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    val context = androidx.compose.ui.platform.LocalContext.current
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

    // 所有列表工作都在 databaseRead 上执行：在桌面 CPU 上，仅一次搜索计数就测到约 70ms，因为
    // 它要扫描每一行的 body_html（总计 45MB），所以在组合期间执行会阻塞帧，
    // 而当时用户正在滚动。页面及其摘录作为同一个状态产出，这样卡片
    // 一次就以最终高度渲染，而不是在第二轮中逐渐撑开。
    var pageData by remember { mutableStateOf(BlogPrewarmer.cachedInitialData ?: BlogPageData()) }
    LaunchedEffect(versions.blogs, listActive, translationEnabled, selectedMemberIds, effectiveQuery, oldestFirst, timeFilter, currentPage) {
        if (!listActive) return@LaunchedEffect
        val query = effectiveQuery
        val memberIds = selectedMemberIds
        val requestedPage = currentPage
        val oldest = oldestFirst
        val bounds = timeFilter
        pageData = readDatabase { cancellation ->
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
                        // 先原文正文，再其译文：一个词项可能匹配其中任意一个，
                        // 当两者都包含它时两段摘录都会显示。标签跟随来源，
                        // 因此仅命中译文的匹配永远不会被标为原文。
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
    }
    val matchingCount = pageData.matchingCount
    val totalCount = pageData.totalCount
    val totalPages = pageData.totalPages
    val page = pageData.page
    val blogs = pageData.posts
    val bodyPreviews = pageData.previews

    LaunchedEffect(selectedBlogId, pageData.loaded, blogs) {
        if (selectedBlogId == null) {
            val targetId = pendingScrollBlogId ?: return@LaunchedEffect
            val index = blogs.indexOfFirst { it.id == targetId }
            if (index >= 0) {
                // Header and search field are the first two list items.
                blogListState.animateScrollToItem(index + 2)
                pendingScrollBlogId = null
            }
        }
    }

    // 在后台预热当前页的封面（上限为不超过页大小，已缓存的文件会被
    // 跳过），这样滚动时从磁盘解码，而不必等待首次下载。
    LaunchedEffect(blogs, listActive) {
        if (!listActive) return@LaunchedEffect
        BlogMediaDownloader.prefetchImages(
            context,
            blogs.mapNotNull { it.imageUrl?.takeIf(::isRealBlogImageUrl) },
        )
    }

    fun goToPage(targetPage: Int) {
        val safePage = targetPage.coerceIn(0, totalPages - 1)
        currentPage = safePage
        pageInput = (safePage + 1).toString()
    }

    LaunchedEffect(selectedMemberIds, oldestFirst, effectiveQuery, timeFilter) {
        currentPage = 0
        pageInput = "1"
    }
    LaunchedEffect(page, totalPages, pageData.loaded) {
        if (!pageData.loaded) return@LaunchedEffect
        if (currentPage != page) currentPage = page
        pageInput = (page + 1).toString()
    }
    var previousPage by remember { mutableIntStateOf(page) }
    LaunchedEffect(page) {
        if (page != previousPage) {
            previousPage = page
            blogListState.scrollToItem(0)
        }
    }

    val currentBlogSignature = remember(blogs, page, matchingCount) {
        if (blogs.isEmpty()) {
            "empty:$matchingCount:$searchQuery"
        } else {
            "$page:$matchingCount:" + blogs.joinToString(",") { it.id }
        }
    }
    var previousBlogSignature by remember { mutableStateOf<String?>(null) }
    var filterAnimKey by remember { mutableIntStateOf(0) }

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
            previousBlogSignature = null
            blogListState.scrollToItem(0)
        }
    }

    LaunchedEffect(currentBlogSignature, pageData.loaded) {
        if (!pageData.loaded) return@LaunchedEffect
        if (previousBlogSignature == null) {
            previousBlogSignature = currentBlogSignature
        } else if (previousBlogSignature != currentBlogSignature) {
            previousBlogSignature = currentBlogSignature
            filterAnimKey++
        }
    }

    val density = LocalDensity.current
    val springOffset = remember { Animatable(0f) }

    LaunchedEffect(filterAnimKey) {
        if (filterAnimKey > 0) {
            blogListState.scrollToItem(0)
            springOffset.snapTo(28f)
            springOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
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
        LocalRelayMirrorStyle provides true,
        com.nogirelay.app.performance.LocalRelayPageWorkPaused provides sheetBackdrop.isAttached,
    ) {
        // Cards and controls only sample this empty page backdrop. The app's outer source
        // continues to capture the completed blog page for the floating navigation.
        RelayGlassBackdrop(
            background = RelayLightBackdrop,
            modifier = Modifier.fillMaxSize().relaySheetBackdrop(sheetBackdrop),
        ) {
            if (showMemberDialog) {
                BlogFilterDialog(
                    members = members,
                    backdropState = sheetBackdrop,
                    selectedIds = selectedMemberIds ?: members.mapTo(linkedSetOf(), BlogMember::id),
                    timeFilter = timeFilter,
                    onDismiss = { showMemberDialog = false },
                    onConfirm = { selectedIds, selectedTimeFilter ->
                        val allIds = members.mapTo(linkedSetOf(), BlogMember::id)
                        selectedMemberIds = selectedIds.takeUnless { it == allIds }
                        timeFilter = selectedTimeFilter
                        currentPage = 0
                        pageInput = "1"
                        showMemberDialog = false
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

            Crossfade(
                targetState = selected,
                animationSpec = tween(durationMillis = 220),
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
                    return@Crossfade
                }

                LazyColumn(
                    state = blogListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "blog-header") {
                        Column(Modifier.fillMaxWidth()) {
                            // Keep the status-bar inset in the scroll content so the toolbar follows it offscreen.
                            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                            TopAppBar(
                                title = {
                                    Text(
                                        text = "博客",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                actions = {
                                    RelayMirrorGlassIconButton(
                                        onClick = { showDataDrawer = true },
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = "数据管理",
                                    )
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    scrolledContainerColor = Color.Transparent,
                                ),
                                windowInsets = WindowInsets(0, 0, 0, 0),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            ) {
                                BlogSortSwitcher(
                                    oldestFirst = oldestFirst,
                                    onOldestFirstChanged = { oldestFirst = it },
                                    modifier = Modifier.weight(1f),
                                )
                                val isMemberFilterActive = selectedMemberIds != null && (members.isEmpty() || selectedMemberIds?.size != members.size)
                                val isFilterActive = timeFilter.isActive || isMemberFilterActive
                                RelayMirrorGlassIconButton(
                                    onClick = { showMemberDialog = true },
                                    imageVector = Icons.Rounded.FilterList,
                                    contentDescription = "筛选成员和时间",
                                    active = isFilterActive,
                                )
                            }
                        }
                    }
                    item(key = "blog-search") {
                        RelaySearchField(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                currentPage = 0
                                pageInput = "1"
                            },
                            placeholder = "搜索博客标题、正文或日期",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    // 首次加载完成前不渲染任何内容，因此空状态不会闪现。
                    if (pageData.loaded && blogs.isEmpty()) {
                        item(key = "blog-empty") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .graphicsLayer { translationY = with(density) { springOffset.value.dp.toPx() } }
                                    .animateItem()
                                    .fillMaxWidth()
                                    .padding(vertical = 64.dp),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    when {
                                        totalCount == 0 -> "正在等待博客同步"
                                        searchQuery.isNotBlank() -> "没有找到相关博客"
                                        else -> "当前成员筛选下没有博客"
                                    },
                                )
                            }
                        }
                    } else if (blogs.isNotEmpty()) {
                        items(blogs, key = BlogSummary::id) { blog ->
                            BlogSummaryCard(
                                modifier = Modifier
                                    .graphicsLayer { translationY = with(density) { springOffset.value.dp.toPx() } }
                                    .animateItem(),
                                blog = blog,
                                searchQuery = effectiveQuery,
                                bodyPreviews = bodyPreviews[blog.id].orEmpty(),
                                translationEnabled = translationEnabled,
                                onClick = {
                                    selectedBlogId = blog.id
                                    pendingScrollBlogId = blog.id
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
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .graphicsLayer { translationY = with(density) { springOffset.value.dp.toPx() } }
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            ) {
                                Text(
                                    text = "共 $matchingCount 篇博客",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    RelayMirrorGlassButton(
                                        onClick = { goToPage(page - 1) },
                                        enabled = page > 0,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("上一页", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }

                                    RelayMirrorGlassButton(
                                        onClick = {
                                            pageInput = (page + 1).toString()
                                            showPageDialog = true
                                        },
                                        enabled = matchingCount > 0,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1.2f),
                                    ) {
                                        Text(
                                            "${page + 1} / $totalPages",
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码", modifier = Modifier.size(18.dp))
                                    }

                                    RelayMirrorGlassButton(
                                        onClick = { goToPage(page + 1) },
                                        enabled = page < totalPages - 1,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("下一页", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp) }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(128.dp)) }
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
private fun BlogSortSwitcher(
    oldestFirst: Boolean,
    onOldestFirstChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    RelaySegmentedTabs(
        labels = listOf("最新", "最早"),
        selectedIndex = if (oldestFirst) 1 else 0,
        onSelected = { onOldestFirstChanged(it == 1) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlogFilterDialog(
    members: List<BlogMember>,
    backdropState: RelaySheetBackdropState,
    selectedIds: Set<String>,
    timeFilter: TimeFilter,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>, TimeFilter) -> Unit,
) {
    var draft by remember(members, selectedIds) { mutableStateOf(selectedIds.toSet()) }
    var draftTimeFilter by remember(timeFilter) { mutableStateOf(timeFilter) }
    val allMemberIds = remember(members) { members.mapTo(linkedSetOf(), BlogMember::id) }
    RelayModalBottomSheet(
        onDismissRequest = onDismiss,
        backdropState = backdropState,
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "博客筛选",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RelayDialogIconButton(
                    onClick = { dismiss(onDismiss) },
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭博客筛选",
                )
            }
            RelayDialogCard {
                Column(Modifier.fillMaxWidth().padding(RelayCardContentInset)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "成员 · 已选 ${draft.size}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        RelayDialogIconButton(
                            onClick = { draft = allMemberIds },
                            imageVector = Icons.Rounded.DoneAll,
                            contentDescription = "全选",
                            enabled = members.isNotEmpty(),
                        )
                        RelayDialogIconButton(
                            onClick = { draft = emptySet() },
                            imageVector = Icons.Rounded.ClearAll,
                            contentDescription = "全不选",
                            enabled = draft.isNotEmpty(),
                        )
                    }
                    if (members.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "正在加载成员...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp,
                            )
                        }
                    } else {
                        MemberPickerGrid(
                            members = members,
                            selectedIds = draft,
                            onSelectedChange = { draft = it },
                            modifier = Modifier.heightIn(max = 340.dp),
                        )
                    }
                }
            }
            RelayDialogCard {
                TimeFilterSection(
                    filter = draftTimeFilter,
                    onFilterChange = { draftTimeFilter = it },
                    modifier = Modifier.fillMaxWidth().padding(RelayCardContentInset),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RelayDialogButton(
                    onClick = { dismiss(onDismiss) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RelayDialogButton(
                    onClick = { dismiss { onConfirm(draft, draftTimeFilter) } },
                    enabled = !draftTimeFilter.hasInvertedRange(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("确定", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RelayHomeCardShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text("跳转页码", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请输入 1 ~ ${totalPages} 之间的页码", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth()) {
                    RelayMirrorGlassBackground(
                        shape = RelayControlShape,
                        modifier = Modifier.matchParentSize(),
                        opaqueBackground = MaterialTheme.colorScheme.surface,
                        emphasizeEdges = true,
                    )
                    OutlinedTextField(
                        value = pageInput,
                        onValueChange = { onInputChange(it.filter(Char::isDigit).take(6)) },
                        singleLine = true,
                        label = { Text("页码") },
                        shape = RelayControlShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandPurple,
                            focusedLabelColor = BrandPurple,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { requestedPage?.takeIf { canJump }?.let(onConfirm) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            RelayDialogButton(
                onClick = { requestedPage?.let(onConfirm) },
                enabled = canJump,
            ) { Text("跳转", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        dismissButton = {
            RelayDialogButton(onClick = onDismiss) { Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    )
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
    RelayMirrorGlassCard(
        onClick = onClick,
        shape = RelayHomeCardShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(RelayCardContentInset)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(
                    url = blog.memberAvatarUrl,
                    contentDescription = blog.memberName,
                    // 以头像尺寸在 IO 上解码：搜索结果会带来头像
                    // 不在内存缓存中的成员，而每张卡片都在主线程同步解码
                    // 正是导致滚动搜索结果卡顿、而最新帖子不卡的原因。
                    loadCachedImmediately = false,
                    maxDecodeDimension = 256,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    NameWithUnreadTag(
                        name = highlightMatches(blog.memberName, searchQuery, highlightBackground, highlightText),
                        isUnread = blog.isUnread,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (translationEnabled) {
                    RelayMirrorGlassIconButton(
                        onClick = onRetranslate,
                        contentDescription = "重新翻译",
                    ) {
                        AiTranslateIcon(
                            size = 22.dp,
                            contentDescription = null,
                        )
                    }
                }
                RelayMirrorGlassIconButton(
                    onClick = onDownload,
                    imageVector = Icons.Rounded.Download,
                    contentDescription = "选择下载博客图片",
                )
            }
            Spacer(Modifier.height(10.dp))
            SearchHighlightText(
                text = blog.title,
                query = searchQuery,
                highlightBackground = highlightBackground,
                highlightTextColor = highlightText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            blog.translatedTitle?.takeIf { translationEnabled }?.let {
                SearchHighlightText(
                    text = it,
                    query = searchQuery,
                    highlightBackground = highlightBackground,
                    highlightTextColor = highlightText,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = BrandPurpleDark,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 匹配词项周围的摘录：显示原文正文中的所有出现位置，
            // 以及当译文也匹配时其中的所有出现位置。
            val visiblePreviews = if (isPreviewsExpanded || bodyPreviews.size <= 3) {
                bodyPreviews
            } else {
                bodyPreviews.take(3)
            }

            visiblePreviews.forEach { preview ->
                // 基线对齐让更小的 原文/译文 标签与摘录保持在同一行。
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        preview.label,
                        color = BrandPurple,
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                TextButton(
                    onClick = { isPreviewsExpanded = !isPreviewsExpanded },
                    shape = RelayNavigationSelectionShape,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        if (isPreviewsExpanded) "收起匹配项" else "展开剩余 ${bodyPreviews.size - 3} 处匹配",
                        color = BrandPurple,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Rounded.ArrowDropDown,
                        contentDescription = null,
                        tint = BrandPurple,
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
                        .heightIn(min = 180.dp)
                        .clip(RelayControlShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                ) {
                    RemoteImage(
                        url = it,
                        contentDescription = blog.title,
                        contentScale = ContentScale.Fit,
                        preserveAspectRatio = true,
                        // 在主线程之外解码，以屏幕宽度而非完整分辨率：
                        // 博客封面可能是高达 3700x2800 的照片。
                        loadCachedImmediately = false,
                        placeholderColor = Color.Transparent,
                        maxDecodeDimension = 1440,
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
    val context = androidx.compose.ui.platform.LocalContext.current
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
        value = withContext(AppGraph.dispatchers.databaseRead) {
            val blog = AppGraph.database.findBlog(blogId) ?: return@withContext null
            val settings = AppGraph.settings.read()
            val parsed = BlogTextCache.parse(blog.id, blog.bodyHtml)
            val contentBlocks = parsed.blocks
            val bodyText = parsed.plainText
            val source = listOf(blog.title.trim(), bodyText).filter(String::isNotBlank).joinToString("\n\n\n")
            val layout = BlogTranslationLayout.from(source)
            val translations = if (settings.translationEnabled) layout.decode(blog.translation) else emptyList()
            val titleTranslation = if (blog.title.isNotBlank()) translations.firstOrNull() else null
            val bodyTranslations = if (blog.title.isNotBlank()) translations.drop(1) else translations
            val displayList = displayBlocks(contentBlocks, bodyTranslations)
            ParsedBlogDetail(
                blog = blog,
                translationEnabled = settings.translationEnabled,
                titleTranslation = titleTranslation,
                displayBlocks = displayList,
            )
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
        // Once committed, deliver the badge invalidation even if the page just closed.
        withContext(kotlinx.coroutines.NonCancellable) {
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在同步博客…") }
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
            // 该详情页没有 TopAppBar，自行避让状态栏。
            .statusBarsPadding()
            .clearSelectionOnTap(focusManager, textToolbar)
    ) {
        item(key = "blog-detail-toolbar") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                RelayMirrorGlassIconButton(
                    onClick = {
                        focusManager.clearFocus()
                        textToolbar.hide()
                        onBack()
                    },
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回博客列表",
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "博客详情",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item(key = "blog-detail-heading") {
            RelayMirrorGlassCard(
                shape = RelayHomeCardShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(RelayCardContentInset)) {
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
                            Text(blog.memberName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatBlogDate(blog.publishedAt),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (translationEnabled && blog.bodyHtml.isNotBlank()) {
                            RelayMirrorGlassIconButton(
                                onClick = {
                                    retranslateScope.launch(Dispatchers.IO) {
                                        AppGraph.database.markBlogForRetranslation(blog.id)
                                        AppGraph.notifyDataChanged(DataChange.BLOG_ROWS, setOf(blog.id))
                                        BlogTranslationManager.enqueue(context, blog.id, force = true)
                                    }
                                },
                                contentDescription = "重新翻译",
                            ) {
                                AiTranslateIcon(
                                    size = 22.dp,
                                    contentDescription = null,
                                )
                            }
                        }
                        RelayMirrorGlassIconButton(
                            onClick = {
                                context.startActivity(BlogImageDownloadActivity.intent(context, blog.id))
                            },
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "选择下载博客图片",
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(blog.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    titleTranslation?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            color = BrandPurpleDark,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (blog.bodyHtml.isBlank()) {
                        Text("正在从乃木坂46官网同步正文…", modifier = Modifier.padding(top = 18.dp))
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
                        placeholderColor = BrandPurpleLight.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clip(RelayControlShape)
                            .clickable { openImage(block.url) },
                    )
                }
                is DisplayBlock.Paragraphs -> RelayMirrorGlassCard(
                    shape = RelayHomeCardShape,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(RelayCardContentInset),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            block.paragraphs.forEach { paragraph ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        paragraph.original,
                                        lineHeight = 24.sp,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    paragraph.translation?.takeIf(String::isNotBlank)?.let {
                                        Text(
                                            it,
                                            color = BrandPurpleDark,
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
                    // Keep long, text-only articles lazy while sharing one glass surface
                    // across nearby paragraphs and their corresponding translations.
                    if (paragraphs.size == 8) flushParagraphs()
                }
            }
        }
        flushParagraphs()
    }
}

/** 一条搜索摘要及其来源字段，标签与正文始终对应。 */
internal data class BlogSearchPreview(val label: String, val text: String)

/**
 * 已加载的一页 BLOG 列表：总数、摘要及其搜索片段。三者在主线程外一次性产出，
 * 卡片一次就以最终内容完成组合。
 */
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

    fun prewarm() {
        if (cachedInitialData != null && cachedMembers != null) return
        val members = AppGraph.database.blogMembers()
        val total = AppGraph.database.countBlogs()
        val posts = AppGraph.database.blogSummaries(limit = BLOG_PAGE_SIZE, offset = 0)
        cachedMembers = members
        cachedInitialData = BlogPageData(
            totalCount = total,
            matchingCount = total,
            totalPages = ((total + BLOG_PAGE_SIZE - 1) / BLOG_PAGE_SIZE).coerceAtLeast(1),
            page = 0,
            posts = posts,
            previews = emptyMap(),
            loaded = true,
        )
    }
}

/** 已存 BLOG 译文的纯文本（段落 JSON 数组），用于搜索摘要。 */
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
