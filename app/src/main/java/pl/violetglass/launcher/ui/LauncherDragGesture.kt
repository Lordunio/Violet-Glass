package pl.violetglass.launcher.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.CancellationException

/** Initial-pass consumption gives a recognized icon drag priority over nested scrolling. */
suspend fun PointerInputScope.detectLauncherDrag(
    acceptsConsumed: (Offset) -> Boolean = { false },
    onStart: (Offset) -> Boolean,
    onDrag: (Offset) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var lastPosition = down.position
            val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var stop = false
                while (!stop) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    stop = change == null || !change.pressed || (change.isConsumed && !acceptsConsumed(down.position)) ||
                        event.changes.count { it.pressed } > 1 ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                    if (change != null) lastPosition = change.position
                }
                true
            }
            if (interrupted == null && onStart(down.position)) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || event.changes.count { it.pressed } > 1) { onCancel(); break }
                    // Consume before the child pager/list sees the event, including the release.
                    change.consume()
                    if (!change.pressed) { onEnd(); break }
                    onDrag(change.position - lastPosition)
                    lastPosition = change.position
                }
            }
        }
    } catch (cancel: CancellationException) { onCancel(); throw cancel }
}
