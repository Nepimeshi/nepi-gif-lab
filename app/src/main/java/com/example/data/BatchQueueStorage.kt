package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionSettings
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.WebPQualityPreset
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "batch_queue_items")
data class BatchQueueEntity(
    @PrimaryKey val id: String,
    val sortOrder: Int,
    val uri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val inputType: String,
    val thumbnailPath: String? = null,
    val status: String = "QUEUED",
    val progress: Float = 0f,
    val isIndeterminate: Boolean = false,
    val stage: String = "Queued",
    val errorMessage: String? = null,
    val savedUri: String? = null,
    val savedFileName: String? = null,
    val savedWidth: Int = 0,
    val savedHeight: Int = 0,
    val savedFps: Float = 0f,
    val savedDurationMs: Long = 0L,
    val savedFrameCount: Int = 0,
    val savedFileSizeBytes: Long = 0L,
    val savedColorCount: Int = 0,
    val savedDitherMode: String? = null,
    val savedThumbnailPath: String? = null,
    val customSettingsEncoded: String? = null,
    val cancelRequested: Boolean = false,
    val savedOutputFormat: String = OutputFormat.GIF.name
)

/**
 * Compact, zero-dependency serializer for persisting per-item [GifConversionSettings]
 * overrides inside [BatchQueueEntity.customSettingsEncoded].
 */
object GifSettingsCodec {
    fun encode(settings: GifConversionSettings): String {
        val s = settings.sanitized()
        return listOf(
            "res=${s.resolutionPreset.name}",
            "cres=${s.customResolutionShortEdge}",
            "col=${s.colorQualityPreset.name}",
            "ccol=${s.customColorCount}",
            "dith=${s.ditherMode.name}",
            "ofps=${s.useOriginalFps}",
            "fps=${s.fps}",
            "spd=${s.speed}",
            "rev=${s.reverse}",
            "pp=${s.pingPong}",
            "rot=${s.rotationDegrees}",
            "cl=${s.cropRect.left}",
            "ct=${s.cropRect.top}",
            "cr=${s.cropRect.right}",
            "cb=${s.cropRect.bottom}",
            "ar=${s.aspectRatioPreset.name}",
            "ts=${s.trimStartMs}",
            "te=${s.trimEndMs}",
            "loop=${s.loopCount}",
            "fmt=${s.outputFormat.name}",
            "wqp=${s.webpQualityPreset.name}",
            "wq=${s.webpQuality}"
        ).joinToString(";")
    }

    fun decode(encoded: String?): GifConversionSettings? {
        if (encoded.isNullOrBlank()) return null
        val map = encoded.split(';').mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx <= 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
        }.toMap()
        if (map.isEmpty()) return null

        return GifConversionSettings(
            resolutionPreset = ResolutionPreset.entries.firstOrNull { it.name == map["res"] }
                ?: ResolutionPreset.P360,
            customResolutionShortEdge = map["cres"]?.toIntOrNull() ?: 360,
            colorQualityPreset = ColorQualityPreset.entries.firstOrNull { it.name == map["col"] }
                ?: ColorQualityPreset.AUTO,
            customColorCount = map["ccol"]?.toIntOrNull() ?: 128,
            ditherMode = DitherMode.entries.firstOrNull { it.name == map["dith"] }
                ?: DitherMode.FLOYD_STEINBERG,
            useOriginalFps = map["ofps"]?.toBooleanStrictOrNull() ?: true,
            fps = map["fps"]?.toFloatOrNull() ?: 15f,
            speed = map["spd"]?.toFloatOrNull() ?: 1f,
            reverse = map["rev"]?.toBooleanStrictOrNull() ?: false,
            pingPong = map["pp"]?.toBooleanStrictOrNull() ?: false,
            rotationDegrees = map["rot"]?.toIntOrNull() ?: 0,
            cropRect = NormalizedCropRect(
                left = map["cl"]?.toFloatOrNull() ?: 0f,
                top = map["ct"]?.toFloatOrNull() ?: 0f,
                right = map["cr"]?.toFloatOrNull() ?: 1f,
                bottom = map["cb"]?.toFloatOrNull() ?: 1f
            ),
            aspectRatioPreset = AspectRatioPreset.entries.firstOrNull { it.name == map["ar"] }
                ?: AspectRatioPreset.ORIGINAL,
            trimStartMs = map["ts"]?.toLongOrNull() ?: 0L,
            trimEndMs = map["te"]?.toLongOrNull() ?: 0L,
            loopCount = map["loop"]?.toIntOrNull() ?: 0,
            outputFormat = OutputFormat.entries.firstOrNull { it.name == map["fmt"] }
                ?: OutputFormat.GIF,
            webpQualityPreset = WebPQualityPreset.entries.firstOrNull { it.name == map["wqp"] }
                ?: WebPQualityPreset.BALANCED,
            webpQuality = map["wq"]?.toIntOrNull() ?: 75
        ).sanitized()
    }
}

@Dao
interface BatchQueueDao {
    @Query("SELECT * FROM batch_queue_items ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<BatchQueueEntity>>

    @Query("SELECT * FROM batch_queue_items ORDER BY sortOrder ASC")
    suspend fun getAll(): List<BatchQueueEntity>

    @Query("SELECT * FROM batch_queue_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BatchQueueEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM batch_queue_items")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<BatchQueueEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BatchQueueEntity)

    @Query("UPDATE batch_queue_items SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Transaction
    suspend fun swapSortOrder(idA: String, orderA: Int, idB: String, orderB: Int) {
        updateSortOrder(idA, orderB)
        updateSortOrder(idB, orderA)
    }

    @Query(
        "UPDATE batch_queue_items SET status = :status, progress = :progress, " +
            "isIndeterminate = :isIndeterminate, stage = :stage, errorMessage = :errorMessage, " +
            "cancelRequested = :cancelRequested WHERE id = :id"
    )
    suspend fun updateStatus(
        id: String,
        status: String,
        progress: Float,
        isIndeterminate: Boolean,
        stage: String,
        errorMessage: String? = null,
        cancelRequested: Boolean = false
    )

    @Query(
        "UPDATE batch_queue_items SET status = 'COMPLETED', progress = 1.0, isIndeterminate = 0, " +
            "stage = 'Completed', errorMessage = NULL, cancelRequested = 0, " +
            "savedUri = :savedUri, savedFileName = :savedFileName, savedWidth = :savedWidth, " +
            "savedHeight = :savedHeight, savedFps = :savedFps, savedDurationMs = :savedDurationMs, " +
            "savedFrameCount = :savedFrameCount, savedFileSizeBytes = :savedFileSizeBytes, " +
            "savedColorCount = :savedColorCount, savedDitherMode = :savedDitherMode, " +
            "savedThumbnailPath = :savedThumbnailPath, savedOutputFormat = :savedOutputFormat WHERE id = :id"
    )
    suspend fun markCompleted(
        id: String,
        savedUri: String,
        savedFileName: String,
        savedWidth: Int,
        savedHeight: Int,
        savedFps: Float,
        savedDurationMs: Long,
        savedFrameCount: Int,
        savedFileSizeBytes: Long,
        savedColorCount: Int,
        savedDitherMode: String,
        savedThumbnailPath: String?,
        savedOutputFormat: String = OutputFormat.GIF.name
    )

    @Query("UPDATE batch_queue_items SET customSettingsEncoded = :encoded WHERE id = :id")
    suspend fun updateCustomSettings(id: String, encoded: String?)

    @Query("UPDATE batch_queue_items SET cancelRequested = 1 WHERE id = :id")
    suspend fun requestCancelItem(id: String)

    @Query("SELECT cancelRequested FROM batch_queue_items WHERE id = :id LIMIT 1")
    suspend fun isCancelRequested(id: String): Boolean?

    @Query(
        "UPDATE batch_queue_items SET status = 'QUEUED', progress = 0.0, isIndeterminate = 0, " +
            "stage = 'Queued', errorMessage = NULL, cancelRequested = 0 " +
            "WHERE status IN ('FAILED', 'CANCELLED')"
    )
    suspend fun resetFailedAndCancelledToQueued()

    @Query(
        "UPDATE batch_queue_items SET status = 'CANCELLED', progress = 0.0, isIndeterminate = 0, " +
            "stage = 'Cancelled', cancelRequested = 0 " +
            "WHERE status IN ('QUEUED', 'PROCESSING')"
    )
    suspend fun markPendingAndActiveCancelled()

    @Query("DELETE FROM batch_queue_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM batch_queue_items WHERE status = 'COMPLETED'")
    suspend fun deleteCompleted()

    @Query("DELETE FROM batch_queue_items")
    suspend fun clearAll()
}

class BatchQueueRepository(private val dao: BatchQueueDao) {
    val allItems: Flow<List<BatchQueueEntity>> = dao.observeAll()

    suspend fun getAll(): List<BatchQueueEntity> = dao.getAll()

    suspend fun getById(id: String): BatchQueueEntity? = dao.getById(id)

    suspend fun appendItems(items: List<BatchQueueEntity>) {
        if (items.isEmpty()) return
        val startOrder = dao.maxSortOrder() + 1
        val numbered = items.mapIndexed { idx, item ->
            item.copy(sortOrder = startOrder + idx)
        }
        dao.insertAll(numbered)
    }

    suspend fun moveItem(id: String, delta: Int): Boolean {
        val current = dao.getAll()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        val targetIndex = index + delta
        if (targetIndex !in current.indices) return false
        val a = current[index]
        val b = current[targetIndex]
        // Only allow reordering non-processing items so the active worker item doesn't jump unexpectedly
        if (a.status == "PROCESSING" || b.status == "PROCESSING") return false
        dao.swapSortOrder(a.id, a.sortOrder, b.id, b.sortOrder)
        return true
    }

    suspend fun removeItem(id: String) = dao.deleteById(id)

    suspend fun removeCompleted() = dao.deleteCompleted()

    suspend fun clearAll() = dao.clearAll()

    suspend fun setCustomSettings(id: String, settings: GifConversionSettings?) {
        dao.updateCustomSettings(id, settings?.let { GifSettingsCodec.encode(it) })
    }

    suspend fun requeueItem(id: String) {
        dao.updateStatus(
            id = id,
            status = "QUEUED",
            progress = 0f,
            isIndeterminate = false,
            stage = "Queued",
            errorMessage = null,
            cancelRequested = false
        )
    }

    suspend fun requestCancelItem(id: String) = dao.requestCancelItem(id)

    suspend fun isCancelRequested(id: String): Boolean = dao.isCancelRequested(id) == true

    suspend fun updateStatus(
        id: String,
        status: String,
        progress: Float,
        isIndeterminate: Boolean,
        stage: String,
        errorMessage: String? = null,
        cancelRequested: Boolean = false
    ) = dao.updateStatus(id, status, progress, isIndeterminate, stage, errorMessage, cancelRequested)

    suspend fun markCompleted(
        id: String,
        savedUri: String,
        savedFileName: String,
        savedWidth: Int,
        savedHeight: Int,
        savedFps: Float,
        savedDurationMs: Long,
        savedFrameCount: Int,
        savedFileSizeBytes: Long,
        savedColorCount: Int,
        savedDitherMode: String,
        savedThumbnailPath: String?,
        savedOutputFormat: String = OutputFormat.GIF.name
    ) = dao.markCompleted(
        id, savedUri, savedFileName, savedWidth, savedHeight, savedFps,
        savedDurationMs, savedFrameCount, savedFileSizeBytes, savedColorCount,
        savedDitherMode, savedThumbnailPath, savedOutputFormat
    )

    suspend fun resetFailedAndCancelledToQueued() = dao.resetFailedAndCancelledToQueued()

    suspend fun markPendingAndActiveCancelled() = dao.markPendingAndActiveCancelled()
}
