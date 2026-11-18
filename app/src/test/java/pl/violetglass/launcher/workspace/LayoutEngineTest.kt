package pl.violetglass.launcher.workspace

import org.junit.Assert.*
import org.junit.Test

class LayoutEngineTest {
    @Test fun removingMiddleFolderChildCompactsOnlyThatFolderInExistingOrder() {
        val folder = LayoutItem("f", title = "Folder", container = DOCK, position = 2)
        val state = LayoutState(items = listOf(folder, app("a", 0, "folder:f"), app("b", 1, "folder:f"), app("c", 4, "folder:f"), app("desktop", 9)))
        val next = LayoutEngine.remove(state, "b")
        assertEquals(listOf("a", "c"), next.children("f").map { it.id })
        assertEquals(listOf(0, 1), next.children("f").map { it.position })
        assertEquals(9, next.items.single { it.id == "desktop" }.position)
        assertEquals(folder, next.items.single { it.id == "f" })
    }
    @Test fun extractingChildThenUninstallRemovesEveryShortcutAndCompactsSurvivors() {
        val folder = LayoutItem("f", title = "Folder", container = DOCK, position = 2)
        val state = LayoutState(items = listOf(folder, app("a", 0, "folder:f"), app("b", 1, "folder:f"),
            app("c", 2, "folder:f").copy(appKey = "0:com.keep/Main"), app("desktop", 9)))
        val extracted = LayoutEngine.place(state, payload("a"), Slot(pageContainer(0), 8))
        assertEquals(listOf(0, 1), extracted.children("f").map { it.position })
        val cleaned = LayoutEngine.removePackage(extracted, "com.test")
        assertEquals(listOf("c"), cleaned.children("f").map { it.id })
        assertEquals(0, cleaned.children("f").single().position)
        assertEquals(folder, cleaned.items.single { it.id == "f" })
        assertEquals(2, cleaned.items.size)
    }
    private fun app(id: String, position: Int, container: String = pageContainer(0)) =
        LayoutItem(id, title = id, appKey = "0:com.test/$id", container = container, position = position)
    private fun payload(id: String) = ItemPayload(itemId = id, title = id)
    private fun rejected(reason: LayoutRejected.Reason, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: LayoutRejected) { assertEquals(reason, error.reason) }
    }
    @Test fun addingToOccupiedSlotNeverOverwrites() {
        val state = LayoutState(items = listOf(app("a", 0)))
        rejected(LayoutRejected.Reason.FULL) { LayoutEngine.place(state, ItemPayload(appKey = "new", title = "new"), Slot(pageContainer(0), 0), false) }
        assertEquals("a", state.items.single().id)
    }
    @Test fun moveBetweenPagesAndDock() {
        val state = LayoutState(LayoutConfig(pages = 2), listOf(app("a", 0)))
        val moved = LayoutEngine.place(state, payload("a"), Slot(pageContainer(1), 3))
        assertEquals("a", moved.at(pageContainer(1), 3)?.id)
        val dock = LayoutEngine.place(moved, payload("a"), Slot(DOCK, 2))
        assertEquals(1, dock.items.size)
        assertEquals("a", dock.at(DOCK, 2)?.id)
    }
    @Test fun twoAppsCreateFolderWithoutLosingEither() {
        val state = LayoutState(items = listOf(app("a", 0), app("b", 1)))
        val next = LayoutEngine.place(state, payload("a"), Slot(pageContainer(0), 1))
        val folder = next.items.single { it.isFolder }
        assertEquals(setOf("a", "b"), next.children(folder.id).map { it.id }.toSet())
        assertEquals(3, next.items.size)
    }
    @Test fun addToFolderThenExtractChild() {
        val folder = LayoutItem("f", title = "F", container = DOCK, position = 0)
        val state = LayoutState(items = listOf(folder, app("a", 0)))
        val merged = LayoutEngine.place(state, payload("a"), Slot(DOCK, 0))
        assertEquals("a", merged.children("f").single().id)
        val extracted = LayoutEngine.place(merged, payload("a"), Slot(pageContainer(0), 4))
        assertEquals("a", extracted.at(pageContainer(0), 4)?.id)
        assertTrue(extracted.children("f").isEmpty())
    }
    @Test fun edgeDropSwapsOccupiedCells() {
        val state = LayoutState(items = listOf(app("a", 0), app("b", 1)))
        val next = LayoutEngine.place(state, payload("a"), Slot(pageContainer(0), 1), false)
        assertEquals("b", next.at(pageContainer(0), 0)?.id)
        assertEquals("a", next.at(pageContainer(0), 1)?.id)
    }
    @Test fun foldersCannotNest() {
        val f = LayoutItem("f", title = "F", container = pageContainer(0), position = 0)
        val g = LayoutItem("g", title = "G", container = pageContainer(0), position = 1)
        rejected(LayoutRejected.Reason.INVALID) { LayoutEngine.place(LayoutState(items = listOf(f, g)), payload("f"), Slot(folderContainer("g"), 0)) }
    }
    @Test fun shrinkingGridAndDockPreservesEveryItem() {
        val desktop = (0..19).map { app("a$it", it) }
        val dock = (0..4).map { app("d$it", it, DOCK) }
        val state = LayoutState(items = desktop + dock)
        val next = LayoutEngine.resize(state, 3, 3, 3)
        assertEquals(state.items.map { it.id }.toSet(), next.items.map { it.id }.toSet())
        assertEquals(3, next.config.pages)
        assertEquals(3, next.items.count { it.container == DOCK })
        LayoutEngine.validate(next)
    }
    @Test fun fullFolderRejectsNewAppAtomically() {
        val folder = LayoutItem("f", title = "F", container = DOCK, position = 0)
        val state = LayoutState(items = listOf(folder) + (0..63).map { app("a$it", it, folderContainer("f")) })
        rejected(LayoutRejected.Reason.FULL) { LayoutEngine.place(state, ItemPayload(appKey = "new", title = "New"), Slot(DOCK, 0)) }
        assertEquals(65, state.items.size)
    }
    @Test fun occupiedLastPageCannotBeDeleted() {
        val state = LayoutState(LayoutConfig(pages = 2), listOf(app("a", 0, pageContainer(1))))
        rejected(LayoutRejected.Reason.PAGE_NOT_EMPTY) { LayoutEngine.removeLastPage(state) }
        assertEquals(1, LayoutEngine.removeLastPage(LayoutState(LayoutConfig(pages = 2))).config.pages)
    }
    @Test fun deletingFolderRemovesItsChildrenOnly() {
        val folder = LayoutItem("f", title = "F", container = DOCK, position = 0)
        val state = LayoutState(items = listOf(folder, app("a", 0, folderContainer("f")), app("b", 0)))
        assertEquals("b", LayoutEngine.remove(state, "f").items.single().id)
    }
    @Test fun renameTrimsAndRejectsBlank() {
        val state = LayoutState(items = listOf(app("a", 0)))
        assertEquals("Nowa", LayoutEngine.rename(state, "a", " Nowa ").items.single().title)
        rejected(LayoutRejected.Reason.INVALID) { LayoutEngine.rename(state, "a", "  ") }
    }
    @Test fun missingSourceOrInvalidTargetCannotCreateGhostShortcut() {
        rejected(LayoutRejected.Reason.INVALID) { LayoutEngine.place(LayoutState(), payload("missing"), Slot(DOCK, 0)) }
        rejected(LayoutRejected.Reason.INVALID) { LayoutEngine.place(LayoutState(), ItemPayload(appKey = "a", title = "A"), Slot(pageContainer(9), 0)) }
    }
    @Test fun duplicatePositionsAndOrphanChildrenAreRejected() {
        rejected(LayoutRejected.Reason.INVALID) { LayoutEngine.validate(LayoutState(items = listOf(app("a", 0), app("b", 0)))) }
        rejected(LayoutRejected.Reason.INVALID) { LayoutEngine.validate(LayoutState(items = listOf(app("a", 0, folderContainer("missing"))))) }
    }
    @Test fun pageLimitIsEnforced() {
        rejected(LayoutRejected.Reason.LIMIT) { LayoutEngine.addPage(LayoutState(LayoutConfig(pages = 10))) }
    }
}
