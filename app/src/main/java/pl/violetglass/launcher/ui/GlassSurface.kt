package pl.violetglass.launcher.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import pl.violetglass.launcher.settings.*

/** A display list, never a CPU bitmap or a screenshot of another app. */
class GlassBackdrop(val layer: GraphicsLayer) {
    var position by mutableStateOf(Offset.Unspecified)
}
val LocalSceneBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
val LocalWorkspaceBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }
val GlassActiveKey = SemanticsPropertyKey<Boolean>("VioletGlassActive")
val GlassBlurKey = SemanticsPropertyKey<Float>("VioletGlassBlurDp")
val GlassModeKey = SemanticsPropertyKey<String>("VioletGlassMode")
val GlassTargetKey = SemanticsPropertyKey<String>("VioletGlassTarget")

fun Modifier.recordGlassBackdrop(source: GlassBackdrop?): Modifier = if (source == null) this else
    onGloballyPositioned { source.position = it.localToScreen(Offset.Zero) }.drawWithContent {
        source.layer.record { this@drawWithContent.drawContent() }
        drawLayer(source.layer)
    }

@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val settings = LocalLauncherPreferences.current.glass
    val wallpaper = LocalLauncherPreferences.current.systemWallpaper
    val environment = rememberGlassEnvironment()
    val policy = resolveGlass(settings, environment)
    // Off, Lite and zero blur allocate no backdrop display lists.
    val scene = if (policy.blurDp > 0 && !wallpaper) rememberGraphicsLayer().let { layer -> remember(layer) { GlassBackdrop(layer) } } else null
    val workspace = if (policy.blurDp > 0) rememberGraphicsLayer().let { layer -> remember(layer) { GlassBackdrop(layer) } } else null
    CompositionLocalProvider(LocalGlassEnvironment provides environment, LocalGlassPolicy provides policy,
        LocalSceneBackdrop provides scene, LocalWorkspaceBackdrop provides workspace, LocalGlassBackdrop provides scene) {
        Box(Modifier.fillMaxSize()) {
            if (!wallpaper) PurpleBackground(Modifier.matchParentSize().recordGlassBackdrop(scene))
            content()
        }
    }
}

@Composable
private fun PurpleBackground(modifier: Modifier) {
    val policy = LocalGlassPolicy.current
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < .5f
    Canvas(modifier) {
        drawRect(colors.background)
        if (policy.enabled) {
            drawRect(Brush.linearGradient(listOf(
                if (dark) Color(0xFF291746) else Color(0xFFECE0FC),
                colors.background, if (dark) Color(0xFF141E36) else Color(0xFFE1EBFF)),
                start = Offset.Zero, end = Offset(size.width, size.height)))
            if (policy.mode != GlassMode.LITE) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF9563DB).copy(alpha = if (dark) .30f else .20f), Color.Transparent),
                    center = Offset(size.width * .15f, size.height * .25f), radius = size.width * .8f),
                    radius = size.width * .8f, center = Offset(size.width * .15f, size.height * .25f))
                // Fixed sculpted ribbons create depth without an idle animation/render loop.
                val ribbon = Path().apply {
                    moveTo(-size.width * .1f, size.height * .68f)
                    cubicTo(size.width * .5f, size.height * .35f, size.width * .4f, size.height * .9f, size.width * 1.1f, size.height * .55f)
                }
                drawPath(ribbon, Color(0xFFBFA2F4).copy(alpha = if (dark) .14f else .23f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * .16f))
                drawPath(ribbon, Color.White.copy(alpha = if (dark) .07f else .22f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

/** Effects are on an isolated background child. Foreground text, icons and native widgets stay sharp. */
@Composable
fun GlassSurface(
    target: GlassTarget,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    plainColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable () -> Unit,
) {
    val settings = LocalLauncherPreferences.current.glass
    val policy = LocalGlassPolicy.current
    val active = policy.enabled && target !in settings.disabledTargets
    val blur = if (active) policy.blurDp else 0f
    val shape = RoundedCornerShape(if (active) settings.corners.dp else 26.dp)
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, tween(if (active) policy.durationMillis else 0), label = "glassEntrance")
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val radius = with(density) { blur.dp.toPx() }
    val hasBackdrop = backdrop != null
    val effect = remember(radius, hasBackdrop) { if (radius > 0 && hasBackdrop) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).asComposeRenderEffect() else null }
    var position by remember { mutableStateOf(Offset.Unspecified) }
    val tintAlpha = if (!active || policy.mode == GlassMode.LITE || !LocalGlassEnvironment.current.hardware) 1f else 1f - settings.transparency / 100f
    Box(modifier.semantics { this[GlassActiveKey] = active; this[GlassBlurKey] = blur; this[GlassModeKey] = policy.mode.name; this[GlassTargetKey] = target.name }
        .then(if (active && policy.durationMillis > 0) Modifier.graphicsLayer {
            alpha = progress; scaleX = .985f + .015f * progress; scaleY = scaleX
        } else Modifier)
        .then(if (active && policy.mode != GlassMode.LITE) Modifier.shadow(10.dp, shape, clip = false,
            ambientColor = Color(0xFF442073), spotColor = Color(0xFF442073)) else Modifier)
        .clip(shape).then(if (effect != null) Modifier.onGloballyPositioned { position = it.localToScreen(Offset.Zero) } else Modifier)) {
        if (effect != null && backdrop != null) {
            Box(Modifier.matchParentSize().graphicsLayer { renderEffect = effect; compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    if (position.isSpecified && backdrop.position.isSpecified) {
                        val delta = backdrop.position - position
                        translate(delta.x, delta.y) { drawLayer(backdrop.layer) }
                    }
                })
        }
        Box(Modifier.matchParentSize().background(plainColor.copy(alpha = tintAlpha)))
        if (active && policy.reflection > 0) {
            Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(
                Color.White.copy(alpha = .26f * policy.reflection), Color(0xFFBF91FF).copy(alpha = .08f * policy.reflection),
                Color.Transparent, Color.White.copy(alpha = .08f * policy.reflection))))
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .48f * policy.reflection),
                    colors.primary.copy(alpha = .12f), Color.White.copy(alpha = .12f * policy.reflection))), shape))
        }
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) { content() }
    }
}

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    target: GlassTarget = GlassTarget.MENUS,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        GlassSurface(target, Modifier.widthIn(max = 560.dp).fillMaxWidth(), backdrop = LocalWorkspaceBackdrop.current) {
            Column(Modifier.padding(24.dp)) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f, fill = false)) { ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() } }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton?.invoke(); confirmButton() }
            }
        }
    }
}
