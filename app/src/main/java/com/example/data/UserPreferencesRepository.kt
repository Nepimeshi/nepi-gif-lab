package com.example.data

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionSettings
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.WebPQualityPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.gifStudioDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gif_studio_preferences"
)

class UserPreferencesRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val RESOLUTION_PRESET = stringPreferencesKey("resolution_preset")
        val CUSTOM_RESOLUTION = intPreferencesKey("custom_resolution_short_edge")
        val COLOR_PRESET = stringPreferencesKey("color_preset")
        val CUSTOM_COLOR_COUNT = intPreferencesKey("custom_color_count")
        val DITHER_MODE = stringPreferencesKey("dither_mode")
        val USE_ORIGINAL_FPS = booleanPreferencesKey("use_original_fps")
        val FPS = floatPreferencesKey("fps")
        val SPEED = floatPreferencesKey("speed")
        val REVERSE = booleanPreferencesKey("reverse")
        val PING_PONG = booleanPreferencesKey("ping_pong")
        val OUTPUT_FORMAT = stringPreferencesKey("output_format")
        val WEBP_QUALITY_PRESET = stringPreferencesKey("webp_quality_preset")
        val WEBP_QUALITY = intPreferencesKey("webp_quality")
    }

    val themeModeFlow: Flow<ThemeMode> = context.gifStudioDataStore.data.map { prefs ->
        val saved = prefs[Keys.THEME_MODE]
        ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.DARK
    }

    /**
     * Persisted user preference for haptic feedback.
     * `null` means the user has not explicitly overridden the setting yet, so the app
     * defaults to following the Android device's native touch-feedback setting.
     */
    val hapticsPreferenceFlow: Flow<Boolean?> = context.gifStudioDataStore.data.map { prefs ->
        prefs[Keys.HAPTICS_ENABLED]
    }

    val savedSettingsFlow: Flow<GifConversionSettings> = context.gifStudioDataStore.data.map { prefs ->
        val resPreset = ResolutionPreset.entries.firstOrNull { it.name == prefs[Keys.RESOLUTION_PRESET] }
            ?: ResolutionPreset.P360
        val colorPreset = ColorQualityPreset.entries.firstOrNull { it.name == prefs[Keys.COLOR_PRESET] }
            ?: ColorQualityPreset.AUTO
        val dither = DitherMode.entries.firstOrNull { it.name == prefs[Keys.DITHER_MODE] }
            ?: DitherMode.FLOYD_STEINBERG
        val outputFormat = OutputFormat.entries.firstOrNull { it.name == prefs[Keys.OUTPUT_FORMAT] }
            ?: OutputFormat.GIF
        val webpPreset = WebPQualityPreset.entries.firstOrNull { it.name == prefs[Keys.WEBP_QUALITY_PRESET] }
            ?: WebPQualityPreset.BALANCED

        GifConversionSettings(
            resolutionPreset = resPreset,
            customResolutionShortEdge = prefs[Keys.CUSTOM_RESOLUTION] ?: 360,
            colorQualityPreset = colorPreset,
            customColorCount = prefs[Keys.CUSTOM_COLOR_COUNT] ?: 128,
            ditherMode = dither,
            useOriginalFps = prefs[Keys.USE_ORIGINAL_FPS] ?: true,
            fps = prefs[Keys.FPS] ?: 15f,
            speed = prefs[Keys.SPEED] ?: 1f,
            reverse = prefs[Keys.REVERSE] ?: false,
            pingPong = prefs[Keys.PING_PONG] ?: false,
            outputFormat = outputFormat,
            webpQualityPreset = webpPreset,
            webpQuality = prefs[Keys.WEBP_QUALITY] ?: 75
        ).sanitized()
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.gifStudioDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        context.gifStudioDataStore.edit { prefs ->
            prefs[Keys.HAPTICS_ENABLED] = enabled
        }
    }

    suspend fun saveLastUsedSettings(settings: GifConversionSettings) {
        val clean = settings.sanitized()
        context.gifStudioDataStore.edit { prefs ->
            prefs[Keys.RESOLUTION_PRESET] = clean.resolutionPreset.name
            prefs[Keys.CUSTOM_RESOLUTION] = clean.customResolutionShortEdge
            prefs[Keys.COLOR_PRESET] = clean.colorQualityPreset.name
            prefs[Keys.CUSTOM_COLOR_COUNT] = clean.customColorCount
            prefs[Keys.DITHER_MODE] = clean.ditherMode.name
            prefs[Keys.USE_ORIGINAL_FPS] = clean.useOriginalFps
            prefs[Keys.FPS] = clean.fps
            prefs[Keys.SPEED] = clean.speed
            prefs[Keys.REVERSE] = clean.reverse
            prefs[Keys.PING_PONG] = clean.pingPong
            prefs[Keys.OUTPUT_FORMAT] = clean.outputFormat.name
            prefs[Keys.WEBP_QUALITY_PRESET] = clean.webpQualityPreset.name
            prefs[Keys.WEBP_QUALITY] = clean.webpQuality
        }
    }

    suspend fun resetConversionSettingsToDefault() {
        saveLastUsedSettings(GifConversionSettings.DEFAULT)
    }

    companion object {
        fun isSystemHapticsEnabled(context: Context): Boolean {
            return try {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1
                ) != 0
            } catch (_: Throwable) {
                true
            }
        }

        fun resolveEffectiveHapticsEnabled(context: Context, userPreference: Boolean?): Boolean {
            val systemEnabled = isSystemHapticsEnabled(context)
            return if (!systemEnabled) {
                false
            } else {
                userPreference ?: true
            }
        }
    }
}
