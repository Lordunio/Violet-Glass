package pl.violetglass.launcher.workspace

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map

class LayoutRepository(private val database: LayoutDatabase) {
    private val dao = database.layout()
    val state = dao.observe().map { rows -> rows.firstOrNull()?.let { LayoutState(it.config, it.items) } }

    suspend fun initialize() = database.withTransaction {
        val config = dao.config()
        if (config == null) dao.putConfig(LayoutConfig())
        else {
            val items = dao.items()
            val previous = items.associateBy { it.id }
            val compacted = LayoutEngine.compactFolders(LayoutState(config, items))
            val changed = compacted.items.filter { previous[it.id] != it }
            if (changed.isNotEmpty()) dao.upsertItems(changed)
        }
    }

    suspend fun edit(transform: (LayoutState) -> LayoutState) = database.withTransaction {
        val config = dao.config()
        val state = LayoutState(config ?: LayoutConfig(), dao.items())
        val next = transform(state)
        LayoutEngine.validate(next)
        if (config == null || next.config != config) dao.putConfig(next.config)
        val previous = state.items.associateBy { it.id }
        val ids = next.items.mapTo(HashSet()) { it.id }
        val removed = previous.keys.filter { it !in ids }
        // Stay below SQLite's bind-parameter limit on Android 12 as well.
        removed.chunked(500).forEach { dao.deleteItems(it) }
        val changed = next.items.filter { previous[it.id] != it }
        if (changed.isNotEmpty()) dao.upsertItems(changed)
    }
}
