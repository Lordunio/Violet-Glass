package pl.violetglass.launcher.pet

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val CARE_MILLIS = 1900

@Composable
internal fun rememberPetCareProgress(event: PetCareEvent?): State<Float> {
    val progress = remember(event) { Animatable(if (event == null) 1f else 0f) }
    LaunchedEffect(event) {
        if (event != null) {
            val age = (SystemClock.elapsedRealtimeNanos() - event.startedAt) / 1_000_000f
            val start = (age / CARE_MILLIS).coerceIn(0f, 1f)
            progress.snapTo(start)
            if (start < 1f) progress.animateTo(1f, tween((CARE_MILLIS * (1f - start)).roundToInt(), easing = LinearEasing))
        }
    }
    return progress.asState()
}

internal fun DrawScope.drawPetCareCharacter(state: PetUiState, event: PetCareEvent?, progress: Float, showEnvelope: Boolean = true) {
    if (!state.ready || !state.visible) return
    val portrait = if (showEnvelope) state else state.copy(
        mode = if (state.mode == PetMode.COURIER) PetMode.IDLE else state.mode, notificationCount = 0)
    if (event == null || progress >= 1f) {
        drawCharacter(portrait)
        return
    }
    val unit = state.sizePx / 16f
    val lift = if (event.action == PetCareAction.REST) 0f else sin(progress * PI.toFloat() * 4).coerceAtLeast(0f) * unit * 1.6f
    val pose = when {
        event.action == PetCareAction.REST -> PetMode.SLEEPING
        event.action == PetCareAction.FEED && state.appearance == PetAppearance.HOME_ROBOT -> PetMode.EATING
        else -> PetMode.IDLE
    }
    drawCharacter(portrait.copy(mode = pose, notificationCount = 0, feet = state.feet - Offset(0f, lift)))
    val origin = state.feet - Offset(0f, unit * 10f)
    val alpha = minOf(1f, (1f - progress) * 4f)
    val tint = when (event.action) {
        PetCareAction.FEED -> Color(0xFFFFD082)
        PetCareAction.CLEAN -> Color(0xFF9DEFFF)
        PetCareAction.PAT -> Color(0xFFFF9EC8)
        PetCareAction.PLAY -> Color(0xFFD1B9FF)
        PetCareAction.REST -> Color(0xFFBFE998)
    }
    repeat(8) { index ->
        val angle = index * PI.toFloat() / 4
        val radius = unit * (5f + progress * 9f)
        val point = origin + Offset(cos(angle), sin(angle)) * radius - Offset(0f, progress * unit * 3)
        drawRect(tint.copy(alpha = alpha), point, Size(unit, unit))
    }
    val iconSize = unit * 7f
    val iconCenter = state.feet + Offset(unit * 6f, -unit * (13 + 4 * progress))
    drawPetCareIcon(event.action, iconCenter - Offset(iconSize / 2, iconSize / 2), iconSize,
        state.appearance == PetAppearance.HOME_ROBOT, alpha)
}

private data class CareGlyph(val pixels: List<String>, val light: Color, val shade: Color)

private val bowl = CareGlyph(listOf("........", "..lll...", ".llllll.", "ssssssss", ".ssssss.", ".llllll.", "..ssss..", "........"),
    Color(0xFFF6CB87), Color(0xFFAA93EE))
private val battery = CareGlyph(listOf("...ss...", "..ssss..", "..slls..", "..slls..", "..slls..", "..slls..", "..ssss..", "........"),
    Color(0xFFFFD979), Color(0xFFA280E5))
private val broom = CareGlyph(listOf("......ls", ".....ls.", "....ls..", "...ls...", "..ssl...", ".sllls..", "slllls..", ".ssss..."),
    Color(0xFFFFD27D), Color(0xFFC18757))
private val heart = CareGlyph(listOf("........", ".ll..ll.", "llllllll", "llllllll", ".llllll.", "..llll..", "...ss...", "........"),
    Color(0xFFFF9EC8), Color(0xFFC767A0))
private val toy = CareGlyph(listOf("........", "..ssss..", ".sllsls.", ".slslls.", ".sllsls.", ".slslls.", "..ssss..", "........"),
    Color(0xFFE8B6FF), Color(0xFF9868CD))
private val bed = CareGlyph(listOf("........", "...lll..", ".llllll.", "lllsslll", "llssllll", ".slllll.", "ssssssss", ".ssssss."),
    Color(0xFFC2E694), Color(0xFF698455))

internal fun DrawScope.drawPetCareIcon(action: PetCareAction, position: Offset, width: Float, robot: Boolean, alpha: Float = 1f) {
    val glyph = when (action) {
        PetCareAction.FEED -> if (robot) battery else bowl
        PetCareAction.CLEAN -> broom
        PetCareAction.PAT -> heart
        PetCareAction.PLAY -> toy
        PetCareAction.REST -> bed
    }
    val unit = (width / 8f).coerceAtLeast(1f)
    glyph.pixels.forEachIndexed { y, row ->
        row.forEachIndexed { x, pixel ->
            if (pixel != '.') drawRect((if (pixel == 'l') glyph.light else glyph.shade).copy(alpha = alpha),
                position + Offset(x * unit, y * unit), Size(unit, unit))
        }
    }
}
