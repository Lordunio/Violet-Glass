package pl.violetglass.launcher.ui

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.*
import android.os.PowerManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.violetglass.launcher.settings.*

val LocalGlassEnvironment = staticCompositionLocalOf { GlassEnvironment() }
val LocalGlassPolicy = staticCompositionLocalOf { resolveGlass(GlassSettings(), GlassEnvironment()) }

/** No polling, background service or idle animation. Register signals only while the UI is started. */
@Composable
fun rememberGlassEnvironment(): GlassEnvironment {
    val context = LocalContext.current.applicationContext
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java).isLowRamDevice }
    fun read() = GlassEnvironment(lowRam, power.isPowerSaveMode,
        runCatching { power.currentThermalStatus }.getOrDefault(0), view.isHardwareAccelerated, ValueAnimator.areAnimatorsEnabled())
    var environment by remember { mutableStateOf(read()) }
    DisposableEffect(lifecycle, context, view) {
        var registered = false
        val thermal = PowerManager.OnThermalStatusChangedListener { environment = read() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { environment = read() }
        }
        fun stop() {
            if (!registered) return
            registered = false
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { power.removeThermalStatusListener(thermal) }
        }
        fun start() {
            if (registered) return
            registered = true
            environment = read()
            androidx.core.content.ContextCompat.registerReceiver(context, receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            runCatching { power.addThermalStatusListener(context.mainExecutor, thermal) }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) { start(); environment = read() }
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    return environment
}
