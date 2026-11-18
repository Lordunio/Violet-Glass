package pl.violetglass.launcher.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import pl.violetglass.launcher.ha.HaSettings
import kotlin.math.roundToInt

/** Own Activity subwindow: HA slides over the stable Google window, never over the wallpaper. */
@Composable
fun FloatingHaPanel(settings: HaSettings, position: Float, refresh: Int, hidden: Boolean, layerRevision: Int, dragging: Boolean,
    onHome: () -> Unit, onSettings: () -> Unit, onDrag: (Float, Boolean) -> Unit) {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val lightBars = MaterialTheme.colorScheme.background.luminance() > .5f
    val currentPosition by rememberUpdatedState(position)
    val drag by rememberUpdatedState(onDrag)
    val offset = if(hidden) -size.width else -(position.coerceIn(0f, 1f) * size.width).roundToInt()
    val activity = LocalActivity.current as ComponentActivity
    val parent = rememberCompositionContext()
    val content = rememberUpdatedState<@Composable () -> Unit> {
        Box(Modifier.requiredSize(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })) {
            HomeAssistantScreen(settings, onHome, onSettings, active = !hidden && position < 1f, refreshKey = refresh)
        }
    }
    val composeView = remember(activity) { ComposeView(activity).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setParentCompositionContext(parent)
        setContent { content.value() }
    } }
    var touchRevision by remember { mutableIntStateOf(0) }
    var raisedLayer by remember { mutableIntStateOf(0) }
    val view = remember(activity, composeView) { HaDragFrame(activity, { currentPosition },
        { value, released -> drag(value, released) }, { touchRevision++ }).apply {
        isFocusableInTouchMode = true
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        addView(composeView, android.widget.FrameLayout.LayoutParams(-1, -1))
    } }
    val params = remember(activity) { WindowManager.LayoutParams(size.width, size.height,
        WindowManager.LayoutParams.TYPE_APPLICATION,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        PixelFormat.TRANSLUCENT).apply {
        token = activity.window.attributes.token; gravity = Gravity.TOP or Gravity.LEFT; x = offset
        title = "Violet Home Assistant"; windowAnimations = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    } }
    var attached by remember { mutableStateOf(false) }
    val backRegistration = remember(activity) { PanelBackRegistration(activity) }
    DisposableEffect(view) {
        activity.windowManager.addView(view, params); attached = true
        view.setOnKeyListener { _, key, event ->
            if(key == android.view.KeyEvent.KEYCODE_BACK) {
                if(event.action == android.view.KeyEvent.ACTION_UP) activity.onBackPressedDispatcher.onBackPressed()
                true
            } else false
        }
        backRegistration.bind(view)
        onDispose {
            attached = false
            backRegistration.release()
            activity.windowManager.removeViewImmediate(view); composeView.disposeComposition()
        }
    }
    LaunchedEffect(view, layerRevision, dragging, touchRevision) {
        if(attached && layerRevision > raisedLayer && !dragging && !view.touching) {
            // Google creates a new sibling window when it attaches. Put this
            // retained HA view above it, keeping the composition/WebView alive.
            backRegistration.release()
            activity.windowManager.removeViewImmediate(view)
            activity.windowManager.addView(view, params)
            backRegistration.bind(view)
            raisedLayer = layerRevision
        }
    }
    SideEffect {
        val wasFocusable = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        val focusable = !hidden && position <= .001f
        params.x = offset; params.width = size.width; params.height = size.height
        params.flags = if(focusable) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if(attached) {
            activity.windowManager.updateViewLayout(view, params)
            if(focusable) {
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                view.windowInsetsController?.setSystemBarsAppearance(if(lightBars) mask else 0, mask)
                if(!wasFocusable) { view.requestFocus(); view.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) }
            }
        }
    }
}

private class PanelBackRegistration(private val activity: ComponentActivity) {
    private var epoch = 0
    private var dispatcher: android.window.OnBackInvokedDispatcher? = null
    private val callback = if(android.os.Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback {
        activity.onBackPressedDispatcher.onBackPressed()
    } else null
    fun release() {
        epoch++
        if(android.os.Build.VERSION.SDK_INT >= 33) callback?.let { dispatcher?.unregisterOnBackInvokedCallback(it) }
        dispatcher = null
    }
    fun bind(view: android.view.View) {
        release()
        val request = epoch
        if(android.os.Build.VERSION.SDK_INT >= 33) view.post {
            if(view.isAttachedToWindow && request == epoch) {
                dispatcher = view.findOnBackInvokedDispatcher()
                callback?.let { dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it) }
            }
        }
    }
}
