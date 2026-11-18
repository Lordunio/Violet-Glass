package pl.violetglass.launcher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions

/** Use the actual gesture now that the app-list button is intentionally removed. */
fun SemanticsNodeInteractionsProvider.openDrawer() {
    val area = onNodeWithTag("appDrawerGestureArea").fetchSemanticsNode().boundsInRoot
    onNodeWithTag("launcherRoot").performTouchInput { swipe(area.center, area.center - Offset(0f, 260f), 350) }
}

fun SemanticsNodeInteractionsProvider.openDesktopOptions() {
    onNodeWithTag("desktopPager").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
}
fun SemanticsNodeInteractionsProvider.openSettings() { openDesktopOptions(); onNodeWithTag("desktopSettings").performClick() }
fun SemanticsNodeInteractionsProvider.openLayout() { openDesktopOptions(); onNodeWithTag("desktopGrid").performClick() }
fun SemanticsNodeInteractionsProvider.addWorkspacePage() { openDesktopOptions(); onNodeWithTag("desktopAddPage").performClick() }
