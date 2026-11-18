package pl.violetglass.launcher.workspace

import org.junit.Assert.*
import org.junit.Test

class ImportedWidgetsTest {
    private val widget = LayoutItem("widget", title = "Widget", provider = "pkg/Widget", container = "page:0", position = 0, spanX = 2, spanY = 2)
    @Test fun unboundWidgetRemainsRectangleAndCanBeRebound() {
        val state = LayoutState(items = listOf(widget))
        LayoutEngine.validate(state)
        assertTrue(widget.isWidget); assertFalse(widget.isFolder)
        assertEquals(widget, state.at("page:0", 5))
        val rebound = LayoutEngine.addWidget(state, 71, "pkg/Widget", "Widget", 2, 2, 0, "widget")
        assertEquals(71, rebound.items.single().widgetId)
        assertEquals(1, rebound.items.size)
    }
    @Test fun unboundWidgetCanBeMovedResizedAndDeleted() {
        val state = LayoutState(config = LayoutConfig(pages = 2), items = listOf(widget))
        val moved = LayoutEngine.place(state, ItemPayload("widget", title = "Widget"), Slot("page:1", 4))
        assertEquals("page:1", moved.items.single().container)
        LayoutEngine.validate(LayoutEngine.resizeWidget(moved, "widget", 3, 2))
        assertTrue(LayoutEngine.remove(moved, "widget").items.isEmpty())
    }
    @Test fun unboundWidgetCannotOverlapOrBecomeAFolderChild() {
        assertThrows(LayoutRejected::class.java) { LayoutEngine.validate(LayoutState(items = listOf(widget, LayoutItem("app", title = "App", appKey = "0:pkg/Activity", container = "page:0", position = 5)))) }
        assertThrows(LayoutRejected::class.java) { LayoutEngine.validate(LayoutState(items = listOf(widget.copy(container = "folder:folder"), LayoutItem("folder", title = "Folder", container = "page:0", position = 8)))) }
    }
}
