package pl.violetglass.launcher.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerId
import pl.violetglass.launcher.settings.DesktopGesture

/** Taps/holds belong to empty space; an early swipe may also start over an app/folder. */
suspend fun PointerInputScope.detectDesktopGestures(
    eligible: (Offset) -> Boolean, allowVertical: (Offset) -> Boolean, onGesture: (DesktopGesture) -> Unit,
    swipeEligible: (Offset) -> Boolean = eligible,
    onPinch: () -> Unit = {},
    onTap: (Offset) -> Unit = {},
    interceptTap: (Offset) -> Boolean = { false },
) {
    var previousTapTime = 0L
    var previousTap = Offset.Zero
    val threshold = 40 * density
    awaitEachGesture {
        val down = awaitFirstDown(false, PointerEventPass.Initial)
        val intercepted = interceptTap(down.position)
        val taps = eligible(down.position) || intercepted
        if(intercepted) down.consume()
        val swipes = swipeEligible(down.position)
        // Capture the origin before a scrollable child can move the hit bounds.
        val vertical = swipes && allowVertical(down.position)
        if (!taps && !swipes) return@awaitEachGesture
        var point = down.position
        var pointers = 1
        var released = false
        var moved = false
        var consumed = false
        var horizontal = false
        var pinchPair: List<PointerId>? = null
        var pinchDistance = 0f
        var pinchCenter = Offset.Zero
        var pinchOwned = false
        var pinched = false
        var pinchCancelled = false
        fun trackPinch(event: PointerEvent) {
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size > 2) pinchCancelled = true
            if (pinchCancelled) return
            if (pinchPair == null && pressed.size == 2 && !consumed && !horizontal &&
                pressed.all { swipeEligible(it.position) }) {
                pinchPair = pressed.map { it.id }
                pinchDistance = (pressed[0].position - pressed[1].position).getDistance()
                pinchCenter = (pressed[0].position + pressed[1].position) / 2f
            }
            val pair = pinchPair?.mapNotNull { id -> event.changes.find { it.id == id } } ?: return
            if (pair.size != 2) return
            val contraction = pinchDistance - (pair[0].position - pair[1].position).getDistance()
            val translation = ((pair[0].position + pair[1].position) / 2f - pinchCenter).getDistance()
            // A translating pair remains the two-finger swipe. Claim a contracting
            // pair before the child pager/grid can interpret it as a one-finger drag.
            if (contraction > viewConfiguration.touchSlop && contraction > translation * 1.5f) pinchOwned = true
            if (pinchOwned) {
                event.changes.forEach { it.consume() }
                if (contraction > maxOf(24 * density, pinchDistance * .18f)) pinched = true
            }
        }
        val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (!released && !moved && pointers == 1) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                pointers = maxOf(pointers, event.changes.count { it.pressed })
                if (change != null) point = change.position
                released = event.changes.none { it.pressed }
                trackPinch(event)
                moved = (point - down.position).getDistance() > viewConfiguration.touchSlop
                val delta = point - down.position
                if (!pinchOwned && moved && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) horizontal = true
                if (!pinchOwned && vertical && !horizontal && classifySwipe(delta.x, delta.y, pointers, viewConfiguration.touchSlop) != null) consumed = true
                if (consumed) event.changes.forEach { it.consume() }
            }
            true
        }
        if (interrupted == null) {
            previousTapTime = 0L
            if(taps) onGesture(DesktopGesture.LONG_PRESS)
            return@awaitEachGesture
        }
        while (!released) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
            pointers = maxOf(pointers, event.changes.count { it.pressed })
            if (change != null) point = change.position
            released = event.changes.none { it.pressed }
            trackPinch(event)
            moved = moved || (point - down.position).getDistance() > viewConfiguration.touchSlop
            val delta = point - down.position
            if (!pinchOwned && !consumed && kotlin.math.abs(delta.x) > viewConfiguration.touchSlop && kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) horizontal = true
            if (!pinchOwned && vertical && !horizontal && classifySwipe(delta.x, delta.y, pointers, viewConfiguration.touchSlop) != null) consumed = true
            if (consumed) event.changes.forEach { it.consume() }
        }
        val delta = point - down.position
        val swipe = if (consumed && !pinchOwned) classifySwipe(delta.x, delta.y, pointers, threshold) else null
        if (pinched && !pinchCancelled) { previousTapTime = 0L; onPinch() }
        else if (swipe != null) { previousTapTime = 0L; onGesture(swipe) }
        else if (taps && !moved && pointers == 1) {
            if(intercepted) { previousTapTime = 0L; onTap(point) }
            else if (previousTapTime != 0L && down.uptimeMillis - previousTapTime in 0..viewConfiguration.doubleTapTimeoutMillis &&
                (point - previousTap).getDistance() < threshold) {
                previousTapTime = 0L
                onGesture(DesktopGesture.DOUBLE_TAP)
            } else { previousTapTime = down.uptimeMillis; previousTap = point; onTap(point) }
        }
    }
}
