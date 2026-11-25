package pl.violetglass.launcher.settings

enum class GlassMode { ULTRA, CLASSIC, LITE }
enum class GlassTarget { DOCK, FOLDERS, DRAWER, SEARCH, MENUS, SETTINGS, PANELS, PET_WIDGETS }
data class GlassSettings(
    val enabled: Boolean = true,
    val mode: GlassMode = GlassMode.CLASSIC,
    val automatic: Boolean = true,
    val disabledTargets: Set<GlassTarget> = emptySet(),
    val blur: Int = 55,
    val transparency: Int = 28,
    val reflections: Int = 35,
    val animation: Int = 60,
    val corners: Int = 28,
)
data class GlassEnvironment(
    val lowRam: Boolean = false, val powerSave: Boolean = false,
    val thermalStatus: Int = 0, val hardware: Boolean = true,
    val animations: Boolean = true,
)
enum class GlassLimit { NONE, LOW_RAM, BATTERY, HEAT, HARDWARE }
data class GlassPolicy(
    val enabled: Boolean, val mode: GlassMode, val blurDp: Float,
    val reflection: Float, val durationMillis: Int, val limit: GlassLimit,
)
fun resolveGlass(settings: GlassSettings, environment: GlassEnvironment): GlassPolicy {
    if (!settings.enabled) return GlassPolicy(false, settings.mode, 0f, 0f, 0, GlassLimit.NONE)
    val limit = when {
        settings.automatic && environment.lowRam -> GlassLimit.LOW_RAM
        settings.automatic && environment.powerSave -> GlassLimit.BATTERY
        settings.automatic && environment.thermalStatus >= 2 -> GlassLimit.HEAT
        !environment.hardware -> GlassLimit.HARDWARE
        else -> GlassLimit.NONE
    }
    val mode = when {
        limit == GlassLimit.LOW_RAM || limit == GlassLimit.BATTERY ||
            (limit == GlassLimit.HEAT && environment.thermalStatus >= 3) -> GlassMode.LITE
        limit == GlassLimit.HEAT && settings.mode == GlassMode.ULTRA -> GlassMode.CLASSIC
        else -> settings.mode
    }
    val blur = if (!environment.hardware || mode == GlassMode.LITE) 0f
        else (if (mode == GlassMode.ULTRA) 32f else 12f) * settings.blur.coerceIn(0, 100) / 100f
    val reflection = if (mode == GlassMode.LITE) 0f else settings.reflections.coerceIn(0, 100) / 100f * (if (mode == GlassMode.ULTRA) 1f else .55f)
    val duration = if (!environment.animations || mode == GlassMode.LITE) 0 else
        ((if (mode == GlassMode.ULTRA) 340 else 180) * settings.animation.coerceIn(0, 100) / 100f).toInt()
    return GlassPolicy(true, mode, blur, reflection, duration, limit)
}
