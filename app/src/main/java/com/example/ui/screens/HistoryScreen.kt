package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConversionHistoryEntity
import com.example.data.ConversionHistorySnapshot
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.StudioFormatters
import com.example.engine.WebPQualityPreset
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.StudioAsyncThumbnail
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioIconButton
import com.example.ui.components.StudioPillButton
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.AccentMint
import com.example.ui.theme.MonoSpecStyle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    items: List<ConversionHistoryEntity>,
    onBack: () -> Unit,
    onOpenGif: (Uri) -> Unit,
    onShareGif: (Uri) -> Unit,
    onDeleteItem: (ConversionHistoryEntity, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onOpenMedia: ((Uri, String) -> Unit)? = null,
    onShareMedia: ((Uri, String) -> Unit)? = null
) {
    BackHandler(onBack = onBack)

    val colors = MaterialTheme.colorScheme
    val haptics = LocalStudioHaptics.current
    var pendingDelete by remember { mutableStateOf<ConversionHistoryEntity?>(null) }
    var deleteGalleryCopy by remember { mutableStateOf(false) }
    var inspectedEntity by remember { mutableStateOf<ConversionHistoryEntity?>(null) }

    fun openEntity(entity: ConversionHistoryEntity) {
        val uri = Uri.parse(entity.contentUri)
        val mime = entity.parsedOutputFormat.mimeType
        if (onOpenMedia != null) onOpenMedia(uri, mime) else onOpenGif(uri)
    }

    fun shareEntity(entity: ConversionHistoryEntity) {
        val uri = Uri.parse(entity.contentUri)
        val mime = entity.parsedOutputFormat.mimeType
        if (onShareMedia != null) onShareMedia(uri, mime) else onShareGif(uri)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            StudioIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                modifier = Modifier.testTag("history_back_button")
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Conversion History",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground
                )
                Text(
                    text = "${items.size} saved export(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        if (items.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GifBox,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Text(
                        text = "No conversion history yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Every GIF and Animated WebP you export is saved to Pictures/Nepi GIF Lab and recorded here with its exact conversion settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
            ) {
                items(items, key = { it.id }) { entity ->
                    HistoryItemCard(
                        entity = entity,
                        onOpen = {
                            haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                            openEntity(entity)
                        },
                        onShare = {
                            haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                            shareEntity(entity)
                        },
                        onViewDetails = {
                            haptics.perform(StudioHapticEvent.SELECTION)
                            inspectedEntity = entity
                        },
                        onDelete = {
                            haptics.perform(StudioHapticEvent.SELECTION)
                            deleteGalleryCopy = false
                            pendingDelete = entity
                        }
                    )
                }
            }
        }
    }

    inspectedEntity?.let { entity ->
        HistoryDetailsBottomSheet(
            entity = entity,
            onOpen = { openEntity(entity) },
            onShare = { shareEntity(entity) },
            onDismiss = { inspectedEntity = null }
        )
    }

    pendingDelete?.let { target ->
        val fmtLabel = target.parsedOutputFormat.shortLabel
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.surfaceContainerHigh,
            title = { Text("Delete History Entry?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Remove \"${target.fileName}\" from your conversion history?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { deleteGalleryCopy = !deleteGalleryCopy }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = deleteGalleryCopy,
                            onCheckedChange = { deleteGalleryCopy = it }
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Also delete $fmtLabel file from Gallery",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteItem(target, deleteGalleryCopy)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistoryItemCard(
    entity: ConversionHistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onViewDetails: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val snapshot = remember(entity.id, entity.settingsSnapshotJson) { entity.parsedSnapshot() }
    val dateStr = remember(entity.createdAtMillis) {
        SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US).format(Date(entity.createdAtMillis))
    }

    val directionLabel = snapshot?.directionLabel ?: run {
        val src = if (entity.parsedSourceInputType == MediaInputType.GIF) "GIF" else "Video"
        "$src → ${entity.parsedOutputFormat.shortLabel}"
    }

    val (summaryLine1, summaryLine2) = remember(entity, snapshot) {
        if (snapshot != null) {
            snapshot.compactSummaryLines()
        } else {
            val dims = "${entity.width} × ${entity.height}"
            val fps = "${ConversionHistorySnapshot.formatFpsCompact(entity.fps)} FPS"
            val size = StudioFormatters.formatFileSize(entity.fileSizeBytes)
            if (entity.parsedOutputFormat == OutputFormat.WEBP) {
                "$dims · ${entity.ditherModeName} · $fps" to size
            } else {
                "$dims · $fps · ${entity.colorCount} colors" to "${entity.ditherModeName} · $size"
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
            .clickable(onClick = onOpen)
            .padding(14.dp)
            .testTag("history_card_${entity.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val thumbModel: Any = entity.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
                ?: Uri.parse(entity.contentUri)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceContainerHighest)
            ) {
                StudioAsyncThumbnail(
                    model = thumbModel,
                    contentDescription = entity.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = colors.surface.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                ) {
                    Text(
                        text = entity.parsedOutputFormat.shortLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entity.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        color = colors.primaryContainer.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(7.dp)
                    ) {
                        Text(
                            text = directionLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summaryLine1,
                    style = MonoSpecStyle,
                    color = colors.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summaryLine2,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = colors.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onViewDetails)
                    .testTag("history_details_${entity.id}")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = "View Details",
                        tint = colors.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "View Details",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StudioIconButton(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "Open ${entity.parsedOutputFormat.shortLabel}",
                    onClick = onOpen,
                    size = 38.dp
                )
                Spacer(Modifier.width(6.dp))
                StudioIconButton(
                    icon = Icons.Rounded.Share,
                    contentDescription = "Share ${entity.parsedOutputFormat.shortLabel}",
                    onClick = onShare,
                    tint = colors.primary,
                    size = 38.dp
                )
                Spacer(Modifier.width(6.dp))
                StudioIconButton(
                    icon = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete entry",
                    onClick = onDelete,
                    tint = colors.error,
                    size = 38.dp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDetailsBottomSheet(
    entity: ConversionHistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snapshot = remember(entity.id, entity.settingsSnapshotJson) { entity.parsedSnapshot() }
    val dateStr = remember(entity.createdAtMillis) {
        SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US).format(Date(entity.createdAtMillis))
    }
    val fmt = snapshot?.outputFormat ?: entity.parsedOutputFormat

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("history_details_sheet")
        ) {
            // Sheet Title & Direction Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.fileName,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Exported $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Surface(
                    color = colors.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    val dir = snapshot?.directionLabel
                        ?: "${if (entity.parsedSourceInputType == MediaInputType.GIF) "GIF" else "Video"} → ${fmt.shortLabel}"
                    Text(
                        text = dir,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            if (snapshot == null) {
                // Legacy history record created before snapshot storage
                val noticeShape = RoundedCornerShape(14.dp)
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(noticeShape)
                        .background(AccentAmber.copy(alpha = 0.12f))
                        .border(1.dp, AccentAmber.copy(alpha = 0.4f), noticeShape)
                        .padding(14.dp)
                        .testTag("history_settings_unavailable")
                ) {
                    Text(
                        text = "Settings unavailable",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface
                    )
                    Text(
                        text = "This conversion was recorded by an earlier version before full settings snapshots were stored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }

                DetailSectionCard(
                    title = "Source Information",
                    icon = Icons.Rounded.Movie,
                    accent = AccentBlue,
                    rows = listOf(
                        "File Name" to entity.sourceVideoName.ifBlank { "Unavailable" },
                        "Input Type" to if (entity.parsedSourceInputType == MediaInputType.GIF) "Animated GIF" else "Video"
                    )
                )

                DetailSectionCard(
                    title = "Output Information",
                    icon = Icons.Rounded.HighQuality,
                    accent = AccentLavender,
                    rows = listOf(
                        "Format" to fmt.label,
                        "Dimensions" to "${entity.width} × ${entity.height} px",
                        "Frame Rate" to "${ConversionHistorySnapshot.formatFpsCompact(entity.fps)} FPS",
                        "Duration" to StudioFormatters.formatDurationShort(entity.durationMs),
                        "Frame Count" to "${entity.frameCount} frames",
                        "File Size" to StudioFormatters.formatFileSize(entity.fileSizeBytes),
                        "Colors / Mode" to if (fmt == OutputFormat.WEBP) {
                            entity.ditherModeName
                        } else {
                            "${entity.colorCount} colors · ${entity.ditherModeName}"
                        }
                    )
                )
            } else {
                // Section 1: Source Information
                val sourceFpsText = when {
                    snapshot.sourceFps != null && snapshot.sourceIsVariableTiming ->
                        "${ConversionHistorySnapshot.formatFpsCompact(snapshot.sourceFps)} FPS (Variable)"
                    snapshot.sourceFps != null ->
                        "${ConversionHistorySnapshot.formatFpsCompact(snapshot.sourceFps)} FPS"
                    else -> "Not available"
                }
                DetailSectionCard(
                    title = "Source Information",
                    icon = Icons.Rounded.Movie,
                    accent = AccentBlue,
                    rows = listOf(
                        "Source File" to snapshot.sourceFileName.ifBlank { entity.sourceVideoName },
                        "Source Type" to if (snapshot.sourceType == MediaInputType.GIF) "Animated GIF" else "Video",
                        "Source Dimensions" to "${snapshot.sourceWidth} × ${snapshot.sourceHeight} px",
                        "Source Duration" to StudioFormatters.formatDurationShort(snapshot.sourceDurationMs),
                        "Source Frame Rate" to sourceFpsText
                    )
                )

                // Section 2: Output Information
                DetailSectionCard(
                    title = "Output Information",
                    icon = Icons.Rounded.HighQuality,
                    accent = AccentLavender,
                    rows = listOf(
                        "Output Format" to snapshot.outputFormat.label,
                        "Output Dimensions" to "${snapshot.outputWidth} × ${snapshot.outputHeight} px",
                        "Actual FPS" to "${ConversionHistorySnapshot.formatFpsCompact(snapshot.outputFps)} FPS",
                        "Output Duration" to StudioFormatters.formatDurationShort(snapshot.outputDurationMs),
                        "File Size" to StudioFormatters.formatFileSize(snapshot.outputFileSizeBytes),
                        "Frame Count" to "${snapshot.outputFrameCount} frames",
                        "Transparency" to if (snapshot.outputHasTransparency) "Preserved (Alpha)" else "Opaque"
                    )
                )

                // Section 3: Resolution and Timing
                val resModeLabel = if (snapshot.resolutionPreset == ResolutionPreset.CUSTOM &&
                    snapshot.customResolutionShortEdge != null
                ) {
                    "Custom (${snapshot.customResolutionShortEdge}px short edge)"
                } else {
                    snapshot.resolutionPreset.label
                }
                val fpsModeLabel = if (snapshot.useOriginalFps) {
                    val detected = snapshot.detectedSourceFps ?: snapshot.sourceFps
                    if (detected != null) {
                        "Original FPS (${ConversionHistorySnapshot.formatFpsCompact(detected)} FPS)"
                    } else {
                        "Original FPS"
                    }
                } else {
                    val target = snapshot.manualFps ?: snapshot.outputFps
                    "Manual (${ConversionHistorySnapshot.formatFpsCompact(target)} FPS)"
                }
                DetailSectionCard(
                    title = "Resolution & Timing",
                    icon = Icons.Rounded.Tune,
                    accent = AccentMint,
                    rows = listOf(
                        "Resolution Mode" to resModeLabel,
                        "Frame Rate Mode" to fpsModeLabel
                    )
                )

                // Section 4: Color and Quality (format-specific only)
                val colorQualityRows = if (snapshot.outputFormat == OutputFormat.WEBP) {
                    val preset = snapshot.webpQualityPreset ?: WebPQualityPreset.BALANCED
                    val lossless = snapshot.webpLossless == true || preset == WebPQualityPreset.LOSSLESS
                    val numericQuality = snapshot.webpQuality ?: preset.defaultQuality
                    listOf(
                        "Compression Mode" to if (lossless) "Lossless" else "Lossy",
                        "WebP Quality Preset" to preset.label,
                        "Quality Level" to if (lossless) "100 (Lossless)" else "$numericQuality / 100"
                    )
                } else {
                    val paletteMode = snapshot.colorQualityPreset?.label ?: "Auto"
                    val effectiveColors = snapshot.effectiveColorCount ?: snapshot.requestedColorCount ?: entity.colorCount
                    val ditherLabel = snapshot.ditherMode?.label ?: entity.ditherModeName
                    val optLabel = if (snapshot.optimizeOutput != false) "Enabled" else "Disabled"
                    buildList {
                        add("Palette Mode" to paletteMode)
                        add("Color Count" to "$effectiveColors colors")
                        add("Dithering" to ditherLabel)
                        add("Frame Optimization" to optLabel)
                    }
                }
                DetailSectionCard(
                    title = "Color & Quality",
                    icon = Icons.Rounded.ColorLens,
                    accent = AccentLavender,
                    rows = colorQualityRows
                )

                // Section 5: Playback and Looping
                val loopLabel = if (snapshot.loopCount <= 0) "Infinite loop" else "${snapshot.loopCount}× loop"
                DetailSectionCard(
                    title = "Playback & Looping",
                    icon = Icons.Rounded.Repeat,
                    accent = AccentAmber,
                    rows = listOf(
                        "Playback Speed" to ConversionHistorySnapshot.formatSpeedShort(snapshot.speed),
                        "Reverse Playback" to if (snapshot.reverse) "Enabled" else "Off",
                        "Ping-Pong Loop" to if (snapshot.pingPong) "Enabled" else "Off",
                        "Loop Count" to loopLabel
                    )
                )

                // Section 6: Crop and Trim
                val isFullCrop = abs(snapshot.cropRect.left - NormalizedCropRect.FULL.left) < 0.005f &&
                    abs(snapshot.cropRect.top - NormalizedCropRect.FULL.top) < 0.005f &&
                    abs(snapshot.cropRect.right - NormalizedCropRect.FULL.right) < 0.005f &&
                    abs(snapshot.cropRect.bottom - NormalizedCropRect.FULL.bottom) < 0.005f
                val cropText = if (isFullCrop) {
                    "Full frame (uncropped)"
                } else {
                    val wPct = ((snapshot.cropRect.right - snapshot.cropRect.left) * 100f).roundToInt()
                    val hPct = ((snapshot.cropRect.bottom - snapshot.cropRect.top) * 100f).roundToInt()
                    "${wPct}% × ${hPct}% region"
                }
                val trimRangeText = if (snapshot.trimEndMs > snapshot.trimStartMs) {
                    "${StudioFormatters.formatTimestamp(snapshot.trimStartMs)} – ${StudioFormatters.formatTimestamp(snapshot.trimEndMs)}"
                } else {
                    "Full clip"
                }
                DetailSectionCard(
                    title = "Crop & Trim",
                    icon = Icons.Rounded.Crop,
                    accent = AccentBlue,
                    rows = listOf(
                        "Aspect Ratio" to snapshot.aspectRatioPreset.label,
                        "Crop Region" to cropText,
                        "Rotation" to "${snapshot.rotationDegrees}°",
                        "Trim Range" to trimRangeText
                    )
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StudioPillButton(
                    text = "Open ${fmt.shortLabel}",
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    variant = StudioButtonVariant.TONAL,
                    onClick = onOpen,
                    modifier = Modifier.weight(1f)
                )
                StudioPillButton(
                    text = "Share ${fmt.shortLabel}",
                    icon = Icons.Rounded.Share,
                    variant = StudioButtonVariant.PRIMARY,
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DetailSectionCard(
    title: String,
    icon: ImageVector,
    accent: Color,
    rows: List<Pair<String, String>>
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
            .padding(14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface
            )
        }

        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.35f))

        rows.forEach { (label, value) ->
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(0.45f)
                )
                Text(
                    text = value,
                    style = MonoSpecStyle.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.55f)
                )
            }
        }
    }
}
