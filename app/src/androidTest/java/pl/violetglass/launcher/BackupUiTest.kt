package pl.violetglass.launcher

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import pl.violetglass.launcher.backup.*
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.pet.*
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.*
import java.util.UUID

class BackupUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    private lateinit var oldLayout: LayoutState
    private lateinit var originalPet: PetSettings
    private val pet get() = PetRepository(compose.activity.petDataStore)
    private val files = mutableListOf<Uri>()
    @Before fun prepare() {
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            original = model.preferencesRepository.data.first()
            originalPet = pet.settings.first()
            pet.update { PetSettings() }
            model.layoutRepository.initialize(); oldLayout = model.layoutRepository.state.first()!!
            model.preferencesRepository.update { LauncherPreferences(systemWallpaper = false, ha = it.ha.copy(enabled = false), discover = it.discover.copy(enabled = false)) }
            model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, dockSlots = 6)) }
        }
        compose.waitUntil(10_000) { model.workspace.value.layout?.config?.columns == 6 && !model.preferences.value.discover.enabled && !model.preferences.value.ha.enabled }
    }
    @After fun restore() {
        if(::original.isInitialized) runBlocking {
            model.dismissBackupPreview()
            model.preferencesRepository.update { original }; model.layoutRepository.edit { oldLayout }
            pet.update { originalPet }
            files.forEach { compose.activity.contentResolver.delete(it, null, null) }
        }
    }
    private fun file(bytes: ByteArray? = null): Uri {
        val uri = Uri.parse("content://pl.violetglass.launcher.test.backup/backup-${UUID.randomUUID()}.json")
        files.add(uri)
        if(bytes != null) compose.activity.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
        return uri
    }
    private fun document() = BackupCodec.capture(LayoutState(LayoutConfig(columns = 6, pages = 2, dockSlots = 6), listOf(
        LayoutItem("folder", title = "KopiaDom", container = "page:0", position = 0),
        LayoutItem("app", title = "Brakująca aplikacja", appKey = "0:missing/Activity", container = "folder:folder", position = 0),
        LayoutItem("widget", title = "Widget z kopii", provider = "pl.violetglass.launcher.test/pl.violetglass.launcher.fixtures.TestWidgetProvider", widgetId = 12345,
            container = "page:1", position = 0, spanX = 2, spanY = 2))), LauncherPreferences(iconSize = 61, ha = original.ha.copy(enabled = false), discover = DiscoverSettings(false)))
    private fun inspect(uri: Uri) {
        compose.runOnIdle { model.inspectBackup(uri) }
        compose.waitUntil(10_000) { !model.backup.value.busy }
    }
    private fun openBackup() {
        compose.openSettings(); compose.onNodeWithTag("section:BACKUP").performScrollTo().performClick()
    }
    @Test fun exportUsesContentResolverAndRestoreRequiresPreviewAndConfirmation() {
        val existingWidgetIds = compose.activity.widgets.host.appWidgetIds.toSet()
        val imported = document()
        val petConfig = PetSettings(appearance = PetAppearance.HOME_ROBOT, motion = PetMotion.FAST_HOPS,
            weather = PetWeather.SNOW, allMessageApps = false, messageApps = setOf("org.example.chat"), restPlace = "item:widget")
        runBlocking {
            model.layoutRepository.edit { imported.layout }
            model.preferencesRepository.update { BackupCodec.merge(imported.settings, it) }
            pet.update { petConfig }
        }
        val uri = file()
        compose.runOnIdle { model.exportBackup(uri) }
        compose.waitUntil(10_000) { !model.backup.value.busy }
        assertEquals(R.string.backup_exported, model.backup.value.message)
        val exported = compose.activity.contentResolver.openInputStream(uri)!!.use { BackupCodec.decode(BackupCodec.read(it)) }
        assertEquals(imported.layout, exported.layout)
        assertEquals(petConfig, exported.pet)
        runBlocking {
            model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, dockSlots = 6)) }
            model.preferencesRepository.update { it.copy(iconSize = 40) }
            pet.update { PetSettings() }
        }
        inspect(uri); openBackup()
        compose.onNodeWithTag("backupSummary").performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { model.layoutRepository.state.first()!!.items.isEmpty() })
        compose.onNodeWithTag("backupRestore").performScrollTo().performClick()
        compose.onNodeWithTag("backupConfirm").assertIsDisplayed()
        assertEquals(40, runBlocking { model.preferencesRepository.data.first().iconSize })
        assertEquals(PetSettings(), runBlocking { pet.settings.first() })
        compose.onNodeWithTag("backupConfirm").performClick()
        compose.waitUntil(10_000) { !model.backup.value.busy && model.backup.value.message == R.string.backup_restored }
        assertEquals(imported.layout, runBlocking { model.layoutRepository.state.first() })
        assertEquals(61, runBlocking { model.preferencesRepository.data.first().iconSize })
        assertEquals(petConfig, runBlocking { pet.settings.first() })
        assertNull(runBlocking { model.layoutRepository.state.first()!!.items.last().widgetId })
        assertEquals(existingWidgetIds, compose.activity.widgets.host.appWidgetIds.toSet())
        compose.onNodeWithTag("closePersonalization").performClick()
        compose.onNodeWithTag("page:1").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Widget z kopii").assertExists()
    }
    @Test fun invalidSecondFileClearsOldPreviewWithoutChangingCurrentLayout() {
        inspect(file(BackupCodec.encode(document())))
        assertNotNull(model.backup.value.preview)
        inspect(file("{invalid".toByteArray()))
        assertNull(model.backup.value.preview)
        assertEquals(R.string.backup_error, model.backup.value.message)
        assertTrue(runBlocking { model.layoutRepository.state.first()!!.items.isEmpty() })
        assertEquals(40, runBlocking { model.preferencesRepository.data.first().iconSize })
    }
    @Test fun rotationKeepsPreviewAndLayoutLockPreventsReplacement() {
        inspect(file(BackupCodec.encode(document()))); openBackup()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("backupSummary").performScrollTo().assertIsDisplayed()
        runBlocking { model.preferencesRepository.update { it.copy(locked = true) } }
        compose.waitUntil(5000) { model.preferences.value.locked }
        compose.onNodeWithTag("backupRestore").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("backupCancel").performScrollTo().performClick()
        assertNull(model.backup.value.preview)
        assertTrue(runBlocking { model.layoutRepository.state.first()!!.items.isEmpty() })
    }
}
