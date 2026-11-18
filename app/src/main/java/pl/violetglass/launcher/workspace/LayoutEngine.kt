package pl.violetglass.launcher.workspace

import java.util.UUID

/** Pure, validated layout transformations; the repository commits each in one Room transaction. */
object LayoutEngine {
    fun compactFolders(state: LayoutState): LayoutState = compactFolders(state, state.items.mapTo(HashSet()) { it.container })
    private fun compactFolders(state: LayoutState, containers: Set<String>): LayoutState {
        val positions = containers.filter { it.startsWith("folder:") }.flatMap { container ->
            state.items.filter { it.container == container }.sortedBy { it.position }
                .mapIndexed { index, item -> item.id to index }
        }.toMap()
        return state.copy(items = state.items.map { item -> positions[item.id]?.let { item.copy(position = it) } ?: item })
    }
    private fun reject(reason: LayoutRejected.Reason = LayoutRejected.Reason.INVALID): Nothing = throw LayoutRejected(reason)
    private fun capacity(state: LayoutState, container: String): Int = when {
        container == DOCK -> state.config.dockSlots
        container.startsWith("page:") -> {
            val page = container.removePrefix("page:").toIntOrNull() ?: reject()
            if (page !in 0 until state.config.pages) reject()
            state.config.capacity
        }
        container.startsWith("folder:") -> {
            val folder = state.items.find { it.id == container.removePrefix("folder:") } ?: reject()
            if (!folder.isFolder || folder.container.startsWith("folder:")) reject()
            64
        }
        else -> reject()
    }

    fun fits(state: LayoutState, item: LayoutItem): Boolean {
        if (!item.container.startsWith("page:") || item.spanX !in 1..state.config.columns || item.spanY !in 1..state.config.rows) return false
        val col = item.position % state.config.columns
        val row = item.position / state.config.columns
        if (item.position < 0 || col + item.spanX > state.config.columns || row + item.spanY > state.config.rows) return false
        val cells = occupiedCells(item, state.config)
        return state.items.none { it.id != item.id && it.container == item.container && occupiedCells(it, state.config).any(cells::contains) }
    }

    fun firstFree(state: LayoutState, container: String): Slot {
        val position = (0 until capacity(state, container)).firstOrNull { state.at(container, it) == null }
            ?: reject(LayoutRejected.Reason.FULL)
        return Slot(container, position)
    }

    private fun firstRectangle(state: LayoutState, item: LayoutItem, container: String): LayoutItem {
        capacity(state, container)
        return (0 until state.config.capacity).asSequence().map { item.copy(container = container, position = it) }
            .firstOrNull { fits(state, it) } ?: reject(LayoutRejected.Reason.FULL)
    }

    fun place(state: LayoutState, payload: ItemPayload, target: Slot, merge: Boolean = true): LayoutState {
        if (target.position !in 0 until capacity(state, target.container)) reject()
        val incoming = if (payload.itemId != null) state.items.find { it.id == payload.itemId } ?: reject()
        else {
            if (payload.appKey.isNullOrBlank()) reject()
            LayoutItem(UUID.randomUUID().toString(), title = payload.title.take(48), appKey = payload.appKey,
                container = target.container, position = target.position)
        }
        if (incoming.isWidget) {
            val moved = incoming.copy(container = target.container, position = target.position)
            if (!target.container.startsWith("page:") || !fits(state, moved)) reject(LayoutRejected.Reason.FULL)
            return state.copy(items = state.items.map { if (it.id == moved.id) moved else it }).also(::validate)
        }
        if (incoming.isFolder && target.container.startsWith("folder:")) reject()
        val occupant = state.at(target.container, target.position)
        if (occupant?.id == incoming.id) return state
        if (occupant?.isWidget == true) reject(LayoutRejected.Reason.FULL)
        val result = state.items.toMutableList()
        result.removeAll { it.id == incoming.id }
        if (merge && !incoming.isFolder && occupant != null && !target.container.startsWith("folder:")) {
            if (occupant.isFolder) {
                if (incoming.container == folderContainer(occupant.id)) return state
                val free = firstFree(state, folderContainer(occupant.id))
                result.add(incoming.copy(container = free.container, position = free.position))
            } else {
                val folderId = UUID.randomUUID().toString()
                result.removeAll { it.id == occupant.id }
                result.add(LayoutItem(folderId, title = "Folder", container = target.container, position = target.position))
                result.add(occupant.copy(container = folderContainer(folderId), position = 0))
                result.add(incoming.copy(container = folderContainer(folderId), position = 1))
            }
        } else {
            if (occupant != null) {
                if (payload.itemId == null || (occupant.isFolder && incoming.container.startsWith("folder:"))) reject(LayoutRejected.Reason.FULL)
                result.removeAll { it.id == occupant.id }
                result.add(occupant.copy(container = incoming.container, position = incoming.position))
            }
            result.add(incoming.copy(container = target.container, position = target.position))
        }
        val destination = result.first { it.id == incoming.id }.container
        return compactFolders(state.copy(items = result), setOf(incoming.container, destination)).also(::validate)
    }

    fun addWidget(state: LayoutState, widgetId: Int, provider: String, title: String, spanX: Int, spanY: Int, page: Int, replaceId: String? = null): LayoutState {
        if (widgetId <= 0 || provider.isBlank()) reject()
        val old = replaceId?.let { id -> state.items.find { it.id == id && it.isWidget } ?: reject() }
        val item = old?.copy(widgetId = widgetId, provider = provider, title = title)
            ?: LayoutItem(UUID.randomUUID().toString(), title = title.ifBlank { "Widget" }, container = pageContainer(page),
                position = 0, widgetId = widgetId, provider = provider, spanX = spanX, spanY = spanY)
        val base = state.copy(items = state.items.filterNot { it.id == old?.id })
        val placed = if (old != null) item.also { if (!fits(base, it)) reject(LayoutRejected.Reason.FULL) }
            else firstRectangle(base, item, pageContainer(page))
        return base.copy(items = base.items + placed).also(::validate)
    }

    fun resizeWidget(state: LayoutState, id: String, spanX: Int, spanY: Int): LayoutState {
        val widget = state.items.find { it.id == id && it.isWidget } ?: reject()
        val next = widget.copy(spanX = spanX, spanY = spanY)
        if (!fits(state, next)) reject(LayoutRejected.Reason.FULL)
        return state.copy(items = state.items.map { if (it.id == id) next else it }).also(::validate)
    }

    fun addFolder(state: LayoutState, container: String, title: String): LayoutState {
        if (container.startsWith("folder:")) reject()
        val slot = firstFree(state, container)
        return state.copy(items = state.items + LayoutItem(UUID.randomUUID().toString(), title = title.trim().ifEmpty { "Folder" }.take(48), container = container, position = slot.position)).also(::validate)
    }

    fun rename(state: LayoutState, id: String, title: String): LayoutState {
        val name = title.trim().take(48)
        if (name.isBlank() || state.items.none { it.id == id }) reject()
        return state.copy(items = state.items.map { if (it.id == id) it.copy(title = name) else it })
    }

    fun remove(state: LayoutState, id: String): LayoutState = compactFolders(state.copy(
        items = state.items.filterNot { it.id == id || it.container == folderContainer(id) },
    ), setOfNotNull(state.items.find { it.id == id }?.container)).also(::validate)

    fun removePackage(state: LayoutState, packageName: String): LayoutState {
        val removed = state.items.filter { it.appKey?.substringAfter(':')?.substringBefore('/') == packageName }
        val ids = removed.mapTo(HashSet()) { it.id }
        return compactFolders(state.copy(items = state.items.filterNot { it.id in ids }), removed.mapTo(HashSet()) { it.container }).also(::validate)
    }

    fun addPage(state: LayoutState): LayoutState {
        if (state.config.pages >= 10) reject(LayoutRejected.Reason.LIMIT)
        return state.copy(config = state.config.copy(pages = state.config.pages + 1))
    }

    fun removeLastPage(state: LayoutState): LayoutState {
        if (state.config.pages <= 1) reject()
        if (state.items.any { it.container == pageContainer(state.config.pages - 1) }) reject(LayoutRejected.Reason.PAGE_NOT_EMPTY)
        return state.copy(config = state.config.copy(pages = state.config.pages - 1))
    }

    fun resize(state: LayoutState, columns: Int, rows: Int, dockSlots: Int): LayoutState {
        if (columns !in 3..8 || rows !in 3..8 || dockSlots !in 3..7) reject()
        val desktop = state.items.filter { it.container.startsWith("page:") }
            .sortedWith(compareBy({ it.container.removePrefix("page:").toInt() }, { it.position }))
        val overflow = state.items.filter { it.container == DOCK && it.position >= dockSlots }.sortedBy { it.position }
        val config = state.config.copy(columns = columns, rows = rows, dockSlots = dockSlots)
        var next = LayoutState(config, state.items.filterNot { it in desktop || it in overflow })
        val deferred = mutableListOf<LayoutItem>()
        for (original in desktop) {
            val item = original.copy(position = original.position / state.config.columns * columns + original.position % state.config.columns)
            if (item.spanX > columns || item.spanY > rows) reject(LayoutRejected.Reason.FULL)
            val col = original.position % state.config.columns
            val row = original.position / state.config.columns
            val fitsGrid = col + item.spanX <= columns && row + item.spanY <= rows
            if (fitsGrid && (if(item.isWidget) fits(next, item) else next.at(item.container, item.position) == null))
                next = next.copy(items = next.items + item)
            else deferred += original
        }
        for (item in deferred + overflow) {
            var placed: LayoutItem? = null
            val firstPage = if(item.container.startsWith("page:")) item.container.removePrefix("page:").toInt() else 0
            while (placed == null) {
                for (page in firstPage until next.config.pages) {
                    placed = if (item.isWidget) runCatching { firstRectangle(next, item, pageContainer(page)) }.getOrNull()
                    else runCatching { firstFree(next, pageContainer(page)) }.getOrNull()?.let { item.copy(container = it.container, position = it.position) }
                    if (placed != null) break
                }
                if (placed == null) {
                    if (next.config.pages >= 10) reject(LayoutRejected.Reason.FULL)
                    next = addPage(next)
                }
            }
            next = next.copy(items = next.items + placed)
        }
        return next.also(::validate)
    }

    fun validate(state: LayoutState) {
        val config = state.config
        if (config.id != 0 || config.columns !in 3..8 || config.rows !in 3..8 || config.pages !in 1..10 || config.dockSlots !in 3..7) reject()
        if (state.items.map { it.id }.distinct().size != state.items.size) reject()
        val widgetIds = state.items.mapNotNull { it.widgetId }
        if (widgetIds.distinct().size != widgetIds.size) reject()
        val occupied = mutableSetOf<Pair<String, Int>>()
        state.items.forEach { item ->
            if (item.position !in 0 until capacity(state, item.container) || item.title.isBlank() || item.layoutId != 0) reject()
            if (item.isFolder && item.container.startsWith("folder:")) reject()
            if (item.isWidget) {
                if (item.widgetId != null && item.widgetId <= 0 || item.provider.isNullOrBlank() || item.appKey != null || !fits(state, item)) reject()
            } else if (item.spanX != 1 || item.spanY != 1) reject()
            for (cell in occupiedCells(item, config)) if (!occupied.add(item.container to cell)) reject()
        }
    }
}
