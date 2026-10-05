package com.nogirelay.app.data.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nogirelay.app.data.HttpStatusException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class SyncStatus(
    val syncing: Boolean = false,
    /** What the last sync did, or why it failed; blank before the first run. */
    val label: String = "",
    val failed: Boolean = false,
)

/**
 * Runs content syncs in the application scope, so a sync is never cut short
 * by a screen leaving composition, and publishes its progress for any screen.
 */
class SyncController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var lastStartedAt = 0L

    /** Starts a sync unless one is already running. */
    fun request() {
        if (job?.isActive == true) return
        lastStartedAt = SystemClock.elapsedRealtime()
        _status.update { it.copy(syncing = true) }
        job = scope.launch {
            val result = runCatching { withContext(dispatcher) { ContentSyncManager.syncContent(context) } }
            result.exceptionOrNull()?.let { Log.w(TAG, "History sync failed", it) }
            _status.value = result.fold(
                onSuccess = { outcome ->
                    SyncStatus(
                        label = if (outcome.messages > 0 || outcome.blogs > 0) {
                            "已同步 ${outcome.messages} 条消息、${outcome.blogs} 篇博客"
                        } else {
                            "消息和博客已是最新"
                        },
                    )
                },
                onFailure = { SyncStatus(label = describeSyncFailure(it), failed = true) },
            )
        }
    }

    /** Syncs when the last sync started at least [minIntervalMillis] ago (or never ran). */
    fun requestIfStale(minIntervalMillis: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastStartedAt == 0L || now - lastStartedAt >= minIntervalMillis) request()
    }

    private companion object {
        const val TAG = "SyncController"
    }
}

/** A short, actionable reason for a failed sync. */
fun describeSyncFailure(error: Throwable): String {
    val causes = generateSequence(error) { it.cause } + error.suppressed.asSequence()
    val http = causes.filterIsInstance<HttpStatusException>().firstOrNull()
    return when {
        http != null && (http.status == 401 || http.status == 403) -> "访问令牌无效，请在「连接」中检查"
        http != null && http.status == 404 -> "同步地址有误，请在「连接」中检查"
        http != null && http.status >= 500 -> "服务器暂时不可用，请稍后再试"
        causes.any { it is UnknownHostException || it is ConnectException } -> "无法连接服务器，请检查网络"
        causes.any { it is SocketTimeoutException } -> "连接超时，请稍后再试"
        causes.any { it is SSLException } -> "安全连接失败，请确认同步地址使用 HTTPS"
        causes.any { it is IOException } -> "网络异常，同步未完成"
        else -> "同步失败，请稍后再试"
    }
}
