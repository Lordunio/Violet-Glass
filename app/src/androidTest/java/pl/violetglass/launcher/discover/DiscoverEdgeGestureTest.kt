package pl.violetglass.launcher.discover

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import pl.violetglass.launcher.MainActivity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class DiscoverEdgeGestureTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private var edge: DiscoverEdgeGesture? = null
    @After fun release() { activity.scenario.onActivity { edge?.hide() } }
    @Test fun continuousEdgeGestureReportsIntermediateProgressThenRelease() {
        val values = java.util.Collections.synchronizedList(mutableListOf<Pair<Float, Boolean>>())
        var density = 1f
        activity.scenario.onActivity {
            it.setContentView(FrameLayout(it)); density = it.resources.displayMetrics.density
            edge = DiscoverEdgeGesture(it)
        }
        // Let disposal of the production Compose host remove its provider window
        // before adding the isolated test window above the replacement content.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        activity.scenario.onActivity { edge!!.show(onDrag = { value, released -> values.add(value to released) }) {} }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val start = SystemClock.uptimeMillis()
        // 104 dp is well outside the old band, which ended at 44 dp.
        for(pair in listOf(MotionEvent.ACTION_DOWN to 104f, MotionEvent.ACTION_MOVE to 145f,
            MotionEvent.ACTION_MOVE to 200f, MotionEvent.ACTION_UP to 270f)) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), pair.first, pair.second * density, 240f * density, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(60)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        org.junit.Assert.assertTrue("Expected intermediate progress and release, got $values", values.size >= 2)
        org.junit.Assert.assertTrue(values.dropLast(1).all { !it.second && it.first > 0f && it.first < 1f })
        org.junit.Assert.assertTrue(values.last().second)
        org.junit.Assert.assertTrue(values.last().first > values.first().first)
    }
    @Test fun feedOutsideNavigationBandReceivesTapsAndVerticalSwipes() {
        val releases = AtomicInteger()
        val moves = AtomicInteger()
        val navigations = AtomicInteger()
        var density = 1f
        activity.scenario.onActivity {
            density = it.resources.displayMetrics.density
            it.setContentView(object : android.view.View(it) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if(event.actionMasked == MotionEvent.ACTION_UP) releases.incrementAndGet()
                    if(event.actionMasked == MotionEvent.ACTION_MOVE) moves.incrementAndGet()
                    return true
                }
            })
            edge = DiscoverEdgeGesture(it)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        activity.scenario.onActivity { edge!!.show { navigations.incrementAndGet() } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun send(action: Int, x: Float, y: Float, start: Long) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x * density, y * density, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            SystemClock.sleep(40)
        }
        fun tap(x: Float, y: Float) {
            val start = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, x, y, start)
            send(MotionEvent.ACTION_UP, x, y, start)
        }
        tap(55f, 78f) // Left side of the Google heading, inside the strip's X range.
        tap(160f, 78f)
        tap(160f, 240f)
        val start = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, 160f, 240f, start)
        send(MotionEvent.ACTION_MOVE, 160f, 320f, start)
        send(MotionEvent.ACTION_UP, 160f, 380f, start)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(4, releases.get())
        org.junit.Assert.assertTrue(moves.get() > 0)
        assertEquals(0, navigations.get())
    }
    @Test fun edgeAcceptsOneRightSwipeAndIgnoresVerticalMovement() {
        val count = AtomicInteger()
        var density = 1f
        activity.scenario.onActivity {
            it.setContentView(FrameLayout(it))
            density = it.resources.displayMetrics.density
            edge = DiscoverEdgeGesture(it)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        activity.scenario.onActivity { edge!!.show { count.incrementAndGet() } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(300)
        fun swipe(dx: Float, dy: Float) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val start = SystemClock.uptimeMillis()
            for((action, elapsed, x, y) in listOf(
                listOf(MotionEvent.ACTION_DOWN, 0, 32f * density, 240f * density),
                listOf(MotionEvent.ACTION_MOVE, 150, (32f + dx) * density, (240f + dy) * density),
                listOf(MotionEvent.ACTION_UP, 250, (32f + dx) * density, (240f + dy) * density),
            )) {
                val event = MotionEvent.obtain(start, start + elapsed.toLong(), action.toInt(), x.toFloat(), y.toFloat(), 0)
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        swipe(110f, 0f)
        assertEquals(1, count.get())
        swipe(1f, 110f)
        assertEquals(1, count.get())
    }
}
