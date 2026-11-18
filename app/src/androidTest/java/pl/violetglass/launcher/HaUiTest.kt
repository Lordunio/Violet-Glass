package pl.violetglass.launcher

import android.webkit.WebView
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.rules.TestName
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import pl.violetglass.launcher.ha.*
import pl.violetglass.launcher.home.LauncherViewModel
import pl.violetglass.launcher.settings.*
import pl.violetglass.launcher.workspace.*
import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.concurrent.thread
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.violetglass.launcher.ui.*
import pl.violetglass.launcher.discover.*

@RunWith(AndroidJUnit4::class)
class HaUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val name = TestName()
    private lateinit var model: LauncherViewModel
    private lateinit var original: LauncherPreferences
    private lateinit var oldLayout: LayoutState
    private lateinit var server: ServerSocket
    private val requests = AtomicInteger()
    private var running = true
    private var tls = false
    private val base get() = "${if(tls) "https" else "http"}://127.0.0.1:${server.localPort}"
    @Before fun prepare() {
        tls = name.methodName.startsWith("untrustedCertificate")
        server = if (tls) {
            val keys = KeyStore.getInstance("PKCS12")
            InstrumentationRegistry.getInstrumentation().context.assets.open("ha-test.p12").use { keys.load(it, "fixture-only".toCharArray()) }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "fixture-only".toCharArray()) }
            val ssl = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            ssl.serverSocketFactory.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        } else ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "HA-webview-fixture") {
            while (running) runCatching { server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream().bufferedReader()
                val request = input.readLine().orEmpty()
                while (!input.readLine().isNullOrEmpty()) { }
                val missing = request.contains("/missing")
                if (request.contains("/dashboard/main")) requests.incrementAndGet()
                val body = (if (missing) "Unavailable" else """
                    <!doctype html><html><head><title>HA WebView integration fixture</title><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                    <body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#eee5ff">
                    <button id="increment" style="width:180px;height:90px" onclick="localStorage.count=Number(localStorage.count||0)+1;this.textContent='Count '+localStorage.count">Test dashboard control</button>
                    <script>document.cookie='violet_fixture=ok; SameSite=Lax; path=/';window.fixtureReady=true;</script></body></html>
                """.trimIndent()).toByteArray(Charsets.UTF_8)
                val status = if (missing) "503 Service Unavailable" else "200 OK"
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                }
            } }
        }
        model = ViewModelProvider(compose.activity)[LauncherViewModel::class.java]
        runBlocking {
            model.layoutRepository.initialize(); oldLayout = model.layoutRepository.state.first { it != null }!!
            original = model.preferencesRepository.data.first()
            model.preferencesRepository.update { LauncherPreferences(discover = pl.violetglass.launcher.settings.DiscoverSettings(enabled = false), ha = HaSettings(localUrl = base, dashboardPath = "/dashboard/main")) }
            model.layoutRepository.edit { LayoutState() }
        }
        compose.waitUntil(15_000) { model.preferences.value.ha.localUrl == base && model.workspace.value.layout?.items?.isEmpty() == true }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.waitForIdle()
    }
    @After fun restore() {
        running = false; if (::server.isInitialized) server.close()
        if (::model.isInitialized && ::original.isInitialized) runBlocking { model.preferencesRepository.update { original }; model.layoutRepository.edit { oldLayout } }
    }
    private fun web(): HaWebView? { var view: HaWebView? = null; compose.activityRule.scenario.onActivity {
        view = android.view.inspector.WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root -> root.findViewById<HaWebView>(R.id.ha_webview) }
    }; return view }
    private fun js(code: String): String {
        var result: String? = null
        val current = web()!!
        compose.runOnUiThread { current.evaluateJavascript(code) { result = it } }
        compose.waitUntil(5000) { result != null }
        return result!!
    }
    private fun openHa() {
        val area = compose.onNodeWithTag("desktopPager").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(Offset(area.left + area.width * .2f, area.center.y), Offset(area.left + area.width * .78f, area.center.y), 350) }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("haScreen").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    private fun loaded() {
        compose.waitUntil(15_000) { web() != null && requests.get() > 0 }
        compose.waitUntil(10_000) { runCatching { js("window.fixtureReady===true") == "true" }.getOrDefault(false) }
    }
    private fun nativeTap(id: String) {
        val geometry = org.json.JSONArray(js("(()=>{const r=document.getElementById('$id').getBoundingClientRect();return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height/2)*devicePixelRatio]})()"))
        val offset = IntArray(2); compose.runOnUiThread { web()!!.getLocationOnScreen(offset) }
        val now = SystemClock.uptimeMillis()
        for((action, at) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 80)) {
            val event = MotionEvent.obtain(now, at, action, offset[0] + geometry.getDouble(0).toFloat(), offset[1] + geometry.getDouble(1).toFloat(), 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
    }
    @Test fun floatingHaTracksIntermediateFingerPositionsAndKeepsTheDocumentAcrossGoogle() {
        var leftDrag: ((Float, Boolean) -> Unit)? = null
        var layerChanged: (() -> Unit)? = null
        val fake: (android.app.Activity) -> DiscoverConnection = { object : DiscoverConnection {
            override val status = kotlinx.coroutines.flow.MutableStateFlow(DiscoverStatus.READY)
            override fun start(value: DiscoverProvider) {}
            override fun stop() {}
            override fun scroll(value: Float) {}
            override fun leftDrag(listener: ((Float, Boolean) -> Unit)?) { leftDrag = listener }
            override fun windowLayer(listener: (() -> Unit)?) { layerChanged = listener }
        } }
        runBlocking { model.preferencesRepository.update { it.copy(discover = DiscoverSettings(true)) } }
        compose.runOnUiThread { compose.activity.setContent {
            val prefs by model.preferences.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalLauncherPreferences provides prefs, LocalWidgetController provides compose.activity.widgets, LocalDiscoverFactory provides fake) {
                VioletTheme { GlassHost { LauncherScreen(model, 1, false, {}, {}, compose.activity.systemActions) } }
            }
        } }
        compose.waitUntil(10000) { leftDrag != null && web() != null }
        loaded()
        val current = web()!!
        val width = compose.activity.window.decorView.width
        fun screenX(): Int { val xy = IntArray(2); compose.runOnUiThread { current.getLocationOnScreen(xy) }; return xy[0] }
        compose.runOnUiThread { leftDrag!!(.4f, false) }
        compose.waitUntil(10000) { screenX() in (-width + 1)..-1 }
        val intermediate = screenX()
        compose.runOnUiThread { leftDrag!!(.7f, false) }
        compose.waitUntil(10000) { screenX() > intermediate }
        compose.runOnUiThread { leftDrag!!(.7f, true) }
        compose.waitUntil(10000) { screenX() == 0 }
        js("window.violetTransient=37")
        val count = requests.get()
        compose.runOnUiThread { layerChanged?.invoke() }
        compose.waitForIdle()
        assertSame("Raising the HA window must retain its WebView", current, web())
        assertEquals("37", js("window.violetTransient"))
        // Deliver screen-coordinate input while the target window itself moves.
        // Local-coordinate delta accumulation used to lag behind the finger.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val started = SystemClock.uptimeMillis()
        val y = compose.activity.window.decorView.height * .5f
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(started, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            SystemClock.sleep(80)
        }
        send(MotionEvent.ACTION_DOWN, width * .8f)
        try {
            for(fraction in listOf(.65f, .5f, .35f)) {
                send(MotionEvent.ACTION_MOVE, width * fraction)
                val expected = -width * (.8f - fraction)
                try { compose.waitUntil(5000) { kotlin.math.abs(screenX() - expected) < width * .06f } }
                catch(error: Throwable) { throw AssertionError("Screen drag expected x=$expected, actual=${screenX()}", error) }
            }
        } finally { send(MotionEvent.ACTION_UP, width * .35f) }
        compose.waitUntil(10000) { screenX() <= -width }
        compose.runOnUiThread { leftDrag!!(.8f, true) }
        compose.waitUntil(10000) { screenX() == 0 }
        compose.runOnUiThread { leftDrag!!(0f, true) }
        compose.waitUntil(10000) { screenX() <= -width }
        compose.runOnUiThread { leftDrag!!(.8f, true) }
        compose.waitUntil(10000) { screenX() == 0 }
        assertSame(current, web()); assertEquals(count, requests.get())
        assertEquals("37", js("window.violetTransient"))
        // Own window must pass real Back events to the Activity dispatcher.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
        compose.waitUntil(10000) { screenX() <= -width }
    }
    @Test fun cssViewportStaysFullHeightAcrossInternalPanelNavigation() {
        openHa(); loaded()
        js("""
            document.documentElement.style.height='100vh';
            document.body.style.cssText='margin:0;height:100%;display:block';
            document.body.innerHTML='<nav style="position:fixed;top:10px;left:25%;z-index:2"><button id="config">Settings</button><button id="map">Map</button><button id="notifications">Notifications</button></nav><main id="panel" style="height:100%;background:#eee5ff"></main>';
            for(const name of ['config','map','notifications'])document.getElementById(name).onclick=()=>{history.pushState({},'','/'+name);document.getElementById('panel').textContent=name;};
        """.trimIndent())
        for(panel in listOf("config", "map", "notifications")) {
            nativeTap(panel)
            compose.waitUntil(5000) { js("location.pathname==='/$panel'") == "true" }
            assertEquals("CSS viewport collapsed after opening $panel", "true",
                js("document.getElementById('panel').getBoundingClientRect().height>=innerHeight-2"))
            assertEquals("true", js("document.getElementById('panel').textContent==='$panel'"))
        }
    }
    @Test fun rightHalfSwipeReturnsToDesktopWithoutStartingAtTheScreenEdge() {
        openHa(); loaded()
        val area = compose.onNodeWithTag("haWebView").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput {
            swipe(Offset(area.left + area.width * .65f, area.center.y), Offset(area.left + area.width * .1f, area.center.y), 500)
        }
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
    }

    @Test fun fullScreenDashboardHasNoLauncherToolbarAndVerticalScrollKeepsClicksWorking() {
        openHa(); loaded()
        compose.onNodeWithTag("haHome").assertDoesNotExist(); compose.onNodeWithTag("haRefresh").assertDoesNotExist()
        val view = web()!!
        compose.runOnUiThread {
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)!!.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val location = IntArray(2); view.getLocationOnScreen(location)
            assertEquals(insets.top, location[1]); assertEquals(compose.activity.window.decorView.width, view.width)
        }
        js("document.body.style.display='block';document.body.style.height='5000px';document.getElementById('increment').style.cssText='position:fixed;left:30%;top:40%;width:160px;height:70px';localStorage.count='0'")
        val area = compose.onNodeWithTag("haWebView").fetchSemanticsNode().boundsInRoot
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = SystemClock.uptimeMillis()
        for(step in 0..20) {
            val action = if(step == 0) MotionEvent.ACTION_DOWN else if(step == 20) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val event = MotionEvent.obtain(now, now + step * 20L, action, area.center.x, area.top + area.height * (.85f - step * .03f), 0)
                .apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            SystemClock.sleep(20)
        }
        compose.waitUntil(5000) { js("window.scrollY>80") == "true" }
        compose.onNodeWithTag("haScreen").assertIsDisplayed()
        var previous = ""; var stable = 0
        compose.waitUntil(5000) {
            SystemClock.sleep(120)
            val current = js("window.scrollY")
            stable = if(current == previous) stable + 1 else 0
            previous = current
            stable >= 3
        }
        nativeTap("increment")
        compose.waitUntil(5000) { js("localStorage.count") == "\"1\"" }
    }
    @Test fun userClickedMapPopupProducesAnExternalLinkPromptInsteadOfBeingIgnored() {
        openHa(); loaded()
        js("document.getElementById('increment').onclick=()=>window.open('https://maps.google.com/?q=Warszawa','_blank')")
        nativeTap("increment")
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText(compose.activity.getString(R.string.ha_external_title)).assertIsDisplayed(); true }.getOrDefault(false) }
        compose.onNodeWithText("Anuluj").performClick()
        compose.onNodeWithTag("haWebView").assertIsDisplayed()
    }
    @Test fun drawerOpensWithBottomSwipeWithoutVisibleButtonAndSearchStaysSingleField() {
        compose.onNodeWithTag("allAppsButton").assertDoesNotExist()
        compose.onAllNodesWithText("Wszystkie aplikacje").assertCountEquals(0)
        val area = compose.onNodeWithTag("appDrawerGestureArea").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("launcherRoot").performTouchInput { swipe(area.center, area.center - Offset(0f, 260f), 350) }
        compose.onNodeWithTag("appSearch").assertIsDisplayed().performTextInput("settings")
        compose.onAllNodesWithText("Szukaj aplikacji").assertCountEquals(0)
    }
    @Test fun rightSwipeLoadsRealWebViewControlsRespondAndReturnKeepsPageWithoutReload() {
        openHa(); loaded()
        val first = web()!!
        js("localStorage.count='0'")
        val geometry = org.json.JSONArray(js("(()=>{const r=document.getElementById('increment').getBoundingClientRect();return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height/2)*devicePixelRatio]})()"))
        val location = IntArray(2)
        compose.runOnUiThread { first.getLocationOnScreen(location) }
        val x = location[0] + geometry.getDouble(0).toFloat()
        val y = location[1] + geometry.getDouble(1).toFloat()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to time, MotionEvent.ACTION_UP to time + 80)) {
            val event = MotionEvent.obtain(time, at, action, x, y, 0).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        compose.waitUntil(5000) { js("localStorage.count") == "\"1\"" }
        assertEquals("true", js("document.cookie.includes('violet_fixture=ok')"))
        val count = requests.get()
        runBlocking { model.preferencesRepository.update { it.copy(glass = it.glass.copy(corners = 33)) } }
        compose.waitForIdle()
        assertSame(first, web()); assertEquals(count, requests.get())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
        assertFalse(first.released)
        compose.onNodeWithTag("desktopPager").assertIsDisplayed()
        openHa(); loaded()
        assertSame(first, web()); assertEquals(count, requests.get())
        assertEquals("\"1\"", js("localStorage.count"))
    }
    @Test fun errorCanBeRetriedAndChangedAddressRecoversWithoutRestartingLauncher() {
        runBlocking { model.preferencesRepository.update { it.copy(ha = it.ha.copy(dashboardPath = "/missing")) } }
        compose.waitUntil(10_000) { model.preferences.value.ha.dashboardPath == "/missing" }
        openHa()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("haError").assertIsDisplayed(); true }.getOrDefault(false) }
        compose.onNodeWithTag("haRetry").assertDoesNotExist()
        runBlocking { model.preferencesRepository.update { it.copy(ha = it.ha.copy(dashboardPath = "/dashboard/main")) } }
        loaded()
        compose.onNodeWithTag("haError").assertDoesNotExist()
    }
    @Test fun refreshRecreatesOnlyOnRequestAndConfigurationRestoresSelectedScreen() {
        openHa(); loaded()
        val count = requests.get()
        compose.runOnIdle { model.refreshHa() }
        compose.waitUntil(15_000) { requests.get() > count }
        loaded()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("haScreen").assertIsDisplayed(); loaded()
        assertEquals("false", js("Boolean(window.externalApp || window.externalAppV2)"))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
        compose.waitUntil(10000) { runCatching { compose.onNodeWithTag("desktopPager").assertIsDisplayed(); true }.getOrDefault(false) }
    }
    @Test fun untrustedCertificateIsRejectedBeforeDashboardLoads() {
        openHa()
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithText("Certyfikat HTTPS jest nieprawidłowy. Połączenie zostało zatrzymane. Popraw certyfikat lub adres serwera.").assertIsDisplayed(); true }.getOrDefault(false) }
        assertEquals(0, requests.get())
    }
    @Test fun rotationKeepsActivityWebViewAndTransientStateWithoutReloadingDashboard() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("sensorLandscape") == "true")
        openHa(); loaded()
        val activity = compose.activity
        val current = web()!!
        js("window.violetTransient=71")
        val count = requests.get()
        runBlocking { model.preferencesRepository.update { it.copy(autoRotate = true) } }
        compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.waitForIdle()
        assertSame(activity, compose.activity)
        assertSame(current, web())
        assertEquals("71", js("window.violetTransient"))
        assertEquals(count, requests.get())
        assertTrue(current.width > current.height)
        runBlocking { model.preferencesRepository.update { it.copy(autoRotate = false) } }
        compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        assertSame(activity, compose.activity)
        assertSame(current, web())
        assertEquals("71", js("window.violetTransient"))
        assertEquals(count, requests.get())
    }

    @Test fun backgroundingPausesPageAndKeepsTheDocumentAndSession() {
        openHa(); loaded()
        val current = web()!!
        js("window.violetTransient=51")
        val count = requests.get()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        var url: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { assertFalse(current.suspended); url = current.url }
        assertTrue(url?.endsWith("/dashboard/main") == true)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        loaded()
        assertSame(current, web()); assertEquals(count, requests.get())
        assertEquals("51", js("window.violetTransient"))
        assertEquals("true", js("document.cookie.includes('violet_fixture=ok')"))
    }
    @Test fun androidSeventeenRequiresExplicitLanConsentBeforeConnecting() {
        Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 37)
        runBlocking { model.preferencesRepository.update { it.copy(ha = it.ha.copy(localUrl = "http://192.168.1.2:8123")) } }
        compose.waitUntil(10_000) { model.preferences.value.ha.localUrl == "http://192.168.1.2:8123" }
        openHa()
        compose.onNodeWithTag("haLanPermission").assertDoesNotExist()
        assertNull(web())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle()
        compose.onNodeWithTag("desktopPager").assertIsDisplayed()
        compose.openSettings()
        compose.onNodeWithTag("section:HA").performScrollTo().performClick()
        compose.onNodeWithTag("haLanPermission").performScrollTo().assertIsDisplayed()
    }
}
