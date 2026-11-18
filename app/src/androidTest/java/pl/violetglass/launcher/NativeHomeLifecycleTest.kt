package pl.violetglass.launcher

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A real HOME launch intent is tracked from the start, including after recreation. */
@RunWith(AndroidJUnit4::class)
class NativeHomeLifecycleTest {
    @get:Rule val compose = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ),
        activityProvider = { rule ->
            var activity: MainActivity? = null
            rule.scenario.onActivity { activity = it }
            requireNotNull(activity)
        },
    )

    @Test fun configurationChangePreservesOpenDrawerOfHomeActivity() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("desktopPager").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.openDrawer()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("appSearch").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("appSearch").assertIsDisplayed(); true }.getOrDefault(false)
        }
    }
}


