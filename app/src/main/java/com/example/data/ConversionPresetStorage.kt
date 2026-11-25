package com.example.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionSettings
import com.example.engine.ResolutionPreset
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversion_presets")
data class ConversionPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val isBuiltIn: Boolean = false,
    val resolutionPreset: String = ResolutionPreset.P360.name,
    val customResolutionShortEdge: Int = 360,
    val useOriginalFps: Boolean = true,
    val fps: Float = 15f,
    val colorQualityPreset: String = ColorQualityPreset.AUTO.name,
    val customColorCount: Int = 128,
    val ditherMode: String = DitherMode.FLOYD_STEINBERG.name,
    val speed: Float = 1f,
    val reverse: Boolean = false,
    val pingPong: Boolean = false,
    val loopCount: Int = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    /**
     * Applies this preset's reusable conversion parameters onto [base] while preserving
     * clip-specific geometry and trimming (trim range, crop rect, aspect ratio, rotation).
     */
    fun applyTo(base: GifConversionSettings): GifConversionSettings {
        val resPreset = ResolutionPreset.entries.firstOrNull { it.name == resolutionPreset }
            ?: ResolutionPreset.P360
        val colorPreset = ColorQualityPreset.entries.firstOrNull { it.name == colorQualityPreset }
            ?: ColorQualityPreset.AUTO
        val dither = DitherMode.entries.firstOrNull { it.name == ditherMode }
            ?: DitherMode.FLOYD_STEINBERG

        return base.copy(
            resolutionPreset = resPreset,
            customResolutionShortEdge = customResolutionShortEdge.coerceAtLeast(2),
            useOriginalFps = useOriginalFps,
            fps = fps.coerceIn(1f, 30f),
            colorQualityPreset = colorPreset,
            customColorCount = customColorCount.coerceIn(2, 256),
            ditherMode = dither,
            speed = speed.coerceIn(0.1f, 4f),
            reverse = reverse,
            pingPong = pingPong,
            loopCount = loopCount.coerceAtLeast(0)
        ).sanitized()
    }

    /**
     * Short human-readable summary of key settings for UI chips and sheets.
     */
    fun summaryText(): String {
        val resLabel = when (val r = ResolutionPreset.entries.firstOrNull { it.name == resolutionPreset }) {
            ResolutionPreset.CUSTOM -> "${customResolutionShortEdge}p"
            null -> "360p"
            else -> r.label
        }
        val fpsLabel = if (useOriginalFps) "Orig FPS" else "${fps.toInt()} FPS"
        val colorLabel = when (val c = ColorQualityPreset.entries.firstOrNull { it.name == colorQualityPreset }) {
            ColorQualityPreset.AUTO -> "Auto col"
            ColorQualityPreset.CUSTOM -> "${customColorCount}c"
            null -> "Auto col"
            else -> "${c.colorCount}c"
        }
        return "$resLabel · $fpsLabel · $colorLabel"
    }

    companion object {
        fun fromSettings(
            name: String,
            settings: GifConversionSettings,
            isBuiltIn: Boolean = false,
            nowMillis: Long = System.currentTimeMillis()
        ): ConversionPresetEntity {
            val clean = settings.sanitized()
            return ConversionPresetEntity(
                name = name.trim(),
                isBuiltIn = isBuiltIn,
                resolutionPreset = clean.resolutionPreset.name,
                customResolutionShortEdge = clean.customResolutionShortEdge,
                useOriginalFps = clean.useOriginalFps,
                fps = clean.fps,
                colorQualityPreset = clean.colorQualityPreset.name,
                customColorCount = clean.customColorCount,
                ditherMode = clean.ditherMode.name,
                speed = clean.speed,
                reverse = clean.reverse,
                pingPong = clean.pingPong,
                loopCount = clean.loopCount,
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis
            )
        }
    }
}

@Dao
interface ConversionPresetDao {
    @Query("SELECT * FROM conversion_presets ORDER BY isBuiltIn ASC, updatedAtMillis DESC, id ASC")
    fun observeAll(): Flow<List<ConversionPresetEntity>>

    @Query("SELECT * FROM conversion_presets ORDER BY isBuiltIn ASC, updatedAtMillis DESC, id ASC")
    suspend fun getAll(): List<ConversionPresetEntity>

    @Query("SELECT * FROM conversion_presets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ConversionPresetEntity?

    @Query("SELECT COUNT(*) FROM conversion_presets WHERE isBuiltIn = 1")
    suspend fun countBuiltIn(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: ConversionPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(presets: List<ConversionPresetEntity>)

    @Query("UPDATE conversion_presets SET name = :newName, updatedAtMillis = :updatedAt WHERE id = :id AND isBuiltIn = 0")
    suspend fun renameCustom(id: Long, newName: String, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM conversion_presets WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: Long): Int
}

class PresetRepository(private val dao: ConversionPresetDao) {
    val allPresets: Flow<List<ConversionPresetEntity>> = dao.observeAll()

    suspend fun getAllPresets(): List<ConversionPresetEntity> = dao.getAll()

    suspend fun ensureBuiltInPresets() {
        if (dao.countBuiltIn() > 0) return
        val baseTime = 1_700_000_000_000L
        val builtIns = listOf(
            ConversionPresetEntity.fromSettings(
                name = "High Quality",
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.P480,
                    useOriginalFps = true,
                    fps = 20f,
                    colorQualityPreset = ColorQualityPreset.C256,
                    customColorCount = 256,
                    ditherMode = DitherMode.FLOYD_STEINBERG
                ),
                isBuiltIn = true,
                nowMillis = baseTime + 4L
            ),
            ConversionPresetEntity.fromSettings(
                name = "Balanced Web",
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.P360,
                    useOriginalFps = false,
                    fps = 15f,
                    colorQualityPreset = ColorQualityPreset.C128,
                    customColorCount = 128,
                    ditherMode = DitherMode.FLOYD_STEINBERG
                ),
                isBuiltIn = true,
                nowMillis = baseTime + 3L
            ),
            ConversionPresetEntity.fromSettings(
                name = "Compact Chat",
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.P240,
                    useOriginalFps = false,
                    fps = 12f,
                    colorQualityPreset = ColorQualityPreset.C64,
                    customColorCount = 64,
                    ditherMode = DitherMode.ORDERED
                ),
                isBuiltIn = true,
                nowMillis = baseTime + 2L
            ),
            ConversionPresetEntity.fromSettings(
                name = "Crisp Flat",
                settings = GifConversionSettings(
                    resolutionPreset = ResolutionPreset.P360,
                    useOriginalFps = false,
                    fps = 15f,
                    colorQualityPreset = ColorQualityPreset.C32,
                    customColorCount = 32,
                    ditherMode = DitherMode.NONE
                ),
                isBuiltIn = true,
                nowMillis = baseTime + 1L
            )
        )
        dao.insertAll(builtIns)
    }

    suspend fun saveCustomPreset(name: String, settings: GifConversionSettings): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Preset name cannot be empty." }
        return dao.insert(
            ConversionPresetEntity.fromSettings(
                name = trimmed.take(40),
                settings = settings,
                isBuiltIn = false
            )
        )
    }

    suspend fun renameCustomPreset(id: Long, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        return dao.renameCustom(id, trimmed.take(40)) > 0
    }

    suspend fun deleteCustomPreset(id: Long): Boolean {
        return dao.deleteCustom(id) > 0
    }
}
