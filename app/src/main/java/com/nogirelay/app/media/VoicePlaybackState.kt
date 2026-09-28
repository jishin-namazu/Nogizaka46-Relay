package com.nogirelay.app.media

data class VoicePlaybackState(
    val messageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speakerOn: Boolean = false,
    val sampledAtMillis: Long = 0L,
) {
    /** Interpolate on the UI frame clock without polling the service at display refresh rate. */
    fun positionAt(nowMillis: Long, knownDurationMs: Int = durationMs): Float {
        val elapsed = if (isPlaying && sampledAtMillis > 0L) {
            // Stop coasting if samples stall, rather than drifting far ahead of the player.
            (nowMillis - sampledAtMillis).coerceIn(0L, 400L)
        } else {
            0L
        }
        return (positionMs.toLong() + elapsed).coerceIn(0L, knownDurationMs.coerceAtLeast(0).toLong()).toFloat()
    }
}
