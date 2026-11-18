package pl.violetglass.launcher.workspace

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LayoutMigrationTest {
    @Test fun versionOneLayoutAndFoldersSurviveAddingWidgetColumns() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "migration-${UUID.randomUUID()}.db"
        // Recreate the shipped v1 schema, including its original Room identity hash.
        val schema = instrumentation.context.assets.open("${LayoutDatabase::class.java.name}/1.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                val table = entity.getString("tableName")
                fun sql(value: String) = value.replace("${'$'}{TABLE_NAME}", table)
                db.execSQL(sql(entity.getString("createSql")))
                val indices = entity.optJSONArray("indices")
                for (i in 0 until (indices?.length() ?: 0)) db.execSQL(sql(indices!!.getJSONObject(i).getString("createSql")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO layout_config(id,columns,rows,pages,dockSlots) VALUES(0,4,5,2,5)")
            db.execSQL("INSERT INTO layout_items(id,layoutId,title,appKey,container,position) VALUES('folder',0,'Dom',NULL,'page:1',0)")
            db.execSQL("INSERT INTO layout_items(id,layoutId,title,appKey,container,position) VALUES('child',0,'Światło','0:pkg/Activity','folder:folder',0)")
            db.execSQL("INSERT INTO layout_items(id,layoutId,title,appKey,container,position) VALUES('dock',0,'Telefon','0:pkg/Phone','dock',2)")
            db.version = 1
        }
        val upgraded = Room.databaseBuilder(context, LayoutDatabase::class.java, name).addMigrations(LayoutDatabase.MIGRATION_1_2).build()
        try {
            // Opening through Room runs the real migration and built-in schema validation.
            val items = upgraded.layout().items()
            assertEquals(3, items.size)
            val child = items.single { it.id == "child" }
            assertEquals("Światło", child.title); assertEquals("folder:folder", child.container)
            assertEquals(0, child.position); assertNull(child.widgetId); assertNull(child.provider)
            assertEquals(1, child.spanX); assertEquals(1, child.spanY)
            assertTrue(items.single { it.id == "folder" }.isFolder)
            assertEquals(2, items.single { it.id == "dock" }.position)
            assertEquals(2, upgraded.layout().config()!!.pages)
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}

