package pl.violetglass.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.violetglass.launcher.apps.LauncherApp
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.workspace.*

@RunWith(AndroidJUnit4::class)
class WorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LayoutState
    private lateinit var apps: List<LauncherApp>

    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            model.layoutRepository.initialize()
            original = model.layoutRepository.state.first { it != null }!!
            apps = model.repository.load()
            model.layoutRepository.edit { LayoutState() }
        }
        compose.waitUntil(10_000) { model.workspace.value.layout?.items?.isEmpty() == true }
    }
    @After fun restore() { if (::original.isInitialized) runBlocking { model.layoutRepository.edit { original } } }
    private fun waitLayout(predicate: (LayoutState) -> Boolean) {
        compose.waitUntil(15_000) { model.workspace.value.layout?.let(predicate) == true }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        FileOutputStream(File(instrumentation.targetContext.filesDir, name)).use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
    private fun seedTwo() {
        runBlocking { model.layoutRepository.edit { state ->
            val a = LayoutEngine.place(state, ItemPayload(appKey = apps[0].key, title = apps[0].label), Slot(pageContainer(0), 0))
            LayoutEngine.place(a, ItemPayload(appKey = apps[1].key, title = apps[1].label), Slot(pageContainer(0), 1))
        } }
        waitLayout { it.items.size == 2 }
    }
    private fun options(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnLongClick) { it() }
    }

    @Test fun addFromDrawerToHomeAndDock() {
        compose.openDrawer()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("app:${apps[0].key}").fetchSemanticsNodes().isNotEmpty() }
        options("app:${apps[0].key}")
        compose.onNodeWithTag("addHome").performClick()
        waitLayout { it.items.size == 1 }
        options("app:${apps[1].key}")
        compose.onNodeWithTag("addDock").performClick()
        waitLayout { it.items.any { item -> item.container == DOCK } }
        compose.onNodeWithTag("homeButton").performClick()
        val state = model.workspace.value.layout!!
        state.items.forEach { compose.onNodeWithTag("item:${it.id}").assertExists() }
    }

    @Test fun moveToSecondPageAndRenameShortcut() {
        seedTwo()
        val id = model.workspace.value.layout!!.at(pageContainer(0), 0)!!.id
        options("item:$id")
        compose.onNodeWithTag("renameItem").performClick()
        compose.onNodeWithTag("nameInput").performTextReplacement("Mój skrót")
        compose.onNodeWithTag("saveName").performClick()
        waitLayout { it.items.any { item -> item.title == "Mój skrót" } }
        compose.addWorkspacePage()
        waitLayout { it.config.pages == 2 }
        compose.onNodeWithTag("page:0").performClick()
        options("item:$id")
        compose.onNodeWithTag("moveItem").performClick()
        compose.onNodeWithTag("page:1").performClick()
        compose.onNodeWithTag("slot:page:1:0").performClick()
        waitLayout { it.at(pageContainer(1), 0)?.id == id }
        compose.activityRule.scenario.recreate()
        waitLayout { it.at(pageContainer(1), 0)?.title == "Mój skrót" }
        assertEquals(2, model.workspace.value.layout!!.items.size)
    }

    @Test fun folderCreationRenameAndExtraction() {
        seedTwo()
        val a = model.workspace.value.layout!!.at(pageContainer(0), 0)!!.id
        options("item:$a")
        compose.onNodeWithTag("moveItem").performClick()
        compose.onNodeWithTag("slot:page:0:1").performClick()
        waitLayout { it.items.any { item -> item.isFolder } }
        val folder = model.workspace.value.layout!!.items.single { it.isFolder }
        options("item:${folder.id}")
        compose.onNodeWithTag("renameItem").performClick()
        compose.onNodeWithTag("nameInput").performTextReplacement("Moje aplikacje")
        compose.onNodeWithTag("saveName").performClick()
        waitLayout { it.items.any { item -> item.title == "Moje aplikacje" } }
        compose.onNodeWithTag("item:${folder.id}").performClick()
        compose.onNodeWithTag("folderContents").assertExists()
        capture("stage2-folder.png")
        options("item:$a")
        compose.onNodeWithTag("moveItem").performClick()
        compose.onNodeWithTag("slot:page:0:3").performClick()
        waitLayout { it.at(pageContainer(0), 3)?.id == a && it.children(folder.id).size == 1 }
        model.add(ItemPayload(appKey = apps[2].key, title = apps[2].label), DOCK)
        waitLayout { it.at(DOCK, 0) != null }
        capture("stage2-desktop.png")
    }

    @Test fun actualDragMovesIconToFreeCell() {
        seedTwo()
        val a = model.workspace.value.layout!!.at(pageContainer(0), 0)!!.id
        val from = compose.onNodeWithTag("item:$a").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithTag("slot:page:0:2").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput {
            down(from)
            advanceEventTime(800)
            moveTo(to, delayMillis = 800)
            up()
        }
        waitLayout { it.at(pageContainer(0), 2)?.id == a }
    }

    @Test fun dragFromDrawerAddsShortcutToDesktop() {
        compose.openDrawer()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("app:${apps[0].key}").fetchSemanticsNodes().isNotEmpty() }
        val from = compose.onNodeWithTag("app:${apps[0].key}").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { down(from) }
        // Pointer-input long-press timeouts run on the Android UI dispatcher.
        Thread.sleep(800)
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("launcherRoot").performTouchInput { moveBy(Offset(30f, 60f), delayMillis = 100) }
        capture("stage2-drawer-drag.png")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("appDrawerGestureArea").fetchSemanticsNodes().isNotEmpty() }
        val to = compose.onNodeWithTag("slot:page:0:0").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { moveTo(to, delayMillis = 400); up() }
        waitLayout { it.at(pageContainer(0), 0)?.appKey == apps[0].key }
    }

    @Test fun draggingAtEdgeMovesBetweenPages() {
        seedTwo()
        compose.addWorkspacePage()
        waitLayout { it.config.pages == 2 }
        compose.onNodeWithTag("page:0").performClick()
        val id = model.workspace.value.layout!!.at(pageContainer(0), 0)!!.id
        val from = compose.onNodeWithTag("item:$id").fetchSemanticsNode().boundsInRoot.center
        val page = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput { down(from) }
        // Pointer-input long-press timeouts run on the Android UI dispatcher.
        Thread.sleep(800)
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("launcherRoot").performTouchInput { moveTo(Offset(page.right - 5f, page.center.y), delayMillis = 400) }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("page:1").assertIsSelected(); true }.getOrDefault(false)
        }
        val to = compose.onNodeWithTag("slot:page:1:2").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { moveTo(to, delayMillis = 400); up() }
        waitLayout { it.at(pageContainer(1), 2)?.id == id }
    }
    @Test fun changingGridAndDockSavesConfiguration() {
        seedTwo()
        compose.openLayout()
        compose.onNodeWithTag("columns-plus").performClick()
        compose.onNodeWithTag("rows-minus").performClick()
        compose.onNodeWithTag("dockSlots-minus").performClick()
        compose.onNodeWithTag("saveLayout").performClick()
        waitLayout { it.config.columns == 5 && it.config.rows == 4 && it.config.dockSlots == 4 }
        compose.activityRule.scenario.recreate()
        waitLayout { it.config.columns == 5 && it.items.size == 2 }
    }
}





