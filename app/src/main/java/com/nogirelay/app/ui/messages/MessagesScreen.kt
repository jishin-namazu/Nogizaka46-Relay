package com.nogirelay.app.ui.messages

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nogirelay.app.UnreadTag
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.AutoClearSelectionOnExit
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.BrandPurpleContainer
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.RelaySearchField
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.clearSelectionOnTap
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.ui.navigation.RelayIconButton
import com.nogirelay.app.ui.transfer.DataTransferDrawer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MEMBER_MESSAGES_PAGE_SIZE = 20

private data class MemberPageData(
    val matchingCount: Int = 0,
    val totalPages: Int = 1,
    val page: Int = 0,
    val messages: List<RelayMessage> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    dataVersion: Long,
    initialMessageId: String?,
    initialMemberId: String? = null,
    onInitialMemberHandled: ((String) -> Unit)? = null,
    onInitialMessageHandled: (String) -> Unit,
    onUnreadChanged: () -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    onUpdateProximity: (VoicePlaybackState) -> Unit,
    isActive: Boolean = true,
    viewModel: MessagesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val downloadScope = rememberCoroutineScope()
    val retranslateScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(dataVersion) {
        viewModel.load()
    }

    val messages = uiState.messages
    val threads = uiState.threads
    val translationEnabled = uiState.translationEnabled
    val userNickname = uiState.userNickname
    val playbackState by VoicePlaybackService.playbackState.collectAsState()
    var selectedMemberId by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var timeFilter by remember { mutableStateOf(TimeFilter()) }
    var currentPage by remember { mutableIntStateOf(0) }
    var pageInput by remember { mutableStateOf("1") }
    var pendingDownload by remember { mutableStateOf<RelayMessage?>(null) }
    var notificationScrollMessageId by remember { mutableStateOf<String?>(null) }
    var sessionUnreadIds by remember(selectedMemberId) { mutableStateOf(emptySet<String>()) }
    var showDataDrawer by remember { mutableStateOf(false) }
    val isViewingLatest = (currentPage == 0 && searchQuery.isBlank() && !timeFilter.isActive)
    val inboxListState = rememberLazyListState()

    LaunchedEffect(isActive) {
        if (!isActive) {
            selectedMemberId = null
            timeFilter = TimeFilter()
            searchQuery = ""
            currentPage = 0
            pageInput = "1"
            showDataDrawer = false
            inboxListState.scrollToItem(0)
        }
    }

    DisposableEffect(selectedMemberId, isActive, isViewingLatest) {
        val memberKey = selectedMemberId
        if (isActive && memberKey != null) {
            MessageReadTracker.openMember(memberKey, viewingLatest = isViewingLatest)
        }
        onDispose {
            if (memberKey != null) MessageReadTracker.closeMember(memberKey)
        }
    }
    
    LaunchedEffect(playbackState) {
        onUpdateProximity(playbackState)
    }
    
    LaunchedEffect(initialMessageId) {
        val targetId = initialMessageId ?: return@LaunchedEffect
        val message = AppGraph.database.find(targetId)
        if (message == null) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        val memberKey = message.memberId.ifBlank { message.memberName }
        val messageIndex = AppGraph.database.messageIndexForMember(memberKey, targetId)
        if (messageIndex < 0) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        searchQuery = ""
        // 目标索引是在不带筛选的情况下计算的，因此清空时间筛选以保持一致。
        timeFilter = TimeFilter()
        selectedMemberId = memberKey
        currentPage = messageIndex / MEMBER_MESSAGES_PAGE_SIZE
        pageInput = (currentPage + 1).toString()
        notificationScrollMessageId = targetId
    }

    LaunchedEffect(initialMemberId) {
        val targetMember = initialMemberId?.ifBlank { null } ?: return@LaunchedEffect
        selectedMemberId = targetMember
        timeFilter = TimeFilter()
        searchQuery = ""
        currentPage = 0
        pageInput = "1"
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
    if (uiState.loading && messages.isEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }
    if (messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Inbox, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))
                Text("还没有同步消息", style = MaterialTheme.typography.titleMedium)
                Text("保存同步设置后，新消息会出现在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    fun download(message: RelayMessage) {
        if (MediaDownloader.needsLegacyWritePermission(context)) {
            pendingDownload = message
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            saveDownload(message)
        }
    }

    val focusManager = LocalFocusManager.current
    val textToolbar = LocalTextToolbar.current
    AutoClearSelectionOnExit(isActive = isActive)

    val selected = selectedMemberId
    BackHandler(enabled = isActive && (selected != null || searchQuery.isNotEmpty())) {
        focusManager.clearFocus()
        textToolbar.hide()
        if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            currentPage = 0
            pageInput = "1"
        } else if (selected != null) {
            selectedMemberId = null
        }
    }
    Crossfade(
        targetState = selected,
        animationSpec = tween(durationMillis = 220),
        label = "member-message-transition",
    ) { selectedMember ->
        if (selectedMember == null) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            text = "消息",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    actions = {
                        RelayIconButton(
                            onClick = { showDataDrawer = true },
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "数据管理",
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                MemberInbox(
                    threads = threads,
                    userNickname = userNickname,
                    state = inboxListState,
                    onSelect = { thread ->
                        searchQuery = ""
                        timeFilter = TimeFilter()
                        selectedMemberId = thread.id
                        currentPage = 0
                        pageInput = "1"
                        
                        // 异步检查该成员是否有正在播放的消息，以避免阻塞 UI 线程
                        val currentPlayingMessageId = playbackState.messageId
                        if (playbackState.isPlaying && currentPlayingMessageId != null) {
                            downloadScope.launch(AppGraph.dispatchers.databaseRead) {
                                val playingMessage = AppGraph.database.find(currentPlayingMessageId)
                                val playingMemberId = playingMessage?.memberId?.ifBlank { playingMessage.memberName }
                                if (playingMemberId == thread.id) {
                                    val playingIndex = AppGraph.database.messageIndexForMember(
                                        memberKey = thread.id,
                                        messageId = currentPlayingMessageId,
                                        startMillis = timeFilter.startMillis,
                                        endMillisExclusive = timeFilter.endMillisExclusive,
                                        nickname = userNickname,
                                    )
                                    if (playingIndex >= 0) {
                                        val targetPage = playingIndex / MEMBER_MESSAGES_PAGE_SIZE
                                        withContext(Dispatchers.Main) {
                                            currentPage = targetPage
                                            pageInput = (targetPage + 1).toString()
                                        }
                                    }
                                }
                            }
                        }
                    },
                )
            }
        } else {
            val thread = threads.firstOrNull { it.id == selectedMember }
            val initialForMember = remember(selectedMember) {
                uiState.messages.filter { it.memberKey == selectedMember }.take(MEMBER_MESSAGES_PAGE_SIZE)
            }
            var pageData by remember(selectedMember) {
                mutableStateOf(
                    MemberPageData(
                        matchingCount = initialForMember.size,
                        totalPages = 1,
                        page = 0,
                        messages = initialForMember,
                        loaded = initialForMember.isNotEmpty(),
                    ),
                )
            }
            LaunchedEffect(selectedMember, searchQuery, timeFilter, currentPage, dataVersion, userNickname) {
                val query = searchQuery
                val bounds = timeFilter
                val requestedPage = currentPage
                pageData = withContext(AppGraph.dispatchers.databaseRead) {
                    val matching = AppGraph.database.countMessagesForMember(
                        memberKey = selectedMember,
                        searchQuery = query,
                        startMillis = bounds.startMillis,
                        endMillisExclusive = bounds.endMillisExclusive,
                        nickname = userNickname,
                    )
                    val totalPages = ((matching + MEMBER_MESSAGES_PAGE_SIZE - 1) / MEMBER_MESSAGES_PAGE_SIZE).coerceAtLeast(1)
                    val page = requestedPage.coerceIn(0, totalPages - 1)
                    val fetchedMessages = AppGraph.database.messagesForMember(
                        memberKey = selectedMember,
                        searchQuery = query,
                        startMillis = bounds.startMillis,
                        endMillisExclusive = bounds.endMillisExclusive,
                        limit = MEMBER_MESSAGES_PAGE_SIZE,
                        offset = page * MEMBER_MESSAGES_PAGE_SIZE,
                        nickname = userNickname,
                    )
                    MemberPageData(
                        matchingCount = matching,
                        totalPages = totalPages,
                        page = page,
                        messages = fetchedMessages,
                        loaded = true,
                    )
                }
            }

            val matchingMessageCount = pageData.matchingCount
            val totalPages = pageData.totalPages
            val page = pageData.page
            val memberMessages = pageData.messages
            val messageListState = rememberLazyListState()
            var showPageDialog by remember(selectedMember, searchQuery, timeFilter) { mutableStateOf(false) }
            var showTimeFilterDialog by remember(selectedMember) { mutableStateOf(false) }

            val currentMessageSignature = remember(memberMessages, page, matchingMessageCount) {
                if (memberMessages.isEmpty()) {
                    "empty:$matchingMessageCount:$searchQuery"
                } else {
                    "$page:$matchingMessageCount:" + memberMessages.joinToString(",") { it.id }
                }
            }
            var initialDbLoaded by remember(selectedMember) { mutableStateOf(false) }
            var previousMessageSignature by remember(selectedMember) { mutableStateOf<String?>(null) }
            var filterAnimKey by remember(selectedMember) { mutableIntStateOf(0) }

            LaunchedEffect(isActive) {
                if (!isActive) {
                    showTimeFilterDialog = false
                    showPageDialog = false
                    previousMessageSignature = null
                }
            }

            LaunchedEffect(currentMessageSignature, pageData.loaded) {
                if (!pageData.loaded) return@LaunchedEffect
                if (!initialDbLoaded) {
                    initialDbLoaded = true
                    previousMessageSignature = currentMessageSignature
                } else if (previousMessageSignature != currentMessageSignature) {
                    previousMessageSignature = currentMessageSignature
                    filterAnimKey++
                }
            }

            val density = LocalDensity.current
            val springOffset = remember(selectedMember) { Animatable(28f) }

            LaunchedEffect(selectedMember) {
                springOffset.snapTo(28f)
                springOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
            }

            LaunchedEffect(filterAnimKey) {
                if (filterAnimKey > 0) {
                    messageListState.scrollToItem(0)
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


            val unreadInCurrentPage = remember(memberMessages) {
                memberMessages.filter { it.isUnread }.map { it.id }
            }
            LaunchedEffect(unreadInCurrentPage) {
                if (unreadInCurrentPage.isNotEmpty()) {
                    sessionUnreadIds = sessionUnreadIds + unreadInCurrentPage
                    val updated = withContext(Dispatchers.IO) {
                        AppGraph.database.markMessagesReadByIds(unreadInCurrentPage)
                    }
                    if (updated > 0) onUnreadChanged()
                }
            }
            
            fun goToPage(targetPage: Int) {
                focusManager.clearFocus()
                textToolbar.hide()
                val safePage = targetPage.coerceIn(0, totalPages - 1)
                currentPage = safePage
                pageInput = (safePage + 1).toString()
            }
            val requestedPage = pageInput.toIntOrNull()
            val canJump = requestedPage != null && requestedPage in 1..totalPages
            LaunchedEffect(page, totalPages, pageData.loaded) {
                if (!pageData.loaded) return@LaunchedEffect
                if (currentPage != page) currentPage = page
                pageInput = (page + 1).toString()
            }
            var previousPage by remember(selectedMember) { mutableIntStateOf(page) }
            LaunchedEffect(page) {
                if (page != previousPage) {
                    previousPage = page
                    messageListState.scrollToItem(0)
                }
            }
            var initialVoiceScrollHandled by remember(selectedMember) { mutableStateOf(false) }
            LaunchedEffect(playbackState.isPlaying, playbackState.messageId, memberMessages) {
                if (!initialVoiceScrollHandled && playbackState.isPlaying && playbackState.messageId != null) {
                    val playingIndex = memberMessages.indexOfFirst { it.id == playbackState.messageId }
                    if (playingIndex >= 0) {
                        initialVoiceScrollHandled = true
                        messageListState.scrollToItem(playingIndex + 1)
                    }
                }
            }
            LaunchedEffect(notificationScrollMessageId, memberMessages) {
                val targetId = notificationScrollMessageId ?: return@LaunchedEffect
                val targetIndex = memberMessages.indexOfFirst { it.id == targetId }
                if (targetIndex >= 0) {
                    // 搜索框占据第 0 项，与现有的语音播放定位保持一致。
                    messageListState.scrollToItem(targetIndex + 1)
                    notificationScrollMessageId = null
                    onInitialMessageHandled(targetId)
                }
            }
            if (showTimeFilterDialog) {
                TimeFilterDialog(
                    filter = timeFilter,
                    onDismiss = { showTimeFilterDialog = false },
                    onConfirm = { updated ->
                        timeFilter = updated
                        currentPage = 0
                        pageInput = "1"
                        showTimeFilterDialog = false
                    },
                )
            }
            if (showPageDialog) {
                AlertDialog(
                    onDismissRequest = { showPageDialog = false },
                    shape = RoundedCornerShape(20.dp),
                    title = { Text("跳转页码", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "请输入 1 ~ ${totalPages} 之间的页码",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = pageInput,
                                onValueChange = { value ->
                                    pageInput = value.filter(Char::isDigit).take(6)
                                },
                                singleLine = true,
                                label = { Text("页码") },
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandPurple,
                                    focusedLabelColor = BrandPurple,
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Go,
                                ),
                                keyboardActions = KeyboardActions(
                                    onGo = {
                                        if (canJump) {
                                            goToPage(requestedPage!! - 1)
                                            showPageDialog = false
                                        }
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                goToPage(requestedPage!! - 1)
                                showPageDialog = false
                            },
                            enabled = canJump,
                            colors = ButtonDefaults.textButtonColors(contentColor = BrandPurple),
                        ) { Text("跳转", fontWeight = FontWeight.Bold) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPageDialog = false }) { Text("取消") }
                    },
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .clearSelectionOnTap(focusManager, textToolbar)
            ) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            textToolbar.hide()
                            if (searchQuery.isNotEmpty()) {
                                searchQuery = ""
                                currentPage = 0
                                pageInput = "1"
                            } else {
                                selectedMemberId = null
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = if (searchQuery.isNotEmpty()) "清空搜索" else "返回成员列表", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = thread?.name ?: "成员消息",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            val remainingUnread = thread?.unreadCount ?: 0
                            if (remainingUnread > 0) {
                                Spacer(Modifier.width(8.dp))
                                UnreadTag("$remainingUnread 条未读")
                            }
                        }
                    },
                    actions = {
                        RelayIconButton(
                            onClick = { showDataDrawer = true },
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "数据管理",
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                LazyColumn(
                    state = messageListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    item(key = "messages-search-bar") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        ) {
                            RelaySearchField(
                                query = searchQuery,
                                onQueryChange = {
                                    searchQuery = it
                                    currentPage = 0
                                    pageInput = "1"
                                },
                                placeholder = "搜索消息内容或日期",
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = CircleShape,
                                color = if (timeFilter.isActive) BrandPurple.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                                border = if (timeFilter.isActive) BorderStroke(1.dp, BrandPurple) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.size(42.dp),
                            ) {
                                IconButton(
                                    onClick = { showTimeFilterDialog = true },
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    Icon(
                                        Icons.Rounded.FilterList,
                                        contentDescription = "筛选时间",
                                        tint = if (timeFilter.isActive) {
                                            BrandPurple
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (pageData.loaded && memberMessages.isEmpty()) {
                        item(key = "messages-empty") {
                            Text(
                                text = if (searchQuery.isBlank()) "暂无消息" else "没有找到相关消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .graphicsLayer { translationY = with(density) { springOffset.value.dp.toPx() } }
                                    .animateItemPlacement()
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                            )
                        }
                    } else if (memberMessages.isNotEmpty()) {
                        items(memberMessages, key = { it.id }) { message ->
                            MessageCard(
                                modifier = Modifier
                                    .graphicsLayer { translationY = with(density) { springOffset.value.dp.toPx() } }
                                    .animateItemPlacement(),
                                message = message,
                                isUnread = message.isUnread || message.id in sessionUnreadIds,
                                audioState = playbackState.takeIf { it.messageId == message.id },
                                translationEnabled = translationEnabled,
                                userNickname = userNickname,
                                searchQuery = searchQuery,
                                onOpenMedia = { onOpenMedia(message) },
                                onPlayVoice = { onPlayVoice(message) },
                                onDownload = { download(message) },
                                onRetranslate = {
                                    retranslateScope.launch {
                                        AppGraph.database.markForRetranslation(message.id)
                                        AppGraph.notifyDataChanged()
                                        // 单条重翻是明确的手动动作，不经过"消息全量翻译"开关。
                                        TranslationManager.enqueueIds(context, listOf(message.id))
                                    }
                                },
                            )
                        }
                    }
                    if (initialDbLoaded) {
                        item(key = "messages-pagination") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .graphicsLayer { translationY = with(density) { springOffset.value.dp.toPx() } }
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(
                                    text = "共 $matchingMessageCount 条消息",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    OutlinedButton(
                                        onClick = { goToPage(page - 1) },
                                        enabled = page > 0,
                                        shape = RelayControlShape,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = BrandPurple,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("上一页", fontSize = 13.sp) }

                                    OutlinedButton(
                                        onClick = {
                                            pageInput = (page + 1).toString()
                                            showPageDialog = true
                                        },
                                        enabled = matchingMessageCount > 0,
                                        shape = RelayControlShape,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = BrandPurple,
                                            containerColor = BrandPurple.copy(alpha = 0.06f),
                                        ),
                                        border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.3f)),
                                        modifier = Modifier.weight(1.2f),
                                    ) {
                                        Text("${page + 1} / $totalPages", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                        Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码", modifier = Modifier.size(18.dp))
                                    }

                                    OutlinedButton(
                                        onClick = { goToPage(page + 1) },
                                        enabled = page < totalPages - 1,
                                        shape = RelayControlShape,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = BrandPurple,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    ) { Text("下一页", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }

    if (showDataDrawer) {
        DataTransferDrawer(
            kind = ExportKind.MESSAGES,
            onDismiss = { showDataDrawer = false },
        )
    }
}
