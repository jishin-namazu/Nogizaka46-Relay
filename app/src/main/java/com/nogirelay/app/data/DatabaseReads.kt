package com.nogirelay.app.data

import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Propagate cancellation into SQLite, including while its blocking query is running. */
suspend fun <T> readDatabase(block: (CancellationSignal) -> T): T = suspendCancellableCoroutine { continuation ->
    val signal = CancellationSignal()
    val job = CoroutineScope(AppGraph.dispatchers.databaseRead).launch {
        try {
            signal.throwIfCanceled()
            val result = block(signal)
            if (continuation.isActive) continuation.resume(result)
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
    continuation.invokeOnCancellation {
        signal.cancel()
        job.cancel()
    }
}
