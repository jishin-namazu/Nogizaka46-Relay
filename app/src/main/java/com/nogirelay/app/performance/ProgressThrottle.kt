package com.nogirelay.app.performance

internal class ProgressThrottle(
    private val nowMillis: () -> Long,
    private val intervalMillis: Long = 100,
) {
    private var lastTime: Long? = null
    private var lastTotal = -1

    fun shouldPublish(done: Int, total: Int): Boolean {
        val now = nowMillis()
        val last = lastTime
        if (last == null || total != lastTotal || done >= total || now - last >= intervalMillis) {
            lastTime = now
            lastTotal = total
            return true
        }
        return false
    }
}
