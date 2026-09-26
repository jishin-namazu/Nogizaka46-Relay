package com.nogirelay.app.performance

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceDispatchers(
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
) {
    private val workerCores = (availableProcessors - 2).coerceIn(1, 4)

    val network: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)
    val databaseRead: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)
    val databaseWrite: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    val parsing: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(workerCores)
    val imageDecode: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        if (availableProcessors >= 8) 2 else 1,
    )
}
