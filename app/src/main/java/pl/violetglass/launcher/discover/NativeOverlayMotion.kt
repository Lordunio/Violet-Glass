package pl.violetglass.launcher.discover

/** Opening echoes stay with the pager; a close after the provider's open acknowledgement owns the pager. */
internal class NativeOverlayMotion {
    var previous = 0f
        private set
    var dragging = false
        private set
    fun cancel() { dragging = false }
    fun reset() { previous = 0f; dragging = false }
    fun update(value: Float, command: Float, blocked: Boolean): Float? {
        val before = previous
        previous = value
        if (blocked) { cancel(); return null }
        // No time gate: losing the first decreasing callback also lost every
        // later callback, leaving an empty Discover page after Google reached 0.
        if (!dragging && command == 1f && before >= .999f && value < before) dragging = true
        if (!dragging) return null
        if (value == 0f || value == 1f) dragging = false
        return value
    }
}
