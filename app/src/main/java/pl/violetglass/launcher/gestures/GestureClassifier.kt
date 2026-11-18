package pl.violetglass.launcher.gestures

import pl.violetglass.launcher.settings.DesktopGesture
import kotlin.math.abs

fun classifySwipe(x: Float, y: Float, pointers: Int, threshold: Float): DesktopGesture? {
    if (abs(y) < threshold || abs(y) < abs(x) * 1.5f) return null
    return if (pointers >= 2) { if (y < 0) DesktopGesture.TWO_FINGER_UP else null }
    else if (y < 0) DesktopGesture.SWIPE_UP else DesktopGesture.SWIPE_DOWN
}
