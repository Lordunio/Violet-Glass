package pl.violetglass.launcher.settings

enum class DiscoverProvider { DIRECT, BRIDGE }
data class DiscoverSettings(val enabled: Boolean = false, val provider: DiscoverProvider = DiscoverProvider.DIRECT)

enum class LauncherPage { HOME_ASSISTANT, DISCOVER, DESKTOP, PET_GAME }
data class LauncherPages(val ha: Boolean, val discover: Boolean, val desktops: Int, val petGame: Boolean = false) {
    val leading = listOfNotNull(LauncherPage.HOME_ASSISTANT.takeIf { ha }, LauncherPage.DISCOVER.takeIf { discover })
    val firstDesktop = leading.size
    val lastDesktop = firstDesktop + desktops.coerceAtLeast(1) - 1
    val count = lastDesktop + if (petGame) 2 else 1
    fun kind(index: Int) = if (petGame && index == lastDesktop + 1) LauncherPage.PET_GAME
        else leading.getOrNull(index) ?: LauncherPage.DESKTOP
    fun desktop(index: Int) = (index - firstDesktop).coerceIn(0, desktops.coerceAtLeast(1) - 1)
    fun index(desktop: Int) = firstDesktop + desktop.coerceIn(0, desktops.coerceAtLeast(1) - 1)
    fun key(index: Int) = if (kind(index) == LauncherPage.DESKTOP) "desktop:${desktop(index)}" else kind(index).name
}
