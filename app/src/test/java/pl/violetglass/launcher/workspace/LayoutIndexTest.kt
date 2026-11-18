package pl.violetglass.launcher.workspace

import org.junit.Assert.*
import org.junit.Test

class LayoutIndexTest {
    @Test fun widgetFootprintAndContainerSeparationMatchGrid() {
        val widget = LayoutItem("w", title = "Widget", container = pageContainer(0), position = 1, widgetId = 1, spanX = 2, spanY = 2)
        val dock = LayoutItem("a", title = "App", appKey = "a", container = DOCK, position = 1)
        val state = LayoutState(items = listOf(widget, dock))
        listOf(1, 2, 5, 6).forEach { assertSame(widget, state.at(pageContainer(0), it)) }
        assertSame(dock, state.at(DOCK, 1)); assertNull(state.at(pageContainer(1), 1))
    }
    @Test fun copiedStateRebuildsIndexesAfterMovingOrResizing() {
        val app = LayoutItem("a", title = "A", appKey = "a", container = pageContainer(0), position = 0)
        val old = LayoutState(items = listOf(app))
        assertSame(app, old.at(pageContainer(0), 0))
        val moved = old.copy(items = listOf(app.copy(position = 5)))
        assertNull(moved.at(pageContainer(0), 0)); assertEquals("a", moved.at(pageContainer(0), 5)?.id)
        assertSame(app, old.at(pageContainer(0), 0))
    }
    @Test fun folderOrderIsStableAndFirstOccupantWinsAsBeforeValidation() {
        val a = LayoutItem("a", title = "A", appKey = "a", container = folderContainer("f"), position = 5)
        val b = a.copy(id = "b", position = 1)
        val state = LayoutState(items = listOf(a, b, b.copy(id = "duplicate")))
        assertEquals(listOf("b", "duplicate", "a"), state.children("f").map { it.id })
        assertEquals("b", state.at(folderContainer("f"), 1)?.id)
    }
}
