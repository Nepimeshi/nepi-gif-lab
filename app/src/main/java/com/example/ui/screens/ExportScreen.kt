package com.example.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.example.engine.OutputFormat
import com.example.engine.StudioFormatters
import com.example.engine.WebPQualityPreset
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.StudioAsyncThumbnail
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioIconButton
import com.example.ui.components.StudioPillButton
import com.example.ui.components.rememberReducedMotionEnabled
import com.example.ui.components.studioTween
import com.example.ui.theme.AccentMint
import com.example.ui.theme.MonoSpecStyle
import com.example.ui.theme.MonoTimestampStyle
import com.example.viewmodel.ExportUiState
import kotlin.math.roundToInt

@Composable
fun ExportScreen(
    state: ExportUiState,
    onCancelExport: () -> Unit,
    onRetryExport: () -> Unit,
    onShareGif: (Uri) -> Unit,
    onOpenGif: (Uri) -> Unit,
    onDone: () -> Unit,
    onBackToEditor: () -> Unit,
    modifier: Modifier = Modifier,
    outputFormat: OutputFormat = OutputFormat.GIF
) {
    BackHandler {
        if (state is ExportUiState.Converting) {
            onCancelExport()
        } else {
            onBackToEditor()
        }
    }

    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotionEnabled()
    val haptics = LocalStudioHaptics.current
    val effectiveFormat = (state as? ExportUiState.Success)?.result?.stats?.outputFormat ?: outputFormat

    val gifImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
    DisposableEffect(gifImageLoader) {
        onDispose { gifImageLoader.shutdown() }
    }

    val statePhase = when (state) {
        is ExportUiState.Idle -> "idle"
        is ExportUiState.Converting -> "converting"
        is ExportUiState.Success -> "success"
        is ExportUiState.Cancelled -> "cancelled"
        is ExportUiState.Error -> "error"
    }

    LaunchedEffect(statePhase) {
        when (statePhase) {
            "success" -> haptics.perform(StudioHapticEvent.EXPORT_SUCCESS)
            "cancelled" -> haptics.perform(StudioHapticEvent.CONVERSION_CANCEL)
            "error" -> haptics.perform(StudioHapticEvent.ERROR)
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Top Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            StudioIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = {
                    if (state is ExportUiState.Converting) onCancelExport() else onBackToEditor()
                }
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (effectiveFormat == OutputFormat.WEBP) "Export Animated WebP" else "Export GIF",
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground
            )
        }

        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            AnimatedContent(
                targetState = statePhase,
                transitionSpec = {
                    val enter = fadeIn(animationSpec = studioTween(240, reducedMotion)) +
                        if (reducedMotion) fadeIn(studioTween(0, true))
                        else scaleIn(initialScale = 0.97f, animationSpec = studioTween(240, false))
                    val exit = fadeOut(animationSpec = studioTween(160, reducedMotion))
                    enter togetherWith exit
                },
                label = "exportPhaseTransition",
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
            ) { phase ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    when (phase) {
                        "idle" -> {
                            Text(
                                text = "Ready to export.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant
                            )
                        }

                        "converting" -> {
                            val converting = state as? ExportUiState.Converting ?: return@Column
                            val progress = converting.progress
                            val clampedTarget = progress.percent.coerceIn(0f, 0.99f)
                            val animatedProgress by animateFloatAsState(
                                targetValue = clampedTarget,
                                animationSpec = studioTween(220, reducedMotion),
                                label = "exportProgress"
                            )
                            val pctInt = (animatedProgress * 100f).roundToInt().coerceIn(0, 99)

                            Spacer(Modifier.height(32.dp))

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(164.dp)
                            ) {
                                if (progress.isIndeterminate) {
                                    CircularProgressIndicator(
                                        strokeWidth = 10.dp,
                                        color = colors.primary,
                                        trackColor = colors.surfaceContainerHighest,
                                        strokeCap = StrokeCap.Round,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("export_indeterminate_progress")
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = if (clampedTarget > 0f) "$pctInt%" else "Working…",
                                            style = if (clampedTarget > 0f) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                                            color = colors.onBackground
                                        )
                                        if (progress.totalFrames > 0) {
                                            Text(
                                                text = "${progress.currentFrame}/${progress.totalFrames}",
                                                style = MonoSpecStyle,
                                                color = colors.secondary
                                            )
                                        }
                                    }
                                } else {
                                    CircularProgressIndicator(
                                        progress = { animatedProgress },
                                        strokeWidth = 10.dp,
                                        color = colors.primary,
                                        trackColor = colors.surfaceContainerHighest,
                                        strokeCap = StrokeCap.Round,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "$pctInt%",
                                            style = MaterialTheme.typography.displaySmall,
                                            color = colors.onBackground,
                                            modifier = Modifier.testTag("export_progress_percent")
                                        )
                                        if (progress.totalFrames > 0) {
                                            Text(
                                                text = "${progress.currentFrame}/${progress.totalFrames}",
                                                style = MonoSpecStyle,
                                                color = colors.secondary
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedContent(
                                targetState = progress.stage,
                                transitionSpec = {
                                    fadeIn(animationSpec = studioTween(160, reducedMotion)) togetherWith
                                        fadeOut(animationSpec = studioTween(120, reducedMotion))
                                },
                                label = "exportStageText"
                            ) { stageText ->
                                Text(
                                    text = stageText,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = colors.onSurface,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("export_progress_stage")
                                )
                            }

                            if (progress.isIndeterminate) {
                                LinearProgressIndicator(
                                    color = colors.primary,
                                    trackColor = colors.surfaceContainerHighest,
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    color = colors.primary,
                                    trackColor = colors.surfaceContainerHighest,
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }

                            Text(
                                text = "Encoding offline on your device. Keeping memory usage low frame by frame.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(Modifier.height(12.dp))

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StudioPillButton(
                                    text = "Cancel Conversion",
                                    icon = Icons.Rounded.Cancel,
                                    variant = StudioButtonVariant.DANGER,
                                    onClick = {
                                        haptics.perform(StudioHapticEvent.CONVERSION_CANCEL)
                                        onCancelExport()
                                    },
                                    modifier = Modifier.testTag("cancel_export_button")
                                )
                            }
                        }

                        "success" -> {
                            val success = state as? ExportUiState.Success ?: return@Column
                            val saved = success.result
                            val stats = saved.stats
                            var revealCheck by remember(saved.contentUri) { mutableStateOf(reducedMotion) }
                            LaunchedEffect(saved.contentUri) {
                                revealCheck = true
                            }
                            val checkScale by animateFloatAsState(
                                targetValue = if (revealCheck) 1f else 0.65f,
                                animationSpec = if (reducedMotion) {
                                    studioTween(0, true)
                                } else {
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                },
                                label = "successCheckScale"
                            )

                            // Success confirmation banner
                            val bannerShape = RoundedCornerShape(18.dp)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(bannerShape)
                                    .background(AccentMint.copy(alpha = 0.14f))
                                    .border(1.dp, AccentMint.copy(alpha = 0.45f), bannerShape)
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                                    .testTag("export_success_banner")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = AccentMint,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .scale(checkScale)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (stats.outputFormat == OutputFormat.WEBP) {
                                            "Animated WebP Saved to Gallery"
                                        } else {
                                            "GIF Saved to Gallery"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.onSurface
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Folder,
                                            contentDescription = null,
                                            tint = colors.onSurfaceVariant,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "${saved.relativeFolder}/${saved.fileName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Real animated final preview rendered via Coil animated decoder with shimmer loading
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(270.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFF0C0B10))
                                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(22.dp))
                            ) {
                                StudioAsyncThumbnail(
                                    model = saved.contentUri,
                                    imageLoader = gifImageLoader,
                                    contentDescription = if (stats.outputFormat == OutputFormat.WEBP) {
                                        "Exported animated WebP preview"
                                    } else {
                                        "Exported animated GIF preview"
                                    },
                                    contentScale = ContentScale.Fit,
                                    crossfadeEnabled = false,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(8.dp)
                                )
                                Surface(
                                    color = colors.surface.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = if (stats.outputFormat == OutputFormat.WEBP) "FINAL WEBP" else "FINAL GIF89a",
                                        style = MonoSpecStyle,
                                        color = colors.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Actual Output Specs Grid
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ExportStatTile(
                                        label = "Dimensions",
                                        value = "${stats.width} × ${stats.height}",
                                        modifier = Modifier.weight(1f)
                                    )
                                    ExportStatTile(
                                        label = "Actual Size",
                                        value = StudioFormatters.formatFileSize(stats.fileSizeBytes),
                                        highlight = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ExportStatTile(
                                        label = "Frame Rate",
                                        value = "${String.format(java.util.Locale.US, "%.1f", stats.fps)} FPS",
                                        modifier = Modifier.weight(1f)
                                    )
                                    ExportStatTile(
                                        label = "Duration",
                                        value = "${StudioFormatters.formatDurationShort(stats.durationMs)} (${stats.frameCount}f)",
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (stats.outputFormat == OutputFormat.WEBP) {
                                        val webpQualityStr = when {
                                            stats.webpQualityPreset == WebPQualityPreset.LOSSLESS -> "Lossless"
                                            stats.webpQualityPreset != null && stats.webpQuality != null ->
                                                "${stats.webpQualityPreset.shortLabel} (${stats.webpQuality})"
                                            stats.webpQuality != null -> "Quality ${stats.webpQuality}"
                                            else -> "Balanced"
                                        }
                                        ExportStatTile(
                                            label = "WebP Quality",
                                            value = webpQualityStr,
                                            modifier = Modifier.weight(1f)
                                        )
                                        ExportStatTile(
                                            label = "Transparency",
                                            value = if (stats.hasTransparency) "Alpha preserved" else "Opaque RGB",
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        ExportStatTile(
                                            label = "Color Palette",
                                            value = "${stats.effectiveColorCount} colors",
                                            modifier = Modifier.weight(1f)
                                        )
                                        ExportStatTile(
                                            label = "Dithering",
                                            value = stats.ditherMode.label,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                if (success.estimatedBytes != null && success.estimatedBytes > 0L) {
                                    Text(
                                        text = "Estimated before export: ${StudioFormatters.formatFileSize(success.estimatedBytes)} · Actual: ${StudioFormatters.formatFileSize(stats.fileSizeBytes)}",
                                        style = MonoSpecStyle,
                                        color = colors.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // Primary Share & Open Actions
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StudioPillButton(
                                    text = if (stats.outputFormat == OutputFormat.WEBP) "Share WebP" else "Share GIF",
                                    icon = Icons.Rounded.Share,
                                    variant = StudioButtonVariant.PRIMARY,
                                    onClick = {
                                        haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                                        onShareGif(saved.contentUri)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("export_share_button")
                                )
                                StudioPillButton(
                                    text = "Open Viewer",
                                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                    variant = StudioButtonVariant.TONAL,
                                    onClick = {
                                        haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                                        onOpenGif(saved.contentUri)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("export_open_button")
                                )
                            }

                            if (stats.outputFormat == OutputFormat.WEBP) {
                                Text(
                                    text = "Saved as a ${stats.frameCount}-frame Animated WebP. Some stock gallery apps show WebP files statically—open in Chrome, Fossify Gallery, or another animated WebP viewer to play externally.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("export_webp_viewer_note")
                                )
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StudioPillButton(
                                    text = "Done, Back to Home",
                                    variant = StudioButtonVariant.OUTLINED,
                                    onClick = onDone,
                                    modifier = Modifier.testTag("export_done_button")
                                )
                            }
                        }

                        "cancelled" -> {
                            Spacer(Modifier.height(40.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceContainerHigh)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Cancel,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = "Conversion Cancelled",
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.onBackground
                            )
                            Text(
                                text = "No incomplete files were saved to your gallery.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StudioPillButton(
                                    text = "Back to Editor",
                                    variant = StudioButtonVariant.TONAL,
                                    onClick = onBackToEditor,
                                    modifier = Modifier.weight(1f)
                                )
                                StudioPillButton(
                                    text = "Export Again",
                                    icon = Icons.Rounded.Refresh,
                                    variant = StudioButtonVariant.PRIMARY,
                                    onClick = onRetryExport,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        "error" -> {
                            val err = state as? ExportUiState.Error ?: return@Column
                            Spacer(Modifier.height(40.dp))
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(colors.errorContainer)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = colors.error,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = "Export Failed",
                                style = MaterialTheme.typography.headlineSmall,
                                color = colors.onBackground
                            )
                            Text(
                                text = err.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                StudioPillButton(
                                    text = "Back to Editor",
                                    variant = StudioButtonVariant.TONAL,
                                    onClick = onBackToEditor,
                                    modifier = Modifier.weight(1f)
                                )
                                StudioPillButton(
                                    text = "Try Again",
                                    icon = Icons.Rounded.Refresh,
                                    variant = StudioButtonVariant.PRIMARY,
                                    onClick = onRetryExport,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .clip(shape)
            .background(if (highlight) colors.primaryContainer.copy(alpha = 0.45f) else colors.surfaceContainer)
            .border(
                width = 1.dp,
                color = if (highlight) colors.primary.copy(alpha = 0.6f) else colors.outlineVariant.copy(alpha = 0.55f),
                shape = shape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
        Text(
            text = value,
            style = MonoTimestampStyle.copy(fontWeight = FontWeight.SemiBold),
            color = if (highlight) colors.primary else colors.onSurface
        )
    }
}
