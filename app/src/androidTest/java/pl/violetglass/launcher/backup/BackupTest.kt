package pl.violetglass.launcher.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.*
import pl.violetglass.launcher.ha.HaSettings
import pl.violetglass.launcher.pet.*
import java.io.File
import java.io.IOException
import java.util.UUID

class BackupTest {
    private val petSettings = PetSettings(enabled = true, wallpaperEnabled = false, appearance = PetAppearance.HOME_ROBOT,
        motion = PetMotion.FAST_HOPS, weather = PetWeather.SNOW, background = PetBackgroundMode.CYBER_LANDSCAPE,
        parallax = false, effectArea = PetEffectArea.LOWER_SCREEN, effectIntensity = .71f, effectSpeed = 1.4f,
        effectSize = 1.6f, effectOpacity = .42f, messagesEnabled = false, allMessageApps = false,
        messagesOnly = false, messageApps = setOf("org.example.chat"), restPlace = "item:widget")
    private fun layout() = LayoutState(LayoutConfig(columns = 6, pages = 2, dockSlots = 6), listOf(
        LayoutItem("folder", title = "Dom", container = "page:0", position = 0),
        LayoutItem("app", title = "Aplikacja", appKey = "0:missing.pkg/Activity", container = "folder:folder", position = 0),
        LayoutItem("dock", title = "Telefon", appKey = "0:pkg/Phone", container = DOCK, position = 0),
        LayoutItem("widget", title = "Zegar", provider = "pkg/Widget", widgetId = 37, spanX = 2, spanY = 2, container = "page:1", position = 0)))
    private fun prefs() = LauncherPreferences(iconSize = 59, theme = ThemeMode.DARK, systemWallpaper = false,
        hiddenApps = setOf("0:missing.pkg/Activity"), discover = DiscoverSettings(false, DiscoverProvider.BRIDGE),
        glass = GlassSettings(mode = GlassMode.LITE), ha = HaSettings(localUrl = "http://secret.example:8123", remoteUrl = "https://private.example/?token=SECRET", dashboardPath = "/private/view"))
    @Test fun roundTripPreservesLayoutAndSettingsButNeverWidgetIdsOrHaAddresses() {
        val document = BackupCodec.capture(layout(), prefs(), 1234)
        val bytes = BackupCodec.encode(document)
        val text = String(bytes)
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("secret.example")); assertFalse(text.contains("private/view")); assertFalse(text.contains("widgetId"))
        val restored = BackupCodec.decode(bytes)
        assertEquals(document, restored)
        assertEquals(1, restored.widgets); assertEquals(1, restored.folders); assertEquals(2, restored.shortcuts)
        assertNull(restored.layout.items.last().widgetId)
        val current = LauncherPreferences(ha = HaSettings(localUrl = "http://current:8123", dashboardPath = "/current/view"))
        val merged = BackupCodec.merge(restored.settings, current)
        assertEquals(current.ha.localUrl, merged.ha.localUrl); assertEquals(current.ha.dashboardPath, merged.ha.dashboardPath)
        assertEquals(59, merged.iconSize); assertEquals(GlassMode.LITE, merged.glass.mode)
        assertFalse(merged.discover.enabled)
    }
    private fun root() = JSONObject(String(BackupCodec.encode(BackupCodec.capture(layout(), prefs()))))
    private fun reject(root: JSONObject) { assertThrows(Exception::class.java) { BackupCodec.decode(root.toString().toByteArray()) } }
    @Test fun rejectsUnsupportedVersionsForeignFilesAndTruncatedInput() {
        reject(root().put("version", 4)); reject(root().put("version", 3)); reject(root().put("format", "nova"))
        assertThrows(Exception::class.java) { BackupCodec.decode(byteArrayOf(0x50, 0x4b, 3, 4)) }
        assertThrows(Exception::class.java) { BackupCodec.decode("{".toByteArray()) }
    }
    @Test fun petConfigurationRoundTripsAndOlderBackupsDecodeWithoutPetConfiguration() {
        val doc = BackupCodec.capture(layout(), prefs(), pet = petSettings)
        val payload = BackupCodec.encode(doc)
        val root = JSONObject(String(payload))
        assertEquals(3, root.getInt("version"))
        assertEquals(doc, BackupCodec.decode(payload))
        assertFalse(root.getJSONObject("pet").has("energy"))
        val older = root().put("version", 1)
        older.getJSONObject("settings").remove("icon_pack")
        older.getJSONObject("settings").remove("icon_overrides")
        assertNull(BackupCodec.decode(older.toString().toByteArray()).pet)
        assertNull(BackupCodec.decode(root().toString().toByteArray()).pet)
        for((key, value) in listOf("motion" to "UNKNOWN", "effectSpeed" to 500, "effectOpacity" to "0.5",
                "restPlace" to "file:/wallpaper.jpg", "energy" to 100)) {
            val invalid = JSONObject(String(payload))
            invalid.getJSONObject("pet").put(key, value)
            reject(invalid)
        }
    }
    @Test fun drawerCategoriesRoundTripAndOlderCopiesKeepExistingCategories() {
        val categories = mapOf("Dom" to setOf("0:example.home/Start"), "Gry" to emptySet())
        val current = prefs().copy(drawerCategories = categories)
        val bytes = BackupCodec.encode(BackupCodec.capture(layout(), current, pet = petSettings))
        val document = BackupCodec.decode(bytes)
        assertEquals(categories, BackupCodec.merge(document.settings, prefs()).drawerCategories)
        val older = JSONObject(String(bytes))
        older.getJSONObject("settings").remove("drawer_categories")
        val settings = BackupCodec.decode(older.toString().toByteArray()).settings
        assertEquals(categories, BackupCodec.merge(settings, current).drawerCategories)
    }
    @Test fun rejectsCollisionsOrphansAndForeignWidgetIds() {
        val collision = root(); collision.getJSONArray("items").getJSONObject(3).put("container", "page:0"); reject(collision)
        val orphan = root(); orphan.getJSONArray("items").getJSONObject(1).put("container", "folder:missing"); reject(orphan)
        val id = root(); id.getJSONArray("items").getJSONObject(3).put("widgetId", 37); reject(id)
    }
    @Test fun rejectsOutOfRangeAndSecretSettingsRatherThanCoercingThem() {
        val range = root(); range.getJSONObject("settings").put("icon_size", 999); reject(range)
        val secret = root(); secret.getJSONObject("settings").put("ha_remote", "https://private"); reject(secret)
        val unknown = root(); unknown.getJSONObject("settings").put("theme", "UNKNOWN"); reject(unknown)
        val wrongType = root(); wrongType.getJSONObject("grid").put("columns", "6"); reject(wrongType)
    }
    @Test fun boundsInputAndRejectsInvalidUtf8() {
        assertThrows(Exception::class.java) { BackupCodec.read(ByteArray(100).inputStream(), 99) }
        assertThrows(Exception::class.java) { BackupCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
    }
    @Test fun deeplyNestedUntrustedJsonIsRejectedBeforeThePlatformParser() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(("[".repeat(1000) + "]".repeat(1000)).toByteArray()) }
        val good = root(); good.getJSONArray("items").getJSONObject(0).put("title", "Dom { [ \\\" test")
        assertEquals("Dom { [ \\\" test", BackupCodec.decode(good.toString().toByteArray()).layout.items.first().title)
    }
    @Test fun noncanonicalPageNamesAndCoercedTextFieldsAreRejected() {
        val page = root(); page.getJSONArray("items").getJSONObject(3).put("container", "page:01"); reject(page)
        val text = root(); text.getJSONArray("items").getJSONObject(0).put("title", 123); reject(text)
    }
    @Test fun interruptedRestoreRollsForwardFromDurableJournalBeforeReleasingOldWidgets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, LayoutDatabase::class.java).build()
        val job = SupervisorJob()
        try {
            val repo = LayoutRepository(db); repo.initialize(); repo.edit { layout() }
            val preferences = PreferencesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { File(dir, "prefs.preferences_pb") }))
            preferences.update { LauncherPreferences(iconSize = 40, ha = HaSettings(localUrl = "http://current:8123")) }
            val pet = PetRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO),
                produceFile = { File(dir, "pet.preferences_pb") }))
            val restored = BackupCodec.capture(layout(), prefs(), pet = petSettings)
            val stats = PetStats(energy = 23f, lastUpdatedTimestamp = 1000)
            pet.save(stats)
            val released = mutableListOf<Int>()
            val journal = File(dir, "pending.json")
            val broken = BackupRestorer(repo, preferences, journal, { released.add(it) }, pet) { throw IOException("simulated interruption after Room") }
            try { broken.restore(restored); fail("Expected interruption") } catch(_: IOException) { }
            assertTrue(journal.exists()); assertTrue(released.isEmpty())
            assertEquals(40, preferences.data.first().iconSize)
            assertNull(repo.state.first()!!.items.last().widgetId)
            assertFalse(pet.settings.first().enabled)
            val restorer = BackupRestorer(repo, preferences, journal, { released.add(it) }, pet)
            restorer.recover()
            assertFalse(journal.exists()); assertEquals(listOf(37), released)
            assertEquals(59, preferences.data.first().iconSize)
            assertEquals("http://current:8123", preferences.data.first().ha.localUrl)
            assertEquals(restored.layout, repo.state.first())
            assertEquals(petSettings, pet.settings.first())
            assertEquals(stats, pet.resume(1000))
            restorer.restore(BackupCodec.capture(layout(), prefs()))
            assertEquals(petSettings, pet.settings.first())
        } finally { job.cancelAndJoin(); db.close(); dir.deleteRecursively() }
    }
    @Test fun selectingAnotherCopyAfterInterruptionDoesNotReportTheOldCopyAsNew() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, LayoutDatabase::class.java).build()
        val job = SupervisorJob()
        try {
            val repo = LayoutRepository(db); repo.initialize(); repo.edit { layout() }
            val preferences = PreferencesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { File(dir, "prefs.preferences_pb") }))
            val journal = File(dir, "pending.json")
            val first = BackupCodec.capture(layout(), prefs(), 100)
            try {
                BackupRestorer(repo, preferences, journal, {}) { throw IOException("interrupted") }.restore(first)
                fail("Expected failure")
            } catch(_: IOException) { }
            val second = BackupCodec.capture(LayoutState(LayoutConfig(columns = 7, dockSlots = 7)), LauncherPreferences(iconSize = 52), 200)
            BackupRestorer(repo, preferences, journal, {}).restore(second)
            assertFalse(journal.exists())
            assertEquals(second.layout, repo.state.first())
            assertEquals(52, preferences.data.first().iconSize)
        } finally { job.cancelAndJoin(); db.close(); dir.deleteRecursively() }
    }
}
