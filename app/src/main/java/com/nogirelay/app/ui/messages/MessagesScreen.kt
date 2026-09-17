package com.nogirelay.app.ui.messages

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import com.nogirelay.app.ui.TimeFilter
import com.nogirelay.app.ui.TimeFilterDialog
import com.nogirelay.app.ui.clearSelectionOnTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MEMBER_MESSAGES_PAGE_SIZE = 20

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessagesScreen(
    dataVersion: Long,
    initialMessageId: String?,
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
    val isViewingLatest = (currentPage == 0 && searchQuery.isBlank() && !timeFilter.isActive)

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
        // The target index is computed without filters, so clear the time filter to stay consistent.
        timeFilter = TimeFilter()
        selectedMemberId = memberKey
        currentPage = messageIndex / MEMBER_MESSAGES_PAGE_SIZE
        pageInput = (currentPage + 1).toString()
        notificationScrollMessageId = targetId
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
    BackHandler(enabled = isActive && selected != null) {
        focusManager.clearFocus()
        textToolbar.hide()
        selectedMemberId = null
    }
    Crossfade(
        targetState = selected,
        animationSpec = tween(durationMillis = 220),
        label = "member-message-transition",
    ) { selectedMember ->
        if (selectedMember == null) {
            MemberInbox(
                threads = threads,
                userNickname = userNickname,
                onSelect = { thread ->
                    searchQuery = ""
                    selectedMemberId = thread.id
                    currentPage = 0
                    pageInput = "1"
                    
                    // Check if there's a playing message for this member asynchronously to avoid blocking UI thread
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
        } else {
            val thread = threads.firstOrNull { it.id == selectedMember }
            val initialForMember = remember(selectedMember) {
                uiState.messages.filter { it.memberKey == selectedMember }.take(MEMBER_MESSAGES_PAGE_SIZE)
            }
            val matchingMessageCount by produceState(
                initialValue = if (searchQuery.isBlank() && !timeFilter.isActive) initialForMember.size else 0,
                key1 = selectedMember,
                key2 = searchQuery,
                key3 = Pair(timeFilter, dataVersion),
            ) {
                value = withContext(AppGraph.dispatchers.databaseRead) {
                    AppGraph.database.countMessagesForMember(
                        memberKey = selectedMember,
                        searchQuery = searchQuery,
                        startMillis = timeFilter.startMillis,
                        endMillisExclusive = timeFilter.endMillisExclusive,
                    )
                }
            }
            val totalPages = ((matchingMessageCount + MEMBER_MESSAGES_PAGE_SIZE - 1) / MEMBER_MESSAGES_PAGE_SIZE)
                .coerceAtLeast(1)
            val page = currentPage.coerceIn(0, totalPages - 1)
            val messageListState = rememberLazyListState()
            var showPageDialog by remember(selectedMember, searchQuery, timeFilter) { mutableStateOf(false) }
            var showTimeFilterDialog by remember(selectedMember) { mutableStateOf(false) }
            val memberMessages by produceState<List<RelayMessage>>(
                initialValue = if (searchQuery.isBlank() && !timeFilter.isActive && page == 0) initialForMember else emptyList(),
                key1 = selectedMember,
                key2 = Pair(searchQuery, timeFilter),
                key3 = Pair(page, dataVersion),
            ) {
                value = withContext(AppGraph.dispatchers.databaseRead) {
                    AppGraph.database.messagesForMember(
                        memberKey = selectedMember,
                        searchQuery = searchQuery,
                        startMillis = timeFilter.startMillis,
                        endMillisExclusive = timeFilter.endMillisExclusive,
                        limit = MEMBER_MESSAGES_PAGE_SIZE,
                        offset = page * MEMBER_MESSAGES_PAGE_SIZE,
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
            LaunchedEffect(selectedMember, searchQuery, timeFilter, page) {
                if (currentPage != page) currentPage = page
                pageInput = (page + 1).toString()
                if (memberMessages.isNotEmpty() && notificationScrollMessageId == null) {
                    // Check if playing message is in current page
                    if (playbackState.isPlaying && playbackState.messageId != null) {
                        val playingIndex = memberMessages.indexOfFirst { it.id == playbackState.messageId }
                        if (playingIndex >= 0) {
                            messageListState.scrollToItem(playingIndex + 1)
                        } else {
                            messageListState.scrollToItem(0)
                        }
                    } else {
                        messageListState.scrollToItem(0)
                    }
                }
            }
            LaunchedEffect(notificationScrollMessageId, memberMessages) {
                val targetId = notificationScrollMessageId ?: return@LaunchedEffect
                val targetIndex = memberMessages.indexOfFirst { it.id == targetId }
                if (targetIndex >= 0) {
                    // The search field occupies item 0, matching the existing voice-playback positioning.
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
                    title = { Text("跳转") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "请输入1-${totalPages}之间的页码",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = pageInput,
                                onValueChange = { value ->
                                    pageInput = value.filter(Char::isDigit).take(6)
                                },
                                singleLine = true,
                                label = { Text("页码") },
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
                        ) { Text("跳转") }
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        textToolbar.hide()
                        selectedMemberId = null
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回成员列表")
                    }
                    Text(
                        text = thread?.name ?: "成员消息",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val remainingUnread = thread?.unreadCount ?: 0
                    if (remainingUnread > 0) {
                        Spacer(Modifier.width(8.dp))
                        UnreadTag("$remainingUnread 条未读")
                    }
                }
                LazyColumn(
                    state = messageListState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    currentPage = 0
                                    pageInput = "1"
                                },
                                singleLine = true,
                                label = { Text("搜索") },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                trailingIcon = if (searchQuery.isNotEmpty()) {
                                    {
                                        IconButton(
                                            onClick = {
                                                searchQuery = ""
                                                currentPage = 0
                                                pageInput = "1"
                                            },
                                        ) {
                                            Icon(Icons.Rounded.Close, contentDescription = "清除搜索")
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            IconButton(onClick = { showTimeFilterDialog = true }) {
                                Icon(
                                    Icons.Rounded.FilterList,
                                    contentDescription = "筛选时间",
                                    tint = if (timeFilter.isActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    if (memberMessages.isEmpty()) {
                        item {
                            Text(
                                text = if (searchQuery.isBlank()) "暂无消息" else "没有找到相关消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            )
                        }
                    }
                    items(memberMessages, key = { it.id }) { message ->
                        MessageCard(
                            modifier = Modifier.animateItemPlacement(),
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
                                    TranslationManager.enqueue(context)
                                }
                            },
                        )
                    }
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = "$matchingMessageCount 条消息",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick = { goToPage(page - 1) },
                                    enabled = page > 0,
                                ) { Text("上一页") }
                                OutlinedButton(
                                    onClick = {
                                        pageInput = (page + 1).toString()
                                        showPageDialog = true
                                    },
                                    enabled = matchingMessageCount > 0,
                                ) {
                                    Text("${page + 1} / $totalPages")
                                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = "选择页码")
                                }
                                OutlinedButton(
                                    onClick = { goToPage(page + 1) },
                                    enabled = page < totalPages - 1,
                                ) { Text("下一页") }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}
