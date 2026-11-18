package pl.violetglass.launcher.discover

import android.app.Activity
import android.graphics.PixelFormat
import android.view.*

/** An app-owned touch strip on this Activity, with no system overlay permission. */
internal class DiscoverEdgeGesture(private val activity: Activity) {
    private var attached = false
    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private var navigate: (() -> Unit)? = null
    private var drag: ((Float, Boolean) -> Unit)? = null
    private var horizontal = false
    private var vertical = false
    private var touching = false
    private var raisePending = false
    private val density = activity.resources.displayMetrics.density
    private val touchSlop = maxOf(ViewConfiguration.get(activity).scaledTouchSlop.toFloat(), 12 * density)
    private val view = object : View(activity) {
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { touching = true; downX = event.rawX; downY = event.rawY; fired = false; horizontal = false; vertical = false }
                android.view.MotionEvent.ACTION_MOVE -> if(!fired && !vertical) {
                    val dx = event.rawX - downX
                    val dy = kotlin.math.abs(event.rawY - downY)
                    if(!horizontal && dy > touchSlop && dy > kotlin.math.abs(dx) * 1.35f) vertical = true
                    if(horizontal || (!vertical && dx > touchSlop && dx > dy * 1.35f)) {
                        if(drag == null) { fired = true; navigate?.invoke() }
                        else { horizontal = true; drag?.invoke(fraction(event), false) }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> { touching = false; val wasHorizontal = horizontal; horizontal = false; if(wasHorizontal) drag?.invoke(fraction(event), true); performClick(); if(raisePending) post { raise() } }
                android.view.MotionEvent.ACTION_CANCEL -> { touching = false; val wasHorizontal = horizontal; horizontal = false; if(wasHorizontal) drag?.invoke(0f, true); if(raisePending) post { raise() } }
            }
            return true
        }
        private fun fraction(event: MotionEvent) = ((event.rawX - downX) / activity.window.decorView.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        override fun performClick(): Boolean { super.performClick(); return true }
        override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "Home Assistant"))
        }
        override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
            if(action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { navigate?.invoke(); return true }
            return super.performAccessibilityAction(action, arguments)
        }
    }.apply { contentDescription = "Home Assistant" }
    fun show(onDrag: ((Float, Boolean) -> Unit)? = null, callback: () -> Unit) {
        navigate = callback
        drag = onDrag
        if(attached || !activity.window.decorView.isAttachedToWindow) return
        val params = WindowManager.LayoutParams(
            // This separate provider window cannot delegate a touch stream to
            // Google's views. Reserve a bounded navigation band, leaving the
            // rest of the native feed available for its own taps and scrolling.
            (96 * density).toInt(), WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            token = activity.window.attributes.token
            gravity = Gravity.TOP or Gravity.LEFT
            x = (20 * density).toInt()
            title = "Violet Discover edge"
        }
        runCatching { activity.windowManager.addView(view, params); attached = true }
    }
    fun hide() {
        if(attached) runCatching { activity.windowManager.removeViewImmediate(view) }
        attached = false; navigate = null; drag = null; raisePending = false; touching = false; horizontal = false
    }
    fun raise() {
        if(!attached) return
        if(touching) { raisePending = true; return }
        val callback = navigate ?: return
        val onDrag = drag
        hide(); show(onDrag, callback)
    }
}
