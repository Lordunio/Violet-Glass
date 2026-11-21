package pl.violetglass.launcher.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import pl.violetglass.launcher.ha.*
import pl.violetglass.launcher.icons.*

val Context.launcherDataStore by preferencesDataStore(name = "launcher_preferences")
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class LabelColor { AUTO, WHITE, BLACK, VIOLET }
enum class BadgeStyle { DOT, NUMBER }
enum class DesktopGesture { SWIPE_UP, SWIPE_DOWN, DOUBLE_TAP, LONG_PRESS, TWO_FINGER_UP }
enum class GestureAction { NONE, APPS, SEARCH, SETTINGS, WIDGETS, APP, SHORTCUT, WALLPAPER, LOCK }
data class GestureBinding(val action: GestureAction = GestureAction.NONE, val appKey: String = "", val shortcutId: String = "")
data class LauncherPreferences(
    val iconSize: Int = 40, val labelSize: Int = 12, val labels: Boolean = true,
    val labelColor: LabelColor = LabelColor.AUTO, val margin: Int = 16, val gap: Int = 3,
    val locked: Boolean = false, val theme: ThemeMode = ThemeMode.SYSTEM,
    val dockVisible: Boolean = true, val dockBackground: Boolean = false, val dockHeight: Int = 92,
    val drawerColumns: Int = 4, val folderColumns: Int = 4,
    val reverseApps: Boolean = false, val hiddenApps: Set<String> = emptySet(),
    val gestures: Map<DesktopGesture, GestureBinding> = defaultGestures(),
    val glass: GlassSettings = GlassSettings(),
    val ha: HaSettings = HaSettings(),
    val systemWallpaper: Boolean = true,
    val autoRotate: Boolean = false,
    val desktopRevision: Int = 1,
    val discover: DiscoverSettings = DiscoverSettings(),
    val iconPack: String = "", val iconOverrides: Set<String> = emptySet(),
    val badgesEnabled: Boolean = true, val badgeStyle: BadgeStyle = BadgeStyle.NUMBER,
    val drawerCategories: Map<String, Set<String>> = emptyMap(),
    val autoAddNewApps: Boolean = false,
    val petGameEnabled: Boolean = false,
)
fun defaultGestures() = DesktopGesture.entries.associateWith {
    GestureBinding(when (it) {
        DesktopGesture.SWIPE_UP -> GestureAction.APPS
        DesktopGesture.SWIPE_DOWN -> GestureAction.SEARCH
        DesktopGesture.LONG_PRESS -> GestureAction.SETTINGS
        else -> GestureAction.NONE
    })
}

class PreferencesRepository(private val store: DataStore<Preferences>) {
    val data = store.data.map(::decode)
    suspend fun update(transform: (LauncherPreferences) -> LauncherPreferences) {
        store.edit { prefs -> encode(prefs, transform(decode(prefs))) }
    }
    suspend fun setAutoAddNewApps(enabled: Boolean, packages: Set<String>) {
        store.edit { prefs ->
            prefs[bool("auto_add_new_apps")] = enabled
            if (enabled) prefs[knownApps] = packages else prefs.remove(knownApps)
        }
    }
    suspend fun knownAutoAddPackages() = store.data.first()[knownApps]
    suspend fun rememberAutoAddPackages(packages: Set<String>) {
        store.edit { it[knownApps] = packages }
    }
    companion object {
        fun snapshot(value: LauncherPreferences): Preferences = mutablePreferencesOf().also { encode(it, value) }
        private fun int(name: String) = intPreferencesKey(name)
        private fun bool(name: String) = booleanPreferencesKey(name)
        private fun str(name: String) = stringPreferencesKey(name)
        private val knownApps = stringSetPreferencesKey("auto_add_known_packages")
        private inline fun <reified T : Enum<T>> enum(value: String?, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback
        fun decode(p: Preferences): LauncherPreferences = LauncherPreferences(
            autoAddNewApps = p[bool("auto_add_new_apps")] ?: false,
            petGameEnabled = p[bool("pet_game_enabled")] ?: false,
            badgesEnabled = p[bool("badges_enabled")] ?: true,
            badgeStyle = enum(p[str("badge_style")], BadgeStyle.NUMBER),
            iconPack = p[str("icon_pack")]?.takeIf { it.isEmpty() || validPackage(it) } ?: "",
            iconOverrides = decodeIconChoices(p[stringSetPreferencesKey("icon_overrides")] ?: emptySet()).map { (key, choice) -> encodeIconChoice(key, choice) }.toSet(),
            discover = DiscoverSettings(p[bool("discover_enabled")] ?: DiscoverSettings().enabled,
                enum(p[str("discover_provider")], DiscoverProvider.DIRECT)),
            systemWallpaper = p[bool("system_wallpaper")] ?: true,
            autoRotate = p[bool("auto_rotate")] ?: false,
            desktopRevision = p[int("desktop_revision")] ?: 0,
            ha = HaSettings(enabled = p[bool("ha_enabled")] ?: HaSettings().enabled,
                localUrl = p[str("ha_local")] ?: HaSettings().localUrl,
                remoteUrl = p[str("ha_remote")] ?: "",
                dashboardPath = p[str("ha_path")] ?: HaSettings().dashboardPath,
                connection = enum(p[str("ha_connection")], HaConnection.LOCAL)),
            glass = GlassSettings(
                enabled = p[bool("glass_enabled")] ?: true,
                mode = enum(p[str("glass_mode")], GlassMode.CLASSIC),
                automatic = p[bool("glass_automatic")] ?: true,
                disabledTargets = (p[stringSetPreferencesKey("glass_disabled_targets")] ?: emptySet()).mapNotNull { name -> GlassTarget.entries.find { it.name == name } }.toSet(),
                blur = (p[int("glass_blur")] ?: 55).coerceIn(0, 100),
                transparency = (p[int("glass_transparency")] ?: 28).coerceIn(0, 100),
                reflections = (p[int("glass_reflections")] ?: 35).coerceIn(0, 100),
                animation = (p[int("glass_animation")] ?: 60).coerceIn(0, 100),
                corners = (p[int("glass_corners")] ?: 28).coerceIn(0, 48),
            ),
            iconSize = (p[int("icon_size")] ?: 40).coerceIn(28, 72),
            labelSize = (p[int("label_size")] ?: 12).coerceIn(10, 18),
            labels = p[bool("labels")] ?: true,
            labelColor = enum(p[str("label_color")], LabelColor.AUTO),
            margin = (p[int("margin")] ?: 16).coerceIn(0, 24), gap = (p[int("gap")] ?: 3).coerceIn(0, 16),
            locked = p[bool("locked")] ?: false, theme = enum(p[str("theme")], ThemeMode.SYSTEM),
            dockVisible = p[bool("dock_visible")] ?: true, dockBackground = p[bool("dock_background")] ?: false,
            dockHeight = (p[int("dock_height")] ?: 92).coerceIn(72, 140),
            drawerColumns = (p[int("drawer_columns")] ?: 4).coerceIn(3, 8),
            folderColumns = (p[int("folder_columns")] ?: 4).coerceIn(3, 6),
            reverseApps = p[bool("reverse_apps")] ?: false,
            hiddenApps = p[stringSetPreferencesKey("hidden_apps")] ?: emptySet(),
            drawerCategories = decodeDrawerCategories(p[stringSetPreferencesKey("drawer_categories")] ?: emptySet()),
            gestures = DesktopGesture.entries.associateWith { gesture ->
                val prefix = "gesture_${gesture.name}_"
                val fallback = defaultGestures().getValue(gesture)
                GestureBinding(enum(p[str(prefix + "action")], fallback.action), p[str(prefix + "app")] ?: "", p[str(prefix + "shortcut")] ?: "")
            },
        )
        private fun encode(p: MutablePreferences, v: LauncherPreferences) {
            p[bool("auto_add_new_apps")] = v.autoAddNewApps
            p[bool("pet_game_enabled")] = v.petGameEnabled
            if (!v.autoAddNewApps) p.remove(knownApps)
            p[bool("badges_enabled")] = v.badgesEnabled; p[str("badge_style")] = v.badgeStyle.name
            p[str("icon_pack")] = v.iconPack.takeIf { it.isEmpty() || validPackage(it) } ?: ""
            p[stringSetPreferencesKey("icon_overrides")] = decodeIconChoices(v.iconOverrides).map { (key, choice) -> encodeIconChoice(key, choice) }.toSet()
            p[bool("discover_enabled")] = v.discover.enabled
            p[str("discover_provider")] = v.discover.provider.name
            p[bool("system_wallpaper")] = v.systemWallpaper
            p[bool("auto_rotate")] = v.autoRotate
            p[int("desktop_revision")] = v.desktopRevision
            p[bool("ha_enabled")] = v.ha.enabled; p[str("ha_local")] = v.ha.localUrl
            p[str("ha_remote")] = v.ha.remoteUrl; p[str("ha_path")] = v.ha.dashboardPath
            p[str("ha_connection")] = v.ha.connection.name
            val g = v.glass
            p[bool("glass_enabled")] = g.enabled; p[str("glass_mode")] = g.mode.name
            p[bool("glass_automatic")] = g.automatic
            p[stringSetPreferencesKey("glass_disabled_targets")] = g.disabledTargets.map { it.name }.toSet()
            p[int("glass_blur")] = g.blur.coerceIn(0, 100)
            p[int("glass_transparency")] = g.transparency.coerceIn(0, 100)
            p[int("glass_reflections")] = g.reflections.coerceIn(0, 100)
            p[int("glass_animation")] = g.animation.coerceIn(0, 100)
            p[int("glass_corners")] = g.corners.coerceIn(0, 48)
            p[int("icon_size")] = v.iconSize.coerceIn(28, 72); p[int("label_size")] = v.labelSize.coerceIn(10, 18)
            p[bool("labels")] = v.labels; p[str("label_color")] = v.labelColor.name
            p[int("margin")] = v.margin.coerceIn(0, 24); p[int("gap")] = v.gap.coerceIn(0, 16)
            p[bool("locked")] = v.locked; p[str("theme")] = v.theme.name
            p[bool("dock_visible")] = v.dockVisible; p[bool("dock_background")] = v.dockBackground
            p[int("dock_height")] = v.dockHeight.coerceIn(72, 140)
            p[int("drawer_columns")] = v.drawerColumns.coerceIn(3, 8); p[int("folder_columns")] = v.folderColumns.coerceIn(3, 6)
            p[bool("reverse_apps")] = v.reverseApps; p[stringSetPreferencesKey("hidden_apps")] = v.hiddenApps
            p[stringSetPreferencesKey("drawer_categories")] = encodeDrawerCategories(v.drawerCategories)
            for ((gesture, binding) in v.gestures) {
                val prefix = "gesture_${gesture.name}_"
                p[str(prefix + "action")] = binding.action.name; p[str(prefix + "app")] = binding.appKey
                p[str(prefix + "shortcut")] = binding.shortcutId
            }
        }
    }
}
