package pl.violetglass.launcher.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

internal suspend fun PointerInputScope.detectDrawerReturn(atTop: () -> Boolean, canDismiss: () -> Boolean,
    onDismiss: () -> Unit) {
    val threshold = maxOf(64f * density, viewConfiguration.touchSlop * 2)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if(!atTop() || !canDismiss()) return@awaitEachGesture
        var claimed = false
        while(true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if(event.changes.any { it.id != down.id && it.pressed } || !canDismiss()) break
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val delta = change.position - down.position
            if(!claimed && delta.getDistance() > viewConfiguration.touchSlop) {
                if(change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis ||
                    delta.y <= abs(delta.x) * 1.25f) break
                claimed = true
            }
            if(claimed) change.consume()
            if(!change.pressed) {
                if(claimed && delta.y >= threshold && delta.y > abs(delta.x) * 1.25f) onDismiss()
                break
            }
        }
    }
}
