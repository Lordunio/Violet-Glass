package pl.violetglass.launcher

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompatibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun landscapeSearchAndSettingsStayReachableAfterRotation() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("desktopPager").fetchSemanticsNodes().isNotEmpty() }
        compose.openDrawer()
        compose.onNodeWithTag("appSearch").performTextInput("rotation-check")
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithTag("appSearch").assertIsDisplayed().assertTextContains("rotation-check")
            compose.onNodeWithTag("homeButton").performClick()
            compose.openSettings()
            compose.onNodeWithTag("section:DISCOVER").performScrollTo().performClick()
            compose.onNodeWithTag("discoverProvider:BRIDGE").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("closePersonalization").assertIsDisplayed().performClick()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        }
    }
}
