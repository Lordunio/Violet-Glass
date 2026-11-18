package pl.violetglass.launcher.notifications

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import pl.violetglass.launcher.MainActivity
import pl.violetglass.launcher.openDrawer
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.ui.*

class BadgeUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking { original = model.preferencesRepository.data.first() }
    }
    @After fun restore() { if(::original.isInitialized) runBlocking { model.preferencesRepository.update { original } } }
    @Test fun badgeSupportsNumberDotAndDisablingWithoutReplacingTheIcon() {
        var prefs by mutableStateOf(LauncherPreferences())
        compose.runOnUiThread { compose.activity.setContent { MaterialTheme {
            CompositionLocalProvider(LocalLauncherPreferences provides prefs) {
                WithNotificationBadge(123, Modifier.size(48.dp)) { Box(Modifier.size(48.dp)) }
            }
        } } }
        compose.onNodeWithTag("notificationBadge").assertIsDisplayed()
        compose.onNodeWithText("99+").assertIsDisplayed()
        compose.runOnUiThread { prefs = prefs.copy(badgeStyle = BadgeStyle.DOT) }
        compose.onNodeWithTag("notificationBadge").assertIsDisplayed()
        compose.onNodeWithText("99+").assertDoesNotExist()
        compose.runOnUiThread { prefs = prefs.copy(badgesEnabled = false) }
        compose.onNodeWithTag("notificationBadge").assertDoesNotExist()
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    @Test fun actualListenerCountsPostedFixtureAndClearsItAfterRemoval() {
        // Never grant listener access on a physical device, even if this class is invoked manually.
        Assume.assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val component = ComponentName(context, BadgeListener::class.java).flattenToString()
        val granted = BadgeStore.hasAccess(context)
        fun fixture(cancel: Boolean) { context.startActivity(Intent().setComponent(ComponentName("pl.violetglass.launcher.test",
            "pl.violetglass.launcher.fixtures.TestNotificationActivity")).putExtra("cancel", cancel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        try {
            runBlocking { model.preferencesRepository.update { it.copy(badgesEnabled = true, badgeStyle = BadgeStyle.NUMBER, hiddenApps = emptySet()) } }
            if(Build.VERSION.SDK_INT >= 33) shell("pm grant pl.violetglass.launcher.test android.permission.POST_NOTIFICATIONS")
            shell("cmd notification allow_listener $component")
            compose.waitUntil(15000) { BadgeStore.connected.value }
            fixture(false)
            compose.waitUntil(15000) { BadgeStore.counts.value["pl.violetglass.launcher.test"] == 3 }
            compose.openDrawer()
            compose.onNodeWithTag("appSearch").performTextInput("Violet Fixture")
            compose.onNodeWithContentDescription(context.getString(pl.violetglass.launcher.R.string.badge_count, 3)).assertIsDisplayed()
            fixture(true)
            compose.waitUntil(10000) { "pl.violetglass.launcher.test" !in BadgeStore.counts.value }
            compose.onNodeWithTag("notificationBadge").assertDoesNotExist()
        } finally { fixture(true); if(!granted) shell("cmd notification disallow_listener $component") }
    }
}
