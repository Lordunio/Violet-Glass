package pl.violetglass.launcher.gestures

import org.junit.Assert.*
import org.junit.Test
import pl.violetglass.launcher.settings.DesktopGesture

class GestureClassifierTest {
    @Test fun shortMovementIsNotSwipe() { assertNull(classifySwipe(0f, 40f, 1, 64f)) }
    @Test fun horizontalPageSwipeIsNotIntercepted() { assertNull(classifySwipe(200f, 100f, 1, 64f)) }
    @Test fun diagonalMovementIsNotAmbiguousVerticalAction() { assertNull(classifySwipe(100f, 130f, 1, 64f)) }
    @Test fun upwardAndDownwardSwipesHaveDifferentActions() {
        assertEquals(DesktopGesture.SWIPE_UP, classifySwipe(10f, -200f, 1, 64f))
        assertEquals(DesktopGesture.SWIPE_DOWN, classifySwipe(10f, 200f, 1, 64f))
    }
    @Test fun twoFingerUpIsSeparateAndDownIsUnassigned() {
        assertEquals(DesktopGesture.TWO_FINGER_UP, classifySwipe(0f, -200f, 2, 64f))
        assertNull(classifySwipe(0f, 200f, 2, 64f))
    }
}
