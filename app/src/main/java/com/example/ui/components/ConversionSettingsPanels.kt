package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.DitherMode
import com.example.engine.GifConversionSettings
import com.example.engine.GifGeometryCalculator
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.StudioFormatters
import com.example.engine.VideoMetadata
import com.example.engine.WebPQualityPreset
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.AccentMint
import com.example.ui.theme.MonoSpecStyle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private fun formatFpsDisplay(fps: Float): String {
    val rounded = fps.roundToInt().toFloat()
    return if (abs(fps - rounded) < 0.005f) {
        rounded.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", fps)
    }
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
    accent: Color,
    trailingBadge: String? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (!trailingBadge.isNullOrBlank()) {
            Surface(
                color = colors.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = trailingBadge,
                    style = MonoSpecStyle,
                    color = colors.primary,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Clean rounded segmented export format selector matching Nepi GIF Lab's lavender selection design.
 * GIF remains the default format; Animated WebP activates genuine libwebp animated encoding.
 */
@Composable
fun OutputFormatSelector(
    selectedFormat: OutputFormat,
    onSelectFormat: (OutputFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalStudioHaptics.current
    val shape = RoundedCornerShape(18.dp)

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(3.dp)
            .testTag("output_format_selector")
    ) {
        OutputFormat.entries.forEach { format ->
            val selected = selectedFormat == format
            val pillShape = RoundedCornerShape(15.dp)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(pillShape)
                    .background(if (selected) colors.primary else Color.Transparent)
                    .clickable {
                        if (selectedFormat != format) {
                            haptics.perform(StudioHapticEvent.SELECTION)
                            onSelectFormat(format)
                        }
                    }
                    .testTag("format_selector_${format.name.lowercase()}")
            ) {
                Text(
                    text = format.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrimAndCropPanel(
    metadata: VideoMetadata,
    settings: GifConversionSettings,
    isCropOverlayVisible: Boolean,
    onUpdateSettings: ((GifConversionSettings) -> GifConversionSettings) -> Unit,
    onApplyAspectRatio: (AspectRatioPreset) -> Unit,
    onRotate90: () -> Unit,
    onToggleCropOverlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    var startInput by remember(metadata.uri) {
        mutableStateOf(StudioFormatters.formatTimestamp(settings.trimStartMs))
    }
    var endInput by remember(metadata.uri) {
        mutableStateOf(StudioFormatters.formatTimestamp(settings.trimEndMs))
    }
    var startFocused by remember { mutableStateOf(false) }
    var endFocused by remember { mutableStateOf(false) }
    var startUserEdited by remember { mutableStateOf(false) }
    var endUserEdited by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf(false) }
    var endError by remember { mutableStateOf(false) }

    val rotated = GifGeometryCalculator.rotatedDimensions(
        metadata.orientedWidth,
        metadata.orientedHeight,
        settings.rotationDegrees
    )
    val cropped = GifGeometryCalculator.croppedSourceDimensions(
        metadata.orientedWidth,
        metadata.orientedHeight,
        settings.rotationDegrees,
        settings.cropRect
    )

    var cropWInput by remember(metadata.uri) { mutableStateOf(cropped.width.toString()) }
    var cropHInput by remember(metadata.uri) { mutableStateOf(cropped.height.toString()) }
    var cropWFocused by remember { mutableStateOf(false) }
    var cropHFocused by remember { mutableStateOf(false) }
    var cropUserEdited by remember { mutableStateOf(false) }
    var cropWError by remember { mutableStateOf(false) }
    var cropHError by remember { mutableStateOf(false) }

    // Keep fields synced with external changes (timeline drag, crop box drag, presets, rotation)
    // only while the user is not actively typing in that field.
    LaunchedEffect(settings.trimStartMs, startFocused) {
        if (!startFocused && !startUserEdited) {
            val formatted = StudioFormatters.formatTimestamp(settings.trimStartMs)
            if (startInput != formatted) startInput = formatted
            startError = false
        }
    }
    LaunchedEffect(settings.trimEndMs, endFocused) {
        if (!endFocused && !endUserEdited) {
            val formatted = StudioFormatters.formatTimestamp(settings.trimEndMs)
            if (endInput != formatted) endInput = formatted
            endError = false
        }
    }
    LaunchedEffect(cropped.width, cropWFocused) {
        if (!cropWFocused && !cropUserEdited) {
            val target = cropped.width.toString()
            if (cropWInput != target) cropWInput = target
            cropWError = false
        }
    }
    LaunchedEffect(cropped.height, cropHFocused) {
        if (!cropHFocused && !cropUserEdited) {
            val target = cropped.height.toString()
            if (cropHInput != target) cropHInput = target
            cropHError = false
        }
    }

    fun applyStartNow() {
        val parsed = StudioFormatters.parseTimestampMs(startInput)
        if (parsed != null && parsed in 0L until settings.trimEndMs) {
            startError = false
            startUserEdited = false
            if (parsed != settings.trimStartMs) {
                onUpdateSettings { current ->
                    if (parsed in 0L until current.trimEndMs && parsed != current.trimStartMs) {
                        current.copy(trimStartMs = parsed)
                    } else current
                }
            }
        } else {
            startError = true
        }
    }

    fun applyEndNow() {
        val parsed = StudioFormatters.parseTimestampMs(endInput)
        if (parsed != null && parsed > settings.trimStartMs && parsed <= metadata.durationMs) {
            endError = false
            endUserEdited = false
            if (parsed != settings.trimEndMs) {
                onUpdateSettings { current ->
                    if (parsed > current.trimStartMs && parsed <= metadata.durationMs && parsed != current.trimEndMs) {
                        current.copy(trimEndMs = parsed)
                    } else current
                }
            }
        } else {
            endError = true
        }
    }

    fun applyCustomCropNow(reqW: Int, reqH: Int) {
        val wFrac = (reqW.toFloat() / rotated.width.toFloat()).coerceIn(0.05f, 1f)
        val hFrac = (reqH.toFloat() / rotated.height.toFloat()).coerceIn(0.05f, 1f)
        val centerX = (settings.cropRect.left + settings.cropRect.right) / 2f
        val centerY = (settings.cropRect.top + settings.cropRect.bottom) / 2f
        val left = (centerX - wFrac / 2f).coerceIn(0f, 1f - wFrac)
        val top = (centerY - hFrac / 2f).coerceIn(0f, 1f - hFrac)
        cropUserEdited = false
        onUpdateSettings {
            it.copy(
                cropRect = NormalizedCropRect(left, top, left + wFrac, top + hFrac),
                aspectRatioPreset = AspectRatioPreset.ORIGINAL
            )
        }
    }

    // Debounced auto-apply for Start timestamp
    LaunchedEffect(startInput, startUserEdited, settings.trimEndMs) {
        if (!startUserEdited) return@LaunchedEffect
        val trimmed = startInput.trim()
        if (trimmed.isEmpty()) {
            startError = false
            return@LaunchedEffect
        }
        val parsed = StudioFormatters.parseTimestampMs(trimmed)
        if (parsed != null && parsed in 0L until settings.trimEndMs) {
            startError = false
            delay(250L)
            startUserEdited = false
            if (parsed != settings.trimStartMs) {
                onUpdateSettings { current ->
                    if (parsed in 0L until current.trimEndMs && parsed != current.trimStartMs) {
                        current.copy(trimStartMs = parsed)
                    } else current
                }
            }
        } else {
            delay(450L)
            startError = true
        }
    }

    // Debounced auto-apply for End timestamp
    LaunchedEffect(endInput, endUserEdited, settings.trimStartMs, metadata.durationMs) {
        if (!endUserEdited) return@LaunchedEffect
        val trimmed = endInput.trim()
        if (trimmed.isEmpty()) {
            endError = false
            return@LaunchedEffect
        }
        val parsed = StudioFormatters.parseTimestampMs(trimmed)
        if (parsed != null && parsed > settings.trimStartMs && parsed <= metadata.durationMs) {
            endError = false
            delay(250L)
            endUserEdited = false
            if (parsed != settings.trimEndMs) {
                onUpdateSettings { current ->
                    if (parsed > current.trimStartMs && parsed <= metadata.durationMs && parsed != current.trimEndMs) {
                        current.copy(trimEndMs = parsed)
                    } else current
                }
            }
        } else {
            delay(450L)
            endError = true
        }
    }

    // Debounced auto-apply for Custom Crop Width & Height
    LaunchedEffect(cropWInput, cropHInput, cropUserEdited, rotated.width, rotated.height) {
        if (!cropUserEdited) return@LaunchedEffect
        val rawW = cropWInput.toIntOrNull()
        val rawH = cropHInput.toIntOrNull()
        val minW = min(32, rotated.width)
        val minH = min(32, rotated.height)
        val wValid = rawW != null && rawW in minW..rotated.width
        val hValid = rawH != null && rawH in minH..rotated.height

        if (wValid) cropWError = false
        if (hValid) cropHError = false

        if (wValid && hValid) {
            delay(250L)
            if (rawW != cropped.width || rawH != cropped.height) {
                applyCustomCropNow(rawW, rawH)
            } else {
                cropUserEdited = false
            }
        } else if (cropWInput.isNotEmpty() || cropHInput.isNotEmpty()) {
            delay(450L)
            cropWError = cropWInput.isNotEmpty() && !wValid
            cropHError = cropHInput.isNotEmpty() && !hValid
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // Precise numerical trim inputs (auto-applied)
        SectionHeader(
            title = "Precise Trim (MM:SS.cc)",
            icon = Icons.Rounded.ContentCut,
            accent = AccentLavender,
            trailingBadge = StudioFormatters.formatDurationShort(
                (settings.trimEndMs - settings.trimStartMs).coerceAtLeast(0L)
            )
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            StudioTextField(
                value = startInput,
                onValueChange = {
                    startInput = it
                    startUserEdited = true
                    startError = false
                },
                label = "Start (00:02.50)",
                isError = startError,
                onDone = {
                    applyStartNow()
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { startFocused = it.isFocused }
                    .testTag("trim_start_input")
            )
            StudioTextField(
                value = endInput,
                onValueChange = {
                    endInput = it
                    endUserEdited = true
                    endError = false
                },
                label = "End (${StudioFormatters.formatTimestamp(metadata.durationMs)})",
                isError = endError,
                onDone = {
                    applyEndNow()
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { endFocused = it.isFocused }
                    .testTag("trim_end_input")
            )
        }

        // Crop & Orientation
        SectionHeader(
            title = "Crop & Orientation",
            icon = Icons.Rounded.Crop,
            accent = AccentBlue,
            trailingBadge = "${cropped.width}×${cropped.height} · ${settings.rotationDegrees}°"
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val haptics = LocalStudioHaptics.current
            AspectRatioPreset.entries.forEach { preset ->
                StudioSelectableChip(
                    label = preset.label,
                    selected = settings.aspectRatioPreset == preset,
                    onClick = {
                        haptics.perform(StudioHapticEvent.SELECTION)
                        onApplyAspectRatio(preset)
                    },
                    modifier = Modifier.testTag("aspect_chip_${preset.name.lowercase()}")
                )
            }
        }

        val hapticsTrim = LocalStudioHaptics.current
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            StudioPillButton(
                text = "Rotate 90°",
                icon = Icons.AutoMirrored.Rounded.RotateRight,
                variant = StudioButtonVariant.TONAL,
                onClick = {
                    hapticsTrim.perform(StudioHapticEvent.SELECTION)
                    onRotate90()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("rotate_90_button")
            )
            StudioPillButton(
                text = if (isCropOverlayVisible) "Hide Box" else "Show Box",
                icon = Icons.Rounded.AspectRatio,
                variant = StudioButtonVariant.OUTLINED,
                onClick = {
                    hapticsTrim.perform(StudioHapticEvent.SELECTION)
                    onToggleCropOverlay()
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Custom crop width & height pixel inputs (auto-applied)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            StudioTextField(
                value = cropWInput,
                onValueChange = {
                    cropWInput = it.filter(Char::isDigit)
                    cropUserEdited = true
                    cropWError = false
                },
                label = "Crop Width (px)",
                isError = cropWError,
                keyboardType = KeyboardType.Number,
                onDone = {
                    val reqW = cropWInput.toIntOrNull()
                    val reqH = cropHInput.toIntOrNull()
                    val minW = min(32, rotated.width)
                    val minH = min(32, rotated.height)
                    if (reqW != null && reqH != null && reqW in minW..rotated.width && reqH in minH..rotated.height) {
                        applyCustomCropNow(reqW, reqH)
                    }
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { cropWFocused = it.isFocused }
                    .testTag("crop_width_input")
            )
            StudioTextField(
                value = cropHInput,
                onValueChange = {
                    cropHInput = it.filter(Char::isDigit)
                    cropUserEdited = true
                    cropHError = false
                },
                label = "Crop Height (px)",
                isError = cropHError,
                keyboardType = KeyboardType.Number,
                onDone = {
                    val reqW = cropWInput.toIntOrNull()
                    val reqH = cropHInput.toIntOrNull()
                    val minW = min(32, rotated.width)
                    val minH = min(32, rotated.height)
                    if (reqW != null && reqH != null && reqW in minW..rotated.width && reqH in minH..rotated.height) {
                        applyCustomCropNow(reqW, reqH)
                    }
                    focusManager.clearFocus()
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { cropHFocused = it.isFocused }
                    .testTag("crop_height_input")
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResolutionAndTimingPanel(
    sourceWidth: Int,
    sourceHeight: Int,
    settings: GifConversionSettings,
    onUpdateSettings: ((GifConversionSettings) -> GifConversionSettings) -> Unit,
    modifier: Modifier = Modifier,
    sourceFps: Float? = null,
    isVariableFrameRate: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val haptics = LocalStudioHaptics.current
    val resDetents = remember {
        SliderHapticDetentTracker(stepInterval = 24f, snapValues = listOf(240f, 360f, 480f))
    }
    val fpsDetents = remember {
        SliderHapticDetentTracker(stepInterval = 2f, snapValues = GifConversionSettings.FPS_PRESETS)
    }

    val croppedDims = GifGeometryCalculator.croppedSourceDimensions(
        orientedSourceWidth = sourceWidth,
        orientedSourceHeight = sourceHeight,
        extraRotationDegrees = settings.rotationDegrees,
        cropRect = settings.cropRect
    )
    val sourceCroppedShortEdge = min(croppedDims.width, croppedDims.height)
    val outputDims = GifGeometryCalculator.calculateOutputDimensions(
        orientedSourceWidth = sourceWidth,
        orientedSourceHeight = sourceHeight,
        settings = settings
    )

    val displayedFpsValue = if (settings.useOriginalFps && sourceFps != null) {
        sourceFps
    } else {
        settings.fps
    }

    var customResText by remember {
        mutableStateOf(settings.customResolutionShortEdge.toString())
    }
    var resFocused by remember { mutableStateOf(false) }
    var resUserEdited by remember { mutableStateOf(false) }
    var resolutionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.customResolutionShortEdge, resFocused) {
        if (!resFocused && !resUserEdited && customResText.toIntOrNull() != settings.customResolutionShortEdge) {
            customResText = settings.customResolutionShortEdge.toString()
            resolutionError = null
        }
    }
    LaunchedEffect(customResText, resUserEdited, sourceCroppedShortEdge) {
        if (!resUserEdited) return@LaunchedEffect
        val px = customResText.toIntOrNull()
        if (px == null) {
            resolutionError = null
            return@LaunchedEffect
        }
        if (px <= 0) {
            resolutionError = "Resolution must be a positive number."
            return@LaunchedEffect
        }
        val aspect = if (sourceCroppedShortEdge > 0) {
            maxOf(croppedDims.width, croppedDims.height).toDouble() / sourceCroppedShortEdge.toDouble()
        } else 1.0
        val estLongEdge = (px * aspect).roundToLong()
        if (estLongEdge > 65535L) {
            resolutionError = "Resolution exceeds GIF specification maximum of 65535 px."
            return@LaunchedEffect
        }
        val requiredBytes = px.toLong() * estLongEdge * 4L
        val maxHeap = Runtime.getRuntime().maxMemory()
        if (requiredBytes > maxHeap / 2L) {
            resolutionError = "Resolution requires ${StudioFormatters.formatFileSize(requiredBytes)} RAM per frame, exceeding device memory limits."
            return@LaunchedEffect
        }
        if (sourceCroppedShortEdge > 0 && px > sourceCroppedShortEdge) {
            resolutionError = "Resolution exceeds original video dimensions (${sourceCroppedShortEdge} px). Upscaling is not supported."
            return@LaunchedEffect
        }
        resolutionError = null
        delay(220L)
        resUserEdited = false
        onUpdateSettings {
            it.copy(
                resolutionPreset = ResolutionPreset.CUSTOM,
                customResolutionShortEdge = px
            )
        }
    }

    var customFpsText by remember {
        mutableStateOf(formatFpsDisplay(displayedFpsValue))
    }
    var fpsFocused by remember { mutableStateOf(false) }
    var fpsUserEdited by remember { mutableStateOf(false) }
    LaunchedEffect(settings.useOriginalFps, settings.fps, sourceFps, fpsFocused) {
        if (!fpsFocused && !fpsUserEdited) {
            val target = formatFpsDisplay(displayedFpsValue)
            if (customFpsText != target) {
                customFpsText = target
            }
        }
    }
    LaunchedEffect(customFpsText, fpsUserEdited) {
        if (!fpsUserEdited) return@LaunchedEffect
        val parsed = customFpsText.toFloatOrNull()
        if (parsed != null && parsed in 1f..30f) {
            delay(220L)
            fpsUserEdited = false
            onUpdateSettings { it.copy(useOriginalFps = false, fps = parsed) }
        }
    }

    var customSpeedText by remember {
        mutableStateOf(String.format(java.util.Locale.US, "%.2f", settings.speed))
    }
    var speedFocused by remember { mutableStateOf(false) }
    var speedUserEdited by remember { mutableStateOf(false) }
    LaunchedEffect(settings.speed, speedFocused) {
        if (!speedFocused && !speedUserEdited) {
            val parsed = customSpeedText.toFloatOrNull()
            if (parsed == null || abs(parsed - settings.speed) > 0.001f) {
                customSpeedText = String.format(java.util.Locale.US, "%.2f", settings.speed)
            }
        }
    }
    LaunchedEffect(customSpeedText, speedUserEdited) {
        if (!speedUserEdited) return@LaunchedEffect
        val spd = customSpeedText.toFloatOrNull()
        if (spd != null && spd in 0.1f..4.0f) {
            delay(220L)
            speedUserEdited = false
            onUpdateSettings { it.copy(speed = spd) }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // A. Resolution
        SectionHeader(
            title = "Resolution",
            icon = Icons.Rounded.HighQuality,
            accent = AccentLavender,
            trailingBadge = "${outputDims.width} × ${outputDims.height} px"
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                ResolutionPreset.ORIGINAL,
                ResolutionPreset.P240,
                ResolutionPreset.P360,
                ResolutionPreset.P480,
                ResolutionPreset.CUSTOM
            ).forEach { preset ->
                StudioSelectableChip(
                    label = preset.label,
                    selected = settings.resolutionPreset == preset,
                    onClick = {
                        haptics.perform(StudioHapticEvent.SELECTION)
                        resolutionError = null
                        onUpdateSettings { s ->
                            val nextCustom = preset.shortEdgePx ?: s.customResolutionShortEdge
                            val safeCustom = if (sourceCroppedShortEdge > 0) {
                                nextCustom.coerceIn(2, sourceCroppedShortEdge)
                            } else {
                                nextCustom.coerceAtLeast(2)
                            }
                            s.copy(
                                resolutionPreset = preset,
                                customResolutionShortEdge = safeCustom
                            )
                        }
                    },
                    modifier = Modifier.testTag("res_chip_${preset.name.lowercase()}")
                )
            }
        }

        val sliderMax = maxOf(sourceCroppedShortEdge, settings.customResolutionShortEdge, 128)
        val sliderMin = minOf(120, sliderMax - 1).coerceAtLeast(2).toFloat()
        val rawCurrentRes = when (settings.resolutionPreset) {
            ResolutionPreset.ORIGINAL -> sourceCroppedShortEdge
            ResolutionPreset.CUSTOM -> settings.customResolutionShortEdge
            else -> settings.resolutionPreset.shortEdgePx ?: 360
        }
        val sliderCurrentRes = rawCurrentRes.coerceIn(sliderMin.roundToInt(), sliderMax)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Slider(
                value = sliderCurrentRes.toFloat(),
                onValueChange = { v ->
                    val rounded = (v.roundToInt() / 8 * 8).coerceIn(sliderMin.roundToInt(), sliderMax)
                    resDetents.onValueChanged(rounded.toFloat(), haptics)
                    customResText = rounded.toString()
                    resolutionError = null
                    resUserEdited = false
                    onUpdateSettings {
                        it.copy(
                            resolutionPreset = ResolutionPreset.CUSTOM,
                            customResolutionShortEdge = rounded
                        )
                    }
                },
                onValueChangeFinished = { resDetents.onValueChangeFinished(haptics) },
                valueRange = sliderMin..sliderMax.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.surfaceContainerHighest
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("resolution_slider")
            )
            StudioTextField(
                value = customResText,
                onValueChange = { txt ->
                    customResText = txt.filter(Char::isDigit)
                    resUserEdited = true
                },
                label = "Short px",
                keyboardType = KeyboardType.Number,
                onDone = { focusManager.clearFocus() },
                modifier = Modifier
                    .width(96.dp)
                    .onFocusChanged { resFocused = it.isFocused }
                    .testTag("resolution_custom_input")
            )
        }
        if (resolutionError != null) {
            Text(
                text = resolutionError!!,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .testTag("resolution_error_text")
            )
        }

        // D. Frame Rate (FPS)
        val fpsBadge = if (settings.useOriginalFps) {
            if (sourceFps != null) "Original · ${formatFpsDisplay(sourceFps)} FPS"
            else "Original (Source)"
        } else {
            "${formatFpsDisplay(settings.fps)} FPS"
        }

        SectionHeader(
            title = "Frame Rate (FPS)",
            icon = Icons.Rounded.Videocam,
            accent = AccentBlue,
            trailingBadge = fpsBadge
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StudioSelectableChip(
                label = if (sourceFps != null) {
                    "Original (${formatFpsDisplay(sourceFps)} FPS)"
                } else {
                    "Original (Source)"
                },
                leadingIcon = Icons.Rounded.AutoAwesome,
                selected = settings.useOriginalFps,
                onClick = {
                    haptics.perform(StudioHapticEvent.SELECTION)
                    onUpdateSettings { it.copy(useOriginalFps = true) }
                },
                modifier = Modifier.testTag("fps_chip_original")
            )
            GifConversionSettings.FPS_PRESETS.forEach { presetFps ->
                StudioSelectableChip(
                    label = "${presetFps.toInt()} FPS",
                    selected = !settings.useOriginalFps && abs(settings.fps - presetFps) < 0.2f,
                    onClick = {
                        haptics.perform(StudioHapticEvent.SELECTION)
                        onUpdateSettings { it.copy(useOriginalFps = false, fps = presetFps) }
                    },
                    modifier = Modifier.testTag("fps_chip_${presetFps.toInt()}")
                )
            }
        }

        // Source FPS detection & format timing note
        val isWebp = settings.outputFormat == OutputFormat.WEBP
        val fpsInfoText = when {
            sourceFps != null && settings.useOriginalFps && isWebp ->
                "Detected source: ${formatFpsDisplay(sourceFps)} FPS${if (isVariableFrameRate) " (VFR)" else ""}. Animated WebP preserves millisecond-accurate frame timestamps."
            sourceFps != null && settings.useOriginalFps && isVariableFrameRate ->
                "Detected source: ${formatFpsDisplay(sourceFps)} FPS (VFR). Preserves frame timestamps using GIF's 10ms centisecond timing (min 20ms/frame)."
            sourceFps != null && settings.useOriginalFps && sourceFps > 50f ->
                "Detected source: ${formatFpsDisplay(sourceFps)} FPS. GIF viewers cap reliable timing at 50 FPS (20ms/frame) using 10ms centisecond steps."
            sourceFps != null && settings.useOriginalFps ->
                "Detected source: ${formatFpsDisplay(sourceFps)} FPS. Preserved as closely as GIF's 10ms centisecond frame delays allow."
            sourceFps != null ->
                "Manual override (${formatFpsDisplay(settings.fps)} FPS) · Detected source: ${formatFpsDisplay(sourceFps)} FPS${if (isVariableFrameRate) " (VFR)" else ""}."
            settings.useOriginalFps && isWebp ->
                "Uses each clip's detected frame rate with millisecond-accurate Animated WebP frame durations."
            settings.useOriginalFps ->
                "Uses each video's detected frame rate, approximated to GIF's 10ms centisecond frame delays (min 20ms/frame)."
            else ->
                "Manual override (${formatFpsDisplay(settings.fps)} FPS) applied to every clip."
        }
        Text(
            text = fpsInfoText,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.testTag("fps_source_info")
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Slider(
                value = displayedFpsValue.coerceIn(1f, 30f),
                onValueChange = { v ->
                    val rounded = v.roundToInt().toFloat().coerceIn(1f, 30f)
                    fpsDetents.onValueChanged(rounded, haptics)
                    customFpsText = rounded.toInt().toString()
                    onUpdateSettings { it.copy(useOriginalFps = false, fps = rounded) }
                },
                onValueChangeFinished = { fpsDetents.onValueChangeFinished(haptics) },
                valueRange = 1f..30f,
                colors = SliderDefaults.colors(
                    thumbColor = colors.secondary,
                    activeTrackColor = colors.secondary,
                    inactiveTrackColor = colors.surfaceContainerHighest
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("fps_slider")
            )
            StudioTextField(
                value = customFpsText,
                onValueChange = { txt ->
                    customFpsText = txt
                    fpsUserEdited = true
                },
                label = "FPS",
                keyboardType = KeyboardType.Decimal,
                onDone = { focusManager.clearFocus() },
                modifier = Modifier
                    .width(86.dp)
                    .onFocusChanged { fpsFocused = it.isFocused }
                    .testTag("fps_custom_input")
            )
        }

        // E. Playback Speed
        SectionHeader(
            title = "Playback Speed",
            icon = Icons.Rounded.Speed,
            accent = AccentMint,
            trailingBadge = "${String.format(java.util.Locale.US, "%.2f", settings.speed)}×"
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GifConversionSettings.SPEED_PRESETS.forEach { presetSpeed ->
                val label = if (presetSpeed % 1f == 0f) "${presetSpeed.toInt()}×"
                else "${presetSpeed}×"
                StudioSelectableChip(
                    label = label,
                    selected = abs(settings.speed - presetSpeed) < 0.02f,
                    onClick = {
                        haptics.perform(StudioHapticEvent.SELECTION)
                        onUpdateSettings { it.copy(speed = presetSpeed) }
                    },
                    modifier = Modifier.testTag("speed_chip_${(presetSpeed * 100).toInt()}")
                )
            }
        }

        StudioTextField(
            value = customSpeedText,
            onValueChange = { txt ->
                customSpeedText = txt
                speedUserEdited = true
            },
            label = "Custom speed multiplier (0.1 – 4.0×)",
            keyboardType = KeyboardType.Decimal,
            onDone = { focusManager.clearFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { speedFocused = it.isFocused }
                .testTag("speed_custom_input")
        )

        // F & G. Reverse Playback + Ping-Pong Loop
        SectionHeader(
            title = "Direction & Loop",
            icon = Icons.Rounded.Repeat,
            accent = AccentAmber
        )

        StudioToggleCard(
            title = "Reverse Playback",
            subtitle = "Play all frames backwards from end to start",
            icon = Icons.Rounded.FastRewind,
            accent = AccentCoral,
            checked = settings.reverse,
            onCheckedChange = { checked ->
                haptics.perform(StudioHapticEvent.SELECTION)
                onUpdateSettings { it.copy(reverse = checked) }
            },
            modifier = Modifier.testTag("reverse_toggle")
        )

        StudioToggleCard(
            title = "Ping-Pong Loop",
            subtitle = "Forward then smooth reverse without duplicate turnaround frames",
            icon = Icons.Rounded.Repeat,
            accent = AccentAmber,
            checked = settings.pingPong,
            onCheckedChange = { checked ->
                haptics.perform(StudioHapticEvent.SELECTION)
                onUpdateSettings { it.copy(pingPong = checked) }
            },
            modifier = Modifier.testTag("pingpong_toggle")
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorAndDitherPanel(
    settings: GifConversionSettings,
    resolvedColorCount: Int?,
    onUpdateSettings: ((GifConversionSettings) -> GifConversionSettings) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    showFormatSelector: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val haptics = LocalStudioHaptics.current
    val reducedMotion = rememberReducedMotionEnabled()

    var customColorText by remember {
        mutableStateOf(settings.customColorCount.toString())
    }
    var colorFocused by remember { mutableStateOf(false) }
    var colorUserEdited by remember { mutableStateOf(false) }

    var webpQualityText by remember {
        mutableStateOf(settings.resolvedWebpQuality.toString())
    }
    var webpQualityFocused by remember { mutableStateOf(false) }
    var webpQualityUserEdited by remember { mutableStateOf(false) }
    val webpDetents = remember {
        SliderHapticDetentTracker(stepInterval = 5f, snapValues = listOf(52f, 75f, 88f, 100f))
    }

    LaunchedEffect(settings.colorQualityPreset, settings.customColorCount, colorFocused) {
        if (!colorFocused && !colorUserEdited) {
            val target = (settings.colorQualityPreset.colorCount ?: settings.customColorCount).toString()
            if (customColorText != target) {
                customColorText = target
            }
        }
    }
    LaunchedEffect(customColorText, colorUserEdited) {
        if (!colorUserEdited) return@LaunchedEffect
        val count = customColorText.toIntOrNull()
        if (count != null && count in 2..256) {
            delay(220L)
            colorUserEdited = false
            onUpdateSettings {
                it.copy(
                    colorQualityPreset = ColorQualityPreset.CUSTOM,
                    customColorCount = count
                )
            }
        }
    }

    LaunchedEffect(settings.webpQualityPreset, settings.webpQuality, webpQualityFocused) {
        if (!webpQualityFocused && !webpQualityUserEdited) {
            val target = settings.resolvedWebpQuality.toString()
            if (webpQualityText != target) {
                webpQualityText = target
            }
        }
    }
    LaunchedEffect(webpQualityText, webpQualityUserEdited) {
        if (!webpQualityUserEdited) return@LaunchedEffect
        val q = webpQualityText.toIntOrNull()
        if (q != null && q in 0..100) {
            delay(220L)
            webpQualityUserEdited = false
            onUpdateSettings {
                it.copy(
                    webpQualityPreset = WebPQualityPreset.CUSTOM,
                    webpQuality = q
                )
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        if (showFormatSelector) {
            SectionHeader(
                title = "Export Format",
                icon = Icons.Rounded.Image,
                accent = AccentLavender,
                trailingBadge = settings.outputFormat.shortLabel
            )
            OutputFormatSelector(
                selectedFormat = settings.outputFormat,
                onSelectFormat = { format ->
                    onUpdateSettings { it.copy(outputFormat = format) }
                }
            )
        }

        AnimatedContent(
            targetState = settings.outputFormat,
            transitionSpec = {
                fadeIn(animationSpec = studioTween(160, reducedMotion)) togetherWith
                    fadeOut(animationSpec = studioTween(120, reducedMotion))
            },
            label = "formatSpecificControls"
        ) { format ->
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (format == OutputFormat.GIF) {
                    // GIF-specific controls: Color Palette (2–256) & Dithering
                    val colorBadge = when (settings.colorQualityPreset) {
                        ColorQualityPreset.AUTO -> "Auto (${resolvedColorCount ?: 128}c)"
                        ColorQualityPreset.CUSTOM -> "${settings.customColorCount} colors"
                        else -> "${settings.colorQualityPreset.colorCount} colors"
                    }
                    SectionHeader(
                        title = "Color Palette (2–256)",
                        icon = Icons.Rounded.ColorLens,
                        accent = AccentLavender,
                        trailingBadge = colorBadge
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            ColorQualityPreset.AUTO,
                            ColorQualityPreset.C16,
                            ColorQualityPreset.C32,
                            ColorQualityPreset.C64,
                            ColorQualityPreset.C96,
                            ColorQualityPreset.C128,
                            ColorQualityPreset.C192,
                            ColorQualityPreset.C256
                        ).forEach { preset ->
                            StudioSelectableChip(
                                label = if (preset == ColorQualityPreset.AUTO) "Auto" else "${preset.label}c",
                                leadingIcon = if (preset == ColorQualityPreset.AUTO) Icons.Rounded.AutoAwesome else null,
                                selected = settings.colorQualityPreset == preset,
                                onClick = {
                                    haptics.perform(StudioHapticEvent.SELECTION)
                                    onUpdateSettings { s ->
                                        s.copy(
                                            colorQualityPreset = preset,
                                            customColorCount = preset.colorCount ?: s.customColorCount
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("color_chip_${preset.name.lowercase()}")
                            )
                        }
                    }

                    StudioTextField(
                        value = customColorText,
                        onValueChange = { txt ->
                            customColorText = txt.filter(Char::isDigit)
                            colorUserEdited = true
                        },
                        label = "Custom palette size (2 – 256 colors)",
                        keyboardType = KeyboardType.Number,
                        onDone = { focusManager.clearFocus() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { colorFocused = it.isFocused }
                            .testTag("color_custom_input")
                    )

                    // Dithering
                    SectionHeader(
                        title = "Dithering",
                        icon = Icons.Rounded.Gradient,
                        accent = AccentBlue,
                        trailingBadge = settings.ditherMode.label
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DitherMode.entries.forEach { mode ->
                            val selected = settings.ditherMode == mode
                            val shape = RoundedCornerShape(16.dp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(
                                        if (selected) colors.primaryContainer.copy(alpha = 0.55f)
                                        else colors.surfaceContainer
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                                        shape = shape
                                    )
                                    .clickable {
                                        haptics.perform(StudioHapticEvent.SELECTION)
                                        onUpdateSettings { it.copy(ditherMode = mode) }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("dither_option_${mode.name.lowercase()}")
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) colors.onPrimaryContainer else colors.onSurface
                                    )
                                    Text(
                                        text = mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (selected) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(colors.primary)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = colors.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Animated WebP-specific quality controls (cleanly separate from GIF palette/dither)
                    val qualityBadge = if (settings.isWebpLossless) {
                        "Lossless"
                    } else {
                        "${settings.webpQualityPreset.shortLabel} · ${settings.resolvedWebpQuality}"
                    }
                    SectionHeader(
                        title = "WebP Quality Mode",
                        icon = Icons.Rounded.HighQuality,
                        accent = AccentLavender,
                        trailingBadge = qualityBadge
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            WebPQualityPreset.LOSSLESS,
                            WebPQualityPreset.HIGH,
                            WebPQualityPreset.BALANCED,
                            WebPQualityPreset.SMALLER
                        ).forEach { preset ->
                            val selected = settings.webpQualityPreset == preset
                            val shape = RoundedCornerShape(16.dp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(
                                        if (selected) colors.primaryContainer.copy(alpha = 0.55f)
                                        else colors.surfaceContainer
                                    )
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) colors.primary else colors.outlineVariant.copy(alpha = 0.6f),
                                        shape = shape
                                    )
                                    .clickable {
                                        haptics.perform(StudioHapticEvent.SELECTION)
                                        onUpdateSettings {
                                            it.copy(
                                                webpQualityPreset = preset,
                                                webpQuality = preset.defaultQuality
                                            )
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .testTag("webp_preset_${preset.name.lowercase()}")
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (preset.isLossless) preset.label else "${preset.label} (${preset.defaultQuality})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) colors.onPrimaryContainer else colors.onSurface
                                    )
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant
                                    )
                                }
                                if (selected) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(colors.primary)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "Selected",
                                            tint = colors.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SectionHeader(
                        title = "Custom Quality (0–100)",
                        icon = Icons.Rounded.Tune,
                        accent = AccentBlue,
                        trailingBadge = if (settings.isWebpLossless) "Lossless" else "${settings.resolvedWebpQuality} / 100"
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Slider(
                            value = settings.resolvedWebpQuality.toFloat().coerceIn(0f, 100f),
                            onValueChange = { v ->
                                val q = v.roundToInt().coerceIn(0, 100)
                                webpDetents.onValueChanged(q.toFloat(), haptics)
                                webpQualityText = q.toString()
                                webpQualityUserEdited = false
                                onUpdateSettings {
                                    it.copy(
                                        webpQualityPreset = WebPQualityPreset.CUSTOM,
                                        webpQuality = q
                                    )
                                }
                            },
                            onValueChangeFinished = { webpDetents.onValueChangeFinished(haptics) },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.primary,
                                activeTrackColor = colors.primary,
                                inactiveTrackColor = colors.surfaceContainerHighest
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("webp_quality_slider")
                        )
                        StudioTextField(
                            value = webpQualityText,
                            onValueChange = { txt ->
                                webpQualityText = txt.filter(Char::isDigit).take(3)
                                webpQualityUserEdited = true
                            },
                            label = "Quality",
                            keyboardType = KeyboardType.Number,
                            onDone = { focusManager.clearFocus() },
                            modifier = Modifier
                                .width(88.dp)
                                .onFocusChanged { webpQualityFocused = it.isFocused }
                                .testTag("webp_quality_input")
                        )
                    }

                    Text(
                        text = "Encoded offline with libwebp WebPAnimEncoder. Full 24-bit color and alpha transparency are preserved automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        StudioPillButton(
            text = "Reset All Settings to Default",
            icon = Icons.Rounded.RestartAlt,
            variant = StudioButtonVariant.OUTLINED,
            onClick = {
                haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                onResetDefaults()
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reset_defaults_button")
        )
    }
}

@Composable
private fun StudioToggleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.onSurfaceVariant,
                uncheckedTrackColor = colors.surfaceContainerHighest
            )
        )
    }
}

@Composable
private fun StudioTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        isError = isError,
        textStyle = MonoSpecStyle.copy(color = colors.onSurface),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary,
            unfocusedBorderColor = colors.outlineVariant,
            focusedContainerColor = colors.surfaceContainer,
            unfocusedContainerColor = colors.surfaceContainerLow
        ),
        modifier = modifier
    )
}
