package pl.violetglass.launcher.discover

/** Compatible with the same legacy int-status callback; never request v10+ without its Bundle callback. */
internal object OverlayProtocol {
    fun nextVersion(previous: Int): Int = if(previous == 8) 9 else 8
}
