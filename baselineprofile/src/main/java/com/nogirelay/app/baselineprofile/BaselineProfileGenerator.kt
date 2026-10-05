package com.nogirelay.app.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods a cold start and the main journeys use,
 * so they are compiled ahead of time instead of interpreted on first use.
 *
 * Generate with a device or emulator connected:
 *   .\gradlew.bat :app:generateReleaseBaselineProfile
 * The result lands in app/src/release/generated/baselineProfiles/.
 * It runs a separate "com.nogirelay.app.profile" build, never the installed app.
 * That build gets the sync server from relay.profile.baseUrl /
 * relay.profile.access.token (or relay.baseUrl / relay.access.token) in
 * local.properties, so it syncs real messages and blogs; push is not needed.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndMainJourneys() = rule.collect(
        packageName = PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()

        // Messages: the inbox list. The first run waits for the initial sync
        // (the profiling build carries the server address and token); later
        // runs find the data already stored.
        device.findObject(By.text("消息"))?.click()
        device.wait(Until.hasObject(By.text("全部成员")), SYNC_TIMEOUT_MILLIS)
        settle()

        // A member's conversation: open the card (before scrolling the inbox:
        // a tap on a list still coasting only stops it), read back through
        // older messages (the newest sit at the bottom), then return.
        device.findObject(By.res(MEMBER_THREAD))?.let { thread ->
            thread.click()
            if (device.wait(Until.hasObject(By.res(MEMBER_TIMELINE)), TIMEOUT_MILLIS) == true) {
                settle()
                repeat(2) { flingFresh(By.res(MEMBER_TIMELINE), Direction.UP) }
                flingFresh(By.res(MEMBER_TIMELINE), Direction.DOWN)

                // Media: the image, video and voice grids of this member. Like
                // the conversation they open at the newest (bottom) end, so
                // older items are reached by scrolling up.
                if (openAuxiliary(MEDIA_ENTRY)) {
                    listOf("图片", "视频", "语音").forEach { category ->
                        device.findObject(By.text(category))?.click()
                        settle()
                        flingFresh(By.res(AUXILIARY_LIST), Direction.UP)
                        device.waitForIdle()
                    }
                    device.pressBack()
                    device.wait(Until.hasObject(By.res(MEMBER_TIMELINE)), TIMEOUT_MILLIS)
                }

                // Favorites of this member: newest first, so scroll down.
                if (openAuxiliary(FAVORITES_ENTRY)) {
                    flingFresh(By.res(AUXILIARY_LIST), Direction.DOWN)
                    device.waitForIdle()
                    device.pressBack()
                    device.wait(Until.hasObject(By.res(MEMBER_TIMELINE)), TIMEOUT_MILLIS)
                }
            }
            device.pressBack()
            device.wait(Until.hasObject(By.text("全部成员")), TIMEOUT_MILLIS)
            settle()
        }
        flingFresh(By.res(MEMBER_INBOX), Direction.DOWN)

        // Blog: one post first (same reason as above), then the infinite list.
        device.findObject(By.text("博客"))?.click()
        device.wait(Until.hasObject(By.res(BLOG_CARD)), SYNC_TIMEOUT_MILLIS)
        settle()
        device.findObject(By.res(BLOG_CARD))?.let { card ->
            card.click()
            if (device.wait(Until.hasObject(By.res(BLOG_DETAIL)), TIMEOUT_MILLIS) == true) {
                settle()
                repeat(2) { flingFresh(By.res(BLOG_DETAIL), Direction.DOWN) }
            }
            device.pressBack()
            device.wait(Until.hasObject(By.res(BLOG_LIST)), TIMEOUT_MILLIS)
            settle()
        }
        flingFresh(By.res(BLOG_LIST), Direction.DOWN)

        // Settings over home, once the home page has finished rising in.
        device.findObject(By.text("主页"))?.click()
        device.wait(Until.hasObject(By.desc("设置")), TIMEOUT_MILLIS)
        settle()
        device.findObject(By.desc("设置"))?.click()
        if (device.wait(Until.hasObject(By.text("外观")), TIMEOUT_MILLIS) == true) settle()
        device.pressBack()
        device.waitForIdle()
    }

    /**
     * Opens a page from the conversation's "筛选与更多" sheet.
     *
     * The entries sit at the end of the sheet's scrolling content, usually
     * below the fold: an off-screen entry is still in the hierarchy, but a tap
     * on it lands outside the screen. So wait for the sheet to stop rising,
     * scroll its content to the end, then tap.
     */
    private fun MacrobenchmarkScope.openAuxiliary(entryTag: String): Boolean {
        device.findObject(By.res(TIMELINE_MORE))?.click() ?: return false
        if (!device.wait(Until.hasObject(By.res(entryTag)), TIMEOUT_MILLIS)) {
            device.pressBack()
            return false
        }
        settle()
        runCatching { device.findObject(By.res(entryTag))?.let(::scrollableAncestor)?.fling(Direction.DOWN) }
        settle()
        val entry = device.findObject(By.res(entryTag))
        if (entry == null || entry.visibleBounds.isEmpty) {
            device.pressBack()
            return false
        }
        entry.click()
        // The sheet slides away, then the page slides in.
        device.wait(Until.gone(By.res(entryTag)), TIMEOUT_MILLIS)
        settle()
        return true
    }

    /**
     * Flings the list [selector] finds now, from the middle of its bounds.
     *
     * The pages float their headers over the top of full-screen lists, so a
     * fling that starts near the list's edge (UiObject2.fling) lands on the
     * header and does nothing. Swipe through the middle band instead:
     * [Direction.UP] reveals content above (finger moves down), [Direction.DOWN]
     * content below. The node is looked up afresh each time, since lists are
     * recreated when a tab or page changes.
     */
    private fun MacrobenchmarkScope.flingFresh(selector: BySelector, direction: Direction) {
        repeat(3) {
            val target = device.findObject(selector) ?: return
            val bounds = try {
                target.visibleBounds
            } catch (_: StaleObjectException) {
                settle()
                return@repeat
            }
            val x = bounds.centerX()
            val upper = bounds.top + bounds.height() * 35 / 100
            val lower = bounds.top + bounds.height() * 80 / 100
            val (from, to) = if (direction == Direction.UP) upper to lower else lower to upper
            device.swipe(x, from, x, to, FLING_STEPS)
            // Let the fling coast to a stop, so the next tap is not spent stopping it.
            device.waitForIdle()
            SystemClock.sleep(FLING_COAST_MILLIS)
            return
        }
    }

    private fun scrollableAncestor(node: UiObject2): UiObject2? {
        var current: UiObject2? = node.parent
        while (current != null && !current.isScrollable) current = current.parent
        return current
    }

    /** Lets an enter/exit animation finish so taps hit where things rest. */
    private fun MacrobenchmarkScope.settle() {
        device.waitForIdle()
        SystemClock.sleep(SETTLE_MILLIS)
    }

    private companion object {
        const val PACKAGE = "com.nogirelay.app.profile"
        const val TIMEOUT_MILLIS = 5_000L

        // UiTestTags in the app, exposed as resource ids.
        const val MEMBER_INBOX = "member-inbox"
        const val MEMBER_THREAD = "member-thread"
        const val AUXILIARY_LIST = "auxiliary-list"
        const val BLOG_LIST = "blog-list"
        const val MEMBER_TIMELINE = "member-timeline"
        const val BLOG_CARD = "blog-card"
        const val BLOG_DETAIL = "blog-detail"
        const val TIMELINE_MORE = "timeline-more"
        const val MEDIA_ENTRY = "timeline-media-entry"
        const val FAVORITES_ENTRY = "timeline-favorites-entry"
        const val SETTLE_MILLIS = 600L

        /** Swipe steps of ~5 ms each: fast enough to fling, not just drag. */
        const val FLING_STEPS = 8
        const val FLING_COAST_MILLIS = 1_200L
        const val SYNC_TIMEOUT_MILLIS = 60_000L
    }
}
