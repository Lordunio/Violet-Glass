package pl.violetglass.launcher.workspace

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LayoutDatabaseTest {
    @Test fun noOpWritesNothingAndRenameTouchesOnlyChangedRow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LayoutDatabase::class.java).build()
        try {
            val repository = LayoutRepository(db)
            repository.initialize()
            repository.edit { state -> (0..3).fold(state) { current, i -> LayoutEngine.place(current, ItemPayload(appKey = "a$i", title = "A$i"), Slot(pageContainer(0), i)) } }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE audit (item_id TEXT)")
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                val row = if (operation == "DELETE") "OLD" else "NEW"
                sql.execSQL("CREATE TRIGGER audit_$operation AFTER $operation ON layout_items BEGIN INSERT INTO audit VALUES ($row.id); END")
            }
            repository.edit { it }
            sql.query("SELECT count(*) FROM audit").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            val original = repository.state.first { it != null }!!
            val id = original.items.first().id
            repository.edit { LayoutEngine.rename(it, id, "Renamed") }
            sql.query("SELECT item_id FROM audit").use { assertTrue(it.moveToFirst()); assertEquals(id, it.getString(0)); assertFalse(it.moveToNext()) }
            assertEquals(4, repository.state.first { it != null }!!.items.size)
        } finally { db.close() }
    }

    @Test fun largeDeletionIsChunkedWithoutLosingRemainingItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LayoutDatabase::class.java).build()
        try {
            val repository = LayoutRepository(db)
            repository.initialize()
            repository.edit { LayoutState(LayoutConfig(columns = 8, rows = 8, pages = 10), (0 until 600).map { i ->
                LayoutItem("item$i", title = "A$i", appKey = "a$i", container = pageContainer(i / 64), position = i % 64)
            }) }
            repository.edit { it.copy(items = it.items.filter { item -> item.id in setOf("item0", "item599") }) }
            assertEquals(setOf("item0", "item599"), repository.state.first { it != null }!!.items.map { it.id }.toSet())
        } finally { db.close() }
    }
    @Test fun layoutSurvivesClosingAndReopeningDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-layout-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, LayoutDatabase::class.java, name).build()
        try {
            val repository = LayoutRepository(db)
            repository.initialize()
            repository.edit { state ->
                val a = LayoutEngine.place(state, ItemPayload(appKey = "app-a", title = "A"), Slot(pageContainer(0), 0))
                val b = LayoutEngine.place(a, ItemPayload(appKey = "app-b", title = "B"), Slot(pageContainer(0), 0))
                val folder = b.items.single { it.isFolder }
                LayoutEngine.rename(LayoutEngine.addPage(b), folder.id, "Dom")
            }
            val before = repository.state.first { it != null }!!
            db.close()
            db = Room.databaseBuilder(context, LayoutDatabase::class.java, name).build()
            val after = LayoutRepository(db).state.first { it != null }!!
            assertEquals(before.config, after.config)
            assertEquals(before.items.toSet(), after.items.toSet())
            assertEquals("Dom", after.items.single { it.isFolder }.title)
            assertEquals(2, after.config.pages)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun concurrentEditsAndRejectedTransactionPreserveAllItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LayoutDatabase::class.java).build()
        try {
            val repository = LayoutRepository(db)
            repository.initialize()
            coroutineScope {
                (0 until 20).map { index -> async(Dispatchers.Default) {
                    repository.edit { LayoutEngine.place(it, ItemPayload(appKey = "app-$index", title = "A$index"), LayoutEngine.firstFree(it, pageContainer(0))) }
                } }.awaitAll()
            }
            val before = repository.state.first { it?.items?.size == 20 }!!
            try {
                repository.edit { LayoutEngine.place(it, ItemPayload(appKey = "extra", title = "Extra"), LayoutEngine.firstFree(it, pageContainer(0))) }
                fail("Full page must reject the edit")
            } catch (expected: LayoutRejected) { assertEquals(LayoutRejected.Reason.FULL, expected.reason) }
            val after = repository.state.first { it != null }!!
            assertEquals(before.items.toSet(), after.items.toSet())
            assertEquals(20, after.items.map { it.position }.distinct().size)
        } finally { db.close() }
    }
}
