package pl.violetglass.launcher

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun waitForInitialLayout() {
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false)
        }
        compose.waitForIdle()
    }
    @Test fun manifestExposesHomeActivity() {
        val context = compose.activity
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
        val homes = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(1, homes.size)
        assertEquals(MainActivity::class.java.name, homes.single().activityInfo.name)
        assertTrue(homes.single().activityInfo.exported)
    }

    @Test fun appListSearchAndBackToHome() {
        compose.openDrawer()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("appsGrid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("appSearch").performTextInput("zzzz-no-such-app-9381")
        compose.onNodeWithText(compose.activity.getString(R.string.no_results)).assertIsDisplayed()
        compose.onNodeWithTag("homeButton").performClick()
        compose.onNodeWithTag("appDrawerGestureArea").assertIsDisplayed()
    }

    @Test fun recreationPreservesSearch() {
        compose.openDrawer()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("appsGrid").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("appSearch").performTextInput("zzzz-recreation-9281")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.no_results)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("appSearch").assertTextContains("zzzz-recreation-9281")
    }
    @Test fun homeCallbackClosesAppList() {
        val originalIntent = Intent(compose.activity.intent)
        try {
            compose.openDrawer()
            compose.activityRule.scenario.onActivity {
                it.onNewIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("appDrawerGestureArea").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("appDrawerGestureArea").assertIsDisplayed()
        } finally {
            // ActivityScenario tracks the original launch intent when finishing its activity.
            compose.activityRule.scenario.onActivity { it.intent = originalIntent }
        }
    }
}




