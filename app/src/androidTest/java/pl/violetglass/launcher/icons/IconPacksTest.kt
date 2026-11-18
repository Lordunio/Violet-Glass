package pl.violetglass.launcher.icons

import android.content.ComponentName
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pl.violetglass.launcher.apps.AppsRepository
import pl.violetglass.launcher.backup.BackupCodec
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.LayoutState
import java.io.File
import java.util.UUID

class IconPacksTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val pack = "pl.violetglass.launcher.test"
    @Test fun installedPackMapsActivitiesAndProvidesManualPreviews() = runBlocking {
        val packs = IconPacks(context)
        assertTrue(packs.installed().any { it.packageName == pack })
        val index = packs.index(pack)
        assertEquals("test_icon_red", index.components[ComponentName("com.android.settings", "com.android.settings.Settings")])
        assertTrue(index.drawables.containsAll(listOf("test_icon_red", "test_icon_green")))
        assertEquals(Color.GREEN, packs.bitmap(pack, "test_icon_green")!!.getPixel(72, 72))
        assertNull(packs.bitmap(pack, "missing_icon"))
        assertTrue(packs.index("missing.icon.pack").drawables.isEmpty())
    }
    @Test fun globalPackIndividualChoiceSystemResetAndMissingResourcesProduceCorrectPixels() = runBlocking {
        val repository = AppsRepository(context)
        val app = repository.load().single { it.component.packageName == pack }
        val system = repository.icon(app)!!
        assertEquals(Color.RED, repository.icon(app, pack)!!.getPixel(72, 72))
        val preview = repository.previewIcon(app, pack)!!
        assertEquals(48, preview.width); assertEquals(Color.RED, preview.getPixel(24, 24))
        assertEquals(Color.GREEN, repository.icon(app, pack, IconChoice(pack, "test_icon_green"))!!.getPixel(72, 72))
        assertTrue(system.sameAs(repository.icon(app, pack, IconChoice())))
        assertTrue(system.sameAs(repository.icon(app, "missing.icon.pack")))
        assertTrue(system.sameAs(repository.icon(app, pack, IconChoice(pack, "missing_icon"))))
        assertEquals(Color.RED, repository.icon(app, pack)!!.getPixel(72, 72))
    }
    @Test fun savedChoicesSurviveRepositoryRecreationAndBackupV2AndLegacyV1Imports(): Unit = runBlocking {
        val file = File(context.cacheDir, "icons-${UUID.randomUUID()}.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val values = LauncherPreferences(iconPack = pack, iconOverrides = setOf(encodeIconChoice("0:com.example/.Main", IconChoice(pack, "test_icon_green"))))
        try {
            val repo = PreferencesRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
            repo.update { values }
            assertEquals(values, repo.data.first())
        } finally { scope.cancel(); scope.coroutineContext[Job]?.join() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = PreferencesRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            assertEquals(values, repo.data.first())
            val bytes = BackupCodec.encode(BackupCodec.capture(LayoutState(), values))
            assertEquals(values.iconOverrides, BackupCodec.merge(BackupCodec.decode(bytes).settings, LauncherPreferences()).iconOverrides)
            val old = JSONObject(String(bytes)).put("version", 1)
            old.getJSONObject("settings").remove("icon_pack"); old.getJSONObject("settings").remove("icon_overrides")
            val legacy = BackupCodec.merge(BackupCodec.decode(old.toString().toByteArray()).settings, values)
            assertEquals("", legacy.iconPack); assertTrue(legacy.iconOverrides.isEmpty())
            val invalid = JSONObject(String(bytes)); invalid.getJSONObject("settings").put("icon_pack", "../../bad")
            assertThrows(Exception::class.java) { BackupCodec.decode(invalid.toString().toByteArray()) }
        } finally { secondScope.cancel(); secondScope.coroutineContext[Job]?.join(); file.delete() }
    }
}
