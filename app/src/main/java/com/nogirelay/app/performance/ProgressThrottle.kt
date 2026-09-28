package com.nogirelay.app.performance

/** Keep first, changed-total and final progress, without driving UI once per database row. */
internal class ProgressThrottle(
    private val nowMillis: () -> Long,
    private val intervalMillis: Long = 100,
) {
    private var lastTime: Long? = null
    private var lastTotal = -1

    fun shouldPublish(done: Int, total: Int): Boolean {
        val now = nowMillis()
        if (lastTime == null || total != lastTotal || done >= total || now - lastTime!! >= intervalMillis) {
            lastTime = now
            lastTotal = total
            return true
        }
        return false
    }
}
