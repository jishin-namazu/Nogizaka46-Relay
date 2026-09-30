package com.nogirelay.app.media

data class VoicePlaybackState(
    val messageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val speakerOn: Boolean = false,
    val sampledAtMillis: Long = 0L,
) {

    fun positionAt(nowMillis: Long, knownDurationMs: Int = durationMs): Float {
        val elapsed = if (isPlaying && sampledAtMillis > 0L) {

            (nowMillis - sampledAtMillis).coerceIn(0L, 400L)
        } else {
            0L
        }
        return (positionMs.toLong() + elapsed).coerceIn(0L, knownDurationMs.coerceAtLeast(0).toLong()).toFloat()
    }
}
