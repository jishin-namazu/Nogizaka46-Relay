package com.nogirelay.app.performance

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class BoundedWorkQueue<T>(
    scope: CoroutineScope,
    parallelism: Int,
    capacity: Int,
    private val key: (T) -> String,
    private val onFailure: (T, Exception) -> Unit,
    process: suspend (T) -> Unit,
) {
    private class Work<T>(val id: String, val value: T)
    private val pending = ConcurrentHashMap<String, Work<T>>()
    private val channel = Channel<Work<T>>(capacity, onUndeliveredElement = { work ->
        pending.remove(work.id, work)
    })
    init {
        require(parallelism > 0)
        scope.coroutineContext[Job]?.invokeOnCompletion { channel.cancel() }
        repeat(parallelism) {
            scope.launch {
                for (work in channel) {
                    try { process(work.value) }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        onFailure(work.value, cancelled)
                    }
                    catch (error: Exception) { onFailure(work.value, error) }
                    finally { pending.remove(work.id, work) }
                }
            }
        }
    }

    suspend fun enqueue(work: T) {
        val entry = Work(key(work), work)
        if (pending.putIfAbsent(entry.id, entry) != null) return

        channel.send(entry)
    }
}
