package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.engine.MediaInputType
import com.example.engine.OutputFormat
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversion_history")
data class ConversionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contentUri: String,
    val fileName: String,
    val thumbnailPath: String?,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val fileSizeBytes: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val durationMs: Long,
    val frameCount: Int,
    val colorCount: Int,
    val ditherModeName: String,
    val sourceVideoName: String,
    val outputFormat: String = OutputFormat.GIF.name,
    val sourceInputType: String = MediaInputType.VIDEO.name,
    val settingsSnapshotJson: String? = null
) {
    val parsedOutputFormat: OutputFormat
        get() = OutputFormat.entries.firstOrNull { it.name == outputFormat }
            ?: if (fileName.endsWith(".webp", ignoreCase = true)) OutputFormat.WEBP else OutputFormat.GIF

    val parsedSourceInputType: MediaInputType
        get() = MediaInputType.entries.firstOrNull { it.name == sourceInputType }
            ?: if (sourceVideoName.endsWith(".gif", ignoreCase = true)) MediaInputType.GIF else MediaInputType.VIDEO

    fun parsedSnapshot(): ConversionHistorySnapshot? =
        ConversionHistorySnapshotCodec.decode(settingsSnapshotJson)
}

@Dao
interface ConversionHistoryDao {
    @Query("SELECT * FROM conversion_history ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<ConversionHistoryEntity>>

    @Query("SELECT * FROM conversion_history ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ConversionHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ConversionHistoryEntity): Long

    @Query("DELETE FROM conversion_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversion_history")
    suspend fun clearAll()
}

@Database(
    entities = [
        ConversionHistoryEntity::class,
        ConversionPresetEntity::class,
        BatchQueueEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GifStudioDatabase : RoomDatabase() {
    abstract fun historyDao(): ConversionHistoryDao
    abstract fun presetDao(): ConversionPresetDao
    abstract fun batchQueueDao(): BatchQueueDao

    companion object {
        @Volatile
        private var INSTANCE: GifStudioDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `conversion_presets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL,
                        `resolutionPreset` TEXT NOT NULL,
                        `customResolutionShortEdge` INTEGER NOT NULL,
                        `useOriginalFps` INTEGER NOT NULL,
                        `fps` REAL NOT NULL,
                        `colorQualityPreset` TEXT NOT NULL,
                        `customColorCount` INTEGER NOT NULL,
                        `ditherMode` TEXT NOT NULL,
                        `speed` REAL NOT NULL,
                        `reverse` INTEGER NOT NULL,
                        `pingPong` INTEGER NOT NULL,
                        `loopCount` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `batch_queue_items` (
                        `id` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `displayName` TEXT NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `inputType` TEXT NOT NULL,
                        `thumbnailPath` TEXT,
                        `status` TEXT NOT NULL,
                        `progress` REAL NOT NULL,
                        `isIndeterminate` INTEGER NOT NULL,
                        `stage` TEXT NOT NULL,
                        `errorMessage` TEXT,
                        `savedUri` TEXT,
                        `savedFileName` TEXT,
                        `savedWidth` INTEGER NOT NULL,
                        `savedHeight` INTEGER NOT NULL,
                        `savedFps` REAL NOT NULL,
                        `savedDurationMs` INTEGER NOT NULL,
                        `savedFrameCount` INTEGER NOT NULL,
                        `savedFileSizeBytes` INTEGER NOT NULL,
                        `savedColorCount` INTEGER NOT NULL,
                        `savedDitherMode` TEXT,
                        `savedThumbnailPath` TEXT,
                        `customSettingsEncoded` TEXT,
                        `cancelRequested` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Non-destructive migration from v2 to v3.
         *
         * Preserves all existing history rows, custom presets, and batch queue items while adding:
         * - `outputFormat`, `sourceInputType`, and `settingsSnapshotJson` to `conversion_history`
         * - `savedOutputFormat` to `batch_queue_items`
         *
         * Legacy history rows receive `outputFormat = 'GIF'`, `sourceInputType = 'VIDEO'`, and
         * `settingsSnapshotJson = NULL`, so the UI gracefully displays "Settings unavailable"
         * while retaining their recorded file metadata.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversion_history` ADD COLUMN `outputFormat` TEXT NOT NULL DEFAULT 'GIF'"
                )
                db.execSQL(
                    "ALTER TABLE `conversion_history` ADD COLUMN `sourceInputType` TEXT NOT NULL DEFAULT 'VIDEO'"
                )
                db.execSQL(
                    "ALTER TABLE `conversion_history` ADD COLUMN `settingsSnapshotJson` TEXT DEFAULT NULL"
                )
                db.execSQL(
                    "ALTER TABLE `batch_queue_items` ADD COLUMN `savedOutputFormat` TEXT NOT NULL DEFAULT 'GIF'"
                )
            }
        }

        fun getInstance(context: Context): GifStudioDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GifStudioDatabase::class.java,
                    "gif_studio_local.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

class HistoryRepository(private val dao: ConversionHistoryDao) {
    val allHistory: Flow<List<ConversionHistoryEntity>> = dao.observeAll()

    fun recentHistory(limit: Int = 5): Flow<List<ConversionHistoryEntity>> = dao.observeRecent(limit)

    suspend fun recordExport(entity: ConversionHistoryEntity): Long = dao.insert(entity)

    suspend fun deleteItem(id: Long) = dao.deleteById(id)

    suspend fun clearAll() = dao.clearAll()
}
