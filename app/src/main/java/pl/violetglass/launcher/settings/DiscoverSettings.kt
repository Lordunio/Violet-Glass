package pl.violetglass.launcher.settings

enum class DiscoverProvider { DIRECT, BRIDGE }
data class DiscoverSettings(val enabled: Boolean = false, val provider: DiscoverProvider = DiscoverProvider.DIRECT)

enum class LauncherPage { HOME_ASSISTANT, DISCOVER, DESKTOP }
data class LauncherPages(val ha: Boolean, val discover: Boolean, val desktops: Int) {
    val leading = listOfNotNull(LauncherPage.HOME_ASSISTANT.takeIf { ha }, LauncherPage.DISCOVER.takeIf { discover })
    val firstDesktop = leading.size
    val count = firstDesktop + desktops.coerceAtLeast(1)
    fun kind(index: Int) = leading.getOrNull(index) ?: LauncherPage.DESKTOP
    fun desktop(index: Int) = (index - firstDesktop).coerceIn(0, desktops.coerceAtLeast(1) - 1)
    fun index(desktop: Int) = firstDesktop + desktop.coerceIn(0, desktops.coerceAtLeast(1) - 1)
    fun key(index: Int) = if (index < firstDesktop) kind(index).name else "desktop:${desktop(index)}"
}
