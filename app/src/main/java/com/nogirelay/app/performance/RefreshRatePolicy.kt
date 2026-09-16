package com.nogirelay.app.performance

sealed interface RefreshRatePolicy {
    data object FollowSystem : RefreshRatePolicy
    data object Maximum : RefreshRatePolicy
    data class Fixed(val fps: Float) : RefreshRatePolicy
    data class Video(val sourceFps: Float) : RefreshRatePolicy
}

interface RefreshRatePolicyOwner {
    fun refreshRatePolicy(): RefreshRatePolicy
}
