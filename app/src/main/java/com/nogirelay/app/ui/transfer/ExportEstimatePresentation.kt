package com.nogirelay.app.ui.transfer

import com.nogirelay.app.data.transfer.ExportEstimatePhase
import com.nogirelay.app.data.transfer.ExportEstimateProgress

internal fun estimateProgressText(progress: ExportEstimateProgress?): String {
    if (progress == null) return "正在准备统计…"
    val phase = when (progress.phase) {
        ExportEstimatePhase.RECORDS -> "扫描记录"
        ExportEstimatePhase.MEDIA -> "检查媒体"
    }
    return "$phase ${progress.done} / ${progress.total}"
}

internal fun mediaRoleLabel(role: String): String = when (role) {
    "media" -> "消息媒体"
    "phone_image" -> "语音配图"
    "body" -> "正文图片"
    "cover" -> "封面图片"
    "image" -> "图片"
    "video" -> "视频"
    "voice", "audio" -> "语音"
    else -> role
}
