package pl.violetglass.launcher.settings

import org.junit.Assert.*
import org.junit.Test

class LauncherPagesTest {
    @Test fun requestedOrderIncludesAllDesktops() {
        val pages = LauncherPages(ha = true, discover = true, desktops = 3)
        assertEquals(listOf(LauncherPage.HOME_ASSISTANT, LauncherPage.DISCOVER, LauncherPage.DESKTOP, LauncherPage.DESKTOP, LauncherPage.DESKTOP), (0 until pages.count).map(pages::kind))
        assertEquals(listOf(0, 1, 2), (2..4).map(pages::desktop))
    }
    @Test fun eitherFeatureCanBeDisabledWithoutChangingWorkspaceNumbers() {
        listOf(true to false, false to true, false to false).forEach { (ha, google) ->
            val pages = LauncherPages(ha, google, 4)
            (0..3).forEach { assertEquals(it, pages.desktop(pages.index(it))) }
            assertEquals(4 + listOf(ha, google).count { it }, pages.count)
        }
    }
    @Test fun pageIdentitySurvivesEnablingAndDisablingPanels() {
        val full = LauncherPages(true, true, 3)
        val none = LauncherPages(false, false, 3)
        (0..2).forEach { assertEquals(full.key(full.index(it)), none.key(none.index(it))) }
    }
    @Test fun transientMissingWorkspaceStillHasAValidHomePage() {
        val pages = LauncherPages(true, false, 0)
        assertEquals(2, pages.count)
        assertEquals(0, pages.desktop(pages.firstDesktop))
    }
}
