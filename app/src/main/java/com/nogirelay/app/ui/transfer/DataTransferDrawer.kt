package com.nogirelay.app.ui.transfer

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nogirelay.app.data.AppGraph
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.MediaRefKind
import com.nogirelay.app.data.transfer.DataExporter
import com.nogirelay.app.data.transfer.DataImporter
import com.nogirelay.app.data.transfer.DataTransferManager
import com.nogirelay.app.data.transfer.ExportEstimate
import com.nogirelay.app.data.transfer.ExportEstimateCache
import com.nogirelay.app.data.transfer.ExportEstimateKey
import com.nogirelay.app.data.transfer.ExportEstimateProgress
import com.nogirelay.app.data.transfer.ExportFormat
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.data.transfer.ExportRequest
import com.nogirelay.app.data.transfer.ImportOptions
import com.nogirelay.app.data.transfer.ImportPreview
import com.nogirelay.app.media.MediaCacheRevision
import com.nogirelay.app.data.transfer.TransferOperation
import com.nogirelay.app.data.transfer.TransferOutcome
import com.nogirelay.app.ui.RelaySheetBackdropState
import com.nogirelay.app.ui.glass.GlassBottomSheet
import com.nogirelay.app.ui.glass.GlassCapsuleButton
import com.nogirelay.app.ui.glass.GlassColors
import com.nogirelay.app.ui.glass.GlassDepths
import com.nogirelay.app.ui.glass.GlassDialog
import com.nogirelay.app.ui.glass.GlassDialogText
import com.nogirelay.app.ui.glass.GlassDialogTitle
import com.nogirelay.app.ui.glass.GlassIconButton
import com.nogirelay.app.ui.glass.GlassLinearProgressIndicator
import com.nogirelay.app.ui.glass.GlassType
import com.nogirelay.app.ui.glass.LocalGlassBlurEnabled
import com.nogirelay.app.ui.glass.GlassPanel
import com.nogirelay.app.ui.glass.GlassSegmentedTabs
import com.nogirelay.app.ui.glass.GlassShapes
import com.nogirelay.app.ui.glass.GlassSwitch
import com.nogirelay.app.ui.glass.GlassTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EXPORT_ESTIMATE_PREFS = "export_estimate_cache"
private const val EXPORT_ESTIMATE_ENTRIES = "entries"

/**
 * Data management (export / import) as a glass sheet: capsule segmented
 * tabs, glass rows, and the same liquid physics as the rest of the app.
 */
@Composable
fun DataTransferDrawer(
    kind: ExportKind,
    backdropState: RelaySheetBackdropState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val exportEstimateCache = remember(context) {
        val prefs = context.applicationContext.getSharedPreferences(EXPORT_ESTIMATE_PREFS, Context.MODE_PRIVATE)
        ExportEstimateCache(
            loadSerialized = { prefs.getString(EXPORT_ESTIMATE_ENTRIES, null) },
            saveSerialized = { serialized ->
                prefs.edit().putString(EXPORT_ESTIMATE_ENTRIES, serialized).apply()
            },
        )
    }
    val scope = rememberCoroutineScope()
    val transferFlow = remember(kind) { DataTransferManager.stateFor(kind) }
    // Progress belongs to the status card, not the entire sheet. In particular,
    // reopening a running transfer must start with its real state on frame one.
    val transferPresentation = remember(transferFlow) {
        transferFlow.map { it.copy(done = 0, total = 0) }.distinctUntilChanged()
    }
    val transfer by transferPresentation.collectAsStateWithLifecycle(
        initialValue = transferFlow.value.copy(done = 0, total = 0),
    )
    val anyTransferRunning by remember {
        DataTransferManager.state
            .map { it.running }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = DataTransferManager.isRunning())

    var members by remember(kind) { mutableStateOf<List<BlogMember>>(emptyList()) }
    var membersLoaded by remember(kind) { mutableStateOf(false) }
    var selectedIds by remember(kind) { mutableStateOf<Set<String>?>(null) }
    var includeMedia by remember(kind) { mutableStateOf(true) }
    var includeTranslations by remember(kind) { mutableStateOf(true) }
    var importMedia by remember(kind) { mutableStateOf(true) }
    var importMembers by remember(kind) { mutableStateOf(true) }
    var showPicker by remember(kind) { mutableStateOf(false) }
    var pendingExport by remember(kind) { mutableStateOf<ExportRequest?>(null) }
    var estimateResult by remember(kind) { mutableStateOf<Pair<ExportEstimateKey, ExportEstimate>?>(null) }
    var estimateFailure by remember(kind) { mutableStateOf<String?>(null) }

    var preview by remember(kind) { mutableStateOf<ImportPreview?>(null) }
    var pendingImportUri by remember(kind) { mutableStateOf<Uri?>(null) }

    var importSelectedIds by remember(kind) { mutableStateOf<Set<String>?>(null) }
    var showImportPicker by remember(kind) { mutableStateOf(false) }
    var tabIndex by remember(kind) { mutableIntStateOf(0) }
    val uiStarted = com.nogirelay.app.performance.isRelayUiStarted()
    val contentFlow = remember(kind) {
        AppGraph.invalidation.versions.map { if (kind == ExportKind.MESSAGES) it.messageStructure else it.blogContent }
            .distinctUntilChanged()
    }
    val initialContentRevision = remember(kind) {
        AppGraph.invalidation.versions.value.let { if (kind == ExportKind.MESSAGES) it.messageStructure else it.blogContent }
    }
    val contentRevision by contentFlow.collectAsStateWithLifecycle(initialValue = initialContentRevision)
    val estimateContentFlow = remember(kind) {
        AppGraph.invalidation.exportVersions.map { if (kind == ExportKind.MESSAGES) it.messages else it.blogContent }
            .distinctUntilChanged()
    }
    val initialEstimateContentRevision = remember(kind) {
        AppGraph.invalidation.exportVersions.value.let {
            if (kind == ExportKind.MESSAGES) it.messages else it.blogContent
        }
    }
    val estimateContentRevision by estimateContentFlow.collectAsStateWithLifecycle(
        initialValue = initialEstimateContentRevision,
    )
    val scopedMediaRevision = remember(kind) {
        MediaCacheRevision.changesFor(
            if (kind == ExportKind.MESSAGES) MediaRefKind.MESSAGES else MediaRefKind.BLOGS,
        )
    }
    val mediaFlow = remember(includeMedia, scopedMediaRevision) {
        if (includeMedia) scopedMediaRevision else flowOf(0L)
    }
    val initialMediaRevision = remember(includeMedia, scopedMediaRevision) {
        if (includeMedia) scopedMediaRevision.value else 0L
    }
    val mediaRevision by mediaFlow.collectAsStateWithLifecycle(initialValue = initialMediaRevision)

    LaunchedEffect(kind, contentRevision, backdropState.isSettled, uiStarted) {
        if (!backdropState.isSettled || !uiStarted) return@LaunchedEffect
        membersLoaded = false
        members = withContext(Dispatchers.IO) {
            when (kind) {
                ExportKind.MESSAGES -> AppGraph.messages.messageExportMembers().map {
                    BlogMember(
                        id = it.memberKey,
                        name = it.name,
                        category = it.category,
                        avatarUrl = it.avatarUrl,
                        displayOrder = it.displayOrder,
                    )
                }
                ExportKind.BLOGS -> AppGraph.blogMembers.blogMembers()
            }
        }
        membersLoaded = true
    }

    val allIds = remember(members) { members.mapTo(linkedSetOf(), BlogMember::id) }
    val effectiveSelection = selectedIds ?: allIds

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
    val estimateRequest = remember(kind, effectiveSelection, includeMedia, estimateContentRevision, mediaRevision) {
        ExportEstimateKey(kind, effectiveSelection.toSet(), includeMedia, estimateContentRevision, mediaRevision)
    }
    val cachedEstimate = remember(estimateRequest) { exportEstimateCache.get(estimateRequest) }
    val estimateProgress = remember(estimateRequest) {
        MutableStateFlow<ExportEstimateProgress?>(cachedEstimate?.scanProgress)
    }
    var estimateFromCache by remember(estimateRequest) { mutableStateOf(cachedEstimate != null) }
    var estimateRun by remember(estimateRequest) { mutableIntStateOf(0) }
    val estimate = estimateResult?.takeIf { it.first == estimateRequest }?.second
    val transferBusy = transfer.running
    // Draw preparation while the sheet enters. The database scan itself still
    // waits until the sheet stops moving.
    val estimatePresentationActive = tabIndex == 0 && !transferBusy &&
        !showPicker && !showImportPicker && (!membersLoaded || effectiveSelection.isNotEmpty())
    val estimateActive = backdropState.isSettled && uiStarted && estimatePresentationActive

    LaunchedEffect(estimateRequest, estimateActive) {
        if (!estimateActive || effectiveSelection.isEmpty()) return@LaunchedEffect
        estimateFailure = null
        estimateRun += 1
        estimateResult = null
        exportEstimateCache.get(estimateRequest)?.let {
            estimateProgress.value = it.scanProgress
            estimateFromCache = true
            estimateResult = estimateRequest to it
            return@LaunchedEffect
        }
        estimateProgress.value = null
        estimateFromCache = false
        var completed = false
        try {
            val result = withContext(Dispatchers.IO) {
                DataExporter.estimate(context, kind, effectiveSelection, includeMedia) { progress ->
                    estimateProgress.value = progress
                }
            }

            val latest = AppGraph.invalidation.exportVersions.value
            val currentContent = if (kind == ExportKind.MESSAGES) latest.messages else latest.blogContent
            if (currentContent == estimateRequest.contentRevision &&
                (!includeMedia || scopedMediaRevision.value == estimateRequest.mediaRevision)) {
                exportEstimateCache.put(estimateRequest, result)
                estimateResult = estimateRequest to result
                completed = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            estimateFailure = error.message ?: "统计失败"
        } finally {
            // Keep the last real report until the UI has presented it. Clearing
            // it here could conflate a fast scan's 100% report away entirely.
            if (!completed) estimateProgress.value = null
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

    LaunchedEffect(kind) { DataTransferManager.clearResult(kind) }

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

    GlassBottomSheet(
        onDismissRequest = onDismiss,
        backdropState = backdropState,
    ) { dismiss, handle ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            handle()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "数据管理 · " + kind.label,
                        style = GlassType.Title2,
                        fontWeight = FontWeight.Bold,
                        color = GlassColors.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassIconButton(
                    onClick = { dismiss(onDismiss) },
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭数据管理",
                )
            }

            GlassSegmentedTabs(
                labels = listOf("导出", "导入"),
                selectedIndex = tabIndex,
                onSelected = { tabIndex = it },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            SectionPanel {
                AnimatedContent(
                    targetState = tabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.TopStart,
                    transitionSpec = {
                        val direction = if (targetState > initialState) 1 else -1
                        (
                            fadeIn(tween(durationMillis = 180, delayMillis = 40)) +
                                slideInHorizontally(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) { width -> (width / 16) * direction }
                            ).togetherWith(
                            fadeOut(tween(durationMillis = 120)) +
                                slideOutHorizontally(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium)) { width -> (-width / 16) * direction },
                        ).using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ ->
                            tween(280, easing = FastOutSlowInEasing)
                        }))
                    },
                    label = "transfer_tab_content",
                ) { contentTab ->
                    val paneActive = contentTab == tabIndex
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (paneActive) Modifier else Modifier.clearAndSetSemantics {}),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (contentTab == 0) {
                            MemberSelectionField(
                                title = "导出成员",
                                summary = "${effectiveSelection.size} / ${members.size}",
                                enabled = paneActive && !transferBusy,
                                onClick = { showPicker = true },
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                ExportEstimateStatus(
                                    estimate, estimateProgress, includeMedia, backfilling,
                                    estimatePresentationActive && paneActive,
                                    estimateFailure,
                                    estimateRequest,
                                    estimateRun,
                                    fromCache = estimateFromCache,
                                    backfillEnabled = paneActive && !anyTransferRunning,
                                    onBackfill = { displayedEstimate ->
                                        DataTransferManager.backfillMedia(context, kind, displayedEstimate.missing)
                                    },
                                )
                                SwitchRow(
                                    label = "包含媒体（图片 / 视频 / 语音）",
                                    checked = includeMedia,
                                    enabled = paneActive && !transferBusy,
                                    onCheckedChange = { includeMedia = it },
                                )
                            }
                            SwitchRow(
                                label = "包含译文",
                                checked = includeTranslations,
                                enabled = paneActive && !transferBusy,
                                onCheckedChange = { includeTranslations = it },
                            )
                        } else {
                            Text(
                                text = "增量合并：重复条目自动跳过。",
                                style = GlassType.Footnote,
                                color = GlassColors.InkSecondary,
                            )
                            SwitchRow(
                                label = "导入媒体（图片 / 视频 / 语音）",
                                checked = importMedia,
                                enabled = paneActive && !transferBusy,
                                onCheckedChange = { importMedia = it },
                            )
                            SwitchRow(
                                label = "导入成员目录（期别 / 头像）",
                                checked = importMembers,
                                enabled = paneActive && !transferBusy,
                                onCheckedChange = { importMembers = it },
                            )
                        }
                    }
                }
                // Keep the action's shadow outside the resizing form viewport.
                GlassCapsuleButton(
                    onClick = {
                        if (tabIndex == 0) startExport()
                        else openDocument.launch(arrayOf("*/*"))
                    },
                    enabled = !anyTransferRunning &&
                        (tabIndex != 0 || (members.isNotEmpty() && effectiveSelection.isNotEmpty())),
                    tone = GlassTone.Accent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        if (tabIndex == 0) Icons.Rounded.Upload else Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (tabIndex == 0) "导出 .zip" else "选择 .zip 文件导入",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AnimatedVisibility(
                visible = transfer.running || transfer.error != null || transfer.outcome != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enter = fadeIn(tween(180)) + expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ) + slideInVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ) { it / 5 },
                exit = fadeOut(tween(120)) + shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(180),
                ),
            ) {
                // Haze captures the expanding layout as a rectangular layer. Keep
                // this animated status card on the clipped fallback surface so
                // the capture bounds cannot appear as a gray frame.
                Box(Modifier.fillMaxWidth().clip(GlassShapes.Card)) {
                    CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                        TransferStatusCard(
                            transferFlow = transferFlow,
                            onCancel = { DataTransferManager.cancel(kind) },
                        )
                    }
                }
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
        GlassDialog(
            onDismissRequest = {
                preview = null
                pendingImportUri = null
                importSelectedIds = null
                showImportPicker = false
            },
        ) {
            GlassDialogTitle("确认导入")
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                GlassDialogText("内容类型：" + pendingPreview.kind.label)
                GlassDialogText("导出时间：" + ExportFormat.displayTimestamp(pendingPreview.exportedAt))
                GlassDialogText("包含媒体：" + if (pendingPreview.includesMedia) "是" else "否")
                GlassDialogText("包含译文：" + if (pendingPreview.includesTranslations) "是" else "否")
                if (previewMembers.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    MemberSelectionField(
                        title = "导入成员",
                        summary = "${effectiveImportSelection.size} / ${previewMembers.size}",
                        enabled = !transferBusy,
                        onClick = { showImportPicker = true },
                    )
                } else {
                    GlassDialogText("成员：" + pendingPreview.members.size + " 位")
                }
                if (pendingPreview.formatVersion < ExportFormat.FORMAT_VERSION) {
                    GlassDialogText("该归档为旧格式（v" + pendingPreview.formatVersion + "），将按当前规则合并。")
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassCapsuleButton(
                    onClick = {
                        preview = null
                        pendingImportUri = null
                        importSelectedIds = null
                        showImportPicker = false
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                }
                GlassCapsuleButton(
                    onClick = {
                        DataTransferManager.importArchive(
                            context,
                            pendingUri,
                            ImportOptions(
                                importMedia = importMedia,
                                importMembers = importMembers,
                                memberIds = effectiveImportSelection.takeUnless {
                                    previewMembers.isEmpty() || it == importAllIds
                                },
                            ),
                            kind = pendingPreview.kind,
                        )
                        preview = null
                        pendingImportUri = null
                        importSelectedIds = null
                        showImportPicker = false
                    },
                    tone = GlassTone.Accent,
                    enabled = !anyTransferRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("开始导入", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun ExportEstimateStatus(
    estimate: ExportEstimate?,
    progressFlow: StateFlow<ExportEstimateProgress?>,
    includeMedia: Boolean,
    backfilling: Boolean,
    active: Boolean,
    failure: String?,
    requestKey: ExportEstimateKey,
    run: Int,
    fromCache: Boolean,
    backfillEnabled: Boolean,
    onBackfill: (ExportEstimate) -> Unit,
) {
    val progress by progressFlow.collectAsStateWithLifecycle()
    val completed = estimate
    // Keep the scan's own progress visible through its minimum presentation
    // time, then reveal the result without a separate completion fill.
    var resultVisible by remember(run) { mutableStateOf(false) }
    val displayedEstimate = completed.takeIf { resultVisible && !backfilling }
    Column(
        Modifier.fillMaxWidth()
            .then(if (displayedEstimate == null) Modifier.heightIn(min = 40.dp) else Modifier),
    ) {
        if (displayedEstimate != null) {
            // The backfill action replaces the progress directly. Keeping this
            // out of an AnimatedContent layer prevents a transient gray frame.
            ExportEstimateSummary(
                displayedEstimate, includeMedia,
                backfillEnabled && displayedEstimate === estimate, onBackfill,
            )
        } else {
            AnimatedVisibility(
                visible = !backfilling && failure == null && (completed != null || active),
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(120)),
            ) {
                Column {
                    val phase = progress?.phase
                    GlassLinearProgressIndicator(
                        progress = progress?.fraction,
                        animationKey = if (phase == null) {
                            "preparing"
                        } else {
                            run to phase
                        },
                        minimumDurationMillis = 600,
                        onCompleted = if (completed != null) ({ resultVisible = true }) else null,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Text(
                        text = if (fromCache) "正在载入统计结果…" else estimateProgressText(progress),
                        style = GlassType.Footnote,
                        color = GlassColors.InkSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (backfilling) {
                Text(
                    text = "补齐媒体中，完成后重新统计",
                    style = GlassType.Footnote,
                    color = GlassColors.InkSecondary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else if (failure != null) {
                Text(failure, color = GlassColors.Danger, style = GlassType.Footnote)
            }
        }
    }
}

@Composable
private fun ExportEstimateSummary(
    estimate: ExportEstimate,
    includeMedia: Boolean,
    backfillEnabled: Boolean,
    onBackfill: (ExportEstimate) -> Unit,
) {
    val missingCount = estimate.missing.size
    val buttonVisible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    var displayedMissingCount by remember { mutableIntStateOf(missingCount) }
    LaunchedEffect(missingCount) {
        if (missingCount > 0) displayedMissingCount = missingCount
        buttonVisible.targetState = missingCount > 0
    }
    Text(
        text = estimate.records.toString() + " 条记录" +
            if (includeMedia) mediaBreakdown(estimate) else " · 不含媒体",
        style = GlassType.Footnote,
        color = GlassColors.InkSecondary,
        modifier = Modifier.padding(top = 2.dp),
    )
    if (missingCount > 0 || buttonVisible.currentState || buttonVisible.targetState) {
        Spacer(Modifier.height(8.dp))
        AnimatedVisibility(
            visibleState = buttonVisible,
            enter = fadeIn(tween(180)) + expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ),
            exit = fadeOut(tween(120)) + shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(160),
            ),
        ) {
            CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                GlassCapsuleButton(
                    enabled = backfillEnabled,
                    onClick = { onBackfill(estimate) },
                    tone = GlassTone.Accent,
                    depth = GlassDepths.None,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("补齐缺失媒体（$displayedMissingCount）", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
    GlassPanel(
        onClick = onClick,
        onClickLabel = title,
        shape = GlassShapes.CardSmall,
        depth = GlassDepths.None,
        fillAlpha = 0.30f,
        blur = 12.dp,
        edgeStrength = 0.5f,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                title,
                style = GlassType.Callout,
                fontWeight = FontWeight.Medium,
                color = GlassColors.Ink.copy(alpha = if (enabled) 1f else 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                summary,
                style = GlassType.Subhead,
                color = GlassColors.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = GlassColors.InkTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionPanel(content: @Composable ColumnScope.() -> Unit) {
    GlassPanel(
        shape = GlassShapes.Card,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
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
        Text(
            label,
            style = GlassType.Callout,
            color = GlassColors.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, label = label)
    }
}

@Composable
private fun TransferStatusCard(
    transferFlow: StateFlow<com.nogirelay.app.data.transfer.TransferState>,
    onCancel: () -> Unit,
) {
    val transfer = transferFlow.collectAsStateWithLifecycle().value
    GlassPanel(
        shape = GlassShapes.Card,
        depth = GlassDepths.None,
        shadowAlpha = 0f,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            when {
                transfer.running -> {
                    Text(transfer.phase.ifBlank { "处理中" }, fontWeight = FontWeight.Medium, color = GlassColors.Ink)
                    Spacer(Modifier.height(10.dp))
                    GlassLinearProgressIndicator(
                        progress = if (transfer.total > 0) transfer.done.toFloat() / transfer.total else null,
                        animationKey = transfer.operation to transfer.phase,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (transfer.total > 0) {
                            transfer.done.toString() + " / " + transfer.total
                        } else {
                            "已处理 " + transfer.done + " 条"
                        },
                        color = GlassColors.InkSecondary,
                        style = GlassType.Footnote,
                    )
                    Spacer(Modifier.height(12.dp))

                    GlassCapsuleButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                transfer.error != null -> {
                    Text(
                        text = transfer.error,
                        color = GlassColors.Danger,
                        fontWeight = FontWeight.Medium,
                    )
                }
                else -> {
                    when (val outcome = transfer.outcome) {
                        is TransferOutcome.Export -> {
                            Text("导出完成", fontWeight = FontWeight.Bold, color = GlassColors.Accent)
                            Spacer(Modifier.height(6.dp))
                            Text(outcome.report.outputName, style = GlassType.Footnote, color = GlassColors.Ink)
                            Text(
                                text = outcome.report.recordCount.toString() + " 条记录 · " +
                                    outcome.report.mediaCount + " 个媒体（" +
                                    formatBytes(outcome.report.mediaBytes) + "）",
                                style = GlassType.Footnote,
                                color = GlassColors.InkSecondary,
                            )
                            if (outcome.report.skippedCount > 0) {
                                Text(
                                    text = outcome.report.skippedCount.toString() + " 个媒体未缓存，未写入归档",
                                    style = GlassType.Footnote,
                                    color = GlassColors.InkSecondary,
                                )
                            }
                        }
                        is TransferOutcome.Backfill -> {
                            Text("补齐完成", fontWeight = FontWeight.Bold, color = GlassColors.Accent)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "新下载 " + outcome.report.downloaded +
                                    "（" + formatBytes(outcome.report.bytes) + "）" +
                                    " · 已有 " + outcome.report.reused +
                                    " · 跳过 " + outcome.report.notFound +
                                    " · 失败 " + outcome.report.failed,
                                style = GlassType.Footnote,
                                color = GlassColors.InkSecondary,
                            )
                            outcome.report.errors.forEach { message ->
                                Text(
                                    text = message,
                                    style = GlassType.Footnote,
                                    color = GlassColors.Danger,
                                )
                            }
                        }
                        is TransferOutcome.Import -> {
                            Text("导入完成", fontWeight = FontWeight.Bold, color = GlassColors.Accent)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "消息新增 " + outcome.report.inserted + " · 重复跳过 " +
                                    outcome.report.duplicates + " · 译文新增 " +
                                    outcome.report.translationsBackfilled + " · 链接刷新 " +
                                    outcome.report.linksRefreshed,
                                style = GlassType.Footnote,
                                color = GlassColors.InkSecondary,
                            )
                            Text(
                                text = "媒体新增 " + outcome.report.mediaStored +
                                    "（" + formatBytes(outcome.report.mediaBytes) + "）" +
                                    " · 媒体重复 " + outcome.report.mediaReused +
                                    " · 失败 " + outcome.report.mediaFailed,
                                style = GlassType.Footnote,
                                color = GlassColors.InkSecondary,
                            )
                        }
                        null -> Unit
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun mediaBreakdown(estimate: ExportEstimate): String {
    if (estimate.byRole.isEmpty()) return ""
    val parts = estimate.byRole.joinToString(" · ") { stat ->
        mediaRoleLabel(stat.role) + " " + stat.cached + "/" + stat.referenced
    }
    val size = if (estimate.mediaBytes > 0L) "（" + formatBytes(estimate.mediaBytes) + "）" else ""
    return " · " + parts + size
}


