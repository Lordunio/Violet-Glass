package pl.violetglass.launcher.pet

import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.violetglass.launcher.MainActivity
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.ha.HaSettings
import pl.violetglass.launcher.notifications.BadgeStore
import pl.violetglass.launcher.openDesktopOptions
import pl.violetglass.launcher.workspace.*
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PetPersistenceTest {
    @Test fun legacyMasterSwitchKeepsBothLayersAndIndependentChoicesSurviveReload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "pet-migration-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        try {
            val legacy = booleanPreferencesKey("enabled")
            store.edit { it[legacy] = true }
            var repo = PetRepository(store)
            assertTrue(repo.settings.first().enabled && repo.settings.first().wallpaperEnabled)
            repo.update { it.copy(enabled = false) }
            repo = PetRepository(store)
            assertFalse(repo.settings.first().enabled)
            assertTrue(repo.settings.first().wallpaperEnabled)
            repo.update { it.copy(enabled = true, wallpaperEnabled = false) }
            repo = PetRepository(store)
            assertTrue(repo.settings.first().enabled)
            assertFalse(repo.settings.first().wallpaperEnabled)
            store.edit { it.clear(); it[legacy] = false }
            assertFalse(repo.settings.first().sceneEnabled)
        } finally { job.cancelAndJoin(); file.delete() }
    }
    @Test fun statsAndConfigurationSurviveNewStoreWithoutDoubleOfflineDecayOrStaleWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "pet-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(directory, "pet.preferences_pb")
        var job = SupervisorJob()
        try {
            fun repo() = PetRepository(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
            var repository = repo()
            val before = PreferencesRepository(context.launcherDataStore).data.first()
            repository.resume(1000)
            val config = PetSettings(true, PetBackgroundMode.CYBER_LANDSCAPE, PetWeather.RAIN, false,
                appearance = PetAppearance.PIXEL_FOX, motion = PetMotion.FAST_HOPS, effectArea = PetEffectArea.LOWER_SCREEN,
                effectIntensity = .9f, effectSpeed = 1.7f, effectSize = 1.3f, effectOpacity = .8f,
                allMessageApps = false, messageApps = setOf("chat.one", "chat.two"), messagesOnly = false,
                restPlace = petRestItem("calendar"), sleepAt = 21 * 60 + 45, wakeAt = 7 * 60 + 15)
            repository.update { config }
            val advanced = repository.resume(3_601_000)
            repository.save(PetStats(1f, 1f, 1f, 500))
            job.cancelAndJoin(); job = SupervisorJob(); repository = repo()
            assertEquals(advanced, repository.resume(3_601_000))
            assertEquals(88f, advanced.energy, .001f)
            assertEquals(config, repository.settings.first())
            assertEquals(before, PreferencesRepository(context.launcherDataStore).data.first())
        } finally { job.cancelAndJoin(); directory.deleteRecursively() }
    }
}

@RunWith(AndroidJUnit4::class)
class SensorLifecycleTest {
    @Test fun actualRotationVectorRegistrationStopsBelowResumedAndRestartsOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val controller = SensorParallaxController(instrumentation.targetContext)
        val job = SupervisorJob()
        val available = instrumentation.targetContext.getSystemService(android.hardware.SensorManager::class.java)
            .getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) != null
        fun waitRegistration(count: Int) {
            val until = android.os.SystemClock.uptimeMillis() + 5000
            while(android.os.SystemClock.uptimeMillis() < until) {
                var actual = -1
                instrumentation.runOnMainSync { actual = controller.registrations }
                if(actual == count) return
                Thread.sleep(15)
            }
            assertEquals(count, controller.registrations)
        }
        try {
            CoroutineScope(job + Dispatchers.Main).launch { controller.offsets(owner.lifecycle).collect {} }
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.STARTED }
            waitRegistration(0)
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitRegistration(if(available) 1 else 0)
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.STARTED }
            waitRegistration(0)
            instrumentation.runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitRegistration(if(available) 1 else 0)
        } finally { runBlocking { job.cancelAndJoin() }; waitRegistration(0) }
    }
}

@RunWith(AndroidJUnit4::class)
class PetUiTest {
    @get:Rule val compose = AndroidComposeTestRule(
        activityRule = ActivityScenarioRule<MainActivity>(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)),
        activityProvider = { rule -> var activity: MainActivity? = null; rule.scenario.onActivity { activity = it }; requireNotNull(activity) },
    )
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: LauncherViewModel
    private lateinit var pet: PetViewModel
    private lateinit var oldPreferences: LauncherPreferences
    private lateinit var oldLayout: LayoutState
    private lateinit var oldPetSettings: PetSettings
    private lateinit var oldStats: PetStats
    private var oldPhoto: ByteArray? = null
    private val repository get() = PetRepository(instrumentation.targetContext.petDataStore)
    private val photoFile get() = File(instrumentation.targetContext.filesDir, "pet-wallpaper.jpg")
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Before fun prepare() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Pet fixtures must run only on emulator" }
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        pet = ViewModelProvider(compose.activity)[PetViewModel::class.java]
        runBlocking {
            model.layoutRepository.initialize()
            oldPreferences = model.preferencesRepository.data.first(); oldLayout = model.layoutRepository.state.first()!!
            oldPetSettings = repository.settings.first(); oldStats = pet.ui.first { it.ready }.stats
            oldPhoto = if(photoFile.exists()) photoFile.readBytes() else null
            repository.update { PetSettings() }
            model.preferencesRepository.update { LauncherPreferences(ha = HaSettings(enabled = false), discover = DiscoverSettings(enabled = false)) }
        }
        compose.waitUntil(15000) { model.state.value.apps.any { it.component.packageName == "pl.violetglass.launcher.test" } }
        val fixture = model.state.value.apps.first { it.component.packageName == "pl.violetglass.launcher.test" }
        val items = (0 until 12).map { index -> LayoutItem("pet-icon-$index", title = "Ikona $index", appKey = fixture.key, container = pageContainer(0), position = 24 + index) } +
            (0 until 6).map { index -> LayoutItem("pet-dock-$index", title = "Dok $index", appKey = fixture.key, container = DOCK, position = index) }
        runBlocking { model.layoutRepository.edit { LayoutState(LayoutConfig(columns = 6, rows = 6, pages = 2, dockSlots = 6), items) } }
        compose.waitUntil(15000) { model.workspace.value.layout?.items?.size == 18 && !pet.settings.value.enabled && !model.preferences.value.ha.enabled }
        shell("dumpsys battery unplug"); shell("dumpsys battery set level 80")
        compose.onNodeWithTag("desktopPager").assertIsDisplayed()
    }
    @After fun restore() {
        if(::pet.isInitialized) {
            runBlocking { repository.update { it.copy(enabled = false, wallpaperEnabled = false) } }
            repeat(8) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(5) }
            compose.mainClock.autoAdvance = true
            runBlocking {
                model.layoutRepository.edit { oldLayout }; model.preferencesRepository.update { oldPreferences }
                repository.save(oldStats.copy(lastUpdatedTimestamp = System.currentTimeMillis()))
                if(oldPhoto != null) photoFile.writeBytes(oldPhoto!!) else photoFile.delete()
                repository.update { oldPetSettings }
            }
        }
        shell("dumpsys battery reset")
    }
    private fun framesUntil(condition: () -> Boolean) {
        repeat(300) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8); if(condition()) return }
        assertTrue("Condition after advancing pet frames: running=${pet.running}, mode=${pet.ui.value.mode}, lifecycle=${compose.activity.lifecycle.currentState}, notification=${BadgeStore.notificationSequence.value}", condition())
    }
    private fun enable(mode: PetBackgroundMode = PetBackgroundMode.CYBER_LANDSCAPE) {
        compose.mainClock.autoAdvance = false
        runBlocking { repository.update { it.copy(enabled = true, wallpaperEnabled = true, background = mode, weather = PetWeather.NONE) } }
        framesUntil { pet.running && pet.frameCount > 1 && pet.ui.value.visible && pet.ui.value.platformId?.startsWith("dock") == true }
    }
    @Test fun particlesKeepAnimatingDuringHeldDesktopSwipeAndReverse() {
        enable()
        for(effect in listOf(PetWeather.FIREFLIES, PetWeather.SNOW, PetWeather.RAIN, PetWeather.SPARKLES)) {
            runBlocking { repository.update { it.copy(weather = effect) } }
            framesUntil { pet.engine.particles.effect == effect }
            val root = compose.onNodeWithTag("launcherRoot")
            root.performTouchInput {
                down(Offset(width * .8f, height * .3f))
                moveTo(Offset(width * .45f, height * .3f), 180)
            }
            compose.mainClock.advanceTimeBy(64)
            compose.onNodeWithTag("petScene").assertExists()
            compose.onNodeWithTag("petCharacter").assertDoesNotExist()
            assertTrue(pet.running)
            val before = pet.frameCount
            val animation = pet.ui.value.animationSeconds
            compose.mainClock.advanceTimeBy(160)
            assertTrue(pet.frameCount > before)
            assertTrue(pet.ui.value.animationSeconds > animation)
            root.performTouchInput { moveTo(Offset(width * .8f, height * .3f), 180); up() }
            compose.mainClock.advanceTimeBy(1000)
            compose.onNodeWithTag("petScene").assertExists()
            assertTrue(pet.running)
        }
    }
    @Test fun sleepingPetReturnsToTheSameSpotAfterPagingAway() {
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        compose.mainClock.autoAdvance = false
        runBlocking { repository.update { it.copy(enabled = true, restPlace = petRestItem("pet-icon-0"),
            sleepAt = (now + 1430) % 1440, wakeAt = (now + 15) % 1440) } }
        val platform = "icon:pet-icon-0"
        framesUntil { pet.ui.value.mode == PetMode.SLEEPING && pet.ui.value.platformId == platform }
        val before = pet.ui.value.feet
        compose.onNodeWithTag("page:1").performClick()
        compose.mainClock.advanceTimeBy(1000)
        framesUntil { !pet.ui.value.visible }
        compose.onNodeWithTag("petCharacter").assertDoesNotExist()
        compose.onNodeWithTag("page:0").performClick()
        compose.mainClock.advanceTimeBy(1000)
        framesUntil { pet.ui.value.visible && pet.ui.value.platformId == platform }
        assertEquals(before, pet.ui.value.feet)
        assertEquals(PetMode.SLEEPING, pet.ui.value.mode)
    }
    @Test fun particlesRemainOnVisibleDesktopWhenCrossingDiscoverBoundary() {
        enable()
        runBlocking { model.preferencesRepository.update { it.copy(discover = DiscoverSettings(enabled = true)) } }
        framesUntil { model.preferences.value.discover.enabled }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("page:0").performClick()
        compose.mainClock.advanceTimeBy(1000)
        framesUntil { pet.running }
        val root = compose.onNodeWithTag("launcherRoot")
        root.performTouchInput {
            down(Offset(width * .15f, height * .3f))
            moveTo(Offset(width * .8f, height * .3f), 200)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("petScene").assertExists()
        compose.onNodeWithTag("petCharacter").assertDoesNotExist()
        val before = pet.frameCount
        compose.mainClock.advanceTimeBy(160)
        assertTrue(pet.running && pet.frameCount > before)
        root.performTouchInput { moveTo(Offset(width * .15f, height * .3f), 200); up() }
        compose.mainClock.advanceTimeBy(1000)
        framesUntil { pet.running }
        compose.onNodeWithTag("petScene").assertExists()
    }
    @Test fun defaultIsOffAndRealPlatformRegistryFollowsLayoutAndDisposal() {
        compose.onNodeWithTag("petScene").assertDoesNotExist()
        val tracker = GridSurfaceTracker()
        var shown by mutableStateOf(true)
        var margin by mutableStateOf(10.dp)
        compose.runOnUiThread { compose.activity.setContent {
            CompositionLocalProvider(LocalGridSurfaceTracker provides tracker) {
                Box(Modifier.fillMaxSize()) { if(shown) Box(Modifier.padding(start = margin).size(160.dp, 48.dp).testTag("pill").registerPlatformSurface("search:pill")) }
            }
        } }
        compose.waitForIdle()
        assertEquals(compose.onNodeWithTag("pill").fetchSemanticsNode().boundsInRoot, tracker.surfaces.single().bounds)
        val first = tracker.surfaces.single().bounds.left
        compose.runOnUiThread { margin = 40.dp }; compose.waitForIdle()
        assertTrue(tracker.surfaces.single().bounds.left > first)
        compose.runOnUiThread { shown = false }; compose.waitForIdle()
        assertTrue(tracker.surfaces.isEmpty())
        val old = Any(); val replacement = Any()
        tracker.register("icon:a", old, Rect(0f, 0f, 50f, 50f)); tracker.register("icon:a", replacement, Rect(10f, 0f, 60f, 50f))
        tracker.unregister("icon:a", old); assertEquals(10f, tracker.surfaces.single().bounds.left, 0f)
    }
    @Test fun independentSwitchesRenderOnlyChosenLayersAndStopWhenBothAreOff() {
        enable()
        runBlocking { repository.update { it.copy(weather = PetWeather.FIREFLIES) } }
        framesUntil { pet.engine.particles.effect == PetWeather.FIREFLIES }
        val stored = model.workspace.value.layout
        compose.openDesktopOptions(); framesUntil { !pet.running }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("desktopSettings").performClick()
        compose.onNodeWithTag("section:PET").performScrollTo().performClick()
        compose.onNodeWithTag("petEnabled").performScrollTo().performClick()
        compose.waitUntil(10000) { !pet.settings.value.enabled }
        assertTrue(pet.settings.value.wallpaperEnabled)
        compose.onNodeWithTag("closePersonalization").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("closePersonalization").performClick()
        compose.mainClock.autoAdvance = false
        framesUntil { pet.running }
        compose.onNodeWithTag("interactiveWallpaper").assertExists()
        compose.onNodeWithTag("petScene").assertExists()
        compose.onNodeWithTag("petCharacter").assertDoesNotExist()
        assertEquals(0, pet.powerRegistrations)
        assertEquals(PetWeather.FIREFLIES, pet.engine.particles.effect)
        val before = pet.frameCount
        compose.mainClock.advanceTimeBy(160)
        assertTrue(pet.frameCount > before)
        runBlocking { repository.update { it.copy(enabled = true) } }
        framesUntil { pet.powerRegistrations == 1 }
        compose.openDesktopOptions(); framesUntil { !pet.running }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("desktopSettings").performClick()
        compose.onNodeWithTag("section:WALLPAPER").performScrollTo().performClick()
        compose.onNodeWithTag("wallpaperEffectsEnabled").performScrollTo().performClick()
        compose.waitUntil(10000) { !pet.settings.value.wallpaperEnabled }
        assertTrue(pet.settings.value.enabled)
        compose.onNodeWithTag("closePersonalization").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("closePersonalization").performClick()
        compose.mainClock.autoAdvance = false
        framesUntil { pet.running && pet.powerRegistrations == 1 && pet.engine.particles.effect == PetWeather.NONE }
        compose.onNodeWithTag("interactiveWallpaper").assertDoesNotExist()
        compose.onNodeWithTag("petCharacter").assertExists()
        runBlocking { repository.update { it.copy(enabled = false) } }
        framesUntil { !pet.running && pet.powerRegistrations == 0 }
        compose.onNodeWithTag("petScene").assertDoesNotExist()
        val stopped = pet.frameCount
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(stopped, pet.frameCount)
        assertEquals(stored, model.workspace.value.layout)
    }
    @Test fun fastHoppingCanBeSelectedWithoutChangingNotificationOptionsOrLayout() {
        enable()
        val stored = model.workspace.value.layout
        val messages = pet.settings.value.messageApps
        compose.openDesktopOptions(); framesUntil { !pet.running }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("desktopSettings").performClick()
        compose.onNodeWithTag("section:PET").performScrollTo().performClick()
        compose.onNodeWithTag("petMotion:FAST_HOPS").performScrollTo().performClick()
        compose.waitUntil(10000) { pet.settings.value.motion == PetMotion.FAST_HOPS }
        compose.onNodeWithTag("petRestPicker").performScrollTo().performClick()
        compose.onNodeWithTag("petRest:pet-icon-0").performClick()
        compose.waitUntil(10000) { pet.settings.value.restPlace == petRestItem("pet-icon-0") }
        compose.onNodeWithTag("petRestPicker").performScrollTo().performClick()
        compose.onNodeWithTag("petRestDock").performClick()
        compose.waitUntil(10000) { pet.settings.value.restPlace == PET_REST_DOCK }
        compose.onNodeWithText("Powiadomienia na brzuchu").assertDoesNotExist()
        compose.onNodeWithTag("closePersonalization").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("closePersonalization").performClick()
        framesUntil { pet.running }
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithTag("petCharacter").assertExists()
        assertEquals(messages, pet.settings.value.messageApps)
        assertEquals(stored, model.workspace.value.layout)
    }
    @Test fun bothCharactersReachANativeUpperWidgetAndTheTopIconRow() {
        val user = shell("am get-current-user").trim().toInt()
        var widgetId = -1
        shell("appwidget grantbind --package pl.violetglass.launcher --user $user")
        try {
            val info = compose.activity.widgets.providers().single {
                it.provider == ComponentName("pl.violetglass.launcher.test", "pl.violetglass.launcher.fixtures.TestWidgetProvider")
            }
            compose.runOnUiThread {
                widgetId = compose.activity.widgets.host.allocateAppWidgetId()
                assertTrue(compose.activity.widgets.manager.bindAppWidgetIdIfAllowed(widgetId, info.provider))
            }
            runBlocking { model.layoutRepository.edit { layout ->
                val topIcon = layout.items.first { !it.isWidget && it.container == pageContainer(0) }
                    .copy(id = "upper-icon", position = 4)
                layout.copy(items = layout.items.filter { it.container == DOCK } + topIcon +
                    LayoutItem("upper-widget", title = "Widget testowy", container = pageContainer(0), position = 6,
                        widgetId = widgetId, provider = info.provider.flattenToString(), spanX = 3, spanY = 2))
            } }
            compose.waitUntil(15000) { model.workspace.value.layout?.items?.any { it.id == "upper-widget" } == true }
            enable()
            runBlocking { repository.update { it.copy(motion = PetMotion.FAST_HOPS) } }
            framesUntil { pet.settings.value.motion == PetMotion.FAST_HOPS }
            val root = compose.onNodeWithTag("launcherRoot").fetchSemanticsNode().boundsInRoot
            val widget = compose.onNodeWithTag("widget:upper-widget").fetchSemanticsNode().boundsInRoot
            val icon = compose.onNodeWithTag("item:upper-icon").fetchSemanticsNode().boundsInRoot
            val dock = compose.onNodeWithTag("slot:dock:3").fetchSemanticsNode().boundsInRoot
            assertTrue("Widget must be above the old roaming boundary", widget.top < root.height * .45f)
            assertTrue(icon.top < widget.top)
            for(appearance in PetAppearance.entries) {
                runBlocking { repository.update { it.copy(appearance = appearance) } }
                for((id, bounds) in listOf("widget:upper-widget" to widget, "icon:upper-icon" to icon, "dock:bottom" to dock)) {
                    compose.runOnUiThread {
                        pet.engine.environment(PowerState(80, false), false)
                        pet.touch(Offset(bounds.center.x, bounds.top))
                    }
                    assertEquals(id, pet.ui.value.targetPlatform)
                    framesUntil { pet.ui.value.platformId == id && pet.ui.value.mode != PetMode.HOPPING }
                    assertEquals(appearance, pet.ui.value.appearance)
                    compose.onNodeWithTag("petCharacter").assertExists()
                }
            }
        } finally {
            runBlocking { repository.update { it.copy(enabled = false, wallpaperEnabled = false) } }
            if(widgetId >= 0) compose.runOnUiThread { compose.activity.widgets.host.deleteAppWidgetId(widgetId) }
            shell("appwidget revokebind --package pl.violetglass.launcher --user $user")
        }
    }
    @Test fun cyberScenePettingAndDrawerGestureDoNotMoveOrLaunchIcons() {
        enable()
        val saved = model.workspace.value.layout
        compose.onNodeWithTag("petCharacter").assertExists()
        val before = pet.ui.value.stats.happiness
        val state = pet.ui.value
        compose.onNodeWithTag("launcherRoot").performTouchInput { click(Offset(state.feet.x, state.feet.y - state.sizePx / 2)) }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(pet.ui.value.stats.happiness > before)
        assertEquals(saved, model.workspace.value.layout)
        shell("screencap -p /sdcard/violet-pet-cyber.png")
        val point = compose.onNodeWithTag("item:pet-icon-2").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(point, point - Offset(0f, 240f), 180) }
        framesUntil { !pet.running }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("appSearch").assertIsDisplayed()
        assertEquals(saved, model.workspace.value.layout)
    }
    @Test fun petMovesOnScreenWhilePhoneCharges() {
        enable()
        runBlocking { repository.update { it.copy(sleepAt = 0, wakeAt = 0, motion = PetMotion.FAST_HOPS) } }
        shell("dumpsys battery set ac 1")
        framesUntil { pet.ui.value.charging && pet.ui.value.sleepingDesktop == null }
        val character = compose.onNodeWithTag("petCharacter")
        val start = character.fetchSemanticsNode().boundsInRoot.center
        val feet = pet.ui.value.feet
        framesUntil { (pet.ui.value.feet - feet).getDistance() > pet.ui.value.sizePx * .5f }
        compose.mainClock.advanceTimeByFrame()
        assertTrue((character.fetchSemanticsNode().boundsInRoot.center - start).getDistance() > 2f)
        assertTrue(pet.ui.value.charging)
    }

    @Test fun pauseCompletelyStopsFrameLoopAndPowerReceiverThenResumes() {
        enable()
        framesUntil { pet.powerRegistrations == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        framesUntil { !pet.running && pet.powerRegistrations == 0 }
        val frames = pet.frameCount
        compose.mainClock.advanceTimeBy(2000)
        assertEquals(frames, pet.frameCount)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        framesUntil { pet.running && pet.frameCount > frames }
    }
    @Test fun folderTapAndEmptySpaceTargetKeepTheStoredLayoutIntact() {
        runBlocking { model.layoutRepository.edit { layout ->
            val icon = layout.items.first { it.id == "pet-icon-3" }
            layout.copy(items = layout.items.filter { it.id != icon.id } +
                icon.copy(appKey = null, title = "Folder testowy") +
                icon.copy(id = "pet-folder-child", container = folderContainer(icon.id), position = 0))
        } }
        enable()
        val stored = model.workspace.value.layout
        val point = compose.onNodeWithTag("launcherRoot").fetchSemanticsNode().boundsInRoot.let { Offset(it.width * .7f, it.height * .55f) }
        compose.onNodeWithTag("launcherRoot").performTouchInput { click(point) }
        framesUntil { pet.ui.value.ripple != null && pet.ui.value.targetPlatform != null }
        val folderCentre = compose.onNodeWithTag("item:pet-icon-3").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { click(folderCentre) }
        framesUntil { !pet.running }
        compose.onNodeWithTag("folderContents").assertExists()
        assertEquals(stored, model.workspace.value.layout)
    }
    @Test fun enabledPetRebasesOntoLandscapeDockWithoutRecreatingTheLauncher() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("sensorLandscape") == "true")
        enable()
        val activity = compose.activity
        val stored = model.workspace.value.layout
        runBlocking { model.preferencesRepository.update { it.copy(autoRotate = true) } }
        framesUntil { activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        framesUntil { pet.ui.value.platformId?.startsWith("dock:slot:") == true && pet.ui.value.visible }
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.runOnUiThread {
            val bars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse("Dark terrain needs light status icons after rotation", bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
        val root = compose.onNodeWithTag("launcherRoot").fetchSemanticsNode().boundsInRoot
        val state = pet.ui.value
        assertTrue(state.feet.x in root.left..root.right && state.feet.y <= root.bottom)
        assertTrue(state.feet.y - state.sizePx >= root.top - 1)
        compose.onNodeWithTag("landscapeDock").assertIsDisplayed()
        shell("screencap -p /sdcard/violet-pet-landscape.png")
        assertSame(activity, compose.activity)
        assertEquals(stored, model.workspace.value.layout)
    }
    @Test fun actualBatteryBroadcastsDriveEatingAndSleepingAndCourierUsesListenerEvent() {
        enable()
        runBlocking { repository.update { it.copy(appearance = PetAppearance.HOME_ROBOT, weather = PetWeather.SPARKLES,
            allMessageApps = false, messageApps = setOf("pl.violetglass.launcher.test")) } }
        framesUntil { pet.ui.value.appearance == PetAppearance.HOME_ROBOT }
        shell("dumpsys battery set ac 1")
        framesUntil { pet.ui.value.mode == PetMode.EATING }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(12) }
        shell("screencap -p /sdcard/violet-robot-charging.png")
        shell("dumpsys battery set ac 0"); shell("dumpsys battery set level 5")
        framesUntil { pet.ui.value.mode == PetMode.SLEEPING }
        val component = "pl.violetglass.launcher/pl.violetglass.launcher.notifications.BadgeListener"
        val granted = BadgeStore.hasAccess(instrumentation.targetContext)
        fun fixture(cancel: Boolean) {
            instrumentation.targetContext.startActivity(Intent().setComponent(ComponentName("pl.violetglass.launcher.test",
                "pl.violetglass.launcher.fixtures.TestNotificationActivity")).putExtra("cancel", cancel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            shell("cmd notification allow_listener $component")
            framesUntil { BadgeStore.connected.value }
            shell("pm grant pl.violetglass.launcher.test android.permission.POST_NOTIFICATIONS")
            fixture(true)
            framesUntil { "pl.violetglass.launcher.test" !in BadgeStore.counts.value && pet.running }
            val sequence = BadgeStore.notificationSequence.value
            fixture(false)
            framesUntil { BadgeStore.notificationSequence.value > sequence }
            framesUntil { pet.ui.value.mode == PetMode.COURIER }
            repeat(4) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(12) }
            shell("screencap -p /sdcard/violet-robot-courier.png")
            compose.mainClock.advanceTimeBy(12000)
            assertEquals("Envelope must persist past the old seven-second timeout", PetMode.COURIER, pet.ui.value.mode)
            runBlocking { repository.update { it.copy(messageApps = emptySet()) } }
            framesUntil { pet.ui.value.notificationCount == 0 && pet.ui.value.mode == PetMode.SLEEPING }
            runBlocking { repository.update { it.copy(messageApps = setOf("pl.violetglass.launcher.test")) } }
            framesUntil { pet.ui.value.notificationCount > 0 && pet.ui.value.mode == PetMode.COURIER }
            // Icon badge preferences must not disable the pet's selected notifications.
            runBlocking { model.preferencesRepository.update { it.copy(badgesEnabled = false) } }
            framesUntil { BadgeStore.counts.value.isEmpty() }
            assertEquals(PetMode.COURIER, pet.ui.value.mode)
            fixture(true)
            framesUntil { pet.ui.value.notificationCount == 0 && pet.ui.value.mode == PetMode.SLEEPING }
        } finally {
            fixture(true)
            if(!granted) shell("cmd notification disallow_listener $component")
        }
    }
    @Test fun robotChoiceAndEffectSlidersApplyFromSettingsWithoutChangingIcons() {
        enable()
        val saved = model.workspace.value.layout
        compose.openDesktopOptions(); framesUntil { !pet.running }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("desktopSettings").performClick()
        compose.onNodeWithTag("section:PET").performScrollTo().performClick()
        compose.onNodeWithTag("petPickPhoto").assertDoesNotExist()
        compose.onNodeWithTag("petAppearance:HOME_ROBOT").performScrollTo().performClick()
        compose.onNodeWithTag("petMotion:NORMAL").performScrollTo().performClick()
        compose.onNodeWithTag("petMotion:CALM").performScrollTo().performClick()
        compose.onNodeWithTag("settingsBack").performClick()
        compose.onNodeWithTag("section:WALLPAPER").performScrollTo().performClick()
        compose.onNodeWithTag("petAppearance:HOME_ROBOT").assertDoesNotExist()
        compose.onNodeWithTag("petEffectArea:LOWER_SCREEN").performScrollTo().performClick()
        compose.onNodeWithTag("petEffectArea:FULL_SCREEN").performScrollTo().performClick()
        for((key, value) in listOf("petIntensity" to 90f, "petSpeed" to 140f, "petEffectSize" to 140f, "petOpacity" to 80f)) {
            compose.onNodeWithTag(key).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
        }
        compose.onNodeWithTag("petWeather:SPARKLES").performScrollTo().performClick()
        compose.onNodeWithTag("settingsBack").performClick()
        compose.onNodeWithTag("section:PET").performScrollTo().performClick()
        compose.onNodeWithTag("petMessageAll").performScrollTo().performClick()
        compose.onNodeWithTag("petMessageChoose").performScrollTo().performClick()
        compose.onNodeWithTag("petMessageSearch").performTextInput("Violet")
        compose.onNodeWithTag("petMessageIcon:pl.violetglass.launcher.test", useUnmergedTree = true).assertIsDisplayed()
        shell("screencap -p /sdcard/violet-message-app-icons.png")
        compose.onNodeWithTag("petMessageApp:pl.violetglass.launcher.test").performScrollTo().performClick()
        compose.onNodeWithTag("petMessagePickerClose").performClick()
        compose.waitUntil(10000) { !pet.settings.value.allMessageApps && "pl.violetglass.launcher.test" in pet.settings.value.messageApps }
        compose.waitUntil(10000) { pet.settings.value.let { it.appearance == PetAppearance.HOME_ROBOT && it.effectIntensity == .9f && it.effectOpacity == .8f && it.effectSpeed == 1.4f && it.effectSize == 1.4f } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("closePersonalization").performClick()
        framesUntil { pet.running && pet.ui.value.appearance == PetAppearance.HOME_ROBOT && pet.engine.particles.effect == PetWeather.SPARKLES }
        assertTrue(pet.engine.particles.y.take(pet.engine.particles.activeCount).any { it < compose.activity.window.decorView.height * .45f })
        shell("screencap -p /sdcard/violet-robot-sparkles.png")
        assertEquals(saved, model.workspace.value.layout)
    }
    @Test fun lowerEffectAreaAndReducedDensityApplyWithoutRestartingTheScene() {
        enable()
        runBlocking { repository.update { it.copy(appearance = PetAppearance.HOME_ROBOT, weather = PetWeather.SNOW,
            effectArea = PetEffectArea.LOWER_SCREEN, effectIntensity = .1f) } }
        framesUntil { pet.engine.particles.effect == PetWeather.SNOW && pet.engine.particles.activeCount < 20 }
        val top = compose.activity.window.decorView.height * .45f
        assertTrue(pet.engine.particles.y.all { it >= top })
        val frames = pet.frameCount
        runBlocking { repository.update { it.copy(effectArea = PetEffectArea.FULL_SCREEN, effectIntensity = 1f) } }
        framesUntil { pet.frameCount > frames && pet.engine.particles.activeCount == 128 && pet.engine.particles.y.any { it < top } }
        compose.onNodeWithTag("petCharacter").assertExists()
    }
    @Test fun selectedAndActiveViewsCanRemoveOneOf64SourcesWithoutChangingOthers() {
        enable()
        val pkg = "pl.violetglass.launcher.test"
        val selected = (1..63).map { "zz.example.source$it" }.toSet()
        runBlocking { repository.update { it.copy(allMessageApps = false, messageApps = selected + pkg) } }
        val component = "pl.violetglass.launcher/pl.violetglass.launcher.notifications.BadgeListener"
        val granted = BadgeStore.hasAccess(instrumentation.targetContext)
        fun fixture(cancel: Boolean) {
            instrumentation.targetContext.contentResolver.call(Uri.parse("content://$pkg.notifications"), "update", null,
                android.os.Bundle().apply { putInt("id", 951); putBoolean("cancel", cancel) })
        }
        try {
            shell("cmd notification allow_listener $component")
            framesUntil { BadgeStore.connected.value }
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            fixture(false)
            framesUntil { pet.ui.value.mode == PetMode.COURIER }
            compose.openDesktopOptions(); framesUntil { !pet.running }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("desktopSettings").performClick()
            compose.onNodeWithTag("section:PET").performScrollTo().performClick()
            compose.onNodeWithTag("petMessageChoose").performScrollTo().performClick()
            val app = compose.onNodeWithTag("petMessageApp:$pkg")
            compose.onNodeWithTag("petMessageFilter:SELECTED").performClick()
            app.assertIsOn()
            compose.onNodeWithTag("petMessageFilter:ACTIVE").performClick()
            app.assertIsOn()
            compose.onNodeWithTag("petMessageIcon:$pkg", useUnmergedTree = true).assertIsDisplayed()
            app.performClick()
            compose.waitUntil(10_000) { pet.settings.value.messageApps == selected }
            app.assertDoesNotExist()
            compose.onNodeWithTag("petMessageFilterEmpty").assertIsDisplayed()
            compose.onNodeWithTag("petMessageFilter:ALL").performClick()
            compose.onNodeWithTag("petMessageSearch").performTextInput(pkg)
            app.assertIsOff()
        } finally {
            fixture(true)
            if(!granted) shell("cmd notification disallow_listener $component")
        }
    }
    @Test fun rainCyclesPastRealPlatformsWithoutPoolingOnTheDock() {
        enable()
        runBlocking { repository.update { it.copy(weather = PetWeather.RAIN, effectIntensity = 1f, appearance = PetAppearance.HOME_ROBOT) } }
        framesUntil { pet.engine.particles.effect == PetWeather.RAIN }
        compose.mainClock.advanceTimeBy(30_000)
        repeat(4) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(12) }
        val dockTop = compose.onNodeWithContentDescription(instrumentation.targetContext.getString(pl.violetglass.launcher.R.string.dock_label))
            .fetchSemanticsNode().boundsInRoot.top
        val particles = pet.engine.particles
        assertTrue(particles.y.take(particles.activeCount).count { kotlin.math.abs(it - dockTop) < 12 } < particles.activeCount / 2)
        assertTrue(particles.y.take(particles.activeCount).any { it < dockTop / 2 })
        shell("screencap -p /sdcard/violet-rain-fixed.png")
    }
    @Test fun mediaNotificationsDoNotHoldEnvelopeAndFilterCanBeConfigured() {
        enable()
        val pkg = "pl.violetglass.launcher.test"
        runBlocking { repository.update { it.copy(allMessageApps = false, messageApps = setOf(pkg), messagesOnly = true) } }
        val component = "pl.violetglass.launcher/pl.violetglass.launcher.notifications.BadgeListener"
        val granted = BadgeStore.hasAccess(instrumentation.targetContext)
        fun fixture(cancel: Boolean, category: String = android.app.Notification.CATEGORY_TRANSPORT) {
            instrumentation.targetContext.startActivity(Intent().setComponent(ComponentName(pkg,
                "pl.violetglass.launcher.fixtures.TestNotificationActivity")).putExtra("cancel", cancel)
                .putExtra("category", category).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            shell("cmd notification allow_listener $component")
            framesUntil { BadgeStore.connected.value }
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            fixture(false)
            framesUntil { pkg in BadgeStore.petMessages.value.all && pet.running }
            assertFalse(pkg in BadgeStore.petMessages.value.messages)
            assertEquals(0, pet.ui.value.notificationCount)
            runBlocking { repository.update { it.copy(messagesOnly = false) } }
            framesUntil { pet.ui.value.mode == PetMode.COURIER }
            runBlocking { repository.update { it.copy(messagesOnly = true) } }
            framesUntil { pet.ui.value.notificationCount == 0 && pet.ui.value.mode != PetMode.COURIER }
            fixture(false, android.app.Notification.CATEGORY_MESSAGE)
            framesUntil { pet.ui.value.notificationCount > 0 && pet.ui.value.mode == PetMode.COURIER }
            fixture(true)
            framesUntil { pet.ui.value.notificationCount == 0 }
        } finally { fixture(true); if(!granted) shell("cmd notification disallow_listener $component") }
    }
    @Test fun finalGroupedMessageClearsEnvelopeWith64SelectedAppsAndCanBeReposted() {
        enable()
        val pkg = "pl.violetglass.launcher.test"
        val sources = (1..63).map { "example.source$it" }.toSet() + pkg
        runBlocking { repository.update { it.copy(allMessageApps = false, messageApps = sources, messagesOnly = true) } }
        val component = "pl.violetglass.launcher/pl.violetglass.launcher.notifications.BadgeListener"
        val granted = BadgeStore.hasAccess(instrumentation.targetContext)
        fun fixture(id: Int, cancel: Boolean = false, summary: Boolean = false) {
            instrumentation.targetContext.contentResolver.call(Uri.parse("content://$pkg.notifications"), "update", null,
                android.os.Bundle().apply {
                    putInt("id", id)
                    putString("group", "violet_courier_group")
                    putBoolean("summary", summary)
                    putBoolean("cancel", cancel)
                })
            compose.waitUntil(10_000) {
                val posted = shell("cmd notification list").lineSequence().any { key ->
                    val fields = key.trim().split('|')
                    fields.getOrNull(1) == pkg && fields.getOrNull(2) == id.toString()
                }
                posted != cancel
            }
        }
        try {
            shell("cmd notification allow_listener $component")
            framesUntil { BadgeStore.connected.value }
            shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            listOf(941, 944, 945, 943).forEach { fixture(it, cancel = true) }
            framesUntil { pet.ui.value.notificationCount == 0 }
            fixture(944)
            framesUntil { pet.ui.value.notificationCount == 3 && pet.ui.value.mode == PetMode.COURIER }
            fixture(945)
            framesUntil { pet.ui.value.notificationCount == 6 && pet.ui.value.mode == PetMode.COURIER }
            fixture(943, summary = true)
            fixture(944, cancel = true)
            framesUntil { pet.ui.value.notificationCount == 3 && pet.ui.value.mode == PetMode.COURIER }
            fixture(945, cancel = true)
            framesUntil { pet.ui.value.notificationCount == 0 && pet.ui.value.mode != PetMode.COURIER }
            assertFalse(pkg in BadgeStore.petMessages.value.all)
            fixture(945)
            framesUntil { pet.ui.value.notificationCount == 3 && pet.ui.value.mode == PetMode.COURIER }
            fixture(945, cancel = true)
            framesUntil { pet.ui.value.notificationCount == 0 && pet.ui.value.mode != PetMode.COURIER }
        } finally {
            listOf(944, 945, 943).forEach { fixture(it, cancel = true) }
            if(!granted) shell("cmd notification disallow_listener $component")
        }
    }
    @Test fun cyberStatusIconsStayLightWhenThemeChangesWhilePetIsRunning() {
        enable()
        runBlocking { model.preferencesRepository.update { it.copy(systemWallpaper = false, theme = ThemeMode.DARK) } }
        framesUntil { model.preferences.value.theme == ThemeMode.DARK && !model.preferences.value.systemWallpaper }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        compose.runOnUiThread {
            assertFalse(androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).isAppearanceLightStatusBars)
        }
        runBlocking { model.preferencesRepository.update { it.copy(theme = ThemeMode.LIGHT) } }
        framesUntil { model.preferences.value.theme == ThemeMode.LIGHT }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        val labelLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("itemLabel:pet-icon-0", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayouts) }
        assertEquals("Auto labels must be light on cyber terrain", androidx.compose.ui.graphics.Color.White,
            labelLayouts.single().layoutInput.style.color)
        shell("screencap -p /sdcard/violet-status-theme.png")
        compose.runOnUiThread {
            val bars = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            assertFalse("Dark cyber wallpaper must retain white status icons after a theme update", bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
    }
    private fun assertStatusBars(lightIcons: Boolean) {
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bars = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, view)
            assertEquals(!lightIcons, bars.isAppearanceLightStatusBars)
            assertEquals(!lightIcons, bars.isAppearanceLightNavigationBars)
            assertTrue("Status bar must remain visible", androidx.core.view.ViewCompat.getRootWindowInsets(view)!!
                .isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()))
        }
    }
    @Test fun statusIconsSurviveMenusDrawerReturnAndThirtySecondsOfIdle() {
        runBlocking { model.preferencesRepository.update { it.copy(systemWallpaper = false, theme = ThemeMode.LIGHT) } }
        enable()
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        assertStatusBars(true)
        compose.openDesktopOptions(); framesUntil { !pet.running }
        assertStatusBars(true)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("desktopSettings").performClick()
        compose.onNodeWithTag("closePersonalization").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("closePersonalization").performClick()
        framesUntil { pet.running }
        compose.mainClock.advanceTimeBy(30_000)
        assertStatusBars(true)
        shell("screencap -p /sdcard/violet-status-idle.png")
        val point = compose.onNodeWithTag("item:pet-icon-2").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(point, point - Offset(0f, 240f), 180) }
        framesUntil { !pet.running }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        compose.onNodeWithTag("appSearch").assertIsDisplayed()
        assertStatusBars(false)
        compose.onNodeWithTag("homeButton").performClick()
        framesUntil { pet.running }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        assertStatusBars(true)
    }
    @Test fun focusAndResumeRestoreVisibleStatusIconsAfterSystemOverride() {
        enable()
        compose.runOnUiThread {
            val bars = androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
            bars.isAppearanceLightStatusBars = true
            bars.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        compose.runOnUiThread { compose.activity.onWindowFocusChanged(true) }
        repeat(20) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        assertStatusBars(true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        framesUntil { !pet.running }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        framesUntil { pet.running }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        assertStatusBars(true)
    }
    @Test fun photoStatusContrastFollowsPhotoRemovalAndSystemWallpaper() {
        runBlocking { model.preferencesRepository.update { it.copy(systemWallpaper = false, theme = ThemeMode.LIGHT) } }
        enable(PetBackgroundMode.PHOTO_PARALLAX)
        compose.runOnUiThread { pet.choosePhoto(Uri.parse("content://pl.violetglass.launcher.test.petphoto/photo.png")) }
        framesUntil { pet.photo.value != null }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        compose.onNodeWithTag("statusBarContrast").assertIsDisplayed()
        assertStatusBars(true)
        shell("screencap -p /sdcard/violet-status-photo.png")
        compose.runOnUiThread { pet.systemPhoto() }
        framesUntil { pet.photo.value == null }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        compose.onNodeWithTag("statusBarContrast").assertDoesNotExist()
        assertStatusBars(false)
        runBlocking { model.preferencesRepository.update { it.copy(systemWallpaper = true) } }
        framesUntil { model.preferences.value.systemWallpaper }
        repeat(12) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(8) }
        compose.onNodeWithTag("statusBarContrast").assertIsDisplayed()
        assertStatusBars(true)
    }
    @Test fun selectedPhotoLoadsThroughContentResolverAndSettingsSwitchBothModes() {
        enable(PetBackgroundMode.PHOTO_PARALLAX)
        compose.runOnUiThread { pet.choosePhoto(Uri.parse("content://pl.violetglass.launcher.test.petphoto/photo.png")) }
        framesUntil { pet.photo.value != null }
        assertTrue(pet.photo.value!!.width <= 2048 && pet.photo.value!!.height <= 2048)
        shell("screencap -p /sdcard/violet-pet-photo.png")
        compose.openDesktopOptions()
        framesUntil { !pet.running }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("desktopSettings").performClick()
        compose.onNodeWithTag("section:WALLPAPER").performScrollTo().performClick()
        val cyberPreview = compose.onNodeWithTag("petBackgroundPreview:CYBER_LANDSCAPE", useUnmergedTree = true)
        cyberPreview.assertIsDisplayed()
        val pixels = cyberPreview.captureToImage().toPixelMap()
        assertNotEquals(pixels[pixels.width / 2, pixels.height / 8], pixels[pixels.width / 2, pixels.height * 7 / 8])
        compose.onNodeWithTag("petBackground:CYBER_LANDSCAPE").performScrollTo().performClick()
        compose.waitUntil(10000) { pet.settings.value.background == PetBackgroundMode.CYBER_LANDSCAPE }
        compose.onNodeWithTag("wallpaperEffectsEnabled").performScrollTo().performClick()
        compose.waitUntil(10000) { !pet.settings.value.wallpaperEnabled }
        assertTrue(pet.settings.value.enabled)
        compose.onNodeWithTag("closePersonalization").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("closePersonalization").performClick()
        framesUntil { pet.running }
        compose.onNodeWithTag("interactiveWallpaper").assertDoesNotExist()
        compose.onNodeWithTag("petScene").assertExists()
        assertEquals(18, model.workspace.value.layout!!.items.size)
    }

    @Test fun pixelWallpapersCanBeSelectedIndependentlyAndRenderDifferentScenes() {
        compose.mainClock.autoAdvance = false
        runBlocking { repository.update { it.copy(wallpaperEnabled = true, parallax = false, weather = PetWeather.NONE) } }
        framesUntil { pet.settings.value.wallpaperEnabled && pet.running }
        val before = model.workspace.value.layout
        val colors = listOf(PetBackgroundMode.PIXEL_FOREST, PetBackgroundMode.NEON_CITY).map { mode ->
            compose.openDesktopOptions()
            framesUntil { !pet.running }
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("desktopSettings").performClick()
            compose.onNodeWithTag("section:WALLPAPER").performScrollTo().performClick()
            compose.onNodeWithTag("petBackground:${mode.name}").performScrollTo().performClick()
            compose.waitUntil(10_000) { pet.settings.value.background == mode }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("closePersonalization").performClick()
            framesUntil { pet.running }
            compose.mainClock.advanceTimeByFrame()
            assertFalse(pet.settings.value.enabled)
            val wallpaper = compose.onNodeWithTag("interactiveWallpaper").captureToImage().toPixelMap()
            shell("screencap -p /sdcard/violet-${mode.name.lowercase()}.png")
            wallpaper[wallpaper.width / 2, wallpaper.height / 8]
        }
        assertNotEquals(colors[0], colors[1])
        assertEquals(before, model.workspace.value.layout)
    }
}
