package pl.violetglass.launcher.icons

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import pl.violetglass.launcher.MainActivity
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.ui.*

class IconPickerUiTest {
    @get:Rule val compose = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
        activityProvider = { rule -> var activity: MainActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) },
    )
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    private val key = "0:com.example/.Main"
    private val pack = "pl.violetglass.launcher.test"
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking { original = model.preferencesRepository.data.first(); model.preferencesRepository.update { LauncherPreferences() } }
    }
    @After fun restore() { if(::original.isInitialized) runBlocking { model.preferencesRepository.update { original } } }
    private fun waitPrefs(predicate: (LauncherPreferences) -> Boolean) {
        compose.waitUntil(15000) { runBlocking { predicate(model.preferencesRepository.data.first()) } }
        compose.waitForIdle()
    }
    @Test fun selectPackSearchPreviewChooseIndividualAndResetAll() {
        compose.runOnUiThread { compose.activity.setContent {
            val prefs by model.preferencesRepository.data.collectAsState(initial = LauncherPreferences())
            var individual by remember { mutableStateOf(false) }
            MaterialTheme { CompositionLocalProvider(LocalLauncherPreferences provides prefs) {
                androidx.compose.foundation.layout.Column {
                    IconPackSettings(model)
                    androidx.compose.material3.TextButton(onClick = { individual = true }, modifier = androidx.compose.ui.Modifier.testTag("editTest")) { androidx.compose.material3.Text("Edit") }
                }
                if(individual) IndividualIconPicker(model, key, "Test") { individual = false }
            } }
        } }
        compose.onNodeWithTag("chooseIconPack").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("pack:$pack").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("pack:$pack").performClick()
        waitPrefs { it.iconPack == pack }
        compose.onNodeWithTag("editTest").performClick()
        compose.onNodeWithTag("iconQuery").performTextInput("green")
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("test_icon_green").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("icon:test_icon_red").assertDoesNotExist()
        compose.onNodeWithTag("icon:test_icon_green").performClick()
        waitPrefs { decodeIconChoices(it.iconOverrides)[key] == IconChoice(pack, "test_icon_green") }
        compose.onNodeWithTag("editTest").performClick()
        compose.onNodeWithTag("iconSystem").performClick()
        waitPrefs { decodeIconChoices(it.iconOverrides)[key] == IconChoice() }
        compose.onNodeWithTag("editTest").performClick()
        compose.onNodeWithTag("iconDefault").performClick()
        waitPrefs { it.iconOverrides.isEmpty() && it.iconPack == pack }
        compose.onNodeWithTag("resetIcons").performClick()
        waitPrefs { it.iconPack.isEmpty() && it.iconOverrides.isEmpty() }
    }
}
