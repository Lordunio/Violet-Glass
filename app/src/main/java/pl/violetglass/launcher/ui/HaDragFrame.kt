package pl.violetglass.launcher.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/** Screen coordinates stay stable while WindowManager moves this window under the finger. */
internal class HaDragFrame(context: Context, private val position: () -> Float,
    private val drag: (Float, Boolean) -> Unit, private val touchFinished: () -> Unit) : FrameLayout(context) {
    var touching = false
        private set
    private var downX = 0f
    private var downY = 0f
    private var start = 0f
    private var dragging = false
    private var eligible = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        val result = super.dispatchTouchEvent(event)
        if(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false; touchFinished()
        }
        return result
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX; downY = event.rawY; start = position(); dragging = false
                eligible = event.x >= width * .5f || event.x <= 48 * resources.displayMetrics.density
            }
            MotionEvent.ACTION_POINTER_DOWN -> eligible = false
            MotionEvent.ACTION_MOVE -> {
                if(!dragging && abs(event.rawY - downY) > slop && abs(event.rawY - downY) > abs(event.rawX - downX) * 1.35f) eligible = false
                if(!dragging && eligible && event.pointerCount == 1 && abs(event.rawX - downX) > slop &&
                    abs(event.rawX - downX) > abs(event.rawY - downY) * 1.35f) {
                    dragging = true
                    // The intercepted MOVE first cancels the child's stream;
                    // ViewGroup may deliver our first onTouchEvent on the next MOVE.
                    drag((start - (event.rawX - downX) / width.coerceAtLeast(1)).coerceIn(0f, 1f), false)
                }
            }
        }
        return dragging
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!dragging) return super.onTouchEvent(event)
        val current = (start - (event.rawX - downX) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> drag(current, false)
            MotionEvent.ACTION_UP -> { dragging = false; drag(current, true) }
            MotionEvent.ACTION_CANCEL -> { dragging = false; drag(start, true) }
        }
        return true
    }
}
