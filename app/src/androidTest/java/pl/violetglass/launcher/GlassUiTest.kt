package pl.violetglass.launcher

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.ui.*
import pl.violetglass.launcher.workspace.*

@RunWith(AndroidJUnit4::class)
class GlassUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking { original = model.preferencesRepository.data.first(); model.preferencesRepository.update { LauncherPreferences(dockBackground = true, glass = GlassSettings(automatic = false)) } }
        waitPrefs { it.glass.automatic == false }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    @After fun restore() { if (::original.isInitialized) runBlocking { model.preferencesRepository.update { original } } }
    private fun waitPrefs(predicate: (LauncherPreferences) -> Boolean) { compose.waitUntil(15_000) { predicate(model.preferences.value) }; compose.waitForIdle() }
    private fun target(target: GlassTarget) = compose.onNode(SemanticsMatcher.expectValue(GlassTargetKey, target.name), useUnmergedTree = true)
    private fun section(name: String) {
        compose.openSettings()
        compose.onNodeWithTag("section:$name").performScrollTo().performClick()
    }
    @Test fun globalOffPersistsAndRemovesEffectsFromSettingsDockDrawerSearchAndMenus() {
        val layout = model.workspace.value.layout
        section("GLASS")
        compose.onNodeWithTag("glassEnabled").performClick()
        waitPrefs { !it.glass.enabled }
        target(GlassTarget.SETTINGS).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        compose.onNodeWithTag("closePersonalization").performClick()
        target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassBlurKey, 0f))
        compose.activityRule.scenario.recreate()
        waitPrefs { !it.glass.enabled }
        target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        compose.openDrawer()
        target(GlassTarget.DRAWER).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        target(GlassTarget.SEARCH).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        compose.waitUntil(15_000) { model.state.value.apps.isNotEmpty() }
        val app = model.state.value.apps.first()
        compose.onNodeWithTag("app:${app.key}").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        target(GlassTarget.MENUS).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        compose.onNodeWithTag("addHome").assertIsEnabled()
        assertEquals(layout, model.workspace.value.layout)
    }
    @Test fun perElementSwitchesLeaveOtherGlassActive() {
        section("GLASS")
        compose.onNodeWithTag("glassTarget:DOCK").performScrollTo().performClick()
        compose.onNodeWithTag("glassTarget:SETTINGS").performScrollTo().performClick()
        waitPrefs { it.glass.disabledTargets == setOf(GlassTarget.DOCK, GlassTarget.SETTINGS) }
        target(GlassTarget.SETTINGS).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        compose.onNodeWithTag("closePersonalization").performClick()
        target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassActiveKey, false))
        compose.openDrawer()
        target(GlassTarget.DRAWER).assert(SemanticsMatcher.expectValue(GlassActiveKey, true))
        target(GlassTarget.SEARCH).assert(SemanticsMatcher.expectValue(GlassActiveKey, true))
    }
    @Test fun selectedModesChangeActualSurfaceBlurBudget() {
        GlassMode.entries.forEach { mode ->
            section("PERFORMANCE")
            compose.onNodeWithTag("glassMode:${mode.name}").performScrollTo().performClick()
            waitPrefs { it.glass.mode == mode }
            compose.onNodeWithTag("glassEffectiveMode").assertTextEquals("Aktywny: ${glassModeLabel(mode)}")
            compose.onNodeWithTag("closePersonalization").performClick()
            target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassModeKey, mode.name))
            val expected = resolveGlass(model.preferences.value.glass, GlassEnvironment()).blurDp
            target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassBlurKey, expected))
        }
    }
    @Test fun allVisualSlidersPersistAcrossRecreationWithoutChangingIcons() {
        section("GLASS")
        val sliders = mapOf("glassBlur" to 100f, "glassTransparency" to 45f, "glassReflections" to 80f, "glassAnimation" to 0f, "glassCorners" to 40f)
        sliders.forEach { (tag, value) -> compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) } }
        waitPrefs { it.glass.blur == 100 && it.glass.transparency == 45 && it.glass.reflections == 80 && it.glass.animation == 0 && it.glass.corners == 40 }
        compose.onNodeWithTag("closePersonalization").performClick()
        compose.activityRule.scenario.recreate()
        waitPrefs { it.glass.corners == 40 }
        assertEquals(40, model.preferences.value.iconSize)
        target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassBlurKey, 12f))
    }
    @Test fun liveBatterySaverLimitsEffectsAndManualOverrideRestoresUltra() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        Assume.assumeTrue("Power simulation is restricted to emulator", shell("getprop ro.kernel.qemu").trim() == "1")
        val power = compose.activity.getSystemService(PowerManager::class.java)
        val originallySaving = power.isPowerSaveMode
        try {
            runBlocking { model.preferencesRepository.update { it.copy(glass = GlassSettings(mode = GlassMode.ULTRA, automatic = true)) } }
            waitPrefs { it.glass.mode == GlassMode.ULTRA && it.glass.automatic }
            shell("dumpsys battery unplug")
            shell("cmd power set-mode 1")
            compose.waitUntil(15_000) { power.isPowerSaveMode }
            compose.waitUntil(15_000) { runCatching { target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassModeKey, "LITE")); true }.getOrDefault(false) }
            section("PERFORMANCE")
            compose.onNodeWithTag("glassEffectiveMode").assertTextEquals("Aktywny: Lite")
            compose.onNodeWithTag("glassAutomatic").performScrollTo().performClick()
            waitPrefs { !it.glass.automatic }
            compose.onNodeWithTag("glassEffectiveMode").assertTextEquals("Aktywny: Ultra Glass")
            compose.onNodeWithTag("closePersonalization").performClick()
            target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassModeKey, "ULTRA"))
            shell("cmd power set-mode 0")
            compose.waitUntil(15_000) { !power.isPowerSaveMode }
            runBlocking { model.preferencesRepository.update { it.copy(glass = it.glass.copy(automatic = true)) } }
            waitPrefs { it.glass.automatic }
            target(GlassTarget.DOCK).assert(SemanticsMatcher.expectValue(GlassModeKey, "ULTRA"))
        } finally {
            shell("cmd power set-mode ${if (originallySaving) 1 else 0}")
            shell("dumpsys battery reset")
        }
    }
    @Test fun transparentScaffoldPreservesReadableTextInBothThemesAndWithGlassOff() {
        for ((theme, enabled) in listOf(ThemeMode.DARK to true, ThemeMode.DARK to false, ThemeMode.LIGHT to true)) {
            runBlocking { model.preferencesRepository.update { it.copy(theme = theme, systemWallpaper = false, glass = it.glass.copy(enabled = enabled)) } }
            waitPrefs { it.theme == theme && it.glass.enabled == enabled }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("pageIndicator", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val luminance = layouts.single().layoutInput.style.color.luminance()
            if (theme == ThemeMode.DARK) assertTrue("Dark desktop needs light text", luminance > .6f)
            else assertTrue("Light desktop needs dark text", luminance < .2f)
        }
    }
}
