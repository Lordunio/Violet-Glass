package pl.violetglass.launcher

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.*

@RunWith(AndroidJUnit4::class)
class PersonalizationUiTest {
    @get:Rule val compose = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
        activityProvider = { rule -> var activity: MainActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) },
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LayoutState
    private lateinit var preferences: LauncherPreferences
    private var previousHomes: List<String> = emptyList()
    private val user by lazy { shell("am get-current-user").trim().toInt() }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    @Before fun prepare() {
        previousHomes = shell("cmd role get-role-holders --user $user android.app.role.HOME").lines().map(String::trim).filter(String::isNotEmpty)
        shell("cmd role add-role-holder --user $user android.app.role.HOME pl.violetglass.launcher")
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            model.layoutRepository.initialize()
            original = model.layoutRepository.state.first { it != null }!!
            preferences = model.preferencesRepository.data.first()
            model.preferencesRepository.update { LauncherPreferences() }
            model.layoutRepository.edit { LayoutState() }
        }
        compose.waitUntil(15_000) { model.preferences.value == LauncherPreferences() && model.workspace.value.layout?.items?.isEmpty() == true && model.state.value.apps.isNotEmpty() }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.waitForIdle()
    }
    @After fun restore() {
        if (::model.isInitialized) {
            shell("input keyevent KEYCODE_HOME")
            runBlocking {
                if (::original.isInitialized) model.layoutRepository.edit { original }
                if (::preferences.isInitialized) model.preferencesRepository.update { preferences }
            }
        }
        if (previousHomes.isEmpty()) shell("cmd role remove-role-holder --user $user android.app.role.HOME pl.violetglass.launcher")
        else previousHomes.forEach { shell("cmd role add-role-holder --user $user android.app.role.HOME $it") }
    }
    private fun fixture() = model.state.value.apps.single { it.component.packageName == "pl.violetglass.launcher.test" }
    private fun waitPreferences(predicate: (LauncherPreferences) -> Boolean) {
        // Leaving for a fixture app and returning HOME can recreate the hosted
        // Activity. Observe the persisted result, not a captured old stateIn VM.
        compose.waitUntil(15_000) { runBlocking { predicate(model.preferencesRepository.data.first()) } }; compose.waitForIdle()
    }
    private fun openSection(section: String) {
        compose.openSettings()
        compose.onNodeWithTag("section:$section").performScrollTo().performClick()
    }
    private fun closeSettings() { compose.onNodeWithTag("closePersonalization").performClick() }
    private fun emptyPoint() = compose.onNodeWithTag("slot:page:0:10").fetchSemanticsNode().boundsInRoot.center
    private fun home() {
        shell("input keyevent KEYCODE_HOME")
        compose.waitUntil(15_000) { runCatching {
            compose.onNodeWithTag("appDrawerGestureArea").assertIsDisplayed()
            compose.onNodeWithTag("desktopPager").assertIsDisplayed()
            compose.onNodeWithTag("page:0").assertIsSelected()
            true
        }.getOrDefault(false) }
        compose.waitForIdle()
    }
    private fun nativeText(text: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 24) return null
            if (node.text?.toString()?.contains(text, ignoreCase = true) == true && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) find(node.getChild(i), depth + 1)?.let { return it }
            return null
        }
        var node: AccessibilityNodeInfo? = null
        compose.waitUntil(15_000) { node = find(instrumentation.uiAutomation.rootInActiveWindow, 0); node != null }
        return node!!
    }

    @Test fun uninstallCancellationKeepsShortcutsAndConfirmationRemovesPackageAndCompactsFolder() {
        val removable = model.state.value.apps.singleOrNull { it.component.packageName == "pl.violetglass.uninstallfixture" }
        assertNotNull("Install the emulator-only fixture with tools/Build-UninstallFixture.ps1 -Install", removable)
        val app = removable!!
        val survivor = fixture()
        val folder = LayoutItem("uninstall-folder", title = "Folder", container = pageContainer(0), position = 0)
        val layout = LayoutState(items = listOf(folder,
            LayoutItem("remove-a", title = app.label, appKey = app.key, container = folderContainer(folder.id), position = 0),
            LayoutItem("keep", title = survivor.label, appKey = survivor.key, container = folderContainer(folder.id), position = 1),
            LayoutItem("remove-b", title = app.label, appKey = app.key, container = DOCK, position = 0)))
        runBlocking { model.layoutRepository.edit { layout } }
        compose.waitUntil(10000) { model.workspace.value.layout == layout }
        fun request() {
            compose.onNodeWithTag("item:${folder.id}").performClick()
            compose.onNodeWithTag("item:remove-a").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("uninstallApp").assertIsDisplayed().performClick()
        }
        fun confirm(accept: Boolean) {
            var button: AccessibilityNodeInfo? = null
            compose.waitUntil(15000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if(root?.packageName?.toString() == "pl.violetglass.launcher") false else {
                    button = root?.findAccessibilityNodeInfosByViewId(if(accept) "android:id/button1" else "android:id/button2")?.firstOrNull()
                    button != null
                }
            }
            assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        request(); confirm(false)
        home()
        assertEquals(layout, model.workspace.value.layout)
        assertTrue(model.state.value.apps.any { it.key == app.key })
        request(); confirm(true)
        home()
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        compose.waitUntil(20000) {
            runBlocking { model.layoutRepository.state.first()?.items?.none { it.appKey == app.key } == true } &&
                runCatching { instrumentation.targetContext.packageManager.getApplicationInfo(app.component.packageName, 0) }.isFailure
        }
        val cleaned = runBlocking { model.layoutRepository.state.first()!! }
        assertEquals(listOf("keep"), cleaned.children(folder.id).map { it.id })
        assertEquals(0, cleaned.children(folder.id).single().position)
        assertEquals(folder, cleaned.items.single { it.id == folder.id })
    }

    @Test fun liveIconSettingsPersistAcrossActivityRecreation() {
        val app = fixture()
        runBlocking { model.layoutRepository.edit { LayoutEngine.place(it, ItemPayload(appKey = app.key, title = "Mój testowy skrót"), Slot(pageContainer(0), 0)) } }
        compose.waitUntil(10_000) { model.workspace.value.layout?.items?.size == 1 }
        val id = model.workspace.value.layout!!.items.single().id
        openSection("ICONS")
        compose.onNodeWithTag("iconSize").performSemanticsAction(SemanticsActions.SetProgress) { it(56f) }
        compose.onNodeWithTag("iconLabels").performClick()
        waitPreferences { it.iconSize == 56 && !it.labels }
        closeSettings()
        compose.onNodeWithTag("item:$id").assertExists()
        compose.onNodeWithText("Mój testowy skrót").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitPreferences { it.iconSize == 56 && !it.labels }
        compose.onNodeWithTag("item:$id").assertExists()
    }

    @Test fun largestIconsReserveAnUnclippedLabelInTheSmallestDock() {
        val app = fixture()
        runBlocking {
            model.layoutRepository.edit { LayoutEngine.place(it, ItemPayload(appKey = app.key, title = "Kalendarz"), Slot(DOCK, 0)) }
            model.preferencesRepository.update { it.copy(iconSize = 72, labelSize = 18, dockHeight = 72, labels = true, gap = 12) }
        }
        waitPreferences { it.iconSize == 72 && it.labelSize == 18 && it.dockHeight == 72 }
        compose.waitUntil(10_000) { model.workspace.value.layout?.items?.size == 1 }
        val id = model.workspace.value.layout!!.items.single().id
        val label = compose.onNodeWithTag("itemLabel:$id", useUnmergedTree = true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val textLayout = layouts.single()
        // A horizontally ellipsized single line may report didExceedMaxLines.
        // Compare measured paragraph height to test actual vertical clipping.
        assertTrue("Label line ${textLayout.multiParagraph.height} must fit ${textLayout.size.height}px",
            textLayout.multiParagraph.height <= textLayout.size.height + 1f)
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val slotBounds = compose.onNodeWithTag("slot:dock:0").fetchSemanticsNode().boundsInRoot
        assertTrue("Label bottom must stay inside the dock cell", labelBounds.bottom <= slotBounds.bottom)
        assertTrue("Label top must stay inside the dock cell", labelBounds.top >= slotBounds.top)
    }

    @Test fun compactDockKeepsLabelsVisibleAtDoubleSystemFontScale() {
        val app = fixture()
        val dockLayout = LayoutEngine.place(LayoutState(LayoutConfig(dockSlots = 6)),
            ItemPayload(appKey = app.key, title = "Kalendarz"), Slot(DOCK, 0))
        val item = dockLayout.items.single()
        val registry = pl.violetglass.launcher.ui.DragRegistry()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
                pl.violetglass.launcher.ui.LocalLauncherPreferences provides
                    LauncherPreferences(iconSize = 72, labelSize = 18, gap = 12, dockHeight = 72),
            ) {
                pl.violetglass.launcher.ui.VioletTheme {
                    androidx.compose.foundation.layout.Box(Modifier.size(360.dp, 160.dp)) {
                        pl.violetglass.launcher.ui.WorkspaceDock(dockLayout, mapOf(app.key to app), model.repository,
                            0L, registry, null, false, {}, {}, {}, compact = true)
                    }
                }
            }
        } }
        compose.waitForIdle()
        val label = compose.onNodeWithTag("itemLabel:${item.id}", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val measured = layouts.single()
        assertTrue("The full text line must fit at 200% font scale: paragraph=${measured.multiParagraph.height}, measured=${measured.size}, label=${label.fetchSemanticsNode().boundsInRoot}",
            measured.multiParagraph.height <= measured.size.height + 1f)
        val labelBounds = label.fetchSemanticsNode().boundsInRoot
        val cellBounds = compose.onNodeWithTag("slot:dock:0").fetchSemanticsNode().boundsInRoot
        assertTrue(labelBounds.top >= cellBounds.top && labelBounds.bottom <= cellBounds.bottom + 1f)
    }

    @Test fun largeIconsKeepLastRowLabelsVisibleInASixColumnViewport() {
        val app = fixture()
        val sizedLayout = LayoutEngine.place(LayoutState(LayoutConfig(columns = 6, rows = 7, dockSlots = 6)),
            ItemPayload(appKey = app.key, title = "Kalendarz"), Slot(pageContainer(0), 41))
        val item = sizedLayout.items.single()
        val registry = pl.violetglass.launcher.ui.DragRegistry()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(pl.violetglass.launcher.ui.LocalLauncherPreferences provides
                LauncherPreferences(iconSize = 72, labelSize = 12, gap = 2)) {
                pl.violetglass.launcher.ui.VioletTheme {
                    pl.violetglass.launcher.ui.WorkspaceGrid(sizedLayout, 0, mapOf(app.key to app), model.repository, 0L,
                        registry, null, false, {}, {}, {}, Modifier.size(360.dp, 570.dp).testTag("sizingGrid"))
                }
            }
        } }
        compose.waitForIdle()
        val label = compose.onNodeWithTag("itemLabel:${item.id}", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("sizingGrid").fetchSemanticsNode().boundsInRoot
        assertTrue("Last-row label must fit the visible grid without extra scrolling", label.bottom <= grid.bottom + 1f)
    }

    @Test fun photoLabelsStayReadableOnWhiteAndBlackAndHonorManualColor() {
        val app = fixture()
        val layout = LayoutEngine.place(LayoutState(LayoutConfig(columns = 6)), ItemPayload(appKey = app.key, title = "ĄgÉy"), Slot(pageContainer(0), 0))
        val item = layout.items.single()
        val background = mutableStateOf(Color.White)
        val choice = mutableStateOf(LabelColor.AUTO)
        val registry = pl.violetglass.launcher.ui.DragRegistry()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(
                pl.violetglass.launcher.ui.LocalLauncherPreferences provides LauncherPreferences(iconSize = 72, labelSize = 18, labelColor = choice.value),
                pl.violetglass.launcher.ui.LocalWorkspaceLabelBackdrop provides pl.violetglass.launcher.ui.WorkspaceLabelBackdrop.PHOTO,
            ) {
                pl.violetglass.launcher.ui.VioletTheme {
                    pl.violetglass.launcher.ui.ItemTile(item, layout, mapOf(app.key to app), model.repository, 0L, registry,
                        {}, {}, Modifier.size(100.dp, 96.dp).background(background.value))
                }
            }
        } }
        val label = compose.onNodeWithTag("itemLabel:${item.id}", useUnmergedTree = true)
        fun assertColor(color: Color) {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertEquals(color, results.single().layoutInput.style.color)
        }
        for(photoColor in listOf(Color.White, Color.Black)) {
            compose.runOnIdle { background.value = photoColor }
            assertColor(Color.White)
            val pixels = label.captureToImage().toPixelMap()
            assertTrue("The label must have a dark backing even over a white photo",
                pixels[pixels.width / 2, 1].luminance() < .18f)
        }
        compose.runOnIdle { background.value = Color.White; choice.value = LabelColor.BLACK }
        assertColor(Color.Black)
        val pixels = label.captureToImage().toPixelMap()
        assertTrue("Manual black text must use the selected photo as its background",
            pixels[pixels.width / 2, 1].luminance() > .85f)
    }

    @Test fun shortLandscapeDockScrollsToAFullLabelAtDoubleFontScale() {
        val app = fixture()
        val layout = LayoutState(LayoutConfig(dockSlots = 7), (0 until 7).map {
            LayoutItem("scaled-dock-$it", title = "Żółć i aplikacja $it", appKey = app.key, container = DOCK, position = it)
        })
        val registry = pl.violetglass.launcher.ui.DragRegistry()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f),
                pl.violetglass.launcher.ui.LocalLauncherPreferences provides LauncherPreferences(iconSize = 72, labelSize = 18, gap = 12),
            ) {
                pl.violetglass.launcher.ui.VioletTheme {
                    androidx.compose.foundation.layout.Box(Modifier.size(160.dp, 200.dp)) {
                        pl.violetglass.launcher.ui.WorkspaceSideDock(layout, mapOf(app.key to app), model.repository,
                            0L, registry, null, false, {}, {}, {})
                    }
                }
            }
        } }
        val label = compose.onNodeWithTag("itemLabel:scaled-dock-6", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val measured = layouts.single()
        assertTrue(measured.multiParagraph.height <= measured.size.height + 1f)
        assertTrue(label.fetchSemanticsNode().boundsInRoot.height >= measured.size.height - 1f)
    }

    @Test fun desktopLockAllowsDrawerAndUnlock() {
        compose.openDesktopOptions()
        compose.onNodeWithTag("desktopLock").performClick()
        waitPreferences { it.locked }
        compose.openDrawer()
        compose.onNodeWithTag("appsGrid").assertIsDisplayed()
        compose.onNodeWithTag("homeButton").performClick()
        compose.openDesktopOptions()
        compose.onNodeWithTag("desktopGrid").assertIsNotEnabled()
        compose.onNodeWithTag("desktopLock").performClick()
        waitPreferences { !it.locked }
    }

    @Test fun glassPreviewUndoAndSave() {
        val original = model.preferences.value.glass.transparency
        val target = if (original == 90) 10 else 90
        openSection("GLASS")
        compose.onNodeWithTag("glassPreviewOpen").performClick()
        val slider = compose.onNodeWithTag("previewSlider")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(target.toFloat()) }
        compose.onNodeWithTag("previewUndo").performClick()
        compose.onNodeWithTag("previewUndo").assertIsNotEnabled()
        assertEquals(original, model.preferences.value.glass.transparency)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(target.toFloat()) }
        compose.onNodeWithTag("previewSave").performClick()
        waitPreferences { it.glass.transparency == target }
        compose.onNodeWithTag("glassPreviewOpen").assertIsDisplayed()
    }

    @Test fun layoutLockDisablesAddingFromDrawer() {
        openSection("DESKTOP")
        compose.onNodeWithTag("lockLayout").performClick()
        waitPreferences { it.locked }
        closeSettings()
        compose.openDrawer()
        val app = model.state.value.apps.first()
        compose.onNodeWithTag("app:${app.key}").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("addHome").assertIsNotEnabled()
        assertTrue(model.workspace.value.layout!!.items.isEmpty())
    }

    @Test fun drawerCategoriesCanBeCreatedFilteredAndKeptAfterReopening() {
        val app = fixture()
        compose.openDrawer()
        compose.onNodeWithTag("drawerCategoriesManage").performClick()
        compose.onNodeWithTag("drawerCategoryNew").performClick()
        compose.onNodeWithTag("drawerCategoryName").performTextInput("Dom")
        compose.onNodeWithTag("drawerCategorySearch").performTextInput(app.component.packageName)
        compose.onNodeWithTag("drawerCategoryApp:${app.key}").performClick().assertIsOn()
        compose.onNodeWithTag("drawerCategorySave").performClick()
        waitPreferences { it.drawerCategories["Dom"] == setOf(app.key) }
        compose.onNodeWithTag("drawerCategory:Dom").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.apps_count, 1)).assertIsDisplayed()
        compose.onNodeWithTag("app:${app.key}").assertIsDisplayed()
        compose.onNodeWithTag("homeButton").performClick()
        compose.openDrawer()
        compose.onNodeWithTag("drawerCategoryAll").assertIsSelected()
        compose.onNodeWithTag("drawerCategory:Dom").performClick()
        compose.onNodeWithTag("drawerCategoriesManage").performClick()
        compose.onNodeWithTag("drawerCategoryDelete").performClick()
        waitPreferences { it.drawerCategories.isEmpty() }
        compose.onNodeWithTag("drawerCategoryAll").assertIsSelected()
    }

    @Test fun downwardDrawerGestureClosesOnlyWhenStartedAtTheTop() {
        runBlocking { model.preferencesRepository.update { it.copy(drawerColumns = 3, iconSize = 72) } }
        waitPreferences { it.drawerColumns == 3 && it.iconSize == 72 }
        compose.openDrawer()
        val grid = compose.onNodeWithTag("appsGrid")
        grid.performScrollToIndex(model.state.value.apps.lastIndex)
        grid.performTouchInput { swipeDown(durationMillis = 300) }
        compose.onNodeWithTag("appSearch").assertIsDisplayed()
        grid.performScrollToIndex(0)
        grid.performTouchInput { swipeDown(durationMillis = 300) }
        compose.onNodeWithTag("appSearch").assertDoesNotExist()
        compose.onNodeWithTag("desktopPager").assertIsDisplayed()
    }

    @Test fun narrowDrawerKeepsReadableSearchCategoryAndAppName() {
        val app = fixture()
        fun textLayout(node: SemanticsNodeInteraction): androidx.compose.ui.text.TextLayoutResult {
            val result = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            return result.single()
        }
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            runBlocking { model.preferencesRepository.update { it.copy(theme = theme, systemWallpaper = false,
                drawerColumns = 8, iconSize = 72, drawerCategories = mapOf("Dom" to setOf(app.key)),
                glass = GlassSettings(mode = GlassMode.ULTRA, transparency = 100, reflections = 100)) } }
            waitPreferences { it.theme == theme && it.glass.transparency == 100 }
            compose.openDrawer()
            val labels = listOf(R.string.search_apps, R.string.drawer_categories, R.string.home, R.string.all_apps)
                .map { compose.activity.getString(it) } + "Dom"
            val dark = theme == ThemeMode.DARK
            for (label in labels) {
                val node = compose.onNodeWithText(label, useUnmergedTree = true)
                val style = textLayout(node).layoutInput.style
                assertEquals(label, Color.Unspecified, style.background)
                val luminance = style.color.luminance()
                assertTrue(label, if (dark || label == compose.activity.getString(R.string.search_apps)) luminance > .65f else luminance < .15f)
            }
            val heading = compose.onNodeWithText(compose.activity.getString(R.string.all_apps), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val home = compose.onNodeWithTag("homeButton").fetchSemanticsNode().boundsInRoot
            assertTrue("Pulpit should share the title row", home.center.y in heading.top..heading.bottom)
            assertTrue("Pulpit should sit to the right of the title", home.left >= heading.right)
            compose.onNodeWithTag("drawerCategory:Dom").performClick().assertIsSelected()
            val appName = textLayout(compose.onNodeWithTag("appLabel:${app.key}", useUnmergedTree = true))
            val foreground = appName.layoutInput.style.color.luminance()
            assertTrue(foreground > .95f)
            assertEquals(app.label, appName.layoutInput.text.text)
            assertFalse(appName.hasVisualOverflow)
            assertEquals(Color.Unspecified, appName.layoutInput.style.background)
            compose.onNodeWithTag("homeButton").performClick()
        }
    }

    @Test fun hidingAppFiltersSearchButKeepsDesktopShortcut() {
        val app = fixture()
        runBlocking { model.layoutRepository.edit { LayoutEngine.place(it, ItemPayload(appKey = app.key, title = app.label), Slot(pageContainer(0), 0)) } }
        compose.waitUntil(10_000) { model.workspace.value.layout?.items?.size == 1 }
        val id = model.workspace.value.layout!!.items.single().id
        openSection("DRAWER")
        compose.onNodeWithTag("hiddenApps").performClick()
        compose.onNodeWithTag("appPickerSearch").performTextInput("Violet Fixture")
        compose.onNodeWithTag("pickApp:${app.key}").performClick()
        waitPreferences { app.key in it.hiddenApps }
        compose.onNodeWithTag("closeAppPicker").performClick()
        closeSettings()
        compose.openDrawer()
        compose.onNodeWithTag("appSearch").performTextInput("Violet Fixture")
        compose.onNodeWithText(compose.activity.getString(R.string.no_results)).assertIsDisplayed()
        compose.onNodeWithTag("homeButton").performClick()
        compose.onNodeWithTag("item:$id").assertExists()
    }

    @Test fun swipeUpOpensAppsAndSwipeDownFocusesSearch() {
        var area = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(Offset(area.center.x, area.bottom - 80f), Offset(area.center.x, area.top + 80f), 400) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("appSearch").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("homeButton").performClick()
        area = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(Offset(area.center.x, area.top + 80f), Offset(area.center.x, area.bottom - 80f), 400) }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("appSearch").assertIsFocused(); true }.getOrDefault(false) }
    }

    @Test fun doubleTapAndLongPressUseSavedAssignments() {
        runBlocking { model.preferencesRepository.update { it.copy(gestures = it.gestures + (DesktopGesture.DOUBLE_TAP to GestureBinding(GestureAction.SETTINGS))) } }
        waitPreferences { it.gestures.getValue(DesktopGesture.DOUBLE_TAP).action == GestureAction.SETTINGS }
        var point = emptyPoint()
        compose.onNodeWithTag("launcherRoot").performTouchInput { doubleClick(point) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("closePersonalization").fetchSemanticsNodes().isNotEmpty() }
        closeSettings()
        point = emptyPoint()
        compose.onNodeWithTag("launcherRoot").performTouchInput { longClick(point) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("desktopSettings").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun twoFingerUpHasIndependentAction() {
        runBlocking { model.preferencesRepository.update { it.copy(gestures = it.gestures + (DesktopGesture.TWO_FINGER_UP to GestureBinding(GestureAction.SETTINGS))) } }
        waitPreferences { it.gestures.getValue(DesktopGesture.TWO_FINGER_UP).action == GestureAction.SETTINGS }
        val area = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        val first = Offset(area.center.x - 30, area.bottom - 80)
        val second = Offset(area.center.x + 30, area.bottom - 80)
        compose.onNodeWithTag("launcherRoot").performTouchInput {
            down(0, first); down(1, second)
            moveTo(0, Offset(first.x, area.top + 100), delayMillis = 180)
            moveTo(1, Offset(second.x, area.top + 100), delayMillis = 180)
            up(0); up(1)
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("closePersonalization").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun configuredGestureLaunchesAppAndManifestShortcut() {
        val app = fixture()
        openSection("GESTURES")
        compose.onNodeWithTag("gesture:DOUBLE_TAP").performClick()
        compose.onNodeWithTag("action:APP").performScrollTo().performClick()
        compose.onNodeWithTag("appPickerSearch").performTextInput("Violet Fixture")
        compose.onNodeWithTag("pickApp:${app.key}").performClick()
        waitPreferences { it.gestures.getValue(DesktopGesture.DOUBLE_TAP).action == GestureAction.APP }
        closeSettings()
        var point = emptyPoint()
        compose.onNodeWithTag("launcherRoot").performTouchInput { doubleClick(point) }
        nativeText("Uruchomiono aplikację testową")
        home()
        openSection("GESTURES")
        compose.onNodeWithTag("gesture:DOUBLE_TAP").performClick()
        compose.onNodeWithTag("action:SHORTCUT").performScrollTo().performClick()
        compose.onNodeWithTag("appPickerSearch").performTextInput("Violet Fixture")
        compose.onNodeWithTag("pickApp:${app.key}").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Skrót testowy").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Skrót testowy").performSemanticsAction(SemanticsActions.OnClick) { it() }
        waitPreferences { it.gestures.getValue(DesktopGesture.DOUBLE_TAP).action == GestureAction.SHORTCUT }
        closeSettings()
        point = emptyPoint()
        compose.onNodeWithTag("launcherRoot").performTouchInput { doubleClick(point) }
        nativeText("Uruchomiono skrót testowy")
        home()
    }
    private fun pinch(first: Offset, second: Offset, remaining: Float = .25f) {
        val middle = (first + second) / 2f
        compose.onNodeWithTag("launcherRoot").performTouchInput {
            down(0, first); down(1, second)
            repeat(10) { step ->
                val scale = 1f + (remaining - 1f) * (step + 1) / 10f
                moveTo(0, middle + (first - middle) * scale, delayMillis = 16)
                moveTo(1, middle + (second - middle) * scale, delayMillis = 16)
            }
            up(0); up(1)
        }
    }

    @Test fun pinchOpensDesktopOptionsOverEmptySpaceAndIconsWithoutOpeningFolderOrDrawer() {
        val area = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        pinch(area.center - Offset(150f, 0f), area.center + Offset(150f, 0f))
        compose.onNodeWithTag("desktopSettings").assertIsDisplayed()
        compose.onNodeWithTag("appSearch").assertDoesNotExist()
        compose.onNodeWithText("Zamknij").performClick()
        compose.onNodeWithTag("desktopSettings").assertDoesNotExist()
        val app = fixture()
        runBlocking { model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, rows = 8), listOf(
            LayoutItem("pinch-app", title = app.label, appKey = app.key, container = pageContainer(0), position = 18),
            LayoutItem("pinch-folder", title = "Folder", container = pageContainer(0), position = 21))) } }
        compose.waitUntil(10000) { model.workspace.value.layout?.items?.size == 2 }
        val first = compose.onNodeWithTag("item:pinch-app").fetchSemanticsNode().boundsInRoot.center
        val second = compose.onNodeWithTag("item:pinch-folder").fetchSemanticsNode().boundsInRoot.center
        pinch(first, second)
        compose.onNodeWithTag("desktopSettings").assertIsDisplayed()
        compose.onNodeWithTag("folderContents").assertDoesNotExist()
        compose.onNodeWithTag("appSearch").assertDoesNotExist()
        assertEquals(2, model.workspace.value.layout!!.items.size)
    }

    @Test fun tinyPinchAndSpreadDoNotOpenDesktopOptions() {
        val area = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        val first = area.center - Offset(150f, 0f)
        val second = area.center + Offset(150f, 0f)
        pinch(first, second, .97f)
        compose.onNodeWithTag("desktopSettings").assertDoesNotExist()
        pinch(first, second, 1.4f)
        compose.onNodeWithTag("desktopSettings").assertDoesNotExist()
        compose.onNodeWithTag("appSearch").assertDoesNotExist()
    }

    @Test fun autoRotationSwitchPersistsAndDisablingReturnsToPortrait() {
        // Host test driver supplies landscape accelerometer readings. User-rotation
        // lock via UiAutomation does not drive a FULL_SENSOR activity.
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("sensorLandscape") == "true")
        openSection("DESKTOP")
        compose.onNodeWithTag("autoRotate").assertIsOff().performClick()
        val rotatingActivity = compose.activity
        waitPreferences { it.autoRotate }
        compose.waitUntil(10000) { compose.activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
        run {
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
            assertSame("Rotation must keep the launcher Activity and its hosted windows", rotatingActivity, compose.activity)
            closeSettings()
            val app = fixture()
            runBlocking { model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, rows = 8, dockSlots = 6), listOf(
                LayoutItem("rotate-app", title = app.label, appKey = app.key, container = pageContainer(0), position = 6),
                LayoutItem("rotate-folder", title = "Folder", container = pageContainer(0), position = 7)) +
                (0 until 6).map { LayoutItem("rotate-dock-$it", title = "Ikona $it", appKey = app.key, container = DOCK, position = it) }) } }
            compose.waitUntil(10000) { model.workspace.value.layout?.items?.size == 8 }
            val desktop = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
            val dock = compose.onNodeWithTag("landscapeDock").fetchSemanticsNode().boundsInRoot
            assertTrue(dock.left >= desktop.right)
            for (index in 0 until 6) {
                val label = compose.onNodeWithTag("itemLabel:rotate-dock-$index", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue(label.top >= dock.top && label.bottom <= dock.bottom + 1)
            }
            val first = compose.onNodeWithTag("item:rotate-app").fetchSemanticsNode().boundsInRoot.center
            val second = compose.onNodeWithTag("item:rotate-folder").fetchSemanticsNode().boundsInRoot.center
            pinch(first, second)
            compose.onNodeWithTag("desktopSettings").assertIsDisplayed()
            compose.onNodeWithText("Zamknij").performClick()
            compose.onNodeWithTag("desktopSettings").assertDoesNotExist()
            assertDrawerSwipes(listOf("rotate-app", "rotate-folder", "rotate-dock-5"))
            openSection("DESKTOP")
            compose.activityRule.scenario.recreate()
            waitPreferences { it.autoRotate }
            compose.waitUntil(10000) { compose.activity.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR }
            // Reopen after recreation if the settings dialog was dismissed.
            if (compose.onAllNodesWithTag("autoRotate").fetchSemanticsNodes().isEmpty()) openSection("DESKTOP")
            compose.onNodeWithTag("autoRotate").assertIsOn().performClick()
            waitPreferences { !it.autoRotate }
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
            assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)
        }
    }

    @Test fun swipeUpStartsOverAnApplicationAndAFolderWithoutOpeningEither() {
        val app = fixture()
        runBlocking {
            model.preferencesRepository.update { it.copy(iconSize = 72, labelSize = 18) }
            model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, rows = 8, dockSlots = 6), listOf(
            LayoutItem("swipe-app", title = app.label, appKey = app.key, container = pageContainer(0), position = 22),
            LayoutItem("swipe-folder", title = "Folder", container = pageContainer(0), position = 23),
            LayoutItem("swipe-dock-app", title = app.label, appKey = app.key, container = DOCK, position = 0),
            LayoutItem("swipe-dock-folder", title = "Dok", container = DOCK, position = 1))) } }
        compose.waitUntil(10000) { model.workspace.value.layout?.items?.size == 4 }
        waitPreferences { it.iconSize == 72 }
        assertDrawerSwipes(listOf("swipe-app", "swipe-folder", "swipe-dock-app", "swipe-dock-folder"))
    }

    private fun assertDrawerSwipes(ids: List<String>) {
        for(id in ids) {
            val point = compose.onNodeWithTag("item:$id").fetchSemanticsNode().boundsInRoot.center
            // Short, fast movement: the grid must not consume it and move the hit bounds.
            compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(point, point - Offset(12f, 150f), 120) }
            try { compose.onNodeWithTag("appSearch").assertIsDisplayed() }
            catch (failure: AssertionError) {
                throw AssertionError("Swipe over $id from $point did not open drawer", failure)
            }
            compose.onNodeWithTag("folderContents").assertDoesNotExist()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
        }
    }
}
