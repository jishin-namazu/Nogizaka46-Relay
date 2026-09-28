package com.nogirelay.app.ui.transfer

import android.os.SystemClock
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import com.nogirelay.app.media.MediaCacheRevision
import com.nogirelay.app.data.transfer.ExportEstimateCache
import com.nogirelay.app.data.transfer.ExportEstimateKey
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.transfer.DataExporter
import com.nogirelay.app.data.transfer.DataImporter
import com.nogirelay.app.data.transfer.DataTransferManager
import com.nogirelay.app.data.transfer.ExportEstimate
import com.nogirelay.app.data.transfer.ExportFormat
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.data.transfer.ExportRequest
import com.nogirelay.app.data.transfer.ImportOptions
import com.nogirelay.app.data.transfer.ImportPreview
import com.nogirelay.app.data.transfer.TransferOperation
import com.nogirelay.app.data.transfer.TransferOutcome
import com.nogirelay.app.ui.BrandPurple
import com.nogirelay.app.ui.RelaySegmentedTabs
import com.nogirelay.app.ui.RelayModalBottomSheet
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.LocalRelayMirrorStyle
import com.nogirelay.app.ui.RelayHomeCardShape
import com.nogirelay.app.ui.RelayCardContentInset
import com.nogirelay.app.ui.RelayDialogButton
import com.nogirelay.app.ui.RelayDialogButtonStyle
import com.nogirelay.app.ui.RelayDialogCard
import com.nogirelay.app.ui.RelayDialogIconButton
import com.nogirelay.app.ui.RelayMirrorGlassSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val exportEstimateCache = ExportEstimateCache(SystemClock::elapsedRealtime)

/**
 * MESSAGES 与 BLOG 界面共用的数据管理抽屉。每个界面各自拥有一个 [kind]，因此导出
 * 部分只提供自己的内容；导入部分与内容无关，会从归档清单中读回
 * 类型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTransferDrawer(
    kind: ExportKind,
    backdropState: RelaySheetBackdropState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val mirrorStyle = LocalRelayMirrorStyle.current
    val scope = rememberCoroutineScope()
    val transfer by DataTransferManager.state.collectAsState()

    var members by remember(kind) { mutableStateOf<List<BlogMember>>(emptyList()) }
    var selectedIds by remember(kind) { mutableStateOf<Set<String>?>(null) }
    var includeMedia by remember(kind) { mutableStateOf(true) }
    var includeTranslations by remember(kind) { mutableStateOf(true) }
    var importMedia by remember(kind) { mutableStateOf(true) }
    var importMembers by remember(kind) { mutableStateOf(true) }
    var showPicker by remember(kind) { mutableStateOf(false) }
    var pendingExport by remember(kind) { mutableStateOf<ExportRequest?>(null) }
    var estimateResult by remember(kind) { mutableStateOf<Pair<ExportEstimateKey, ExportEstimate>?>(null) }
    var estimateFailure by remember(kind) { mutableStateOf<String?>(null) }
    // 统计跑在 IO 线程，进度经 StateFlow 回传，避免在后台线程写 Compose 状态。
    val estimateProgress = remember(kind) { MutableStateFlow<Pair<Int, Int>?>(null) }
    var preview by remember(kind) { mutableStateOf<ImportPreview?>(null) }
    var pendingImportUri by remember(kind) { mutableStateOf<Uri?>(null) }
    // 导入成员选择：null = 归档中的全部成员。归档成员只有预览后才知道，所以换一个归档就重置。
    var importSelectedIds by remember(kind) { mutableStateOf<Set<String>?>(null) }
    var showImportPicker by remember(kind) { mutableStateOf(false) }
    var tabIndex by remember(kind) { mutableIntStateOf(0) }
    val uiStarted = com.nogirelay.app.performance.isRelayUiStarted()
    val contentFlow = remember(kind) {
        AppGraph.dataVersions.map { if (kind == ExportKind.MESSAGES) it.messageStructure else it.blogContent }
            .distinctUntilChanged()
    }
    val initialContentRevision = remember(kind) {
        AppGraph.dataVersions.value.let { if (kind == ExportKind.MESSAGES) it.messageStructure else it.blogContent }
    }
    val contentRevision by contentFlow.collectAsStateWithLifecycle(initialValue = initialContentRevision)
    val mediaFlow = remember(includeMedia) { if (includeMedia) MediaCacheRevision.changes else flowOf(0L) }
    val initialMediaRevision = remember(includeMedia) { if (includeMedia) MediaCacheRevision.changes.value else 0L }
    val mediaRevision by mediaFlow.collectAsStateWithLifecycle(initialValue = initialMediaRevision)

    LaunchedEffect(kind, contentRevision, backdropState.isSettled, uiStarted) {
        if (!backdropState.isSettled || !uiStarted) return@LaunchedEffect
        members = withContext(Dispatchers.IO) {
            when (kind) {
                ExportKind.MESSAGES -> AppGraph.database.messageExportMembers().map {
                    BlogMember(
                        id = it.memberKey,
                        name = it.name,
                        category = it.category,
                        avatarUrl = it.avatarUrl,
                        displayOrder = it.displayOrder,
                    )
                }
                ExportKind.BLOGS -> AppGraph.database.blogMembers()
            }
        }
    }

    val allIds = remember(members) { members.mapTo(linkedSetOf(), BlogMember::id) }
    val effectiveSelection = selectedIds ?: allIds

    // 导入成员直接来自归档清单，因此筛选界面在导入前就能显示"这个归档里有哪些成员"。
    val previewMembers = remember(preview) {
        preview?.members.orEmpty().map { member ->
            BlogMember(
                id = member.id,
                name = member.name,
                category = member.category,
                avatarUrl = member.avatarUrl,
                displayOrder = member.displayOrder,
                graduated = member.graduated,
            )
        }
    }
    val importAllIds = remember(previewMembers) { previewMembers.mapTo(linkedSetOf(), BlogMember::id) }
    val effectiveImportSelection = importSelectedIds ?: importAllIds

    val backfilling = transfer.running && transfer.operation == TransferOperation.BACKFILL
    val estimateRequest = remember(kind, effectiveSelection, includeMedia, contentRevision, mediaRevision) {
        ExportEstimateKey(kind, effectiveSelection.toSet(), includeMedia, contentRevision, mediaRevision)
    }
    val estimate = estimateResult?.takeIf { it.first == estimateRequest }?.second
    val estimateActive = backdropState.isSettled && uiStarted && tabIndex == 0 &&
        !transfer.running && !showPicker && !showImportPicker

    LaunchedEffect(estimateRequest, estimateActive) {
        if (!estimateActive || effectiveSelection.isEmpty()) return@LaunchedEffect
        estimateFailure = null
        exportEstimateCache.get(estimateRequest)?.let {
            estimateResult = estimateRequest to it
            return@LaunchedEffect
        }
        estimateProgress.value = 0 to 0
        try {
            val result = withContext(Dispatchers.IO) {
                DataExporter.estimate(context, kind, effectiveSelection, includeMedia) { done, total ->
                    estimateProgress.value = done to total
                }
            }
            // A concurrent download/content change must not make an old scan reusable.
            val latest = AppGraph.dataVersions.value
            val currentContent = if (kind == ExportKind.MESSAGES) latest.messageStructure else latest.blogContent
            if (currentContent == estimateRequest.contentRevision &&
                (!includeMedia || MediaCacheRevision.changes.value == estimateRequest.mediaRevision)) {
                exportEstimateCache.put(estimateRequest, result)
                estimateResult = estimateRequest to result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            estimateFailure = error.message ?: "统计失败"
        } finally {
            estimateProgress.value = null
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) {
            DataTransferManager.export(context, request, uri)
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { DataImporter.preview(context, uri) }.getOrNull()
            }
            if (parsed == null) {
                Toast.makeText(context, "无法读取归档：文件可能不是 Nogi Relay 导出的 .zip", Toast.LENGTH_LONG).show()
            } else {
                preview = parsed
                pendingImportUri = uri
                importSelectedIds = null
                showImportPicker = false
            }
        }
    }

    // Content and complete media publications invalidate estimates, including partial imports.
    LaunchedEffect(Unit) { DataTransferManager.clearResult() }

    fun startExport() {
        if (effectiveSelection.isEmpty()) {
            Toast.makeText(context, "请至少选择一位成员", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "NogiRelay_" + kind.fileStem + "_" + ExportFormat.fileNameTimestamp() + ".zip"
        pendingExport = ExportRequest(
            kind = kind,
            memberKeys = effectiveSelection,
            includeTranslations = includeTranslations,
            outputName = name,
            includeMedia = includeMedia,
        )
        createDocument.launch(name)
    }

    RelayModalBottomSheet(
        onDismissRequest = onDismiss,
        backdropState = backdropState,
    ) { dismiss ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "数据管理 · " + kind.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RelayDialogIconButton(
                    onClick = { dismiss(onDismiss) },
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭数据管理",
                )
            }

            RelaySegmentedTabs(
                labels = listOf("导出", "导入"),
                selectedIndex = tabIndex,
                onSelected = { tabIndex = it },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            SectionCard {
                AnimatedContent(
                    targetState = tabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        if (mirrorStyle) {
                            val direction = if (targetState > initialState) 1 else -1
                            (
                                fadeIn(tween(durationMillis = 180, delayMillis = 40)) +
                                    slideInHorizontally(tween(durationMillis = 220)) { direction * it / 18 }
                            ).togetherWith(
                                fadeOut(tween(durationMillis = 120)) +
                                    slideOutHorizontally(tween(durationMillis = 180)) { -direction * it / 24 },
                            ).using(
                                SizeTransform(clip = false) { _, _ ->
                                    tween(durationMillis = 240, easing = FastOutSlowInEasing)
                                },
                            )
                        } else {
                            (EnterTransition.None togetherWith ExitTransition.None).using(null)
                        }
                    },
                    label = "transfer_tab_content",
                ) { contentTab ->
                    // The outgoing pane can remain drawn while fading, but cannot run actions.
                    val paneActive = contentTab == tabIndex
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .then(if (paneActive) Modifier else Modifier.clearAndSetSemantics {}),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (contentTab == 0) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = paneActive && !transfer.running) { showPicker = true }
                                    .padding(vertical = 6.dp),
                            ) {
                                MemberSelectionField(
                                    title = "导出成员",
                                    summary = "${effectiveSelection.size} / ${members.size}",
                                    enabled = paneActive && !transfer.running,
                                    onClick = { showPicker = true },
                                )
                                ExportEstimateStatus(
                                    estimate, estimateProgress, includeMedia, backfilling,
                                    estimateActive && paneActive, estimateFailure,
                                )
                            }
                            // 统计过程已经遍历过记录并保留了确切的 URL，因此补齐流程
                            // 直接下载该列表，而不再重新扫描整个库。
                            val missingMedia = estimate?.missing?.size ?: 0
                            if (missingMedia > 0 && !transfer.running) {
                                RelayDialogButton(
                                    style = RelayDialogButtonStyle.Outlined,
                                    enabled = paneActive && !transfer.running,
                                    onClick = {
                                        estimate?.let { DataTransferManager.backfillMedia(context, kind, it.missing) }
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalRelayMirrorStyle.current) 48.dp else 44.dp),
                                ) {
                                    Text("补齐缺失媒体（" + missingMedia + "）", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            SwitchRow(
                                label = "包含媒体（图片 / 视频 / 语音）",
                                checked = includeMedia,
                                enabled = paneActive && !transfer.running,
                                onCheckedChange = { includeMedia = it },
                            )
                            SwitchRow(
                                label = "包含译文",
                                checked = includeTranslations,
                                enabled = paneActive && !transfer.running,
                                onCheckedChange = { includeTranslations = it },
                            )
                            RelayDialogButton(
                                style = RelayDialogButtonStyle.Filled,
                                onClick = { startExport() },
                                enabled = paneActive && members.isNotEmpty() && effectiveSelection.isNotEmpty() && !transfer.running,
                                modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalRelayMirrorStyle.current) 48.dp else 44.dp),
                            ) {
                                Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("导出 .zip", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(
                                text = "增量合并：重复条目自动跳过。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SwitchRow(
                                label = "导入媒体（图片 / 视频 / 语音）",
                                checked = importMedia,
                                enabled = paneActive && !transfer.running,
                                onCheckedChange = { importMedia = it },
                            )
                            SwitchRow(
                                label = "导入成员目录（期别 / 头像）",
                                checked = importMembers,
                                enabled = paneActive && !transfer.running,
                                onCheckedChange = { importMembers = it },
                            )
                            RelayDialogButton(
                                style = RelayDialogButtonStyle.Filled,
                                onClick = { openDocument.launch(arrayOf("*/*")) },
                                enabled = paneActive && !transfer.running,
                                modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalRelayMirrorStyle.current) 48.dp else 44.dp),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text("选择 .zip 文件导入", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            if (transfer.running || transfer.error != null || transfer.outcome != null) {
                Spacer(Modifier.height(12.dp))
                TransferStatusCard(
                    transfer = transfer,
                    onCancel = { DataTransferManager.cancel() },
                )
            }
        }
    }

    if (showPicker) {
        TransferMemberPickerDialog(
            title = "选择导出成员",
            members = members,
            selectedIds = effectiveSelection,
            onDismiss = { showPicker = false },
            onConfirm = { picked ->
                selectedIds = picked.takeUnless { it == allIds }
                showPicker = false
            },
        )
    }

    val pendingPreview = preview
    val pendingUri = pendingImportUri
    if (showImportPicker && pendingPreview != null) {
        // 复用导出区块的成员筛选界面：成员来自归档清单，默认全选。
        TransferMemberPickerDialog(
            title = "选择导入成员",
            members = previewMembers,
            selectedIds = effectiveImportSelection,
            onDismiss = { showImportPicker = false },
            onConfirm = { picked ->
                importSelectedIds = picked.takeUnless { it == importAllIds }
                showImportPicker = false
            },
        )
    } else if (pendingPreview != null && pendingUri != null) {
        AlertDialog(
            onDismissRequest = {
                preview = null
                pendingImportUri = null
                importSelectedIds = null
                showImportPicker = false
            },
            shape = if (LocalRelayMirrorStyle.current) RelayHomeCardShape else RoundedCornerShape(20.dp),
            title = { Text("确认导入", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("内容类型：" + pendingPreview.kind.label)
                    Text("导出时间：" + ExportFormat.displayTimestamp(pendingPreview.exportedAt))
                    Text("包含媒体：" + if (pendingPreview.includesMedia) "是" else "否")
                    Text("包含译文：" + if (pendingPreview.includesTranslations) "是" else "否")
                    if (previewMembers.isNotEmpty()) {
                        MemberSelectionField(
                            title = "导入成员",
                            summary = "${effectiveImportSelection.size} / ${previewMembers.size}",
                            enabled = !transfer.running,
                            onClick = { showImportPicker = true },
                        )
                    } else {
                        Text("成员：" + pendingPreview.members.size + " 位")
                    }
                    if (pendingPreview.formatVersion < ExportFormat.FORMAT_VERSION) {
                        Text(
                            text = "该归档为旧格式（v" + pendingPreview.formatVersion + "），将按当前规则合并。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            },
            confirmButton = {
                RelayDialogButton(
                    onClick = {
                        DataTransferManager.importArchive(
                            context,
                            pendingUri,
                            ImportOptions(
                                importMedia = importMedia,
                                importMembers = importMembers,
                                // 全选（或归档没有成员清单）＝ null，表示不按成员过滤。
                                memberIds = effectiveImportSelection.takeUnless {
                                    previewMembers.isEmpty() || it == importAllIds
                                },
                            ),
                        )
                        preview = null
                        pendingImportUri = null
                        importSelectedIds = null
                        showImportPicker = false
                    },
                ) { Text("开始导入", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                RelayDialogButton(
                    onClick = {
                        preview = null
                        pendingImportUri = null
                        importSelectedIds = null
                        showImportPicker = false
                    },
                ) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ExportEstimateStatus(
    estimate: ExportEstimate?,
    progressFlow: StateFlow<Pair<Int, Int>?>,
    includeMedia: Boolean,
    backfilling: Boolean,
    active: Boolean,
    failure: String?,
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    // Starting progress must not change the sheet anchor and cancel its own scan.
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val current = estimate
        if (current == null) {
            if (backfilling) {
                // 正在下载缺失媒体，这里不再触发全库重扫，等补齐结束后再统计。
                Text(
                    text = "补齐媒体中，完成后重新统计",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (failure != null) {
                Text(failure, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            } else if (!active) {
                Text("正在准备统计…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val scanned = progress?.first ?: 0
                val count = progress?.second ?: 0
                if (count > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (scanned.toFloat() / count.toFloat()).coerceIn(0f, 1f)
                        },
                        // 去掉轨道最右端的紫色端点圆点，统计进度条只保留进度本身。
                        drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Text(
                    text = if (count > 0) {
                        "正在统计 " + scanned + " / " + count
                    } else {
                        "正在统计..."
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        } else {
            Text(
                text = current.records.toString() + " 条记录" +
                    if (includeMedia) mediaBreakdown(current) else " · 不含媒体",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun MemberSelectionField(
    title: String,
    summary: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
    if (LocalRelayMirrorStyle.current) {
        RelayDialogButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            style = RelayDialogButtonStyle.Outlined,
            content = content,
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    RelayDialogCard(
        border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (LocalRelayMirrorStyle.current) RelayCardContentInset else 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        if (LocalRelayMirrorStyle.current) {
            RelayMirrorGlassSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, label = label)
        } else {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun TransferStatusCard(
    transfer: com.nogirelay.app.data.transfer.TransferState,
    onCancel: () -> Unit,
) {
    RelayDialogCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(if (LocalRelayMirrorStyle.current) RelayCardContentInset else 16.dp)) {
            when {
                transfer.running -> {
                    Text(transfer.phase.ifBlank { "处理中" }, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    if (transfer.total > 0) {
                        LinearProgressIndicator(
                            progress = { transfer.done.toFloat() / transfer.total.toFloat() },
                            // 与统计进度条一致：不显示轨道末端的端点圆点。
                            drawStopIndicator = {},
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (transfer.total > 0) {
                            transfer.done.toString() + " / " + transfer.total
                        } else {
                            "已处理 " + transfer.done + " 条"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    // 与这个抽屉中所有其他操作一样占满整宽：Material 默认的按钮
                    // 内容内边距会把标签挤到卡片的内容边界之外。
                    RelayDialogButton(
                        style = RelayDialogButtonStyle.Outlined,
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth().heightIn(min = if (LocalRelayMirrorStyle.current) 48.dp else 44.dp),
                    ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                transfer.error != null -> {
                    Text(
                        text = transfer.error,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
                else -> {
                    when (val outcome = transfer.outcome) {
                        is TransferOutcome.Export -> {
                            Text("导出完成", fontWeight = FontWeight.Bold, color = BrandPurple)
                            Spacer(Modifier.height(6.dp))
                            Text(outcome.report.outputName, fontSize = 12.sp)
                            Text(
                                text = outcome.report.recordCount.toString() + " 条记录 · " +
                                    outcome.report.mediaCount + " 个媒体（" +
                                    formatBytes(outcome.report.mediaBytes) + "）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (outcome.report.skippedCount > 0) {
                                Text(
                                    text = outcome.report.skippedCount.toString() + " 个媒体未缓存，未写入归档",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is TransferOutcome.Import -> {
                            Text("导入完成", fontWeight = FontWeight.Bold, color = BrandPurple)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "消息新增 " + outcome.report.inserted + " · 重复跳过 " +
                                    outcome.report.duplicates + " · 译文新增 " +
                                    outcome.report.translationsBackfilled + " · 链接刷新 " +
                                    outcome.report.linksRefreshed,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "媒体新增 " + outcome.report.mediaStored +
                                    "（" + formatBytes(outcome.report.mediaBytes) + "）" +
                                    " · 媒体重复 " + outcome.report.mediaReused +
                                    " · 失败 " + outcome.report.mediaFailed,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (outcome.report.mediaAdopted > 0) {
                                Text(
                                    text = "缓存迁移 " + outcome.report.mediaAdopted +
                                        " 个（沿用旧主机已下载的图片，无需重新联网）",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (outcome.report.membersMerged > 0) {
                                Text(
                                    text = "成员目录合并 " + outcome.report.membersMerged + " 位",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (outcome.report.invalid > 0) {
                                Text(
                                    text = "无法解析的记录 " + outcome.report.invalid + " 条",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            outcome.report.errors.forEach { message ->
                                Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        is TransferOutcome.Backfill -> {
                            Text("补齐完成", fontWeight = FontWeight.Bold, color = BrandPurple)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "新下载 " + outcome.report.downloaded +
                                    "（" + formatBytes(outcome.report.bytes) + "）" +
                                    " · 已有 " + outcome.report.reused +
                                    " · 跳过 " + outcome.report.notFound +
                                    " · 失败 " + outcome.report.failed,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            outcome.report.errors.forEach { message ->
                                Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index += 1
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}

private fun mediaRoleLabel(role: String): String = when (role) {
    "media" -> "主媒体"
    "phone_image" -> "来电写真"
    "cover" -> "封面"
    "body" -> "正文图"
    else -> role
}

/** 一行紧凑文本：按角色给出已缓存/已引用的数量，以及已在磁盘上的大小。 */
private fun mediaBreakdown(estimate: ExportEstimate): String {
    if (estimate.byRole.isEmpty()) return ""
    val parts = estimate.byRole.joinToString(" · ") { stat ->
        mediaRoleLabel(stat.role) + " " + stat.cached + "/" + stat.referenced
    }
    val size = if (estimate.mediaBytes > 0L) "（" + formatBytes(estimate.mediaBytes) + "）" else ""
    return " · " + parts + size
}
