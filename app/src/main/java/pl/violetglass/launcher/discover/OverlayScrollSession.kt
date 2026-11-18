package pl.violetglass.launcher.discover

/** A continuous pager motion is one provider gesture, not one gesture per frame. */
internal class OverlayScrollSession {
    private var last: Float? = null
    private var scrolling = false
    fun reset() { last = null; scrolling = false }
    fun commands(progress: Float): List<Int> {
        if(last == progress) return emptyList()
        val initial = last == null
        last = progress
        return buildList {
            if(initial && (progress == 0f || progress == 1f)) {
                add(1); add(2); add(3); add(if(progress == 1f) 9 else 6)
                return@buildList
            }
            if(progress > 0f && progress < 1f) {
                if(!scrolling) { add(1); scrolling = true }
                add(2)
            } else if(scrolling) {
                add(2); add(3); add(if(progress == 1f) 9 else 6); scrolling = false
            } else add(if(progress == 1f) 9 else 6)
        }
    }
}
