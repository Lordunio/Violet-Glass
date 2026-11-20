package pl.violetglass.launcher.pet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max

enum class PetMode { IDLE, WALKING, HOPPING, SLEEPING, EATING, HAPPY, COURIER }
enum class PetBackgroundMode { PHOTO_PARALLAX, CYBER_LANDSCAPE }
enum class PetWeather { AUTO, NONE, SNOW, RAIN, FIREFLIES, SPARKLES }
enum class PetAppearance { PIXEL_CAT, HOME_ROBOT }
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
    val restPlace: String? = null) {
    val sceneEnabled: Boolean get() = enabled || wallpaperEnabled
    fun normalized() = copy(effectIntensity = effectIntensity.safe(.1f, 1f, .5f), effectSpeed = effectSpeed.safe(.4f, 2f, 1f),
        effectSize = effectSize.safe(.5f, 2f, 1f), effectOpacity = effectOpacity.safe(.15f, 1f, .6f),
        messageApps = messageApps.filter { it.isNotBlank() }.take(1024).toSet())
    fun messageCount(active: Map<String, Int>): Int = if(!messagesEnabled) 0 else active.entries
        .filter { allMessageApps || it.key in messageApps }.sumOf { it.value.coerceIn(0, 999) }.coerceAtMost(999)
}
internal const val PET_REST_DOCK = "dock"
internal fun petRestItem(id: String) = "item:$id"
private fun Float.safe(min: Float, max: Float, fallback: Float) = if(isFinite()) coerceIn(min, max) else fallback
data class PowerState(val percent: Int = 100, val plugged: Boolean = false)
private const val EXHAUSTED_ENERGY = 8f
internal const val RECOVERED_ENERGY = 24f
data class PetStats(val energy: Float = 80f, val happiness: Float = 80f, val cleanliness: Float = 100f,
    val lastUpdatedTimestamp: Long = System.currentTimeMillis(), val recovering: Boolean = false) {
    val needsRest: Boolean get() = energy <= EXHAUSTED_ENERGY || recovering && energy < RECOVERED_ENERGY

    /** Offline exhaustion follows the same recovery rule, without assuming a charging history. */
    fun advance(now: Long, plugged: Boolean = false, sleeping: Boolean = false): PetStats {
        val hours = (max(0L, now - lastUpdatedTimestamp).coerceAtMost(30L * 86_400_000) / 3_600_000.0).toFloat()
        val rate = when { plugged -> 12f; sleeping || needsRest -> 3f; else -> -1.2f }
        val nextEnergy = (energy + hours * rate).coerceIn(0f, 100f)
        val nextRecovering = if (needsRest) nextEnergy < RECOVERED_ENERGY else nextEnergy <= EXHAUSTED_ENERGY
        return copy(energy = nextEnergy, recovering = nextRecovering,
            happiness = (happiness - hours * 1.5f).coerceIn(0f, 100f), cleanliness = (cleanliness - hours * .8f).coerceIn(0f, 100f),
            lastUpdatedTimestamp = max(now, lastUpdatedTimestamp))
    }
}
/** Coordinates are launcher-root pixels; position is the centre of the pet's feet. */
data class PetUiState(val ready: Boolean = false, val mode: PetMode = PetMode.IDLE, val stats: PetStats = PetStats(),
    val feet: Offset = Offset.Zero, val sizePx: Float = 44f, val facing: Int = 1, val platformId: String? = null,
    val targetPlatform: String? = null, val targetX: Float? = null, val animationSeconds: Float = 0f,
    val visible: Boolean = false, val ripple: Offset? = null, val rippleAge: Float = 2f,
    val parallax: Offset = Offset.Zero, val night: Boolean = false,
    val appearance: PetAppearance = PetAppearance.PIXEL_CAT, val notificationCount: Int = 0, val charging: Boolean = false)
