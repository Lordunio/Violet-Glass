package pl.violetglass.launcher.backup

import org.json.JSONArray
import org.json.JSONObject
import pl.violetglass.launcher.pet.DEFAULT_SLEEP_AT
import pl.violetglass.launcher.pet.DEFAULT_WAKE_AT
import pl.violetglass.launcher.pet.PET_REST_DOCK
import pl.violetglass.launcher.pet.PetAppearance
import pl.violetglass.launcher.pet.PetBackgroundMode
import pl.violetglass.launcher.pet.PetEffectArea
import pl.violetglass.launcher.pet.PetMotion
import pl.violetglass.launcher.pet.PetSettings
import pl.violetglass.launcher.pet.PetWeather

/** User settings only. The live pet, notification contents and the wallpaper bitmap stay local. */
internal object PetBackupSettings {
    private val itemReference = Regex("item:[A-Za-z0-9_-]{1,80}")
    private val packageName = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
    private val keys by lazy { encode(PetSettings()).keys().asSequence().toSet() }
    private val optionalKeys = setOf("sleepAt", "wakeAt")

    fun encode(settings: PetSettings): JSONObject = JSONObject()
        .put("enabled", settings.enabled)
        .put("wallpaperEnabled", settings.wallpaperEnabled)
        .put("background", settings.background.name)
        .put("weather", settings.weather.name)
        .put("parallax", settings.parallax)
        .put("appearance", settings.appearance.name)
        .put("motion", settings.motion.name)
        .put("effectArea", settings.effectArea.name)
        .put("effectIntensity", settings.effectIntensity.toDouble())
        .put("effectSpeed", settings.effectSpeed.toDouble())
        .put("effectSize", settings.effectSize.toDouble())
        .put("effectOpacity", settings.effectOpacity.toDouble())
        .put("messagesEnabled", settings.messagesEnabled)
        .put("allMessageApps", settings.allMessageApps)
        .put("messageApps", JSONArray(settings.messageApps.sorted()))
        .put("messagesOnly", settings.messagesOnly)
        .put("restPlace", settings.restPlace ?: JSONObject.NULL)
        .put("sleepAt", settings.sleepAt)
        .put("wakeAt", settings.wakeAt)

    fun decode(json: JSONObject): PetSettings {
        val fields = json.keys().asSequence().toSet()
        require(fields - optionalKeys == keys - optionalKeys)
        val apps = json.getJSONArray("messageApps")
        require(apps.length() <= 1024)
        val packages = (0 until apps.length()).map { apps.get(it) as String }.toSet()
        require(packages.size == apps.length())
        return PetSettings(
            enabled = json.get("enabled") as Boolean,
            wallpaperEnabled = json.get("wallpaperEnabled") as Boolean,
            background = PetBackgroundMode.valueOf(json.get("background") as String),
            weather = PetWeather.valueOf(json.get("weather") as String),
            parallax = json.get("parallax") as Boolean,
            appearance = PetAppearance.valueOf(json.get("appearance") as String),
            motion = PetMotion.valueOf(json.get("motion") as String),
            effectArea = PetEffectArea.valueOf(json.get("effectArea") as String),
            effectIntensity = (json.get("effectIntensity") as Number).toFloat(),
            effectSpeed = (json.get("effectSpeed") as Number).toFloat(),
            effectSize = (json.get("effectSize") as Number).toFloat(),
            effectOpacity = (json.get("effectOpacity") as Number).toFloat(),
            messagesEnabled = json.get("messagesEnabled") as Boolean,
            allMessageApps = json.get("allMessageApps") as Boolean,
            messageApps = packages,
            messagesOnly = json.get("messagesOnly") as Boolean,
            restPlace = if(json.isNull("restPlace")) null else json.get("restPlace") as String,
            sleepAt = if (json.has("sleepAt")) json.get("sleepAt") as Int else DEFAULT_SLEEP_AT,
            wakeAt = if (json.has("wakeAt")) json.get("wakeAt") as Int else DEFAULT_WAKE_AT,
        ).also(::validate)
    }

    fun validate(settings: PetSettings) {
        require(settings == settings.normalized())
        require(settings.messageApps.all { it.length <= 255 && packageName.matches(it) })
        val restPlace = settings.restPlace
        require(restPlace == null || restPlace == PET_REST_DOCK || itemReference.matches(restPlace))
    }
}
