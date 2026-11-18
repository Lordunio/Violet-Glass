package pl.violetglass.launcher.backup

import android.content.ComponentName
import androidx.datastore.preferences.core.*
import org.json.JSONArray
import org.json.JSONObject
import pl.violetglass.launcher.pet.PetSettings
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.*
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BackupDocument(val layout: LayoutState, val settings: Preferences, val createdAt: Long, val pet: PetSettings? = null) {
    val folders get() = layout.items.count { it.isFolder }
    val widgets get() = layout.items.count { it.isWidget }
    val shortcuts get() = layout.items.count { !it.isFolder && !it.isWidget }
}

/** A versioned, bounded, allowlisted format. It never serializes a database or WebView profile. */
object BackupCodec {
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_ITEMS = 5000
    private val excluded = setOf("ha_local", "ha_remote", "ha_path", "ha_connection", "desktop_revision", "badges_enabled", "badge_style", "auto_rotate")
    private val schema by lazy { PreferencesRepository.snapshot(LauncherPreferences()).asMap().filterKeys { it.name !in excluded } }
    private val optionalSettings = setOf("drawer_categories")

    fun settings(value: LauncherPreferences): Preferences {
        val canonical = PreferencesRepository.snapshot(PreferencesRepository.decode(PreferencesRepository.snapshot(value)))
        val result = mutablePreferencesOf()
        canonical.asMap().filterKeys { it.name !in excluded }.forEach { (key, field) -> put(result, key.name, field) }
        return result
    }
    fun merge(settings: Preferences, current: LauncherPreferences): LauncherPreferences {
        validateSettings(settings)
        val merged = PreferencesRepository.snapshot(current).toMutablePreferences()
        settings.asMap().forEach { (key, value) -> put(merged, key.name, value) }
        return PreferencesRepository.decode(merged).copy(desktopRevision = maxOf(1, current.desktopRevision))
    }
    fun capture(layout: LayoutState, prefs: LauncherPreferences, now: Long = System.currentTimeMillis(),
        pet: PetSettings? = null): BackupDocument {
        LayoutEngine.validate(layout)
        val portable = layout.copy(items = layout.items.map { if(it.isWidget) it.copy(widgetId = null) else it })
        return BackupDocument(portable, settings(prefs), now, pet).also(::validate)
    }
    fun read(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while(true) {
            val count = input.read(buffer)
            if(count < 0) break
            require(count > 0 && output.size() + count <= limit) { "backup_size" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    fun encode(document: BackupDocument): ByteArray {
        validate(document)
        val c = document.layout.config
        val root = JSONObject().put("format", "violet-glass-backup")
            .put("version", if(document.pet == null) 2 else 3).put("createdAt", document.createdAt)
        document.pet?.let { root.put("pet", PetBackupSettings.encode(it)) }
        root.put("grid", JSONObject().put("columns", c.columns).put("rows", c.rows).put("pages", c.pages).put("dockSlots", c.dockSlots))
        val items = JSONArray()
        document.layout.items.forEach { item ->
            items.put(JSONObject().put("id", item.id).put("title", item.title).put("container", item.container).put("position", item.position)
                .put("kind", if(item.isWidget) "widget" else if(item.isFolder) "folder" else "app")
                .put("appKey", item.appKey ?: JSONObject.NULL).put("provider", item.provider ?: JSONObject.NULL)
                .put("spanX", item.spanX).put("spanY", item.spanY))
        }
        val prefs = JSONObject()
        document.settings.asMap().toList().sortedBy { it.first.name }.forEach { (key, value) ->
            prefs.put(key.name, if(value is Set<*>) JSONArray(value.filterIsInstance<String>().sorted()) else value)
        }
        root.put("items", items).put("settings", prefs)
        return root.toString(2).toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): BackupDocument {
        require(bytes.size in 1..MAX_BYTES)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        // Reject excessive nesting before the recursive platform JSON parser sees untrusted input.
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { char ->
            if(quoted) {
                if(escaped) escaped = false
                else if(char == '\\') escaped = true
                else if(char == '"') quoted = false
            } else when(char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 12) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
        val root = JSONObject(text)
        val version = integer(root, "version")
        require(root.get("format") == "violet-glass-backup" && version in 1..3)
        val keys = setOf("format", "version", "createdAt", "grid", "items", "settings")
        require(root.keySet() == if(version == 3) keys + "pet" else keys)
        val grid = root.getJSONObject("grid")
        require(grid.keySet() == setOf("columns", "rows", "pages", "dockSlots"))
        val config = LayoutConfig(columns = integer(grid, "columns"), rows = integer(grid, "rows"), pages = integer(grid, "pages"), dockSlots = integer(grid, "dockSlots"))
        val items = root.getJSONArray("items")
        require(items.length() <= MAX_ITEMS)
        val parsed = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            require(item.keySet() == setOf("id", "title", "container", "position", "kind", "appKey", "provider", "spanX", "spanY"))
            val kind = string(item, "kind")
            val app = if(item.isNull("appKey")) null else string(item, "appKey")
            val provider = if(item.isNull("provider")) null else string(item, "provider")
            require(when(kind) { "app" -> app != null && provider == null; "folder" -> app == null && provider == null; "widget" -> app == null && provider != null; else -> false })
            LayoutItem(string(item, "id"), title = string(item, "title"), appKey = app, provider = provider,
                container = string(item, "container"), position = integer(item, "position"), spanX = integer(item, "spanX"), spanY = integer(item, "spanY"))
        }
        val fields = root.getJSONObject("settings")
        val activeSchema = schema.filterKeys {
            (version >= 2 || it.name !in setOf("icon_pack", "icon_overrides")) &&
                (it.name !in optionalSettings || fields.has(it.name))
        }
        require(fields.keySet() == activeSchema.keys.map { it.name }.toSet())
        val prefs = mutablePreferencesOf()
        if(version == 1) { prefs[stringPreferencesKey("icon_pack")] = ""; prefs[stringSetPreferencesKey("icon_overrides")] = emptySet() }
        activeSchema.forEach { (key, example) ->
            val value = fields.get(key.name)
            val parsedValue: Any = when(example) {
                is Int -> integer(fields, key.name)
                is Boolean -> value.also { require(it is Boolean) }
                is String -> value.also { require(it is String && it.length <= 1024) }
                is Set<*> -> (value as JSONArray).let { array ->
                    require(array.length() <= MAX_ITEMS)
                    (0 until array.length()).map { array.get(it).also { field -> require(field is String && field.length <= 1024) } as String }.toSet()
                        .also { require(it.size == array.length()) }
                }
                else -> error("unsupported_setting")
            }
            put(prefs, key.name, parsedValue)
        }
        val createdAt = root.get("createdAt")
        require(createdAt is Long || createdAt is Int)
        val pet = if(version == 3) PetBackupSettings.decode(root.getJSONObject("pet")) else null
        return BackupDocument(LayoutState(config, parsed), prefs, (createdAt as Number).toLong(), pet).also(::validate)
    }
    private fun integer(json: JSONObject, key: String): Int = json.get(key).let {
        require(it is Int || it is Long && it in Int.MIN_VALUE..Int.MAX_VALUE)
        (it as Number).toInt()
    }
    private fun string(json: JSONObject, key: String): String = json.get(key).let { require(it is String); it }
    private fun validateSettings(prefs: Preferences) {
        val fields = prefs.asMap()
        val names = fields.keys.map { it.name }.toSet()
        val expected = schema.keys.map { it.name }.toSet()
        require(expected.containsAll(names) && names.containsAll(expected - optionalSettings))
        val canonical = settings(PreferencesRepository.decode(prefs)).asMap().filterKeys { it.name in names }
        require(canonical == fields) { "invalid_setting" }
    }
    private fun validate(document: BackupDocument) {
        require(document.createdAt >= 0 && document.layout.items.size <= MAX_ITEMS)
        document.layout.items.forEach {
            require(it.id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && it.title.length in 1..256 && it.container.length <= 90)
            require(it.container == DOCK || it.container in (0 until document.layout.config.pages).map(::pageContainer) || it.container.startsWith("folder:"))
            require(it.widgetId == null && (it.appKey == null || it.appKey.length in 1..1024))
            if(it.isWidget) require(it.provider!!.length <= 512 && ComponentName.unflattenFromString(it.provider) != null)
        }
        LayoutEngine.validate(document.layout)
        validateSettings(document.settings)
        document.pet?.let(PetBackupSettings::validate)
    }
    @Suppress("UNCHECKED_CAST")
    private fun put(prefs: MutablePreferences, name: String, value: Any) {
        when(value) {
            is Int -> prefs[intPreferencesKey(name)] = value
            is Boolean -> prefs[booleanPreferencesKey(name)] = value
            is String -> prefs[stringPreferencesKey(name)] = value
            is Set<*> -> prefs[stringSetPreferencesKey(name)] = value as Set<String>
            else -> error("unsupported_setting")
        }
    }
}
