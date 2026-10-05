package com.nogirelay.app.data.transfer

import android.content.Context
import android.net.Uri
import com.nogirelay.app.media.MediaCacheRevision
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

internal class TransferStateStore {
    private val current = MutableStateFlow(TransferState())
    val state: StateFlow<TransferState> = current.asStateFlow()
    private val scoped = ExportKind.entries.associateWith { MutableStateFlow(TransferState(kind = it)) }
    private val scopedStates = scoped.mapValues { it.value.asStateFlow() }

    fun stateFor(kind: ExportKind): StateFlow<TransferState> = scopedStates.getValue(kind)

    @Synchronized
    fun publish(snapshot: TransferState) {
        val kind = requireNotNull(snapshot.kind)
        scoped.getValue(kind).value = snapshot
        current.value = snapshot
    }

    @Synchronized
    fun progress(kind: ExportKind, phase: String, done: Int, total: Int) {
        val snapshot = current.value
        if (snapshot.kind != kind || !snapshot.running) return
        publish(snapshot.copy(phase = phase, done = done, total = total))
    }

    @Synchronized
    fun clearResult(kind: ExportKind) {
        if (scoped.getValue(kind).value.running) return
        scoped.getValue(kind).value = TransferState(kind = kind)
        if (current.value.kind == kind) current.value = TransferState()
    }
}

object DataTransferManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = TransferStateStore()
    val state: StateFlow<TransferState> = states.state
    private var job: Job? = null

    fun isRunning(): Boolean = state.value.running

    fun stateFor(kind: ExportKind): StateFlow<TransferState> = states.stateFor(kind)

    fun clearResult(kind: ExportKind) = states.clearResult(kind)

    fun export(context: Context, request: ExportRequest, outputUri: Uri) {
        if (isRunning()) return
        val appContext = context.applicationContext
        states.publish(TransferState(
            running = true,
            kind = request.kind,
            operation = TransferOperation.EXPORT,
            phase = "准备导出",
        ))
        job = scope.launch {
            try {
                val report = DataExporter.export(appContext, request, outputUri) { phase, done, total ->
                    states.progress(request.kind, phase, done, total)
                }
                states.publish(TransferState(
                    kind = request.kind,
                    operation = TransferOperation.EXPORT,
                    outcome = TransferOutcome.Export(report),
                ))
            } catch (cancelled: CancellationException) {

                states.publish(TransferState(kind = request.kind))
            } catch (error: Throwable) {
                states.publish(TransferState(kind = request.kind, error = error.message ?: "导出失败"))
            }
        }
    }

    fun importArchive(context: Context, sourceUri: Uri, options: ImportOptions, kind: ExportKind) {
        if (isRunning()) return
        val appContext = context.applicationContext
        states.publish(TransferState(running = true, kind = kind, operation = TransferOperation.IMPORT, phase = "读取清单"))
        job = scope.launch {
            try {
                val report = MediaCacheRevision.batch {
                    DataImporter.importArchive(appContext, sourceUri, options) { phase, done, total ->
                        states.progress(kind, phase, done, total)
                    }
                }
                states.publish(TransferState(
                    kind = kind,
                    operation = TransferOperation.IMPORT,
                    outcome = TransferOutcome.Import(report),
                ))
            } catch (cancelled: CancellationException) {
                states.publish(TransferState(kind = kind))
            } catch (error: Throwable) {
                states.publish(TransferState(kind = kind, error = error.message ?: "导入失败"))
            }
        }
    }

    fun backfillMedia(context: Context, kind: ExportKind, candidates: List<MediaCandidate>) {
        if (isRunning() || candidates.isEmpty()) return
        val appContext = context.applicationContext
        states.publish(TransferState(
            running = true,
            kind = kind,
            operation = TransferOperation.BACKFILL,
            phase = "补齐媒体",
            total = candidates.size,
        ))

        val foregroundStarted = runCatching { MediaBackfillService.start(appContext) }.isSuccess
        if (!foregroundStarted) {
            states.publish(TransferState(kind = kind, error = "无法启动后台下载服务"))
            return
        }
        job = scope.launch {
            try {
                val report = MediaBackfill.download(appContext, candidates) { phase, done, total ->
                    states.progress(kind, phase, done, total)
                }
                states.publish(TransferState(
                    kind = kind,
                    operation = TransferOperation.BACKFILL,
                    outcome = TransferOutcome.Backfill(report),
                ))
            } catch (cancelled: CancellationException) {
                states.publish(TransferState(kind = kind))
            } catch (error: Throwable) {
                states.publish(TransferState(kind = kind, error = error.message ?: "补齐媒体失败"))
            }
        }
    }

    fun cancel(kind: ExportKind? = null) {
        if (kind != null && state.value.kind != kind) return
        job?.cancel()
    }
}
