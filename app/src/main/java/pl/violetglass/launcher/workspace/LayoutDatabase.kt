package pl.violetglass.launcher.workspace

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class ConfigWithItems(
    @Embedded val config: LayoutConfig,
    @Relation(parentColumn = "id", entityColumn = "layoutId") val items: List<LayoutItem>,
)

@Dao
interface LayoutDao {
    @Transaction @Query("SELECT * FROM layout_config WHERE id = 0")
    fun observe(): Flow<List<ConfigWithItems>>
    @Query("SELECT * FROM layout_config WHERE id = 0") suspend fun config(): LayoutConfig?
    @Query("SELECT * FROM layout_items") suspend fun items(): List<LayoutItem>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putConfig(config: LayoutConfig)
    @Insert suspend fun insertItems(items: List<LayoutItem>)
    @Upsert suspend fun upsertItems(items: List<LayoutItem>)
    @Query("DELETE FROM layout_items WHERE id IN (:ids)") suspend fun deleteItems(ids: List<String>)
    @Query("DELETE FROM layout_items") suspend fun clearItems()
}

@Database(entities = [LayoutConfig::class, LayoutItem::class], version = 2, exportSchema = true)
abstract class LayoutDatabase : RoomDatabase() {
    abstract fun layout(): LayoutDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE layout_items ADD COLUMN widgetId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE layout_items ADD COLUMN provider TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE layout_items ADD COLUMN spanX INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE layout_items ADD COLUMN spanY INTEGER NOT NULL DEFAULT 1")
            }
        }
        @Volatile private var instance: LayoutDatabase? = null
        fun get(context: Context): LayoutDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LayoutDatabase::class.java, "workspace.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

