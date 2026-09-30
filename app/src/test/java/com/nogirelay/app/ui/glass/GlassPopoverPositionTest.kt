package com.nogirelay.app.ui.glass

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassPopoverPositionTest {
    private fun provider() = GlassPopoverPositionProvider(Density(1f), 10, 32)
    private fun size(height: Int) = IntSize(240 + 64, height + 64)
    private val window = IntSize(400, 800)

    @Test
    fun shortMenuOpensBelowWithoutCoveringField() {
        val provider = provider()
        val anchor = IntRect(20, 100, 260, 156)
        val position = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(180))
        assertEquals(anchor.bottom + 6, position.y + 32)
        assertFalse(provider.opensUpward)
        assertTrue(provider.fitsAvailableSpace)
    }

    @Test
    fun bottomFieldOpensUpward() {
        val provider = provider()
        val anchor = IntRect(80, 660, 320, 716)
        val position = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(280))
        assertEquals(anchor.top - 6, position.y + 32 + 280)
        assertTrue(provider.opensUpward)
        assertTrue(provider.fitsAvailableSpace)
    }

    @Test
    fun tallMenuUsesAvailableSpaceInsteadOfCenteringOverField() {
        val provider = provider()
        val anchor = IntRect(80, 350, 320, 406)
        provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(420))
        assertFalse(provider.opensUpward)
        assertEquals(378, provider.maxHeightPx)
        assertFalse(provider.fitsAvailableSpace)
        val position = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(provider.maxHeightPx))
        assertEquals(anchor.bottom + 6, position.y + 32)
        assertEquals(window.height - 10, position.y + 32 + provider.maxHeightPx)
        assertTrue(provider.fitsAvailableSpace)
    }

    @Test
    fun heightAdjustmentKeepsTheChosenSide() {
        val provider = provider()
        val anchor = IntRect(80, 470, 320, 526)
        provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(420))
        assertTrue(provider.opensUpward)
        val position = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(180))
        assertTrue(provider.opensUpward)
        assertEquals(anchor.top - 6, position.y + 32 + 180)
    }

    @Test
    fun movedFieldRecalculatesAvailableSpace() {
        val provider = provider()
        provider.calculatePosition(IntRect(80, 660, 320, 716), window, LayoutDirection.Ltr, size(280))
        val anchor = IntRect(80, 90, 320, 146)
        val position = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(280))
        assertFalse(provider.opensUpward)
        assertEquals(anchor.bottom + 6, position.y + 32)
    }

    @Test
    fun rightEdgeAlignmentExcludesTransparentEffectPadding() {
        val provider = provider()
        val anchor = IntRect(354, 100, 390, 136)
        val position = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, size(100))
        assertEquals(anchor.right, position.x + 32 + 240)
    }
}
