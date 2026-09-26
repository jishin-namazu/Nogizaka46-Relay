package com.nogirelay.app.ui.transfer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
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
import com.nogirelay.app.ui.RelayCardShape
import com.nogirelay.app.ui.RelayControlShape
import com.nogirelay.app.ui.navigation.RelayIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MESSAGES 与 BLOG 界面共用的数据管理抽屉。每个界面各自拥有一个 [kind]，因此导出
 * 部分只提供自己的内容；导入部分与内容无关，会从归档清单中读回
 * 类型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTransferDrawer(
    kind: ExportKind,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val transfer by DataTransferManager.state.collectAsState()

    var members by remember(kind) { mutableStateOf<List<BlogMember>>(emptyList()) }
    var selectedIds by remember(kind) { mutableStateOf<Set<String>?>(null) }
    var includeMedia by remember(kind) { mutableStateOf(true) }
    var includeTranslations by remember(kind) { mutableStateOf(true) }
    var importMedia by remember(kind) { mutableStateOf(true) }
    var importMembers by remember(kind) { mutableStateOf(true) }
    var showPicker by remember(kind) { mutableStateOf(false) }
    var pendingExport by remember(kind) { mutableStateOf<ExportRequest?>(null) }
    var estimate by remember(kind) { mutableStateOf<ExportEstimate?>(null) }
    // 统计跑在 IO 线程，进度经 StateFlow 回传，避免在后台线程写 Compose 状态。
    val estimateProgress = remember(kind) { MutableStateFlow<Pair<Int, Int>?>(null) }
    val progress by estimateProgress.collectAsState()
    var preview by remember(kind) { mutableStateOf<ImportPreview?>(null) }
    var pendingImportUri by remember(kind) { mutableStateOf<Uri?>(null) }
    // 导入成员选择：null = 归档中的全部成员。归档成员只有预览后才知道，所以换一个归档就重置。
    var importSelectedIds by remember(kind) { mutableStateOf<Set<String>?>(null) }
    var showImportPicker by remember(kind) { mutableStateOf(false) }
    var tabIndex by remember(kind) { mutableStateOf(0) }
    var estimateKey by remember(kind) { mutableStateOf(0) }

    LaunchedEffect(kind) {
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

    // 补齐媒体期间不统计导出数据：下载会改变缓存状态。
    // 补齐结束后 transfer.running 变回 false，下面的 estimateKey 会自增，届时再统一统计一次。
    val backfilling = transfer.running && transfer.operation == TransferOperation.BACKFILL

    LaunchedEffect(effectiveSelection, kind, estimateKey, backfilling, includeMedia) {
        if (backfilling) {
            estimateProgress.value = null
            return@LaunchedEffect
        }
        if (effectiveSelection.isEmpty()) {
            estimate = null
            estimateProgress.value = null
            return@LaunchedEffect
        }
        // 重新统计期间先清掉旧结果，界面切到进度条而不是继续显示上一次的数字。
        estimate = null
        estimateProgress.value = 0 to 0
        try {
            estimate = withContext(Dispatchers.IO) {
                runCatching {
                    DataExporter.estimate(context, kind, effectiveSelection, includeMedia) { done, total ->
                        estimateProgress.value = done to total
                    }
                }.getOrNull()
            }
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

        // 一次运行缓存的任何内容都会改变预览，包括中途被取消的运行，所以
        // 每次传输停止运行时都会重新计算统计。
    var transferWasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(transfer.running) {
        if (transferWasRunning && !transfer.running) estimateKey += 1
        transferWasRunning = transfer.running
    }

        // 结果属于产生它的那次运行：重新打开抽屉时会从干净状态开始。仍在进行中的传输
        // 不会被 clearResult() 清除。
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

        // 与主页抽屉一致：先将面板动画收起，再将其从组合中移除。
    fun closeDrawer() {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            ) {
                Column {
                    Text(
                        text = "数据管理 · " + kind.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                RelayIconButton(
                    onClick = { closeDrawer() },
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
                if (tabIndex == 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !transfer.running) { showPicker = true }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "导出成员",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = effectiveSelection.size.toString() + " / " + members.size,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
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
                                        // 统计过程已经遍历过记录并保留了确切的 URL，因此补齐流程
                                        // 直接下载该列表，而不再重新扫描整个库。
                    val missingMedia = estimate?.missing?.size ?: 0
                    if (missingMedia > 0 && !transfer.running) {
                        OutlinedButton(
                            onClick = {
                                estimate?.let { DataTransferManager.backfillMedia(context, kind, it.missing) }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            Text("补齐缺失媒体（" + missingMedia + "）", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    SwitchRow(
                        label = "包含媒体（图片 / 视频 / 语音）",
                        checked = includeMedia,
                        enabled = !transfer.running,
                        onCheckedChange = { includeMedia = it },
                    )
                    SwitchRow(
                        label = "包含译文",
                        checked = includeTranslations,
                        enabled = !transfer.running,
                        onCheckedChange = { includeTranslations = it },
                    )
                    Button(
                        onClick = { startExport() },
                        enabled = members.isNotEmpty() && effectiveSelection.isNotEmpty() && !transfer.running,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("导出 .zip", fontWeight = FontWeight.SemiBold, maxLines = 1)
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
                        enabled = !transfer.running,
                        onCheckedChange = { importMedia = it },
                    )
                    SwitchRow(
                        label = "导入成员目录（期别 / 头像）",
                        checked = importMembers,
                        enabled = !transfer.running,
                        onCheckedChange = { importMembers = it },
                    )
                    Button(
                        onClick = { openDocument.launch(arrayOf("*/*")) },
                        enabled = !transfer.running,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("选择 .zip 文件导入", fontWeight = FontWeight.SemiBold, maxLines = 1)
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
            shape = RoundedCornerShape(20.dp),
            title = { Text("确认导入", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("内容类型：" + pendingPreview.kind.label)
                    Text("导出时间：" + ExportFormat.displayTimestamp(pendingPreview.exportedAt))
                    Text("包含媒体：" + if (pendingPreview.includesMedia) "是" else "否")
                    Text("包含译文：" + if (pendingPreview.includesTranslations) "是" else "否")
                    if (previewMembers.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !transfer.running) { showImportPicker = true }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                text = "导入成员",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = effectiveImportSelection.size.toString() + " / " + previewMembers.size,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
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
                TextButton(
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
                    colors = ButtonDefaults.textButtonColors(contentColor = BrandPurple),
                ) { Text("开始导入", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(
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
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RelayCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, BrandPurple.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
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
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun TransferStatusCard(
    transfer: com.nogirelay.app.data.transfer.TransferState,
    onCancel: () -> Unit,
) {
    Card(
        shape = RelayCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
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
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) { Text("取消", fontWeight = FontWeight.SemiBold, maxLines = 1) }
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
