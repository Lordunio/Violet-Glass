package pl.violetglass.launcher.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import pl.violetglass.launcher.discover.DiscoverConnection
import pl.violetglass.launcher.discover.DiscoverStatus
import pl.violetglass.launcher.settings.DiscoverProvider
import pl.violetglass.launcher.settings.DiscoverSettings

private object UnavailableSidePanel : DiscoverConnection {
    override val status = MutableStateFlow(DiscoverStatus.UNAVAILABLE)
    override fun start(value: DiscoverProvider) = Unit
    override fun scroll(value: Float) = Unit
    override fun stop() = Unit
}

val LocalDiscoverFactory = staticCompositionLocalOf<(Activity) -> DiscoverConnection> {
    { UnavailableSidePanel }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun DiscoverScreen(settings: DiscoverSettings, onHome: () -> Unit, onSettings: () -> Unit,
    progress: () -> Float, onNativeScroll: (Float) -> Unit = {}, connection: DiscoverConnection? = null) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
fun DiscoverControls(settings: DiscoverSettings, onChange: (DiscoverSettings) -> Unit) = Unit
