package pl.violetglass.launcher.discover

import org.junit.Assert.*
import org.junit.Test

class NativeOverlayMotionTest {
    @Test fun fastCloseFromRecordedOppoSequenceTracksTheFirstMoveThroughZero() {
        val motion = NativeOverlayMotion()
        listOf(0f, .4f, .99f, 1f).forEach { assertNull(motion.update(it, 1f, false)) }
        // First decrease arrived 156 ms after opening, formerly rejected by the
        // 250 ms gate. The later decreases never crossed the old start condition.
        val recorded = listOf(.962963f, .92314816f, .6175926f, .475f, .025925925f, 0f)
        assertEquals(recorded, recorded.map { motion.update(it, 1f, false) })
        assertFalse(motion.dragging)
    }
    @Test fun openingAndPagerDrivenClosingEchoesDoNotStartANativeGesture() {
        val motion = NativeOverlayMotion()
        assertNull(motion.update(0f, 1f, false))
        assertNull(motion.update(1f, 1f, false))
        assertNull(motion.update(.8f, .8f, false))
        assertNull(motion.update(.2f, .2f, false))
        assertNull(motion.update(0f, 0f, false))
        assertNull(motion.update(.5f, 1f, false))
        assertNull(motion.update(1f, 1f, false))
        assertEquals(0f, motion.update(0f, 1f, false))
    }
    @Test fun cancellationAndHaCoverageDoNotLeaveNativeTrackingArmed() {
        val motion = NativeOverlayMotion()
        motion.update(1f, 1f, false)
        assertEquals(.7f, motion.update(.7f, 1f, false))
        assertEquals(1f, motion.update(1f, 1f, false))
        assertFalse(motion.dragging)
        assertNull(motion.update(.5f, 1f, true))
        assertFalse(motion.dragging)
        motion.reset()
        assertNull(motion.update(0f, 1f, false))
    }
}
