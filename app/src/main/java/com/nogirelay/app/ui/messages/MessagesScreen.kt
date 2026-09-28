package com.nogirelay.app.ui.messages

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.DataChange
import com.nogirelay.app.data.DataVersions
import com.nogirelay.app.data.MessageReadTracker
import com.nogirelay.app.data.RelayMessage
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.media.MediaDownloader
import com.nogirelay.app.media.VoicePlaybackService
import com.nogirelay.app.media.VoicePlaybackState
import com.nogirelay.app.translation.TranslationManager
import com.nogirelay.app.ui.AutoClearSelectionOnExit
import com.nogirelay.app.ui.LocalRelayMirrorStyle
import com.nogirelay.app.ui.RelayGlassBackdrop
import com.nogirelay.app.ui.RelayLightBackdrop
import com.nogirelay.app.ui.RelayMirrorGlassIconButton
import com.nogirelay.app.ui.relaySheetBackdrop
import com.nogirelay.app.ui.rememberRelaySheetBackdropState
import com.nogirelay.app.ui.transfer.DataTransferDrawer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    versions: DataVersions,
    initialMessageId: String?,
    initialMemberId: String? = null,
    onInitialMemberHandled: ((String) -> Unit)? = null,
    onInitialMessageHandled: (String) -> Unit,
    onUnreadChanged: (Set<String>) -> Unit,
    onOpenMedia: (RelayMessage) -> Unit,
    onPlayVoice: (RelayMessage) -> Unit,
    isActive: Boolean = true,
    viewModel: MessagesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val sheetBackdrop = rememberRelaySheetBackdropState()
    val workActive = isActive && com.nogirelay.app.performance.isRelayUiStarted() && !sheetBackdrop.isAttached
    val downloadScope = rememberCoroutineScope()
    val retranslateScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(versions.messages, versions.settings, workActive) {
        if (workActive) viewModel.load(versions)
        onDispose { viewModel.stopLoading() }
    }

    val threads = uiState.threads
    val translationEnabled = uiState.translationEnabled
    val userNickname = uiState.userNickname
    val playbackFlow = remember(workActive) {
        if (workActive) VoicePlaybackService.playbackState else flowOf(VoicePlaybackService.playbackState.value)
    }
    val playbackState by playbackFlow.collectAsStateWithLifecycle(initialValue = VoicePlaybackState())
    var selectedEntry by remember { mutableStateOf<MemberMessageEntry?>(null) }
    val selectedMemberId = selectedEntry?.memberKey
    var viewingLatest by remember(selectedEntry) { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RelayMessage?>(null) }
    var showDataDrawer by remember { mutableStateOf(false) }
    val inboxListState = rememberLazyListState()

    fun openMember(memberKey: String, notificationMessageId: String? = null) {
        selectedEntry = MemberMessageEntry(
            memberKey = memberKey,
            playbackState = VoicePlaybackService.playbackState.value,
            notificationMessageId = notificationMessageId,
        )
    }

    LaunchedEffect(isActive) {
        if (!isActive) {
            selectedEntry = null
            showDataDrawer = false
            inboxListState.scrollToItem(0)
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
        val message = withContext(AppGraph.dispatchers.databaseRead) { AppGraph.database.find(targetId) }
        if (message == null) {
            onInitialMessageHandled(targetId)
            return@LaunchedEffect
        }
        openMember(message.memberKey, notificationMessageId = targetId)
        initialMemberId?.let { onInitialMemberHandled?.invoke(it) }
    }

    LaunchedEffect(initialMemberId, initialMessageId, isActive) {
        if (!isActive || initialMessageId != null) return@LaunchedEffect
        val targetMember = initialMemberId?.ifBlank { null } ?: return@LaunchedEffect
        openMember(targetMember)
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
    if (uiState.loading && threads.isEmpty()) {
        Box(Modifier.fillMaxSize())
        return
    }
    if (threads.isEmpty()) {
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

    AutoClearSelectionOnExit(isActive = isActive)
    CompositionLocalProvider(
        LocalRelayMirrorStyle provides true,
        com.nogirelay.app.performance.LocalRelayPageWorkPaused provides sheetBackdrop.isAttached,
    ) {
        RelayGlassBackdrop(
            background = RelayLightBackdrop,
            modifier = Modifier.fillMaxSize().relaySheetBackdrop(sheetBackdrop),
        ) {
            Crossfade(
                targetState = selectedEntry,
                animationSpec = tween(durationMillis = 220),
                label = "member-message-transition",
                modifier = Modifier.fillMaxSize(),
            ) { entry ->
                if (entry == null) {
                    MemberInbox(
                        threads = threads,
                        userNickname = userNickname,
                        state = inboxListState,
                        header = {
                            Column(Modifier.fillMaxWidth()) {
                                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
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
                                        // The inbox action disappears as soon as a member is
                                        // selected, including the outgoing side of the crossfade.
                                        if (selectedEntry == null) {
                                            RelayMirrorGlassIconButton(
                                                onClick = { showDataDrawer = true },
                                                imageVector = Icons.Rounded.Settings,
                                                contentDescription = "数据管理",
                                            )
                                        }
                                    },
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    ),
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                )
                            }
                        },
                        onSelect = { thread -> openMember(thread.id) },
                    )
                } else {
                    MemberTimelineScreen(
                        entry = entry,
                        active = isActive && selectedEntry === entry,
                        versions = versions,
                        playbackState = playbackState,
                        translationEnabled = translationEnabled,
                        userNickname = userNickname,
                        backdropState = sheetBackdrop,
                        onBack = {
                            if (selectedEntry === entry) {
                                if (initialMessageId != null && initialMessageId == entry.notificationMessageId) {
                                    onInitialMessageHandled(initialMessageId)
                                }
                                selectedEntry = null
                            }
                        },
                        onInitialMessageHandled = { if (selectedEntry === entry) onInitialMessageHandled(it) },
                        onViewingLatest = { if (selectedEntry === entry) viewingLatest = it },
                        onUnreadChanged = onUnreadChanged,
                        onOpenMedia = onOpenMedia,
                        onPlayVoice = onPlayVoice,
                        onDownload = ::download,
                        onRetranslate = { message ->
                            retranslateScope.launch {
                                withContext(AppGraph.dispatchers.databaseWrite) {
                                    AppGraph.database.markForRetranslation(message.id)
                                }
                                AppGraph.notifyDataChanged(DataChange.MESSAGE_ROWS, setOf(message.id))
                                TranslationManager.enqueueIds(context, listOf(message.id))
                            }
                        },
                    )
                }
            }

            if (showDataDrawer) {
                DataTransferDrawer(
                    kind = ExportKind.MESSAGES,
                    backdropState = sheetBackdrop,
                    onDismiss = { showDataDrawer = false },
                )
            }
        }
    }
}
