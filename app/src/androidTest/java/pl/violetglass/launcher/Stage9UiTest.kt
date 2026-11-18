package pl.violetglass.launcher

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.violetglass.launcher.discover.*
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.ui.*
import pl.violetglass.launcher.workspace.*

/** Deterministic pager tests never contact a user's Google account. The real provider is checked separately. */
@RunWith(AndroidJUnit4::class)
class Stage9UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    private lateinit var oldLayout: LayoutState
    private var starts = 0
    private var stops = 0
    private var nativeProgress: ((Float) -> Unit)? = null
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            original = model.preferencesRepository.data.first()
            model.layoutRepository.initialize(); oldLayout = model.layoutRepository.state.first { it != null }!!
            model.preferencesRepository.update { LauncherPreferences(systemWallpaper = false) }
            model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, pages = 2, dockSlots = 6)) }
        }
        compose.waitUntil(10_000) { model.preferences.value.discover.enabled && model.preferences.value.ha.enabled && model.workspace.value.layout?.config?.pages == 2 }
        val fake: (android.app.Activity) -> DiscoverConnection = { object : DiscoverConnection {
            override val status = MutableStateFlow(DiscoverStatus.UNAVAILABLE)
            override fun start(value: DiscoverProvider) { starts++ }
            override fun scroll(value: Float) {}
            override fun nativeScroll(listener: (Float) -> Unit) { nativeProgress = listener }
            override fun stop() { stops++ }
        } }
        compose.runOnUiThread { compose.activity.setContent {
            val prefs by model.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalLauncherPreferences provides prefs, LocalWidgetController provides compose.activity.widgets, LocalDiscoverFactory provides fake) {
                VioletTheme { GlassHost { LauncherScreen(model, 1, false, {}, {}, compose.activity.systemActions) } }
            }
        } }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    @After fun restore() { if(::original.isInitialized) runBlocking { model.preferencesRepository.update { original }; model.layoutRepository.edit { oldLayout } } }
    private fun right() { compose.onNodeWithTag("screenPager").performTouchInput { swipeRight(durationMillis = 450) }; compose.waitForIdle() }
    private fun left() { compose.onNodeWithTag("screenPager").performTouchInput { swipe(androidx.compose.ui.geometry.Offset(width - 75f, height / 2f), androidx.compose.ui.geometry.Offset(75f, height / 2f), 450) }; compose.waitForIdle() }

    @Test fun enteringDiscoverStartsOnlyOneConnectionOnAnAlreadyResumedActivity() {
        val before = starts
        right()
        compose.onNodeWithTag("discoverScreen").assertIsDisplayed()
        assertEquals("Entering a page must reuse the Activity connection", before, starts)
        assertEquals("Only one Activity connection is needed", 1, starts)
    }

    @Test fun recordedFastNativeCloseReturnsToDesktopInsteadOfLeavingAnEmptyDiscoverPage() {
        right()
        compose.onNodeWithTag("discoverScreen").assertIsDisplayed()
        val motion = NativeOverlayMotion()
        assertNull(motion.update(1f, 1f, false))
        compose.runOnUiThread {
            for (value in listOf(.962963f, .6175926f, .025925925f, 0f)) {
                motion.update(value, 1f, false)?.let { nativeProgress!!(it) }
            }
        }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.onNodeWithTag("page:0").assertIsSelected()
    }

    @Test fun onePagerTraversesHaGoogleAndAllWorkspacePages() {
        compose.onNodeWithTag("page:0").assertIsSelected()
        right(); compose.onNodeWithTag("discoverScreen").assertIsDisplayed()
        right(); compose.onNodeWithTag("haScreen").assertIsDisplayed()
        compose.onNodeWithTag("haHome").assertDoesNotExist()
        compose.onNodeWithTag("haRefresh").assertDoesNotExist()
        compose.onNodeWithTag("discoverPageHandle").assertDoesNotExist()
        compose.onNodeWithText("192.0.2.10:8123").assertDoesNotExist()
        left(); compose.onNodeWithTag("discoverScreen").assertIsDisplayed()
        left(); compose.onNodeWithTag("page:0").assertIsSelected()
        left(); compose.onNodeWithTag("page:1").assertIsSelected()
        compose.onAllNodesWithText("Wiadomości").assertCountEquals(0)
    }
    @Test fun panelsCanBeDisabledIndependentlyThroughSettings() {
        compose.openSettings(); compose.onNodeWithTag("section:SCREENS").performScrollTo().performClick()
        compose.onNodeWithTag("panelDiscoverEnabled").performClick()
        compose.waitUntil(5000) { !model.preferences.value.discover.enabled }
        compose.onNodeWithTag("closePersonalization").performClick()
        right(); compose.onNodeWithTag("haScreen").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle(); compose.waitForIdle()
        compose.openSettings(); compose.onNodeWithTag("section:SCREENS").performScrollTo().performClick()
        compose.onNodeWithTag("panelHaEnabled").performClick()
        compose.waitUntil(5000) { !model.preferences.value.ha.enabled }
        compose.onNodeWithTag("closePersonalization").performClick()
        right(); compose.onNodeWithTag("page:0").assertIsSelected()
        compose.onNodeWithTag("haScreen").assertDoesNotExist()
        compose.onNodeWithTag("discoverScreen").assertDoesNotExist()
        assertEquals(2, model.workspace.value.layout!!.config.pages)
    }
    @Test fun catalogSearchGroupsProvidersAndLoadsPreviewWithoutAllocatingWidgets() {
        val before = compose.activity.widgets.host.appWidgetIds.toSet()
        compose.openDesktopOptions(); compose.onNodeWithTag("desktopWidgets").performClick()
        compose.onNodeWithTag("widgetSearch").performTextInput("VIOLET")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("widgetGroup:pl.violetglass.launcher.test").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Widgety: 2").assertExists()
        val key = "pl.violetglass.launcher.test/pl.violetglass.launcher.fixtures.TestWidgetProvider"
        compose.onNodeWithTag("widgetPreviewLayout:$key").performScrollTo().assertExists()
        compose.onNodeWithTag("widgetSearch").performTextReplacement("zzzz-no-widget")
        compose.onNodeWithText("Nie znaleziono pasujących widgetów.").assertIsDisplayed()
        assertEquals(before, compose.activity.widgets.host.appWidgetIds.toSet())
    }
}
