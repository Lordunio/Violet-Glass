package pl.violetglass.launcher

import android.view.WindowManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.*

@RunWith(AndroidJUnit4::class)
class Stage6UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    private lateinit var oldLayout: LayoutState
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            model.layoutRepository.initialize(); oldLayout = model.layoutRepository.state.first { it != null }!!
            original = model.preferencesRepository.data.first()
            model.preferencesRepository.update { LauncherPreferences(discover = DiscoverSettings(enabled = false), ha = pl.violetglass.launcher.ha.HaSettings(enabled = false)) }
            model.layoutRepository.edit { LayoutState() }
        }
        compose.waitUntil(15_000) { model.workspace.value.layout?.items?.isEmpty() == true && !model.preferences.value.discover.enabled }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.waitForIdle()
    }
    @After fun restore() { if (::original.isInitialized) runBlocking { model.preferencesRepository.update { original }; model.layoutRepository.edit { oldLayout } } }
    @Test fun cleanDesktopShowsOptionsOnlyAfterActualLongPress() {
        compose.onNodeWithTag("layoutButton").assertDoesNotExist()
        compose.onNodeWithTag("personalizationButton").assertDoesNotExist()
        compose.onNodeWithText("VIOLET GLASS · 0.6").assertDoesNotExist()
        compose.onNodeWithTag("desktopSettings").assertDoesNotExist()
        compose.onNodeWithTag("desktopPager").performTouchInput { longClick(center) }
        compose.onNodeWithTag("desktopSettings").assertIsDisplayed().performClick()
        compose.onNodeWithTag("section:DESKTOP").assertIsDisplayed()
        compose.onNodeWithTag("closePersonalization").performClick()
        compose.onNodeWithTag("appDrawerGestureArea").performTouchInput { longClick(center) }
        compose.onNodeWithTag("desktopSettings").assertIsDisplayed()
    }
    @Test fun folderDragReordersChildrenWithoutClosingFolderAndSurvivesRecreation() {
        runBlocking { model.layoutRepository.edit { state ->
            val a = LayoutEngine.place(state, ItemPayload(appKey = "a", title = "Alfa"), Slot(pageContainer(0), 0))
            LayoutEngine.place(a, ItemPayload(appKey = "b", title = "Beta"), Slot(pageContainer(0), 0))
        } }
        compose.waitUntil(10_000) { model.workspace.value.layout?.items?.size == 3 }
        val folder = model.workspace.value.layout!!.items.single { it.isFolder }
        val a = model.workspace.value.layout!!.children(folder.id).first()
        val b = model.workspace.value.layout!!.children(folder.id).last()
        compose.onNodeWithTag("item:${folder.id}").performClick()
        val from = compose.onNodeWithTag("item:${a.id}").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithTag("item:${b.id}").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput {
            down(from); advanceEventTime(700); moveTo(from + Offset(16f, 0f), 80); moveTo(to, 350); up()
        }
        compose.waitUntil(10_000) { model.workspace.value.layout?.items?.find { it.id == a.id }?.position == 1 }
        compose.onNodeWithTag("folderContents").assertIsDisplayed()
        assertEquals(listOf(b.id, a.id), model.workspace.value.layout!!.children(folder.id).map { it.id })
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("folderContents").assertIsDisplayed()
        assertEquals(listOf(b.id, a.id), model.workspace.value.layout!!.children(folder.id).map { it.id })
    }
    @Test fun initializingExistingLayoutRepairsFolderGapsAndPreservesPageAndDockSlots() {
        val folder = LayoutItem("old-folder", title = "Folder", container = DOCK, position = 3)
        val desktop = LayoutItem("unchanged", title = "Pulpit", appKey = "other", container = pageContainer(0), position = 9)
        runBlocking {
            model.layoutRepository.edit { LayoutState(items = listOf(folder, desktop,
                LayoutItem("old-a", title = "A", appKey = "a", container = folderContainer(folder.id), position = 2),
                LayoutItem("old-b", title = "B", appKey = "b", container = folderContainer(folder.id), position = 7))) }
            model.layoutRepository.initialize()
        }
        compose.waitUntil(10000) { model.workspace.value.layout?.children(folder.id)?.map { it.position } == listOf(0, 1) }
        assertEquals(folder, model.workspace.value.layout!!.items.single { it.id == folder.id })
        assertEquals(desktop, model.workspace.value.layout!!.items.single { it.id == desktop.id })
    }

    @Test fun removingFolderChildClosesGapAndPersistsWithoutMovingDesktop() {
        val folder = LayoutItem("compact-folder", title = "Folder", container = pageContainer(0), position = 0)
        val children = (0..2).map { LayoutItem("child-$it", title = "Ikona $it", appKey = "test-$it", container = folderContainer(folder.id), position = it) }
        val desktop = LayoutItem("untouched", title = "Zostaje", appKey = "other", container = pageContainer(0), position = 8)
        runBlocking { model.layoutRepository.edit { LayoutState(items = listOf(folder, desktop) + children) } }
        compose.waitUntil(10000) { model.workspace.value.layout?.items?.size == 5 }
        compose.onNodeWithTag("item:${folder.id}").performClick()
        compose.onNodeWithTag("item:child-1").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("removeItem").performClick()
        compose.waitUntil(10000) { model.workspace.value.layout?.children(folder.id)?.size == 2 }
        val layout = model.workspace.value.layout!!
        assertEquals(listOf(0, 1), layout.children(folder.id).map { it.position })
        assertEquals(desktop, layout.items.single { it.id == desktop.id })
        compose.onNodeWithTag("item:child-2").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { model.workspace.value.layout?.children(folder.id)?.size == 2 }
        assertEquals(listOf("child-0", "child-2"), model.workspace.value.layout!!.children(folder.id).map { it.id })
    }

    @Test fun sixColumnPresetChangesGridAndDockWithoutRemovingItems() {
        compose.openSettings()
        compose.onNodeWithTag("section:DESKTOP").performClick()
        compose.onNodeWithTag("sixColumns").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.workspace.value.layout?.config?.columns == 6 && model.workspace.value.layout?.config?.dockSlots == 6 }
        compose.onNodeWithTag("closePersonalization").performClick()
        compose.onNodeWithTag("slot:page:0:5").assertExists()
        compose.onNodeWithTag("slot:dock:5").assertExists()
    }
    @Test fun wallpaperFlagCanBeDisabledWithoutReadingPrivateWallpaperFile() {
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0) }
        runBlocking { model.preferencesRepository.update { it.copy(systemWallpaper = false) } }
        compose.waitUntil(10_000) { !model.preferences.value.systemWallpaper }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) }
    }
}
