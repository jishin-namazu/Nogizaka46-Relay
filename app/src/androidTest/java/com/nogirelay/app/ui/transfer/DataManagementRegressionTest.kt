package com.nogirelay.app.ui.transfer

import android.view.ViewGroup
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.nogirelay.app.BuildConfig
import com.nogirelay.app.data.BlogMember
import com.nogirelay.app.data.transfer.ExportEstimate
import com.nogirelay.app.data.transfer.ExportEstimateKey
import com.nogirelay.app.data.transfer.ExportEstimatePhase
import com.nogirelay.app.data.transfer.ExportEstimateProgress
import com.nogirelay.app.data.transfer.ExportKind
import com.nogirelay.app.ui.NogiRelayTheme
import com.nogirelay.app.ui.glass.GlassDialog
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class DataManagementRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val key = ExportEstimateKey(ExportKind.MESSAGES, setOf("member"), true, 1L, 1L)
    private val progress = MutableStateFlow<ExportEstimateProgress?>(null)

    @Before fun requireIsolatedPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.RENDER_VERIFICATION && context.packageName.endsWith(".rendercheck"))
    }

    @Composable
    private fun Status(estimate: ExportEstimate?, fromCache: Boolean, run: Int = 0, active: Boolean = true) {
        NogiRelayTheme {
            ExportEstimateStatus(
                estimate = estimate, progressFlow = progress, includeMedia = true,
                backfilling = false, active = active, failure = null, requestKey = key, run = run,
                fromCache = fromCache, backfillEnabled = true, onBackfill = {},
            )
        }
    }

    @Test fun cacheHitReplaysProgressBeforeRevealingTheResult() {
        compose.mainClock.autoAdvance = false
        var run by mutableStateOf(0)
        var estimate by mutableStateOf<ExportEstimate?>(null)
        var fromCache by mutableStateOf(false)
        compose.setContent { Status(estimate, fromCache, run, active = run > 0) }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText("正在准备统计…").assertCountEquals(0)
        compose.runOnIdle {
            progress.value = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 600, 600)
            estimate = ExportEstimate(1000, 600, 500, 1234L, scanProgress = progress.value!!)
            fromCache = true
            run += 1
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("正在载入统计结果…").assertIsDisplayed()
        compose.onAllNodesWithText("正在准备统计…").assertCountEquals(0)
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(1)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("1000 条记录").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test fun estimatePreparationStateIsVisibleBeforeTheRequestStarts() {
        progress.value = null
        compose.setContent { Status(null, false, run = 0, active = true) }
        compose.onNodeWithText("正在准备统计…").assertIsDisplayed()
    }

    @Test fun reopeningAnUnchangedEstimateReplaysItsProgressAgain() {
        var run by mutableStateOf(0)
        val completed = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 600, 600)
        progress.value = completed
        val estimate = ExportEstimate(1000, 600, 500, 1234L, scanProgress = completed)
        compose.setContent { Status(estimate, true, run) }
        compose.waitForIdle()
        compose.onNodeWithText("1000 条记录").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { run += 1 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("正在载入统计结果…").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(1)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("1000 条记录").assertIsDisplayed()
    }

    @Test fun liveReportsShowTheUnitOfTheActualScan() {
        progress.value = ExportEstimateProgress(ExportEstimatePhase.RECORDS, 250, 1000)
        compose.setContent { Status(null, false) }
        compose.onNodeWithText("扫描记录 250 / 1000").assertIsDisplayed()
        compose.runOnIdle {
            progress.value = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 300, 600)
        }
        compose.onNodeWithText("检查媒体 300 / 600").assertIsDisplayed()
    }

    @Test fun realScanFinishesBeforeRevealingItsSummary() {
        var estimate by mutableStateOf<ExportEstimate?>(null)
        progress.value = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 300, 600)
        compose.setContent { Status(estimate, false) }
        compose.onNodeWithText("检查媒体 300 / 600").assertIsDisplayed()
        compose.runOnIdle {
            progress.value = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 600, 600)
            estimate = ExportEstimate(1000, 600, 500, 1234L,
                scanProgress = ExportEstimateProgress(ExportEstimatePhase.MEDIA, 600, 600))
        }
        compose.waitForIdle()
        compose.onNodeWithText("1000 条记录").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    @Test fun windowSizeIsFixedBeforeTheCardAnimationFinishes() {
        compose.mainClock.autoAdvance = false
        var window: Window? = null
        var dismissed = false
        compose.setContent {
            NogiRelayTheme {
                GlassDialog(onDismissRequest = { dismissed = true }, frostedBackground = false) {
                    val currentWindow = (LocalView.current.parent as DialogWindowProvider).window
                    SideEffect { window = currentWindow }
                    Text("短对话框")
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            val attributes = checkNotNull(window).attributes
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, attributes.height)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, attributes.width)
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("短对话框").performTouchInput { click() }
        compose.runOnIdle { assertFalse(dismissed) }
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, height / 2f)) }
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun tallMemberPickerStaysInPlaceAndPreservesSelectionAndScrolling() {
        compose.mainClock.autoAdvance = false
        val members = List(60) { BlogMember("$it", "成员$it", "测试成员", null, it) }
        var confirmed: Set<String>? = null
        var dismissed = false
        compose.setContent {
            NogiRelayTheme {
                Box(Modifier.fillMaxSize()) {
                    TransferMemberPickerDialog(
                        title = "选择导出成员", members = members, selectedIds = setOf("0"),
                        onDismiss = { dismissed = true }, onConfirm = { confirmed = it },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        val first = compose.onNodeWithText("选择导出成员").fetchSemanticsNode().positionOnScreen
        compose.mainClock.advanceTimeBy(1200)
        val settled = compose.onNodeWithText("选择导出成员").fetchSemanticsNode().positionOnScreen
        assertEquals(first.x, settled.x, 1f)
        assertEquals(first.y, settled.y, 1f)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("成员1").performClick()
        // The footer is not composed until the lazy grid scrolls to its item.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(members.size + 2)
        compose.onNodeWithText("确定").performClick()
        compose.runOnIdle {
            assertEquals(setOf("0", "1"), confirmed)
            assertFalse(dismissed)
        }
    }
}
