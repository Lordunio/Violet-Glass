package pl.violetglass.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
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
class WidgetHostUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LayoutState
    private lateinit var preferences: LauncherPreferences
    private var originalIds: Set<Int> = emptySet()
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private val currentUser by lazy { shell("am get-current-user").trim().toInt() }
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            model.layoutRepository.initialize()
            original = model.layoutRepository.state.first { it != null }!!
            preferences = model.preferencesRepository.data.first()
            model.preferencesRepository.update { LauncherPreferences() }
            model.layoutRepository.edit { LayoutState() }
        }
        originalIds = compose.activity.widgets.host.appWidgetIds.toSet()
        compose.waitUntil(15_000) { model.workspace.value.layout?.items?.isEmpty() == true && !model.preferences.value.locked }
    }
    @After fun restore() {
        shell("appwidget revokebind --package pl.violetglass.launcher --user $currentUser")
        if (::model.isInitialized) {
            compose.activityRule.scenario.onActivity { activity ->
                model.cancelWidget()
                (activity.widgets.host.appWidgetIds.toSet() - originalIds).forEach { activity.widgets.host.deleteAppWidgetId(it) }
            }
            runBlocking {
                if (::original.isInitialized) model.layoutRepository.edit { original }
                if (::preferences.isInitialized) model.preferencesRepository.update { preferences }
            }
        }
    }
    private fun provider() = compose.activity.widgets.providers().single {
        it.provider == ComponentName("pl.violetglass.launcher.test", "pl.violetglass.launcher.fixtures.TestWidgetProvider")
    }
    private fun waitLayout(predicate: (LayoutState) -> Boolean) {
        compose.waitUntil(15_000) { model.workspace.value.layout?.let(predicate) == true }
        compose.waitForIdle()
    }
    private fun waitNativeText(text: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        try { compose.waitUntil(20_000) {
            fun find(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
                if (node == null || depth > 24) return null
                if (node.text?.toString()?.contains(text, ignoreCase = true) == true && node.isVisibleToUser) return node
                for (index in 0 until node.childCount) find(node.getChild(index), depth + 1)?.let { return it }
                return null
            }
            result = find(instrumentation.uiAutomation.rootInActiveWindow, 0)
            result != null
        } } catch (error: Throwable) {
            captureFailure()
            throw error
        }
        return requireNotNull(result)
    }
    private fun captureFailure() {
        val context = instrumentation.targetContext
        val name = "violet-widget-${SystemClock.uptimeMillis()}.png"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VioletTests")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        android.util.Log.d("VioletWidgetTest", "Screenshot: $name")
        fun dump(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12) return
            android.util.Log.d("VioletWidgetTest", "${node.className} text=${node.text} desc=${node.contentDescription}")
            for (index in 0 until node.childCount) dump(node.getChild(index), depth + 1)
        }
        dump(instrumentation.uiAutomation.rootInActiveWindow, 0)
    }
    private fun tapNative(text: String, holdMillis: Long = 50) {
        val node = waitNativeText(text)
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val now = SystemClock.uptimeMillis()
        fun event(action: Int, time: Long) = MotionEvent.obtain(now, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val down = event(MotionEvent.ACTION_DOWN, now)
        instrumentation.uiAutomation.injectInputEvent(down, true); down.recycle()
        if(holdMillis > 50) compose.mainClock.advanceTimeBy(holdMillis)
        SystemClock.sleep(holdMillis)
        val up = event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
        instrumentation.uiAutomation.injectInputEvent(up, true); up.recycle()
    }
    private fun addConfiguredWidget(): LayoutItem {
        shell("appwidget grantbind --package pl.violetglass.launcher --user $currentUser")
        val info = provider()
        compose.activityRule.scenario.onActivity { it.widgets.add(info, 0) }
        tapNative("Zapisz widget testowy")
        waitLayout { it.items.any { item -> item.isWidget } }
        compose.waitUntil(10_000) { model.pending() == null }
        waitNativeText("Widget działa: 0")
        return model.workspace.value.layout!!.items.single { it.isWidget }
    }

    @Test fun fullWidthWidgetStaysOnItsPageAfterRowsChangeAndDragsFromItsCenter() {
        val widget = addConfiguredWidget()
        runBlocking { model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, rows = 5, pages = 3, dockSlots = 6),
            listOf(widget.copy(container = "page:2", position = 0, spanX = 6, spanY = 2))) } }
        waitLayout { it.config.pages == 3 && it.items.single().spanX == 6 }
        compose.onNodeWithTag("page:2").performClick(); compose.waitForIdle()
        compose.openLayout()
        compose.onNodeWithTag("rows-plus").performClick()
        compose.onNodeWithTag("saveLayout").performClick()
        waitLayout { it.config.rows == 6 }
        assertEquals("page:2", model.workspace.value.layout!!.items.single().container)
        val source = compose.onNodeWithTag("widget:${widget.id}").fetchSemanticsNode().boundsInRoot.center
        val from = compose.onNodeWithTag("slot:page:2:3").fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithTag("slot:page:2:9").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { down(source) }
        Thread.sleep(800); compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("launcherRoot").performTouchInput { moveTo(source + (to - from), delayMillis = 450); up() }
        waitLayout { it.items.single().position == 6 }
        assertEquals("page:2", model.workspace.value.layout!!.items.single().container)
        assertEquals(widget.widgetId, model.workspace.value.layout!!.items.single().widgetId)
    }
    @Test fun realRemoteViewsConfigureRespondResizeMoveAndDelete() {
        val widget = addConfiguredWidget()
        tapNative("Zwiększ licznik")
        waitNativeText("Widget działa: 1")
        val before = compose.activity.widgets.manager.getAppWidgetOptions(widget.widgetId!!).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        compose.onNodeWithTag("widget:${widget.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("resizeWidget").performClick()
        compose.onNodeWithTag("widgetWidth-plus").performClick()
        compose.onNodeWithTag("widgetHeight-plus").performClick()
        compose.onNodeWithTag("saveWidgetSize").performClick()
        waitLayout { it.items.single().spanX == 3 && it.items.single().spanY == 3 }
        compose.waitUntil(10_000) { compose.activity.widgets.manager.getAppWidgetOptions(widget.widgetId).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) > before }
        compose.addWorkspacePage()
        waitLayout { it.config.pages == 2 }
        compose.onNodeWithTag("page:0").performClick()
        compose.onNodeWithTag("widget:${widget.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("moveWidget").performClick()
        compose.onNodeWithTag("page:1").performClick()
        compose.onNodeWithTag("slot:page:1:0").performClick()
        waitLayout { it.items.single().container == pageContainer(1) }
        assertEquals(widget.widgetId, model.workspace.value.layout!!.items.single().widgetId)
        waitNativeText("Widget działa: 1")
        compose.activityRule.scenario.recreate()
        waitNativeText("Widget działa: 1")
        compose.onNodeWithTag("widget:${widget.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("removeWidget").performClick()
        waitLayout { it.items.isEmpty() }
        compose.waitUntil(10_000) { widget.widgetId !in compose.activity.widgets.host.appWidgetIds }
    }

    @Test fun cancellingNativeBindConsentReleasesAllocatedId() {
        shell("appwidget revokebind --package pl.violetglass.launcher --user $currentUser")
        val info = provider()
        compose.activityRule.scenario.onActivity { it.widgets.add(info, 0) }
        compose.waitUntil(10_000) {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != "pl.violetglass.launcher" } == true
        }
        val id = requireNotNull(model.pending()).id
        shell("input keyevent KEYCODE_BACK")
        compose.waitUntil(10_000) { model.pending() == null }
        assertTrue(model.workspace.value.layout!!.items.isEmpty())
        assertFalse(id in compose.activity.widgets.host.appWidgetIds)
    }

    @Test fun pinchingNativeWidgetLeavesDesktopOptionsClosed() {
        val widget = addConfiguredWidget()
        val area = compose.onNodeWithTag("widget:${widget.id}").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput {
            down(0, area.center - androidx.compose.ui.geometry.Offset(area.width * .35f, 0f))
            down(1, area.center + androidx.compose.ui.geometry.Offset(area.width * .35f, 0f))
            repeat(10) { step ->
                val distance = area.width * (.35f - .025f * (step + 1))
                moveTo(0, area.center - androidx.compose.ui.geometry.Offset(distance, 0f), delayMillis = 16)
                moveTo(1, area.center + androidx.compose.ui.geometry.Offset(distance, 0f), delayMillis = 16)
            }
            up(0); up(1)
        }
        compose.onNodeWithTag("desktopSettings").assertDoesNotExist()
        compose.onNodeWithTag("appSearch").assertDoesNotExist()
        waitNativeText("Zwiększ licznik")
        assertEquals(widget.widgetId, model.workspace.value.layout!!.items.single().widgetId)
    }

    @Test fun holdingNativeWidgetButtonOpensOptionsWithoutClickOrThreeDotButton() {
        val widget = addConfiguredWidget()
        compose.onNodeWithTag("widgetMenu:${widget.id}").assertDoesNotExist()
        tapNative("Zwiększ licznik", android.view.ViewConfiguration.getLongPressTimeout().toLong() + 250)
        compose.onNodeWithTag("resizeWidget").assertIsDisplayed()
        compose.onNodeWithText("Zamknij").performClick()
        waitNativeText("Widget działa: 0")
        tapNative("Zwiększ licznik")
        waitNativeText("Widget działa: 1")
    }

    @Test fun cancellingProviderConfigurationDoesNotLeaveGhostWidget() {
        shell("appwidget grantbind --package pl.violetglass.launcher --user $currentUser")
        val info = provider()
        compose.activityRule.scenario.onActivity { it.widgets.add(info, 0) }
        waitNativeText("Zapisz widget testowy")
        val id = requireNotNull(model.pending()).id
        shell("input keyevent KEYCODE_BACK")
        compose.waitUntil(10_000) { model.pending() == null }
        assertTrue(model.workspace.value.layout!!.items.isEmpty())
        assertFalse(id in compose.activity.widgets.host.appWidgetIds)
    }
}




