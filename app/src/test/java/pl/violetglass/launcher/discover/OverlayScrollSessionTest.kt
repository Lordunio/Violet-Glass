package pl.violetglass.launcher.discover

import org.junit.Assert.*
import org.junit.Test

class OverlayScrollSessionTest {
    @Test fun dragUsesOneStartAndOneEndWithoutRepeatedProgrammaticAnimations() {
        val session = OverlayScrollSession()
        assertEquals(listOf(1, 2, 3, 6), session.commands(0f))
        val commands = listOf(.1f, .3f, .7f, 1f).flatMap(session::commands)
        assertEquals(listOf(1, 2, 2, 2, 2, 3, 9), commands)
        assertTrue(session.commands(1f).isEmpty())
        assertEquals(listOf(1, 2, 2, 3, 6), listOf(.8f, 0f).flatMap(session::commands))
    }
    @Test fun cancellationAndFreshConnectionResetGestureState() {
        val session = OverlayScrollSession()
        session.commands(.4f)
        assertEquals(listOf(2, 3, 6), session.commands(0f))
        session.reset()
        assertEquals(listOf(1, 2, 3, 9), session.commands(1f))
    }
}
