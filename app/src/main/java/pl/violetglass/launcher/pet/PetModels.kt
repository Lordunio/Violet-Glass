package pl.violetglass.launcher.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.abs

enum class PetMode { IDLE, WALKING, HOPPING, SLEEPING, EATING, HAPPY, COURIER }
enum class PetBackgroundMode {
    PHOTO_PARALLAX, CYBER_LANDSCAPE, PIXEL_FOREST, NEON_CITY;

    val isProcedural: Boolean get() = this != PHOTO_PARALLAX
    internal val isPixelScene: Boolean get() = this == PIXEL_FOREST || this == NEON_CITY
}
enum class PetWeather { AUTO, NONE, SNOW, RAIN, FIREFLIES, SPARKLES }
enum class PetAppearance { PIXEL_CAT, HOME_ROBOT, PIXEL_FOX }
enum class PetMotion { CALM, NORMAL, FAST_HOPS }
enum class PetEffectArea { FULL_SCREEN, LOWER_SCREEN }
enum class PlatformKind { SEARCH, ICON, DOCK, FLOOR, WIDGET }
data class PlatformSurface(val id: String, val bounds: Rect, val kind: PlatformKind = PlatformKind.ICON)
data class PetSettings(val enabled: Boolean = false, val background: PetBackgroundMode = PetBackgroundMode.PHOTO_PARALLAX,
    val weather: PetWeather = PetWeather.AUTO, val parallax: Boolean = true,
    val appearance: PetAppearance = PetAppearance.PIXEL_CAT, val motion: PetMotion = PetMotion.CALM,
    val effectArea: PetEffectArea = PetEffectArea.FULL_SCREEN, val effectIntensity: Float = .5f,
    val effectSpeed: Float = 1f, val effectSize: Float = 1f, val effectOpacity: Float = .6f,
    val messagesEnabled: Boolean = true, val allMessageApps: Boolean = true, val messageApps: Set<String> = emptySet(),
    val messagesOnly: Boolean = true, val wallpaperEnabled: Boolean = enabled,
    val restPlace: String? = null, val sleepAt: Int = DEFAULT_SLEEP_AT, val wakeAt: Int = DEFAULT_WAKE_AT) {
    val sceneEnabled: Boolean get() = enabled || wallpaperEnabled
    fun normalized() = copy(effectIntensity = effectIntensity.safe(.1f, 1f, .5f), effectSpeed = effectSpeed.safe(.4f, 2f, 1f),
        effectSize = effectSize.safe(.5f, 2f, 1f), effectOpacity = effectOpacity.safe(.15f, 1f, .6f),
        messageApps = messageApps.filter { it.isNotBlank() }.take(1024).toSet(),
        sleepAt = sleepAt.takeIf { it in 0 until 1440 } ?: DEFAULT_SLEEP_AT,
        wakeAt = wakeAt.takeIf { it in 0 until 1440 } ?: DEFAULT_WAKE_AT)
    internal fun scheduledSleep(minute: Int): Boolean = when {
        sleepAt < wakeAt -> minute in sleepAt until wakeAt
        sleepAt > wakeAt -> minute >= sleepAt || minute < wakeAt
        else -> false
    }
    fun messageCount(active: Map<String, Int>): Int = if(!messagesEnabled) 0 else active.entries
        .filter { allMessageApps || it.key in messageApps }.sumOf { it.value.coerceIn(0, 999) }.coerceAtMost(999)
}
internal const val DEFAULT_SLEEP_AT = 22 * 60
internal const val DEFAULT_WAKE_AT = 6 * 60
internal const val PET_REST_DOCK = "dock"
internal fun petRestItem(id: String) = "item:$id"
private fun Float.safe(min: Float, max: Float, fallback: Float) = if(isFinite()) coerceIn(min, max) else fallback
internal enum class PetCareAction { FEED, CLEAN, PAT, PLAY, REST }
internal data class PetCareEvent(val action: PetCareAction, val startedAt: Long)
data class PowerState(val percent: Int = 100, val plugged: Boolean = false)
private const val EXHAUSTED_ENERGY = 8f
internal const val RECOVERED_ENERGY = 24f
data class PetStats(val energy: Float = 80f, val happiness: Float = 80f, val cleanliness: Float = 100f,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis(), val recovering: Boolean = false, val fullness: Float = 80f) {
    val needsRest: Boolean get() = energy <= EXHAUSTED_ENERGY || recovering && energy < RECOVERED_ENERGY

    fun advance(now: Long, plugged: Boolean = false, sleeping: Boolean = false, away: Boolean = false): PetStats {
        val hours = (max(0L, now - lastUpdatedTimestamp).coerceAtMost(30L * 86_400_000) / 3_600_000.0).toFloat()
        val rate = when {
            away -> 8f
            sleeping || needsRest -> if (plugged) 12f else 3f
            else -> -60f
        }
        val nextEnergy = (energy + hours * rate).coerceIn(0f, 100f)
        val nextRecovering = if (needsRest) nextEnergy < RECOVERED_ENERGY else nextEnergy <= EXHAUSTED_ENERGY
        return copy(energy = nextEnergy, recovering = nextRecovering,
            happiness = (happiness - hours * 1.5f).coerceIn(0f, 100f), cleanliness = (cleanliness - hours * if (away) 6f else 30f).coerceIn(0f, 100f),
            fullness = (fullness - hours * if (away) 10f else 40f).coerceIn(0f, 100f), lastUpdatedTimestamp = max(now, lastUpdatedTimestamp))
    }
}
/** Coordinates are launcher-root pixels; position is the centre of the pet's feet. */
data class PetUiState(val ready: Boolean = false, val mode: PetMode = PetMode.IDLE, val stats: PetStats = PetStats(),
    val feet: Offset = Offset.Zero, val sizePx: Float = 44f, val facing: Int = 1, val platformId: String? = null,
    val targetPlatform: String? = null, val targetX: Float? = null, val animationSeconds: Float = 0f,
    val visible: Boolean = false, val ripple: Offset? = null, val rippleAge: Float = 2f,
    val parallax: Offset = Offset.Zero, val night: Boolean = false,
    val appearance: PetAppearance = PetAppearance.PIXEL_CAT, val notificationCount: Int = 0, val charging: Boolean = false,
    val sleepingDesktop: Int? = null)

/** A reversible hop between desktops, driven by the pager rather than a second animation clock. */
internal fun PetUiState.acrossDesktop(from: Offset, pageDelta: Float, pageWidth: Float): PetUiState {
    val fraction = abs(pageDelta).coerceIn(0f, 1f)
    if (fraction == 0f) return copy(feet = from)
    if (fraction == 1f) return this
    val direction = if (pageDelta > 0f) 1 else -1
    val blend = fraction * fraction * (3f - 2f * fraction)
    val arc = 4f * fraction * (1f - fraction)
    val x = from.x + (feet.x - from.x) * blend + direction * minOf(pageWidth * .18f, sizePx * 2f) * arc
    val y = from.y + (feet.y - from.y) * blend - sizePx * 1.5f * arc
    return copy(
        mode = PetMode.HOPPING,
        facing = direction,
        feet = Offset(x.coerceIn(sizePx / 2f, max(sizePx / 2f, pageWidth - sizePx / 2f)), y.coerceAtLeast(sizePx)),
    )
}
