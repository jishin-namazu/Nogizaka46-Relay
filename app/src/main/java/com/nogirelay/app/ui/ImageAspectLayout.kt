package com.nogirelay.app.ui

import kotlin.math.abs

internal fun imageHeightFraction(aspectRatio: Float?): Float? =
    aspectRatio?.takeIf { it.isFinite() && it > 0f }
        ?.let { 1f / it }
        ?.takeIf { it.isFinite() && it > 0f }

internal fun imageHeightSettled(current: Float, target: Float): Boolean =
    current.isFinite() && target.isFinite() && current > 0f && target > 0f && abs(current - target) < 0.0001f
