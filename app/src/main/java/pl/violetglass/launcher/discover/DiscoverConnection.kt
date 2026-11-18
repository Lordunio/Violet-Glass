package pl.violetglass.launcher.discover

import pl.violetglass.launcher.settings.DiscoverProvider

enum class DiscoverStatus { CONNECTING, READY, UNAVAILABLE, BRIDGE_REQUIRED, GOOGLE_REQUIRED }
interface DiscoverConnection {
    val status: kotlinx.coroutines.flow.StateFlow<DiscoverStatus>
    fun start(value: DiscoverProvider)
    fun scroll(value: Float)
    fun frame(left: Int, top: Int, width: Int, height: Int) {}
    fun nativeScroll(listener: (Float) -> Unit) {}
    fun leftNavigation(listener: (() -> Unit)?) {}
    fun leftDrag(listener: ((Float, Boolean) -> Unit)?) {}
    fun coveredByHa(value: Boolean) {}
    fun pause() { stop() }
    fun windowLayer(listener: (() -> Unit)?) {}
    fun stop()
}


