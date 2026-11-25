package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.example.data.UserPreferencesRepository
import kotlin.math.abs

/**
 * Semantic haptic events across Nepi GIF Lab.
 *
 * All feedback uses subtle native Android [View.performHapticFeedback] constants so no extra
 * permissions are needed and feedback automatically blends with the platform feel.
 */
enum class StudioHapticEvent {
    /** Chips, presets, tab switches, toggles, format/dither/quality selectors. */
    SELECTION,
    /** Key CTA buttons (Export, Apply, Import, Share, Open). */
    PRIMARY_ACTION,
    /** Grabbing a crop corner/edge or timeline trim handle. */
    HANDLE_GRAB,
    /** Releasing a crop handle, trim handle, or finishing a slider drag. */
    HANDLE_RELEASE,
    /** Discrete frame-by-frame timeline navigation (Prev / Next frame). */
    STEP_FRAME,
    /** Restrained detent when a slider crosses a meaningful value interval or snap point. */
    SLIDER_DETENT,
    /** Starting a single or batch conversion. */
    CONVERSION_START,
    /** Cancelling an active conversion or batch queue. */
    CONVERSION_CANCEL,
    /** Conversion saved successfully. */
    EXPORT_SUCCESS,
    /** Conversion error or invalid action. */
    ERROR
}

interface StudioHapticsController {
    val isEffectivelyEnabled: Boolean
    fun perform(event: StudioHapticEvent): Boolean
}

object NoOpStudioHapticsController : StudioHapticsController {
    override val isEffectivelyEnabled: Boolean = false
    override fun perform(event: StudioHapticEvent): Boolean = false
}

/**
 * Native Android haptic controller that:
 * 1. Honors the user's in-app preference (defaulting to the device's system touch-feedback setting).
 * 2. Always respects the system-level Android haptic preference (suppresses when system haptics are off).
 * 3. Gracefully no-ops on devices without haptic/vibrator hardware.
 * 4. Requires zero extra permissions (uses [View.performHapticFeedback] without IGNORE flags).
 */
class AndroidStudioHapticsController(
    private val view: View,
    private val context: Context,
    private val userPreferenceEnabled: Boolean?,
    private val systemHapticsProvider: () -> Boolean = {
        UserPreferencesRepository.isSystemHapticsEnabled(context)
    },
    private val hasVibratorProvider: () -> Boolean = {
        deviceHasVibrator(context)
    },
    private val performFeedbackCallback: ((Int) -> Boolean)? = null
) : StudioHapticsController {

    override val isEffectivelyEnabled: Boolean
        get() {
            if (!hasVibratorProvider()) return false
            if (!systemHapticsProvider()) return false
            return userPreferenceEnabled ?: true
        }

    override fun perform(event: StudioHapticEvent): Boolean {
        if (!isEffectivelyEnabled) return false
        val constant = mapEventToConstant(event, Build.VERSION.SDK_INT)
        return try {
            performFeedbackCallback?.invoke(constant)
                ?: view.performHapticFeedback(constant)
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        @Suppress("InlinedApi")
        fun mapEventToConstant(event: StudioHapticEvent, sdkInt: Int): Int {
            return when (event) {
                StudioHapticEvent.SELECTION -> HapticFeedbackConstants.CLOCK_TICK
                StudioHapticEvent.STEP_FRAME -> HapticFeedbackConstants.CLOCK_TICK
                StudioHapticEvent.SLIDER_DETENT -> {
                    if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                }
                StudioHapticEvent.PRIMARY_ACTION -> {
                    if (sdkInt >= Build.VERSION_CODES.M) {
                        HapticFeedbackConstants.CONTEXT_CLICK
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                }
                StudioHapticEvent.HANDLE_GRAB -> {
                    if (sdkInt >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.GESTURE_START
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                }
                StudioHapticEvent.HANDLE_RELEASE -> {
                    if (sdkInt >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.GESTURE_END
                    } else {
                        HapticFeedbackConstants.CLOCK_TICK
                    }
                }
                StudioHapticEvent.CONVERSION_START -> {
                    if (sdkInt >= Build.VERSION_CODES.M) {
                        HapticFeedbackConstants.CONTEXT_CLICK
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                }
                StudioHapticEvent.CONVERSION_CANCEL -> {
                    if (sdkInt >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.GESTURE_END
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                }
                StudioHapticEvent.EXPORT_SUCCESS -> {
                    if (sdkInt >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.CONFIRM
                    } else {
                        HapticFeedbackConstants.VIRTUAL_KEY
                    }
                }
                StudioHapticEvent.ERROR -> {
                    if (sdkInt >= Build.VERSION_CODES.R) {
                        HapticFeedbackConstants.REJECT
                    } else {
                        HapticFeedbackConstants.LONG_PRESS
                    }
                }
            }
        }

        fun deviceHasVibrator(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.hasVibrator() ?: false
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.hasVibrator() ?: false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * Restrained haptic detent tracker for continuous sliders so dragging never spams vibration on
 * every pixel move. Fires [StudioHapticEvent.SLIDER_DETENT] only when crossing a discrete step
 * interval or snapping onto a preset landmark, throttled by [minIntervalMs].
 */
class SliderHapticDetentTracker(
    private val stepInterval: Float,
    private val snapValues: List<Float> = emptyList(),
    private val minIntervalMs: Long = 45L,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var lastBucket: Int? = null
    private var lastSnapIndex: Int? = null
    private var lastTriggerMs: Long = 0L

    fun onValueChanged(value: Float, haptics: StudioHapticsController): Boolean {
        val now = clockMs()
        val snapIdx = snapValues.indexOfFirst { abs(it - value) <= stepInterval * 0.25f }
            .takeIf { it >= 0 }
        val bucket = if (stepInterval > 0f) (value / stepInterval).toInt() else 0

        if (lastBucket == null) {
            lastBucket = bucket
            lastSnapIndex = snapIdx
            return false
        }

        val crossedSnap = snapIdx != null && snapIdx != lastSnapIndex
        val crossedBucket = bucket != lastBucket
        lastBucket = bucket
        lastSnapIndex = snapIdx

        if ((crossedSnap || crossedBucket) && (now - lastTriggerMs >= minIntervalMs)) {
            lastTriggerMs = now
            return haptics.perform(StudioHapticEvent.SLIDER_DETENT)
        }
        return false
    }

    fun onValueChangeFinished(haptics: StudioHapticsController): Boolean {
        lastBucket = null
        lastSnapIndex = null
        return haptics.perform(StudioHapticEvent.HANDLE_RELEASE)
    }

    fun reset() {
        lastBucket = null
        lastSnapIndex = null
        lastTriggerMs = 0L
    }
}

val LocalStudioHaptics = staticCompositionLocalOf<StudioHapticsController> {
    NoOpStudioHapticsController
}

@Composable
fun rememberStudioHaptics(userPreferenceEnabled: Boolean?): StudioHapticsController {
    val view = LocalView.current
    val context = LocalContext.current
    return remember(view, context, userPreferenceEnabled) {
        AndroidStudioHapticsController(
            view = view,
            context = context,
            userPreferenceEnabled = userPreferenceEnabled
        )
    }
}
