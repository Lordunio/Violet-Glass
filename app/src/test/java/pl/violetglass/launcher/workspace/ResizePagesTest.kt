package pl.violetglass.launcher.workspace

import org.junit.Assert.*
import org.junit.Test

class ResizePagesTest {
    private fun calendar(position: Int = 6) = LayoutItem("calendar", title = "Calendar", provider = "pkg/Calendar", widgetId = 42,
        container = "page:2", position = position, spanX = 6, spanY = 2)
    @Test fun increasingRowsKeepsCalendarOnItsPageAndPosition() {
        val widget = calendar()
        val before = LayoutState(LayoutConfig(columns = 6, rows = 5, pages = 3, dockSlots = 6), listOf(widget))
        val after = LayoutEngine.resize(before, 6, 7, 6)
        assertEquals(widget, after.items.single())
    }
    @Test fun reducingRowsNeverMovesOverflowBackToFirstDesktop() {
        val widget = calendar(24)
        val before = LayoutState(LayoutConfig(columns = 6, rows = 6, pages = 3), listOf(widget))
        val after = LayoutEngine.resize(before, 6, 3, 5)
        assertEquals("page:2", after.items.single().container)
        assertEquals(0, after.items.single().position)
        assertEquals(42, after.items.single().widgetId)
    }
    @Test fun overflowDoesNotDisplaceItemsAlreadyFittingOnFollowingPages() {
        val widget = calendar(24)
        val next = LayoutItem("app", title = "App", appKey = "0:pkg/App", container = "page:3", position = 0)
        val full = (0 until 18).map { LayoutItem("app$it", title = "A$it", appKey = "0:pkg/A$it", container = "page:2", position = it) }
        val after = LayoutEngine.resize(LayoutState(LayoutConfig(columns = 6, rows = 6, pages = 4), full + widget + next), 6, 3, 5)
        assertEquals(next, after.items.find { it.id == "app" })
        assertEquals("page:3", after.items.find { it.id == "calendar" }!!.container)
        LayoutEngine.validate(after)
    }
}
