package com.nogirelay.app.call

/** Reject startup/stale samples and brief near pulses without requiring a far-first event. */
internal class ProximityStabilityGate {
    private var enabledAt: Long? = null
    private var nearSince: Long? = null

    fun enable(nowMillis: Long) {
        enabledAt = nowMillis
        nearSince = null
    }

    fun disable() {
        enabledAt = null
        nearSince = null
    }

    fun sample(near: Boolean, sampleMillis: Long) {
        val start = enabledAt ?: return
        if (sampleMillis < start) return
        if (!near) nearSince = null
        else if (nearSince == null) nearSince = sampleMillis
    }

    fun remainingMillis(nowMillis: Long): Long? {
        val start = enabledAt ?: return null
        val near = nearSince ?: return null
        return (maxOf(start + 250L, near + 150L) - nowMillis).coerceAtLeast(0L)
    }
}
