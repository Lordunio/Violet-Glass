package pl.violetglass.launcher.pet

import android.app.WallpaperManager
import android.view.Surface
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import pl.violetglass.launcher.R
import pl.violetglass.launcher.notifications.BadgeStore
import pl.violetglass.launcher.notifications.PetNotificationSources
import pl.violetglass.launcher.ui.SettingSlider
import pl.violetglass.launcher.ui.AppIcon
import androidx.compose.ui.text.style.TextOverflow
import pl.violetglass.launcher.home.LauncherViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.*

class GridSurfaceTracker {
    private data class Entry(val owner: Any, val surface: PlatformSurface)
    private val entries = mutableStateMapOf<String, Entry>()
    val surfaces: List<PlatformSurface> get() = entries.values.map { it.surface }.sortedBy { it.id }
    internal fun register(id: String, owner: Any, bounds: androidx.compose.ui.geometry.Rect) {
        if(!bounds.left.isFinite() || !bounds.top.isFinite() || bounds.width <= 0 || bounds.height <= 0) { unregister(id, owner); return }
        val kind = when { id.startsWith("search") -> PlatformKind.SEARCH; id.startsWith("dock") -> PlatformKind.DOCK
            id.startsWith("widget:") -> PlatformKind.WIDGET; else -> PlatformKind.ICON }
        val next = Entry(owner, PlatformSurface(id, bounds, kind))
        if(entries[id] != next) entries[id] = next
    }
    internal fun unregister(id: String, owner: Any) { if(entries[id]?.owner === owner) entries.remove(id) }
}
val LocalGridSurfaceTracker = compositionLocalOf<GridSurfaceTracker?> { null }

private class TerrainCache {
    val paths = Array(3) { Path() }
    val colors = arrayOf(Color(0xFF252337), Color(0xFF343045), Color(0xFF454156))
    private var dimensions = Size.Zero
    private var previousCell = 0f
    fun update(size: Size, cell: Float) {
        if(size == dimensions && cell == previousCell) return
        dimensions = size; previousCell = cell
        for(layer in 0..2) paths[layer].apply {
            reset(); moveTo(-cell * 2, size.height)
            var x = -cell * 2
            while(x < size.width + cell * 2) {
                val y = size.height * (.47f + layer * .14f) +
                    (sin(x / cell * .7f + layer * 2) * 2 + sin(x / cell * .23f + layer)) * cell
                lineTo(x, y); lineTo(x + cell, y); x += cell
            }
            lineTo(size.width + cell * 3, size.height); close()
        }
    }
}

/** Only provide a tracker to the active desktop; old/disposed owners cannot erase a replacement. */
fun Modifier.registerPlatformSurface(id: String): Modifier = composed {
    val tracker = LocalGridSurfaceTracker.current
    if(tracker == null) this else {
        val owner = remember(tracker, id) { Any() }
        DisposableEffect(tracker, id, owner) { onDispose { tracker.unregister(id, owner) } }
        this.onGloballyPositioned { if(it.isAttached) tracker.register(id, owner, it.boundsInRoot()) }
    }
}

/** Behind each desktop, so the existing pager moves the wallpaper with its page. */
@Composable
fun InteractiveWallpaperLayer(model: PetViewModel, settings: PetSettings, modifier: Modifier = Modifier) {
    if(!settings.wallpaperEnabled) return
    val frame = model.ui.collectAsStateWithLifecycle()
    val photo = model.photo.collectAsStateWithLifecycle()
    val terrain = remember { TerrainCache() }
    Canvas(modifier.fillMaxSize().testTag("interactiveWallpaper")) {
        val state = frame.value
        val tilt = state.parallax * (8.dp.toPx())
        if(settings.background == PetBackgroundMode.PHOTO_PARALLAX) {
            photo.value?.let { image ->
                val factor = max(size.width / image.width, size.height / image.height) * 1.08f
                val width = (image.width * factor).roundToInt(); val height = (image.height * factor).roundToInt()
                drawImage(image, dstOffset = IntOffset(((size.width - width) / 2 + tilt.x).roundToInt(), ((size.height - height) / 2 + tilt.y).roundToInt()),
                    dstSize = IntSize(width, height), filterQuality = FilterQuality.Low)
            }
            drawRect(if(state.night) Color(0xFF19172D).copy(alpha = .08f) else Color(0xFFFFDDA9).copy(alpha = .025f))
        } else {
            drawRect(Color(0xFF10101C))
            val cell = max(12.dp.toPx(), size.width / 48)
            terrain.update(size, cell)
            for(layer in 0..2) {
                val shift = tilt.x * (layer + 1) * .55f
                withTransform({ translate(shift, tilt.y * layer * .3f) }) {
                    drawPath(terrain.paths[layer], terrain.colors[layer])
                    drawPath(terrain.paths[layer], Color(0xFFB8FFDD).copy(alpha = .12f + layer * .04f), style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

/** One lifecycle owner drives physics, sensors and power. There is no background frame/timer job. */
@Composable
fun InteractiveTamagotchiOverlay(model: PetViewModel, tracker: GridSurfaceTracker, settings: PetSettings,
    active: Boolean, modifier: Modifier = Modifier, sceneLeftFraction: Float = 0f, interactive: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val renderView = LocalView.current
    val density = LocalDensity.current
    val bottom = WindowInsets.navigationBars.getBottom(density)
    val statusTop = WindowInsets.statusBars.getTop(density)
    val sensor = remember(context, activity) { SensorParallaxController(context) { activity?.display?.rotation ?: Surface.ROTATION_0 } }
    LaunchedEffect(tracker, model) { snapshotFlow { tracker.surfaces }.collect { model.surfaces(it) } }
    LaunchedEffect(active, settings.enabled, settings.wallpaperEnabled, settings.parallax, lifecycle, model) {
        if(!active || !settings.sceneEnabled) {
            model.pause(); return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.ui.first { it.ready }; model.resume()
            val window = activity?.window
            val oldRate = window?.attributes?.preferredRefreshRate
            val oldViewRate = if(android.os.Build.VERSION.SDK_INT >= 35) renderView.requestedFrameRate else null
            if(android.os.Build.VERSION.SDK_INT >= 35) renderView.requestedFrameRate = 120f
            else if(window != null) window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
            var systemShifted = false
            try { coroutineScope {
                if(settings.enabled) {
                    BadgeStore.refreshAccess(context)
                    launch { model.powerEvents().collect { model.power(it) } }
                    launch { combine(BadgeStore.petMessages, model.settings) { messages, _ -> messages }.collect { model.messages(it) } }
                }
                if(settings.wallpaperEnabled && settings.parallax) launch {
                    var lastOffsetTime = 0L
                    sensor.offsets(lifecycle).collect { offset ->
                        model.motion(offset)
                        // No bitmap access. System wallpaper engines may ignore offsets.
                        val now = android.os.SystemClock.uptimeMillis()
                        if(model.photo.value == null && model.settings.value.background == PetBackgroundMode.PHOTO_PARALLAX && now - lastOffsetTime >= 50) {
                            window?.decorView?.windowToken?.let { token ->
                                runCatching { WallpaperManager.getInstance(context).setWallpaperOffsets(token, .5f + offset.x * .015f, .5f + offset.y * .015f) }
                                systemShifted = true; lastOffsetTime = now
                            }
                        }
                    }
                }
                var previous = 0L
                while(isActive) withFrameNanos { nanos ->
                    if(previous != 0L) model.frame(((nanos - previous) / 1e9).toFloat())
                    previous = nanos
                }
            } } finally {
                model.pause()
                if(window != null && window.decorView.isAttachedToWindow && oldRate != null) runCatching {
                    if(android.os.Build.VERSION.SDK_INT >= 35 && oldViewRate != null) renderView.requestedFrameRate = oldViewRate
                    else window.attributes = window.attributes.apply { preferredRefreshRate = oldRate }
                    if(systemShifted) WallpaperManager.getInstance(context).setWallpaperOffsets(window.decorView.windowToken, .5f, .5f)
                }
            }
        }
    }
    if(!active || !settings.sceneEnabled) return
    val frame = model.ui.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val shown by remember(frame) { derivedStateOf { frame.value.visible } }
    Box(modifier.fillMaxSize().onSizeChanged { model.viewport(it.width, it.height, density.density, bottom) }) {
        Canvas(Modifier.fillMaxSize().testTag("petScene")) {
            clipRect(left = size.width * sceneLeftFraction.coerceIn(0f, 1f)) {
            val state = frame.value
            clipRect(top = if(settings.effectArea == PetEffectArea.LOWER_SCREEN) max(size.height * .45f, statusTop.toFloat()) else statusTop.toFloat(),
                bottom = size.height - bottom) {
            val field = model.engine.particles
            val opacity = settings.effectOpacity
            val unit = settings.effectSize * density.density
            repeat(if(settings.wallpaperEnabled) field.activeCount else 0) { i ->
                val point = Offset(field.x[i], field.y[i])
                val shimmer = .55f + .45f * abs(sin(state.animationSeconds * settings.effectSpeed + i * 1.7f))
                val radius = (1.3f + i % 3 * .6f) * unit
                when(field.effect) {
                    PetWeather.RAIN -> {
                        val tail = point - Offset(2 * unit, (8 + i % 9) * unit)
                        drawLine(Color(0xFF79CFFF).copy(alpha = opacity * .1f), tail, point, 3 * unit, StrokeCap.Round)
                        drawLine(Color(0xFFC2EBFF).copy(alpha = opacity * .65f), tail, point, unit, StrokeCap.Round)
                        if(field.splash[i] > 0) drawCircle(Color(0xFFB7EDFF).copy(alpha = opacity * field.splash[i]),
                            (1 - field.splash[i] / .4f) * 9 * unit, Offset(field.splashX[i], field.splashY[i]), style = Stroke(unit * .6f))
                    }
                    PetWeather.SNOW -> {
                        drawCircle(Color.White.copy(alpha = opacity * .08f), radius * 3, point)
                        drawCircle(Color.White.copy(alpha = opacity * .7f), radius * .6f, point)
                        if(i % 3 == 0) repeat(3) { arm ->
                            val angle = arm * PI.toFloat() / 3 + state.animationSeconds * .08f
                            val delta = Offset(cos(angle), sin(angle)) * radius
                            drawLine(Color.White.copy(alpha = opacity * .8f), point - delta, point + delta, unit * .6f)
                        }
                    }
                    PetWeather.FIREFLIES, PetWeather.SPARKLES -> {
                        val tint = if(i % 3 == 0) Color(0xFFC2B2FF) else if(i % 3 == 1) Color(0xFF89F8E0) else Color(0xFFFFD997)
                        drawCircle(tint.copy(alpha = opacity * shimmer * .06f), radius * 5, point)
                        drawCircle(tint.copy(alpha = opacity * shimmer * .2f), radius * 2.2f, point)
                        drawCircle(tint.copy(alpha = opacity * shimmer * .85f), radius * .6f, point)
                        if(field.effect == PetWeather.SPARKLES) {
                            drawLine(tint.copy(alpha = opacity * shimmer), point - Offset(radius * 1.8f, 0f), point + Offset(radius * 1.8f, 0f), unit * .6f)
                            drawLine(tint.copy(alpha = opacity * shimmer), point - Offset(0f, radius * 1.8f), point + Offset(0f, radius * 1.8f), unit * .6f)
                        }
                    }
                    else -> Unit
                }
            }
            if(state.rippleAge < .9f) state.ripple?.let { origin ->
                val progress = state.rippleAge / .9f
                drawCircle(Color(0xFFC4F8E2).copy(alpha = (1 - progress) * .4f), (5 + progress * 48).dp.toPx(), origin, style = Stroke(1.dp.toPx()))
                repeat(12) { i ->
                    val angle = i * PI.toFloat() / 6
                    val point = origin + Offset(cos(angle), sin(angle)) * (progress * 40).dp.toPx()
                    drawCircle(Color(0xFFA9F5ED).copy(alpha = (1 - progress) * .75f), (1 - progress * .7f) * 2.dp.toPx(), point)
                }
            }
            }
            if(settings.enabled) drawCharacter(state)
            }
        }
        // Placement-phase reads move only this accessibility target, never the launcher grid.
        if(settings.enabled && shown && interactive) Box(Modifier.offset {
            val state = frame.value
            IntOffset((state.feet.x - with(density) { 24.dp.toPx() }).roundToInt(), (state.feet.y - state.sizePx).roundToInt())
        }.size(48.dp).testTag("petCharacter").semantics {
            role = Role.Button; contentDescription = resources.getString(R.string.pet_pat)
            onClick { model.pat(); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); true }
        })
    }
}

/** Sprite-ready public renderer: rows = PetMode.ordinal, columns = animation frames. */
@Composable
fun TamagotchiCharacter(state: State<PetUiState>, modifier: Modifier = Modifier, spriteSheet: ImageBitmap? = null,
    frameSize: IntSize = IntSize(32, 32)) {
    Canvas(modifier) { drawCharacter(state.value, spriteSheet, frameSize) }
}

private fun DrawScope.drawCharacter(state: PetUiState, sprite: ImageBitmap? = null, frameSize: IntSize = IntSize(32, 32)) {
    if(!state.ready || !state.visible) return
    if(state.appearance == PetAppearance.HOME_ROBOT && sprite == null) { drawHomeRobot(state); return }
    val p = (state.sizePx / 16).roundToInt().coerceAtLeast(1).toFloat()
    val breath = sin(state.animationSeconds * 2.4f) * p * .25f
    withTransform({
        translate((state.feet.x - p * 8).roundToInt().toFloat(), (state.feet.y - p * 16).roundToInt().toFloat() + breath)
        scale(state.facing.toFloat(), 1f, Offset(p * 8, p * 8))
    }) {
        if(sprite != null && frameSize.width > 0 && frameSize.height > 0 && sprite.width >= frameSize.width && sprite.height >= frameSize.height) {
            val columns = sprite.width / frameSize.width
            val row = state.mode.ordinal.coerceAtMost(sprite.height / frameSize.height - 1)
            val column = (state.animationSeconds * 8).toInt().mod(columns)
            drawImage(sprite, srcOffset = IntOffset(column * frameSize.width, row * frameSize.height), srcSize = frameSize,
                dstSize = IntSize((p * 16).toInt(), (p * 16).toInt()), filterQuality = FilterQuality.None)
        } else {
            fun pixel(x: Float, y: Float, w: Float, h: Float, color: Color) = drawRect(color, Offset(x * p, y * p), Size(w * p, h * p))
            val body = if(state.mode == PetMode.SLEEPING) Color(0xFF9A94B0) else Color(0xFFB9F4DC)
            pixel(3f, 3f, 3f, 4f, body); pixel(10f, 3f, 3f, 4f, body)
            pixel(2f, 6f, 12f, 8f, Color(0xFF30343E)); pixel(3f, 6f, 10f, 7f, body)
            pixel(0f, 10f, 3f, 2f, body)
            val foot = if(state.mode == PetMode.WALKING || state.mode == PetMode.COURIER) sin(state.animationSeconds * 12) * .6f else 0f
            pixel(4f, 14f + foot, 3f, 2f, body); pixel(10f, 14f - foot, 3f, 2f, body)
            val ink = Color(0xFF252735)
            pixel(5f, if(state.mode == PetMode.SLEEPING) 9f else 8f, 2f, if(state.mode == PetMode.SLEEPING) .5f else 2f, ink)
            pixel(10f, if(state.mode == PetMode.SLEEPING) 9f else 8f, 2f, if(state.mode == PetMode.SLEEPING) .5f else 2f, ink)
            pixel(8f, 11f, 1f, 1f, ink)
            if(state.mode == PetMode.COURIER) {
                pixel(9f, 9f, 7f, 5f, Color(0xFF4B5263)); pixel(9.5f, 9.5f, 6f, 4f, Color.White)
                drawLine(ink, Offset(10f * p, 10f * p), Offset(12.5f * p, 12f * p), p * .5f)
                drawLine(ink, Offset(15f * p, 10f * p), Offset(12.5f * p, 12f * p), p * .5f)
            }
        }
    }
    val effect = Offset(state.feet.x + p * 5, state.feet.y - p * 18 + sin(state.animationSeconds * 3) * p)
    when(state.mode) {
        PetMode.HAPPY -> {
            drawRect(Color(0xFFFF95B4), effect, Size(2*p, 2*p)); drawRect(Color(0xFFFF95B4), effect + Offset(3*p, 0f), Size(2*p, 2*p))
            drawRect(Color(0xFFFF95B4), effect + Offset(p, 2*p), Size(3*p, 2*p)); drawRect(Color(0xFFFF95B4), effect + Offset(2*p, 4*p), Size(p, p))
        }
        PetMode.SLEEPING -> {
            val color = Color(0xFFD4C9FF)
            drawLine(color, effect, effect + Offset(4*p, 0f), p)
            drawLine(color, effect + Offset(4*p, 0f), effect + Offset(0f, 4*p), p)
            drawLine(color, effect + Offset(0f, 4*p), effect + Offset(4*p, 4*p), p)
        }
        PetMode.EATING -> {
            drawRect(Color(0xFFFFD56C), effect, Size(2*p, 3*p)); drawRect(Color(0xFFFFD56C), effect + Offset(-p, 2*p), Size(3*p, p))
            drawRect(Color(0xFFFFD56C), effect + Offset(-p, 3*p), Size(2*p, 3*p))
        }
        else -> Unit
    }
}

/** Vector companion inspired by the supplied reference. Its status display is on the belly. */
private fun DrawScope.drawHomeRobot(state: PetUiState) {
    val u = state.sizePx / 100
    val floatY = sin(state.animationSeconds * 1.6f) * u * .8f
    val pulse = .65f + .35f * sin(state.animationSeconds * 3f)
    drawOval(Color.Black.copy(alpha = .18f), Offset(state.feet.x - 19*u, state.feet.y - 2*u), Size(38*u, 4*u))
    withTransform({
        translate(state.feet.x - state.sizePx / 2, state.feet.y - state.sizePx + floatY)
        scale(u, u, Offset.Zero)
        scale(state.facing.toFloat(), 1f, Offset(50f, 0f))
    }) {
        val mint = Color(0xFF70D5CA)
        val edge = Color(0xFF99D7F3)
        fun line(a: Offset, b: Offset, color: Color, width: Float) = drawLine(color, a, b, width, StrokeCap.Round)
        fun rounded(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, Offset(x,y), Size(w,h), CornerRadius(r))
        line(Offset(50f, 15f), Offset(50f, 5f), edge, 2.5f)
        drawCircle(mint.copy(alpha = .12f), 7f, Offset(50f, 4f))
        drawCircle(mint.copy(alpha = .7f), 4f, Offset(50f, 4f))
        rounded(mint, 16f, 26f, 4f, 15f, 2f); rounded(mint, 80f, 26f, 4f, 15f, 2f)
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF354F65), Color(0xFF1C3244)), Offset(20f,15f), Offset(80f,52f)),
            Offset(19f,15f), Size(62f,38f), CornerRadius(12f))
        drawRoundRect(edge, Offset(19f,15f), Size(62f,38f), CornerRadius(12f), style = Stroke(1.5f))
        rounded(Color(0xFF0C202B), 26f, 22f, 48f, 23f, 9f)
        if(state.mode == PetMode.SLEEPING) {
            line(Offset(35f,35f), Offset(42f,35f), mint, 2f); line(Offset(58f,35f), Offset(65f,35f), mint, 2f)
        } else {
            drawOval(mint, Offset(35f,28f), Size(8f,11f)); drawOval(mint, Offset(57f,28f), Size(8f,11f))
        }
        rounded(Color(0xFF446675), 43f, 53f, 14f, 5f, 2f)
        val swing = if(state.mode == PetMode.WALKING) sin(state.animationSeconds * 5) * 2 else 0f
        line(Offset(30f,64f), Offset(16f,79f + swing), edge, 5f)
        line(Offset(70f,64f), Offset(84f,79f - swing), edge, 5f)
        drawCircle(Color(0xFFE6F8FF), 4.5f, Offset(15f,80f + swing)); drawCircle(Color(0xFFE6F8FF), 4.5f, Offset(85f,80f - swing))
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF07BED2), Color(0xFF087E9D)), Offset(30f,57f), Offset(70f,92f)),
            Offset(28f,57f), Size(44f,35f), CornerRadius(10f))
        drawRoundRect(edge.copy(alpha = .85f), Offset(28f,57f), Size(44f,35f), CornerRadius(10f), style = Stroke(1.3f))
        val tail = Path().apply { moveTo(42f,93f); lineTo(58f,93f); lineTo(50f,100f); close() }
        drawPath(tail, mint.copy(alpha = .75f))
        val glow = when(state.mode) { PetMode.EATING -> Color(0xFFFFD86D); PetMode.COURIER -> Color(0xFFE2CCFF)
            PetMode.HAPPY -> Color(0xFFFFA1C3); else -> mint }
        drawCircle(glow.copy(alpha = .08f + pulse * .1f), 15f, Offset(50f,75f))
        when(state.mode) {
            PetMode.EATING -> {
                val bolt = Path().apply { moveTo(52f,62f); lineTo(42f,76f); lineTo(49f,76f); lineTo(46f,87f)
                    lineTo(59f,71f); lineTo(52f,71f); close() }
                drawPath(bolt, glow.copy(alpha = .75f + pulse * .25f))
            }
            PetMode.COURIER -> {
                drawRoundRect(glow, Offset(37f,67f), Size(26f,17f), CornerRadius(2f), style = Stroke(2f))
                line(Offset(38f,68f), Offset(50f,76f), glow, 2f); line(Offset(62f,68f), Offset(50f,76f), glow, 2f)
                drawCircle(Color.White.copy(alpha = pulse), 2f, Offset(62f,66f))
                if(state.charging) {
                    line(Offset(66f,63f), Offset(63f,69f), Color(0xFFFFD86D), 2f)
                    line(Offset(63f,69f), Offset(67f,69f), Color(0xFFFFD86D), 2f)
                    line(Offset(67f,69f), Offset(64f,75f), Color(0xFFFFD86D), 2f)
                }
            }
            PetMode.HAPPY -> {
                val heart = Path().apply { moveTo(50f,84f); cubicTo(28f,71f,42f,60f,50f,70f); cubicTo(58f,60f,72f,71f,50f,84f); close() }
                drawPath(heart, glow)
            }
            PetMode.SLEEPING -> {
                line(Offset(43f,69f), Offset(57f,69f), glow, 2f); line(Offset(57f,69f), Offset(43f,82f), glow, 2f)
                line(Offset(43f,82f), Offset(57f,82f), glow, 2f)
            }
            else -> {
                val house = Path().apply { moveTo(38f,74f); lineTo(50f,64f); lineTo(62f,74f); lineTo(62f,85f); lineTo(38f,85f); close() }
                drawPath(house, Color(0xFFD5FAFF), style = Stroke(2.2f))
                drawCircle(mint.copy(alpha = .55f), 3f, Offset(50f,79f))
            }
        }
    }
}

@Composable
fun PetControls(model: PetViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val state by model.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationSources by BadgeStore.petMessages.collectAsStateWithLifecycle()
    val launcher: LauncherViewModel = viewModel()
    val appState by launcher.state.collectAsStateWithLifecycle()
    val currentSources = settings.activeMessagePackages(notificationSources)
    val sourceNames = currentSources.map { pkg -> appState.apps.firstOrNull { it.component.packageName == pkg }?.label ?: pkg }.sorted().joinToString(", ")
    var messagePicker by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.pet_enable)); Switch(settings.enabled, { value -> model.configure { it.copy(enabled = value) } }, Modifier.testTag("petEnabled"))
    }
    Text(stringResource(R.string.pet_appearance), style = MaterialTheme.typography.titleMedium)
    PetAppearance.entries.forEach { appearance ->
        TextButton(onClick = { model.configure { it.copy(appearance = appearance) } }, modifier = Modifier.testTag("petAppearance:${appearance.name}")) {
            RadioButton(settings.appearance == appearance, null)
            Canvas(Modifier.size(64.dp).padding(6.dp)) {
                drawCharacter(PetUiState(ready = true, visible = true, feet = Offset(size.width / 2, size.height),
                    sizePx = size.minDimension * .9f, appearance = appearance, mode = PetMode.IDLE))
            }
            Text(stringResource(if(appearance == PetAppearance.PIXEL_CAT) R.string.pet_pixel_cat else R.string.pet_home_robot))
        }
    }
    Text(stringResource(R.string.pet_motion), style = MaterialTheme.typography.titleMedium)
    PetMotion.entries.forEach { motion -> TextButton(onClick = { model.configure { it.copy(motion = motion) } }, modifier = Modifier.testTag("petMotion:${motion.name}")) {
        RadioButton(settings.motion == motion, null)
        Text(stringResource(when(motion) { PetMotion.CALM -> R.string.pet_calm; PetMotion.NORMAL -> R.string.pet_normal_motion; PetMotion.FAST_HOPS -> R.string.pet_fast_hops }))
    } }
    PetRestPlaceControl(launcher, model, settings.restPlace)
    Text(stringResource(R.string.pet_messages), style = MaterialTheme.typography.titleMedium)
    Text(if(sourceNames.isEmpty()) stringResource(R.string.pet_message_sources_none) else stringResource(R.string.pet_message_sources, sourceNames),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("petMessageSources"))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pet_messages_enable), Modifier.weight(1f))
        Switch(settings.messagesEnabled, { checked -> model.configure { it.copy(messagesEnabled = checked) } }, Modifier.testTag("petMessagesEnabled"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pet_messages_only), Modifier.weight(1f))
        Switch(settings.messagesOnly, { checked -> model.configure { it.copy(messagesOnly = checked) } }, Modifier.testTag("petMessagesOnly"))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.pet_messages_all), Modifier.weight(1f))
        Switch(settings.allMessageApps, { checked -> model.configure { it.copy(allMessageApps = checked) } }, Modifier.testTag("petMessageAll"))
    }
    TextButton(onClick = { messagePicker = true }, modifier = Modifier.testTag("petMessageChoose")) {
        Text(if(settings.allMessageApps) stringResource(R.string.pet_messages_choose_all)
            else stringResource(R.string.pet_messages_choose, settings.messageApps.size))
    }
    Text(stringResource(R.string.pet_stats, state.stats.energy.roundToInt(), state.stats.happiness.roundToInt(), state.stats.cleanliness.roundToInt()), modifier = Modifier.testTag("petStats"))
    Row { TextButton(onClick = model::pat, modifier = Modifier.testTag("petPat")) { Text(stringResource(R.string.pet_pat)) }
        TextButton(onClick = model::clean, modifier = Modifier.testTag("petClean")) { Text(stringResource(R.string.pet_clean)) } }
    if(!BadgeStore.hasAccess(context)) TextButton(onClick = { runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }) {
        Text(stringResource(R.string.pet_notification_access))
    }
    if(messagePicker) PetMessageAppPicker(model) { messagePicker = false }
}

@Composable
private fun PetRestPlaceControl(launcher: LauncherViewModel, model: PetViewModel, selected: String?) {
    val workspace by launcher.workspace.collectAsStateWithLifecycle()
    val layout = workspace.layout
    val choices = remember(layout) {
        layout?.items.orEmpty().filter { it.container.startsWith("page:") }
            .sortedWith(compareBy({ it.container.substringAfter(':').toIntOrNull() ?: 0 }, { it.position }))
    }
    val dock = stringResource(R.string.dock_label)
    val none = stringResource(R.string.pet_rest_none)
    val selectedLabel = if(selected == null) none else if(selected == PET_REST_DOCK) dock else
        choices.firstOrNull { petRestItem(it.id) == selected }?.title ?: stringResource(R.string.pet_rest_missing)
    var open by remember { mutableStateOf(false) }
    Text(stringResource(R.string.pet_rest_place), style = MaterialTheme.typography.titleMedium)
    TextButton(onClick = { open = true }, modifier = Modifier.testTag("petRestPicker")) { Text(selectedLabel) }
    if(open) {
        fun select(value: String?) {
            model.configure { it.copy(restPlace = value) }
            open = false
        }
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.pet_rest_place)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    item { TextButton(onClick = { select(null) }, modifier = Modifier.testTag("petRestNone")) {
                        RadioButton(selected == null, null)
                        Text(none)
                    } }
                    item { TextButton(onClick = { select(PET_REST_DOCK) }, modifier = Modifier.testTag("petRestDock")) {
                        RadioButton(selected == PET_REST_DOCK, null)
                        Text(dock)
                    } }
                    items(choices, key = { it.id }) { item ->
                        val value = petRestItem(item.id)
                        val page = item.container.substringAfter(':').toIntOrNull() ?: 0
                        TextButton(onClick = { select(value) }, modifier = Modifier.testTag("petRest:${item.id}")) {
                            RadioButton(selected == value, null)
                            Text(stringResource(R.string.pet_rest_item, item.title, page + 1))
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } })
    }
}

private fun PetSettings.activeMessagePackages(sources: PetNotificationSources): Set<String> {
    if(!messagesEnabled) return emptySet()
    val matching = if(messagesOnly) sources.messages else sources.all
    return matching.keys.filterTo(mutableSetOf()) { allMessageApps || it in messageApps }
}

private enum class PetMessagePickerFilter { ALL, SELECTED, ACTIVE }

@Composable
private fun PetMessageAppPicker(model: PetViewModel, onDismiss: () -> Unit) {
    val launcher: LauncherViewModel = viewModel()
    val apps by launcher.state.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val sources by BadgeStore.petMessages.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PetMessagePickerFilter.ALL) }
    val appsByPackage = remember(apps.apps) { apps.apps.distinctBy { it.component.packageName }.associateBy { it.component.packageName } }
    val active = remember(settings, sources) { settings.activeMessagePackages(sources) }
    val knownPackages = remember(appsByPackage, settings.messageApps, sources.all) {
        appsByPackage.keys + settings.messageApps + sources.all.keys
    }
    val available = remember(appsByPackage, knownPackages, settings, query, filter, active) {
        val candidates = when(filter) {
            PetMessagePickerFilter.ALL -> knownPackages
            PetMessagePickerFilter.SELECTED -> if(settings.allMessageApps) knownPackages else settings.messageApps
            PetMessagePickerFilter.ACTIVE -> active
        }
        candidates.filter { pkg -> pkg.contains(query, true) || appsByPackage[pkg]?.label?.contains(query, true) == true }
            .sortedWith(compareBy({ pkg -> (appsByPackage[pkg]?.label ?: pkg).lowercase() }, { it }))
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.pet_messages)) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.pet_messages_search)) },
                modifier = Modifier.fillMaxWidth().testTag("petMessageSearch"))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PetMessagePickerFilter.entries.forEach { option ->
                    val label = when(option) {
                        PetMessagePickerFilter.ALL -> R.string.pet_message_filter_all
                        PetMessagePickerFilter.SELECTED -> R.string.pet_message_filter_selected
                        PetMessagePickerFilter.ACTIVE -> R.string.pet_message_filter_active
                    }
                    FilterChip(selected = filter == option, onClick = { filter = option },
                        label = { Text(stringResource(label)) }, modifier = Modifier.testTag("petMessageFilter:${option.name}"))
                }
            }
            if(available.isEmpty()) Text(stringResource(when {
                query.isNotBlank() -> R.string.no_results
                filter == PetMessagePickerFilter.ACTIVE -> R.string.pet_message_sources_none
                filter == PetMessagePickerFilter.SELECTED -> R.string.pet_message_selected_empty
                else -> R.string.no_apps
            }), modifier = Modifier.padding(top = 8.dp).testTag("petMessageFilterEmpty"))
            key(filter, query) {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(available, key = { it }) { packageName ->
                        val app = appsByPackage[packageName]
                        val name = app?.label ?: packageName
                        val checked = settings.allMessageApps || packageName in settings.messageApps
                        Row(Modifier.fillMaxWidth().testTag("petMessageApp:$packageName").toggleable(
                            value = checked, role = Role.Checkbox, onValueChange = { selected ->
                                model.configure { current ->
                                    val before = if(current.allMessageApps) knownPackages else current.messageApps
                                    current.copy(allMessageApps = false,
                                        messageApps = if(selected) before + packageName else before - packageName)
                                }
                            }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, null)
                            AppIcon(app, name, launcher.repository, apps.revision, size = 36.dp, showBadge = false,
                                modifier = Modifier.testTag("petMessageIcon:$packageName"))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if(app != null) Text(packageName, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("petMessagePickerClose")) { Text(stringResource(R.string.close)) } })
}

@Composable
fun WallpaperEffectControls(model: PetViewModel) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(model::choosePhoto) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.pet_wallpaper_enable))
        Switch(settings.wallpaperEnabled, { value -> model.configure { it.copy(wallpaperEnabled = value) } }, Modifier.testTag("wallpaperEffectsEnabled"))
    }
    Text(stringResource(R.string.pet_photo_explanation), style = MaterialTheme.typography.bodySmall)
    PetBackgroundMode.entries.forEach { mode ->
        TextButton(onClick = { model.configure { it.copy(background = mode) } }, modifier = Modifier.testTag("petBackground:${mode.name}")) {
            RadioButton(settings.background == mode, null)
            Text(stringResource(if(mode == PetBackgroundMode.PHOTO_PARALLAX) R.string.pet_photo_mode else R.string.pet_cyber_mode))
        }
    }
    Row {
        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.testTag("petPickPhoto")) { Text(stringResource(R.string.pet_pick_photo)) }
        TextButton(onClick = model::systemPhoto, modifier = Modifier.testTag("petSystemPhoto")) { Text(stringResource(R.string.pet_system_photo)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.pet_parallax)); Switch(settings.parallax, { value -> model.configure { it.copy(parallax = value) } }, Modifier.testTag("petParallax"))
    }
    Text(stringResource(R.string.pet_particles))
    PetEffectArea.entries.forEach { area -> TextButton(onClick = { model.configure { it.copy(effectArea = area) } }, modifier = Modifier.testTag("petEffectArea:${area.name}")) {
        RadioButton(settings.effectArea == area, null)
        Text(stringResource(if(area == PetEffectArea.FULL_SCREEN) R.string.pet_full_screen else R.string.pet_lower_screen))
    } }
    SettingSlider(stringResource(R.string.pet_effect_intensity), (settings.effectIntensity * 100).roundToInt(), 10..100, "petIntensity") {
        value -> model.configure { it.copy(effectIntensity = value / 100f) } }
    SettingSlider(stringResource(R.string.pet_effect_speed), (settings.effectSpeed * 100).roundToInt(), 40..200, "petSpeed") {
        value -> model.configure { it.copy(effectSpeed = value / 100f) } }
    SettingSlider(stringResource(R.string.pet_effect_size), (settings.effectSize * 100).roundToInt(), 50..200, "petEffectSize") {
        value -> model.configure { it.copy(effectSize = value / 100f) } }
    SettingSlider(stringResource(R.string.pet_effect_opacity), (settings.effectOpacity * 100).roundToInt(), 15..100, "petOpacity") {
        value -> model.configure { it.copy(effectOpacity = value / 100f) } }
    PetWeather.entries.forEach { weather -> TextButton(onClick = { model.configure { it.copy(weather = weather) } }, modifier = Modifier.testTag("petWeather:${weather.name}")) {
        RadioButton(settings.weather == weather, null)
        Text(stringResource(when(weather) { PetWeather.AUTO -> R.string.pet_auto_weather; PetWeather.NONE -> R.string.pet_none_weather
            PetWeather.SNOW -> R.string.pet_snow; PetWeather.RAIN -> R.string.pet_rain; PetWeather.FIREFLIES -> R.string.pet_fireflies
            PetWeather.SPARKLES -> R.string.pet_sparkles }))
    } }
    if(error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
}
