package pl.violetglass.launcher.workspace

import org.junit.Assert.*
import org.junit.Test

class WidgetGeometryTest {
    private fun state(): LayoutState = LayoutEngine.addWidget(LayoutState(), 10, "test/Provider", "Widget", 2, 2, 0)
    private fun rejected(block: () -> Unit) { try { block(); fail("Collision must reject the operation") } catch (_: LayoutRejected) {} }
    @Test fun widgetOccupiesRectangleAndFirstFreeSkipsEveryCoveredCell() {
        val state = state()
        val widget = state.items.single()
        assertEquals(setOf(0, 1, 4, 5), occupiedCells(widget, state.config))
        assertEquals(widget.id, state.at(pageContainer(0), 5)?.id)
        assertEquals(2, LayoutEngine.firstFree(state, pageContainer(0)).position)
        assertFalse(widget.isFolder)
    }
    @Test fun iconCannotBeDroppedInsideWidgetOrCreateFolderWithIt() {
        val state = state()
        rejected { LayoutEngine.place(state, ItemPayload(appKey = "app", title = "A"), Slot(pageContainer(0), 5)) }
        assertEquals(1, state.items.size)
    }
    @Test fun widgetCannotWrapAcrossRightEdgeOrEnterDock() {
        val state = state(); val item = state.items.single()
        rejected { LayoutEngine.place(state, ItemPayload(item.id, title = item.title), Slot(pageContainer(0), 3)) }
        rejected { LayoutEngine.place(state, ItemPayload(item.id, title = item.title), Slot(DOCK, 0)) }
    }
    @Test fun widgetCanMoveBetweenPagesWithoutChangingBinding() {
        val state = LayoutEngine.addPage(state()); val item = state.items.single()
        val moved = LayoutEngine.place(state, ItemPayload(item.id, title = item.title), Slot(pageContainer(1), 1))
        assertEquals(10, moved.items.single().widgetId)
        assertEquals(setOf(1, 2, 5, 6), occupiedCells(moved.items.single(), moved.config))
    }
    @Test fun expansionRejectsCollisionAndShrinkingFreesCells() {
        val state = state(); val item = state.items.single()
        val withIcon = LayoutEngine.place(state, ItemPayload(appKey = "app", title = "A"), Slot(pageContainer(0), 2))
        rejected { LayoutEngine.resizeWidget(withIcon, item.id, 3, 2) }
        val shrunk = LayoutEngine.resizeWidget(withIcon, item.id, 1, 1)
        assertNull(shrunk.at(pageContainer(0), 5))
        assertEquals(2, shrunk.items.size)
    }
    @Test fun gridReflowPreservesWidgetRectangleAndEveryIcon() {
        var state = state()
        repeat(6) { index -> state = LayoutEngine.place(state, ItemPayload(appKey = "a$index", title = "A$index"), LayoutEngine.firstFree(state, pageContainer(0))) }
        val next = LayoutEngine.resize(state, 3, 3, 3)
        assertEquals(state.items.map { it.id }.toSet(), next.items.map { it.id }.toSet())
        assertEquals(2, next.items.single { it.isWidget }.spanX)
        LayoutEngine.validate(next)
    }
    @Test fun tooWideWidgetPreventsGridShrinkInsteadOfLosingData() {
        val state = LayoutEngine.addWidget(LayoutState(), 11, "test/Provider", "Wide", 4, 1, 0)
        rejected { LayoutEngine.resize(state, 3, 3, 3) }
        assertEquals(4, state.items.single().spanX)
    }
    @Test fun restoringBindingKeepsItemPositionAndIdentity() {
        val state = state(); val old = state.items.single()
        val next = LayoutEngine.addWidget(state, 12, "test/Provider", "Widget", 1, 1, 0, old.id)
        assertEquals(old.id, next.items.single().id)
        assertEquals(old.spanX, next.items.single().spanX)
        assertEquals(12, next.items.single().widgetId)
    }
    @Test fun duplicateWidgetIdsAndOrphanProviderAreRejected() {
        val state = state(); val item = state.items.single()
        rejected { LayoutEngine.validate(state.copy(items = state.items + item.copy(id = "second", position = 10))) }
        rejected { LayoutEngine.validate(state.copy(items = listOf(item.copy(provider = null)))) }
    }
}
