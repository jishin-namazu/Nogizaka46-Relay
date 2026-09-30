package com.nogirelay.app.data.transfer

import android.content.Context
import android.net.Uri
import com.nogirelay.app.data.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TransferOutcome {
    data class Export(val report: ExportReport) : TransferOutcome
    data class Import(val report: ImportReport) : TransferOutcome
    data class Backfill(val report: MediaBackfillReport) : TransferOutcome
}

enum class TransferOperation { EXPORT, IMPORT, BACKFILL }

data class TransferState(
    val running: Boolean = false,
    val kind: ExportKind? = null,
    val operation: TransferOperation? = null,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val outcome: TransferOutcome? = null,
    val error: String? = null,
)

object DataTransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = _state.asStateFlow()
    private var job: Job? = null

    fun isRunning(): Boolean = _state.value.running

    fun clearResult() {
        if (_state.value.running) return
        _state.value = TransferState()
    }

    fun export(context: Context, request: ExportRequest, outputUri: Uri) {
        if (isRunning()) return
        val appContext = context.applicationContext
        _state.value = TransferState(
            running = true,
            kind = request.kind,
            operation = TransferOperation.EXPORT,
            phase = "准备导出",
        )
        job = scope.launch {
            try {
                val report = DataExporter.export(appContext, request, outputUri) { phase, done, total ->
                    _state.value = _state.value.copy(phase = phase, done = done, total = total)
                }
                _state.value = TransferState(
                    kind = request.kind,
                    operation = TransferOperation.EXPORT,
                    outcome = TransferOutcome.Export(report),
                )
            } catch (cancelled: CancellationException) {

                _state.value = TransferState(kind = request.kind)
            } catch (error: Throwable) {
                _state.value = TransferState(kind = request.kind, error = error.message ?: "导出失败")
            }
        }
    }

    fun importArchive(context: Context, sourceUri: Uri, options: ImportOptions) {
        if (isRunning()) return
        val appContext = context.applicationContext
        _state.value = TransferState(running = true, operation = TransferOperation.IMPORT, phase = "读取清单")
        job = scope.launch {
            try {
                val report = DataImporter.importArchive(appContext, sourceUri, options) { phase, done, total ->
                    _state.value = _state.value.copy(phase = phase, done = done, total = total)
                }
                _state.value = TransferState(
                    kind = report.kind,
                    operation = TransferOperation.IMPORT,
                    outcome = TransferOutcome.Import(report),
                )
            } catch (cancelled: CancellationException) {
                _state.value = TransferState()
            } catch (error: Throwable) {
                _state.value = TransferState(error = error.message ?: "导入失败")
            } finally {

                AppGraph.notifyDataChanged(com.nogirelay.app.data.DataChange.CONTENT)
            }
        }
    }

    fun backfillMedia(context: Context, kind: ExportKind, candidates: List<MediaCandidate>) {
        if (isRunning() || candidates.isEmpty()) return
        val appContext = context.applicationContext
        _state.value = TransferState(
            running = true,
            kind = kind,
            operation = TransferOperation.BACKFILL,
            phase = "补齐媒体",
            total = candidates.size,
        )

        val foregroundStarted = runCatching { MediaBackfillService.start(appContext) }.isSuccess
        if (!foregroundStarted) {
            _state.value = TransferState(kind = kind, error = "无法启动后台下载服务")
            return
        }
        job = scope.launch {
            try {
                val report = MediaBackfill.download(appContext, candidates) { phase, done, total ->
                    _state.value = _state.value.copy(running = true, phase = phase, done = done, total = total)
                }
                _state.value = TransferState(
                    kind = kind,
                    operation = TransferOperation.BACKFILL,
                    outcome = TransferOutcome.Backfill(report),
                )
            } catch (cancelled: CancellationException) {
                _state.value = TransferState(kind = kind)
            } catch (error: Throwable) {
                _state.value = TransferState(kind = kind, error = error.message ?: "补齐媒体失败")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
