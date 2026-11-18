package pl.violetglass.launcher.workspace

import androidx.room.Entity
import androidx.room.PrimaryKey

const val DOCK = "dock"
fun pageContainer(page: Int) = "page:$page"
fun folderContainer(id: String) = "folder:$id"

@Entity(tableName = "layout_config")
data class LayoutConfig(
    @PrimaryKey val id: Int = 0,
    val columns: Int = 4,
    val rows: Int = 5,
    val pages: Int = 1,
    val dockSlots: Int = 5,
) {
    val capacity: Int get() = columns * rows
}

@Entity(tableName = "layout_items", indices = [androidx.room.Index("layoutId")])
data class LayoutItem(
    @PrimaryKey val id: String,
    val layoutId: Int = 0,
    val title: String,
    val appKey: String? = null,
    val container: String,
    val position: Int,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val widgetId: Int? = null,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val provider: String? = null,
    @androidx.room.ColumnInfo(defaultValue = "1") val spanX: Int = 1,
    @androidx.room.ColumnInfo(defaultValue = "1") val spanY: Int = 1,
) {
    val isFolder: Boolean get() = appKey == null && !isWidget
    val isWidget: Boolean get() = widgetId != null || provider != null
}

data class LayoutState(val config: LayoutConfig = LayoutConfig(), val items: List<LayoutItem> = emptyList()) {
    // One index per immutable snapshot, instead of rescanning every item for every cell/frame.
    private val cells by lazy {
        buildMap<Slot, LayoutItem> {
            items.forEach { item -> occupiedCells(item, config).forEach { position ->
                val slot = Slot(item.container, position)
                if (slot !in this) put(slot, item)
            } }
        }
    }
    private val contents by lazy { items.groupBy { it.container }.mapValues { (_, children) -> children.sortedBy { it.position } } }
    fun at(container: String, position: Int) = cells[Slot(container, position)]
    fun children(id: String) = contents[folderContainer(id)].orEmpty()
}

fun occupiedCells(item: LayoutItem, config: LayoutConfig): Set<Int> {
    if (!item.isWidget) return setOf(item.position)
    val row = item.position / config.columns
    val col = item.position % config.columns
    return (row until row + item.spanY).flatMap { y ->
        (col until col + item.spanX).map { x -> y * config.columns + x }
    }.toSet()
}

data class ItemPayload(val itemId: String? = null, val appKey: String? = null, val title: String)
data class Slot(val container: String, val position: Int)
class LayoutRejected(val reason: Reason) : IllegalArgumentException(reason.name) {
    enum class Reason { FULL, INVALID, PAGE_NOT_EMPTY, LIMIT, LOCKED }
}


