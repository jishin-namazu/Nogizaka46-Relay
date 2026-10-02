package com.nogirelay.app.data.transfer

/** The counter's unit is part of the report, not inferred from export options. */
enum class ExportEstimatePhase { RECORDS, MEDIA }

data class ExportEstimateProgress(
    val phase: ExportEstimatePhase,
    val done: Int,
    val total: Int,
) {
    val fraction: Float
        get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 1f
}
