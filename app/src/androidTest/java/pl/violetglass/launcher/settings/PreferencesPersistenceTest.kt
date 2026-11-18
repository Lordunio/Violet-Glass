package pl.violetglass.launcher.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PreferencesPersistenceTest {
    @Test fun visualSettingsAndGestureBindingsSurviveNewDataStoreInstance() = runBlocking {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "prefs-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "settings.preferences_pb")
        var job = SupervisorJob()
        try {
            fun repository() = PreferencesRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
            var repo = repository()
            repo.update { it.copy(autoRotate = true, iconSize = 58, labels = false, locked = true, theme = ThemeMode.DARK, drawerColumns = 6,
                discover = DiscoverSettings(enabled = false, provider = DiscoverProvider.BRIDGE), systemWallpaper = false,
                ha = pl.violetglass.launcher.ha.HaSettings(enabled = false, localUrl = "http://10.0.0.2:8123", remoteUrl = "https://ha.example.com", dashboardPath = "/dashboard-test/room", connection = pl.violetglass.launcher.ha.HaConnection.REMOTE),
                glass = GlassSettings(enabled = false, mode = GlassMode.ULTRA, automatic = false,
                    disabledTargets = setOf(GlassTarget.DOCK, GlassTarget.MENUS), blur = 83, transparency = 42, reflections = 67, animation = 27, corners = 44),
                hiddenApps = setOf("hidden"), gestures = it.gestures + (DesktopGesture.DOUBLE_TAP to GestureBinding(GestureAction.SHORTCUT, "0:pkg/Activity", "route"))) }
            val expected = repo.data.first()
            assertTrue(expected.autoRotate)
            job.cancelAndJoin()
            job = SupervisorJob()
            repo = repository()
            assertEquals(expected, repo.data.first())
            repo.update { it.copy(margin = 0) }
            coroutineScope { (0 until 20).map { async(Dispatchers.Default) { repo.update { it.copy(margin = it.margin + 1) } } }.awaitAll() }
            assertEquals(20, repo.data.first().margin)
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}
