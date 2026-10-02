package com.nogirelay.app.ui.transfer

import com.nogirelay.app.data.transfer.ExportEstimatePhase
import com.nogirelay.app.data.transfer.ExportEstimateProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportEstimatePresentationTest {
    @Test fun recordScansNameTheirCounterUnit() {
        val progress = ExportEstimateProgress(ExportEstimatePhase.RECORDS, 250, 1000)
        assertEquals("扫描记录 250 / 1000", estimateProgressText(progress))
        assertEquals(0.25f, progress.fraction, 0f)
    }

    @Test fun mediaChecksDoNotDisplayTheRecordTotal() {
        val progress = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 300, 600)
        assertEquals("检查媒体 300 / 600", estimateProgressText(progress))
        assertEquals(0.5f, progress.fraction, 0f)
    }

    @Test fun preparingHasNoInventedCounter() {
        assertEquals("正在准备统计…", estimateProgressText(null))
    }

    @Test fun emptyScansCompleteWithTheirActualUnit() {
        ExportEstimatePhase.entries.forEach { phase ->
            val progress = ExportEstimateProgress(phase, 0, 0)
            assertEquals(1f, progress.fraction, 0f)
            assertEquals(if (phase == ExportEstimatePhase.MEDIA) "检查媒体 0 / 0" else "扫描记录 0 / 0",
                estimateProgressText(progress))
        }
    }

    @Test fun drawingFractionRemainsBounded() {
        assertEquals(1f, ExportEstimateProgress(ExportEstimatePhase.RECORDS, 101, 100).fraction, 0f)
        assertEquals(0f, ExportEstimateProgress(ExportEstimatePhase.RECORDS, -1, 100).fraction, 0f)
    }

    @Test fun completedMediaRolesUseChineseLabels() {
        assertEquals("消息媒体", mediaRoleLabel("media"))
        assertEquals("语音配图", mediaRoleLabel("phone_image"))
        assertEquals("正文图片", mediaRoleLabel("body"))
        assertEquals("封面图片", mediaRoleLabel("cover"))
    }
}
