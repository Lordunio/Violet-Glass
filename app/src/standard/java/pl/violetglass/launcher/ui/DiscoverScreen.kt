package pl.violetglass.launcher.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.violetglass.launcher.R
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.discover.*

val LocalDiscoverFactory = staticCompositionLocalOf<(Activity) -> DiscoverConnection> {
    { DiscoverOverlay(it) }
}

@Composable
fun DiscoverScreen(settings: DiscoverSettings, onHome: () -> Unit, onSettings: () -> Unit, progress: () -> Float,
    onNativeScroll: (Float) -> Unit = {}, connection: DiscoverConnection? = null) {
    val activity = LocalActivity.current ?: return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val factory = LocalDiscoverFactory.current
    val ownedOverlay = remember(activity, factory) { factory(activity) }
    val overlay = connection ?: ownedOverlay
    val status by overlay.status.collectAsStateWithLifecycle()
    var retry by remember { mutableIntStateOf(0) }
    val currentProgress by rememberUpdatedState(progress)
    val visible by remember { derivedStateOf { currentProgress() > 0f } }
    val nativeScroll by rememberUpdatedState(onNativeScroll)
    DisposableEffect(overlay) {
        overlay.nativeScroll { nativeScroll(it) }
        onDispose { overlay.nativeScroll {} }
    }
    DisposableEffect(overlay, lifecycle, settings.provider, visible, retry) {
        val observer = LifecycleEventObserver { _, event ->
            if(connection == null && event == Lifecycle.Event.ON_START && visible) overlay.start(settings.provider)
            if(connection == null && event == Lifecycle.Event.ON_STOP) overlay.pause()
        }
        lifecycle.addObserver(observer)
        // addObserver replays ON_START for an already started Activity. Starting
        // again here races bind/unbind and leaves AIDL Bridge with a stale client.
        onDispose { lifecycle.removeObserver(observer); if(connection == null) overlay.stop() }
    }
    LaunchedEffect(overlay) { if(connection == null) snapshotFlow { currentProgress() }.collect { overlay.scroll(it) } }
    BackHandler(enabled = visible) { onHome() }
    Box(Modifier.fillMaxSize().systemBarsPadding().testTag("discoverScreen").onGloballyPositioned {
            val position = it.positionInWindow()
            overlay.frame(position.x.roundToInt(), position.y.roundToInt(), it.size.width, it.size.height)
        }) {
        if(status != DiscoverStatus.READY) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(when(status) {
                    DiscoverStatus.CONNECTING -> R.string.discover_connecting
                    DiscoverStatus.BRIDGE_REQUIRED -> R.string.discover_bridge_required
                    DiscoverStatus.GOOGLE_REQUIRED -> R.string.discover_google_required
                    else -> R.string.discover_unavailable
                }), modifier = Modifier.testTag("discoverStatus"))
                Text(stringResource(R.string.discover_settings_path), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun DiscoverControls(settings: DiscoverSettings, onChange: (DiscoverSettings) -> Unit) {
    val context = LocalContext.current
    Toggle(stringResource(R.string.discover_enabled), settings.enabled, "discoverEnabled") { onChange(settings.copy(enabled = it)) }
    Text(stringResource(R.string.discover_experimental), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
        android.net.Uri.parse("https://github.com/amirzaidi/AIDLBridge/releases/download/v3/aidlbridge.apk"))) } }, modifier = Modifier.testTag("discoverInstallBridge")) {
        Text(stringResource(R.string.discover_install_bridge))
    }
    TextButton(onClick = {
        context.packageManager.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")?.let { runCatching { context.startActivity(it) } }
    }) { Text(stringResource(R.string.discover_google_app)) }
    DiscoverProvider.entries.forEach { provider ->
        RadioRow(stringResource(if(provider == DiscoverProvider.DIRECT) R.string.discover_direct else R.string.discover_bridge), settings.provider == provider,
            "discoverProvider:${provider.name}") { onChange(settings.copy(provider = provider)) }
    }
}
