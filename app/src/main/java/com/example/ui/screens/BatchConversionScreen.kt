package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConversionPresetEntity
import com.example.engine.BatchItemEstimateState
import com.example.engine.BatchTotalSizeEstimate
import com.example.engine.ColorQualityPreset
import com.example.engine.GifConversionSettings
import com.example.engine.MediaInputType
import com.example.engine.OutputFormat
import com.example.engine.ResolutionPreset
import com.example.engine.StudioFormatters
import com.example.engine.WebPQualityPreset
import com.example.ui.components.ColorAndDitherPanel
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.PresetQuickBar
import com.example.ui.components.ResolutionAndTimingPanel
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioDashedButton
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioIconButton
import com.example.ui.components.StudioLoadingPill
import com.example.ui.components.StudioPillButton
import com.example.ui.components.rememberReducedMotionEnabled
import com.example.ui.components.studioTween
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentMint
import com.example.ui.theme.MonoSpecStyle
import com.example.viewmodel.BatchItemStatus
import com.example.viewmodel.BatchQueueItem
import com.example.viewmodel.BatchUiState
import com.example.viewmodel.computeBatchTotalEstimate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConversionScreen(
    state: BatchUiState,
    onBack: () -> Unit,
    onAddVideos: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onClearQueue: () -> Unit,
    onUpdateSharedSettings: ((GifConversionSettings) -> GifConversionSettings) -> Unit,
    onResetDefaults: () -> Unit,
    onStartBatch: () -> Unit,
    onCancelBatch: () -> Unit,
    onOpenGif: (Uri) -> Unit,
    onShareGif: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    presets: List<ConversionPresetEntity> = emptyList(),
    onApplyPresetToBatch: (ConversionPresetEntity) -> Unit = {},
    onSaveBatchPreset: (String) -> Unit = {},
    onRenamePreset: (Long, String) -> Unit = { _, _ -> },
    onDeletePreset: (Long) -> Unit = {},
    onMoveItemUp: (String) -> Unit = {},
    onMoveItemDown: (String) -> Unit = {},
    onRetryItem: (String) -> Unit = {},
    onCancelItem: (String) -> Unit = {},
    onRemoveCompleted: () -> Unit = {},
    onSetItemCustomSettings: (String, GifConversionSettings?) -> Unit = { _, _ -> }
) {
    BackHandler {
        // Navigating back while batch is running keeps background WorkManager queue alive!
        onBack()
    }

    val colors = MaterialTheme.colorScheme
    var showSettingsSheet by remember { mutableStateOf(false) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var editingItemSettings by remember { mutableStateOf<GifConversionSettings?>(null) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            StudioIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Batch Conversion",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onBackground
                )
                Text(
                    text = if (state.completedCount > 0) {
                        "${state.items.size} item(s) in queue · ${state.completedCount} done"
                    } else {
                        "${state.items.size} item(s) in queue"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            if (state.completedCount > 0) {
                StudioIconButton(
                    icon = Icons.Rounded.DoneAll,
                    contentDescription = "Remove completed items",
                    onClick = onRemoveCompleted,
                    modifier = Modifier.testTag("batch_clear_completed_button")
                )
                Spacer(Modifier.width(4.dp))
            }
            if (!state.isRunning) {
                if (state.items.isNotEmpty()) {
                    StudioIconButton(
                        icon = Icons.Rounded.DeleteSweep,
                        contentDescription = "Clear batch queue",
                        onClick = onClearQueue,
                        modifier = Modifier.testTag("batch_clear_button")
                    )
                    Spacer(Modifier.width(6.dp))
                }
                StudioPillButton(
                    text = "Settings",
                    icon = Icons.Rounded.Tune,
                    variant = StudioButtonVariant.TONAL,
                    onClick = { showSettingsSheet = true },
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("batch_settings_button")
                )
            }
        }

        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 680.dp)
            ) {
                // Shared settings summary card
                item {
                    val s = state.sharedSettings
                    val shape = RoundedCornerShape(18.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(colors.surfaceContainer)
                            .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Shared Batch Settings",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = summarizeBatchSettings(s),
                                style = MonoSpecStyle,
                                color = colors.primary
                            )
                        }
                    }
                }

                // Queue preparation indicator when importing metadata/thumbnails
                item {
                    val reducedMotion = rememberReducedMotionEnabled()
                    androidx.compose.animation.AnimatedVisibility(
                        visible = state.isPreparingQueue,
                        enter = fadeIn(studioTween(180, reducedMotion)) + expandVertically(studioTween(180, reducedMotion)),
                        exit = fadeOut(studioTween(140, reducedMotion)) + shrinkVertically(studioTween(140, reducedMotion))
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            StudioLoadingPill(
                                text = "Preparing queue items…",
                                modifier = Modifier.testTag("batch_preparing_queue")
                            )
                        }
                    }
                }

                // Overall Queue Progress Card
                if (state.isRunning || state.completedCount > 0 || state.failedCount > 0) {
                    item {
                        val reducedMotion = rememberReducedMotionEnabled()
                        val targetOverall = if (state.isRunning) {
                            state.overallProgress.coerceIn(0f, 0.99f)
                        } else {
                            state.overallProgress.coerceIn(0f, 1f)
                        }
                        val animatedOverall by animateFloatAsState(
                            targetValue = targetOverall,
                            animationSpec = studioTween(220, reducedMotion),
                            label = "batchOverallProgress"
                        )
                        val isOverallIndeterminate = state.isRunning &&
                            state.completedCount == 0 &&
                            state.failedCount == 0 &&
                            state.overallProgress <= 0f &&
                            state.items.any { it.status == BatchItemStatus.PROCESSING && it.isIndeterminate }
                        val overallPctInt = if (state.isRunning) {
                            (animatedOverall * 100f).roundToInt().coerceIn(0, 99)
                        } else {
                            (animatedOverall * 100f).roundToInt().coerceIn(0, 100)
                        }
                        val shape = RoundedCornerShape(18.dp)
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(colors.surfaceContainerHigh)
                                .padding(14.dp)
                                .testTag("batch_overall_progress")
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (state.isRunning) "Converting Queue…" else "Batch Summary",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = if (isOverallIndeterminate) {
                                        "Preparing… (${state.completedCount}/${state.items.size})"
                                    } else {
                                        "$overallPctInt% (${state.completedCount}/${state.items.size})"
                                    },
                                    style = MonoSpecStyle,
                                    color = colors.primary
                                )
                            }
                            if (isOverallIndeterminate) {
                                LinearProgressIndicator(
                                    color = colors.primary,
                                    trackColor = colors.surfaceContainerHighest,
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            } else {
                                LinearProgressIndicator(
                                    progress = { animatedOverall },
                                    color = colors.primary,
                                    trackColor = colors.surfaceContainerHighest,
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(7.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                            }
                        }
                    }
                }

                // Add More Videos or GIFs dashed button
                item {
                    StudioDashedButton(
                        text = if (state.items.isEmpty()) "Select Videos or GIFs" else "Add More Videos or GIFs",
                        icon = Icons.Rounded.Add,
                        accentColor = colors.secondary,
                        onClick = onAddVideos,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("batch_add_videos_button")
                    )
                }

                if (state.items.isEmpty()) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VideoLibrary,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Queue is empty",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Pick videos, animated GIFs, or a mix of both to convert with shared or per-item settings and save each separately to Pictures/Nepi GIF Lab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                        val canMoveUp = index > 0 &&
                            item.status != BatchItemStatus.PROCESSING &&
                            state.items[index - 1].status != BatchItemStatus.PROCESSING
                        val canMoveDown = index < state.items.lastIndex &&
                            item.status != BatchItemStatus.PROCESSING &&
                            state.items[index + 1].status != BatchItemStatus.PROCESSING

                        BatchQueueRow(
                            item = item,
                            canRemove = item.status != BatchItemStatus.PROCESSING,
                            canMoveUp = canMoveUp,
                            canMoveDown = canMoveDown,
                            onMoveUp = { onMoveItemUp(item.id) },
                            onMoveDown = { onMoveItemDown(item.id) },
                            onRemove = { onRemoveItem(item.id) },
                            onRetry = { onRetryItem(item.id) },
                            onCancelItem = { onCancelItem(item.id) },
                            onEditSettings = {
                                editingItemId = item.id
                                editingItemSettings = item.customSettings ?: state.sharedSettings
                            },
                            onOpen = { item.savedResult?.contentUri?.let(onOpenGif) },
                            onShare = { item.savedResult?.contentUri?.let(onShareGif) }
                        )
                    }
                }
            }
        }

        // Bottom Action Bar with Prominent Total Batch Size Estimate
        Surface(
            color = colors.surfaceContainer,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            val effectiveTotalEstimate = remember(state.items, state.totalSizeEstimate) {
                if (state.totalSizeEstimate.totalEstimatableCount > 0 || state.totalSizeEstimate.completedCount > 0) {
                    state.totalSizeEstimate
                } else {
                    computeBatchTotalEstimate(state.items)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                if (state.items.isNotEmpty() && !state.isRunning) {
                    BatchTotalEstimateBanner(
                        totalEstimate = effectiveTotalEstimate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 620.dp)
                    )
                }
                val haptics = LocalStudioHaptics.current
                if (state.isRunning) {
                    StudioPillButton(
                        text = "Cancel Queue",
                        icon = Icons.Rounded.Cancel,
                        variant = StudioButtonVariant.DANGER,
                        onClick = {
                            haptics.perform(StudioHapticEvent.CONVERSION_CANCEL)
                            onCancelBatch()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 620.dp)
                            .testTag("batch_cancel_button")
                    )
                } else {
                    val actionableCount = state.items.count { it.status != BatchItemStatus.COMPLETED }
                    StudioPillButton(
                        text = if (actionableCount > 0) "Convert All ($actionableCount)" else "All Completed",
                        icon = Icons.Rounded.PlayArrow,
                        variant = StudioButtonVariant.PRIMARY,
                        enabled = actionableCount > 0,
                        onClick = {
                            haptics.perform(StudioHapticEvent.CONVERSION_START)
                            onStartBatch()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 620.dp)
                            .testTag("batch_start_button")
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState,
            containerColor = colors.surface
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Shared Batch Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface
                )
                PresetQuickBar(
                    presets = presets,
                    currentSettings = state.sharedSettings,
                    onApplyPreset = onApplyPresetToBatch,
                    onSavePreset = onSaveBatchPreset,
                    onRenamePreset = onRenamePreset,
                    onDeletePreset = onDeletePreset,
                    onResetDefaults = onResetDefaults
                )
                val sampleItem = state.items.firstOrNull { it.width > 0 && it.height > 0 }
                ResolutionAndTimingPanel(
                    sourceWidth = sampleItem?.width ?: 1080,
                    sourceHeight = sampleItem?.height ?: 1920,
                    settings = state.sharedSettings,
                    onUpdateSettings = onUpdateSharedSettings
                )
                ColorAndDitherPanel(
                    settings = state.sharedSettings,
                    resolvedColorCount = state.sharedSettings.resolvedColorCount,
                    onUpdateSettings = onUpdateSharedSettings,
                    onResetDefaults = onResetDefaults
                )
                StudioPillButton(
                    text = "Done",
                    variant = StudioButtonVariant.PRIMARY,
                    onClick = { showSettingsSheet = false },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    val targetItemId = editingItemId
    val currentPerItem = editingItemSettings
    val targetItem = state.items.firstOrNull { it.id == targetItemId }
    if (targetItemId != null && currentPerItem != null && targetItem != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editingItemId = null },
            sheetState = sheetState,
            containerColor = colors.surface
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Item Override · ${targetItem.displayName}",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ResolutionAndTimingPanel(
                    sourceWidth = targetItem.width.takeIf { it > 0 } ?: 1080,
                    sourceHeight = targetItem.height.takeIf { it > 0 } ?: 1920,
                    settings = currentPerItem,
                    onUpdateSettings = { transform ->
                        editingItemSettings = transform(currentPerItem).sanitized()
                    }
                )
                ColorAndDitherPanel(
                    settings = currentPerItem,
                    resolvedColorCount = currentPerItem.resolvedColorCount,
                    onUpdateSettings = { transform ->
                        editingItemSettings = transform(currentPerItem).sanitized()
                    },
                    onResetDefaults = { editingItemSettings = state.sharedSettings }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioPillButton(
                        text = "Use Shared",
                        variant = StudioButtonVariant.OUTLINED,
                        onClick = {
                            onSetItemCustomSettings(targetItemId, null)
                            editingItemId = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                    StudioPillButton(
                        text = "Save Override",
                        variant = StudioButtonVariant.PRIMARY,
                        onClick = {
                            onSetItemCustomSettings(targetItemId, currentPerItem)
                            editingItemId = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

private fun summarizeBatchSettings(s: GifConversionSettings): String {
    val resSummary = if (s.resolutionPreset == ResolutionPreset.CUSTOM) {
        "${s.customResolutionShortEdge}p"
    } else {
        s.resolutionPreset.label
    }
    val extras = buildList {
        if (s.reverse) add("Reverse")
        if (s.pingPong) add("Ping-Pong")
    }.joinToString(" · ")
    val fpsSummary = if (s.useOriginalFps) "Original FPS" else "${s.fps.roundToInt()} FPS"
    return buildString {
        if (s.outputFormat == OutputFormat.WEBP) {
            val webpQuality = if (s.isWebpLossless) {
                "Lossless"
            } else {
                "${s.webpQualityPreset.shortLabel} (${s.resolvedWebpQuality})"
            }
            append("WebP · $resSummary · $fpsSummary · ${s.speed}× · $webpQuality")
        } else {
            val colorSummary = when (s.colorQualityPreset) {
                ColorQualityPreset.AUTO -> "Auto colors"
                ColorQualityPreset.CUSTOM -> "${s.customColorCount} colors"
                else -> "${s.colorQualityPreset.colorCount} colors"
            }
            append("$resSummary · $fpsSummary · ${s.speed}× · $colorSummary · ${s.ditherMode.label}")
        }
        if (extras.isNotEmpty()) append(" · $extras")
    }
}

@Composable
private fun BatchQueueRow(
    item: BatchQueueItem,
    canRemove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    onCancelItem: () -> Unit,
    onEditSettings: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    val shape = RoundedCornerShape(18.dp)

    val borderColor by animateColorAsState(
        targetValue = when (item.status) {
            BatchItemStatus.PROCESSING -> colors.primary.copy(alpha = 0.65f)
            BatchItemStatus.COMPLETED -> AccentMint.copy(alpha = 0.55f)
            BatchItemStatus.FAILED -> colors.error.copy(alpha = 0.6f)
            BatchItemStatus.CANCELLED -> AccentAmber.copy(alpha = 0.5f)
            BatchItemStatus.QUEUED -> colors.outlineVariant.copy(alpha = 0.55f)
        },
        animationSpec = studioTween(220, reducedMotion),
        label = "batchRowBorder"
    )
    val containerColor by animateColorAsState(
        targetValue = when (item.status) {
            BatchItemStatus.PROCESSING -> colors.primaryContainer.copy(alpha = 0.22f)
            BatchItemStatus.COMPLETED -> AccentMint.copy(alpha = 0.08f)
            BatchItemStatus.FAILED -> colors.errorContainer.copy(alpha = 0.16f)
            else -> colors.surfaceContainer
        },
        animationSpec = studioTween(220, reducedMotion),
        label = "batchRowContainer"
    )
    val animatedItemProgress by animateFloatAsState(
        targetValue = item.progress.coerceIn(0f, 0.99f),
        animationSpec = studioTween(200, reducedMotion),
        label = "batchItemProgress"
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .padding(12.dp)
            .testTag("batch_item_${item.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHighest)
            ) {
                val bmp = item.thumbnail
                if (bmp != null && !bmp.isRecycled) {
                    val img = remember(bmp) { bmp.asImageBitmap() }
                    Image(
                        bitmap = img,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (item.inputType == MediaInputType.GIF) Icons.Rounded.GifBox else Icons.Rounded.VideoLibrary,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant
                    )
                }
                if (item.inputType == MediaInputType.GIF) {
                    Surface(
                        color = colors.surface.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(5.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                    ) {
                        Text(
                            text = "GIF",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.customSettings != null) {
                        Surface(
                            color = colors.primaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Custom",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                if (item.durationMs > 0L && item.estimateState is BatchItemEstimateState.Idle && item.savedResult == null) {
                    Text(
                        text = "${item.width}×${item.height} · ${StudioFormatters.formatDurationShort(item.durationMs)}",
                        style = MonoSpecStyle,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = item.stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (item.status) {
                        BatchItemStatus.COMPLETED -> AccentMint
                        BatchItemStatus.FAILED -> colors.error
                        BatchItemStatus.PROCESSING -> colors.primary
                        BatchItemStatus.CANCELLED -> AccentAmber
                        BatchItemStatus.QUEUED -> colors.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedContent(
                targetState = item.status,
                transitionSpec = {
                    fadeIn(studioTween(180, reducedMotion)) togetherWith
                        fadeOut(studioTween(140, reducedMotion))
                },
                label = "batchStatusTrailing"
            ) { status ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when (status) {
                        BatchItemStatus.PROCESSING -> {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = colors.primary,
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier
                                    .size(18.dp)
                                    .testTag("batch_item_processing_${item.id}")
                            )
                            Spacer(Modifier.width(4.dp))
                            StudioIconButton(
                                icon = Icons.Rounded.StopCircle,
                                contentDescription = "Cancel item",
                                onClick = onCancelItem,
                                tint = colors.error,
                                size = 34.dp,
                                modifier = Modifier.testTag("batch_item_cancel_${item.id}")
                            )
                        }
                        BatchItemStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = "Completed",
                                tint = AccentMint,
                                modifier = Modifier.size(18.dp)
                            )
                            StudioIconButton(
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open GIF",
                                onClick = onOpen,
                                size = 34.dp
                            )
                            StudioIconButton(
                                icon = Icons.Rounded.Share,
                                contentDescription = "Share GIF",
                                onClick = onShare,
                                tint = colors.primary,
                                size = 34.dp
                            )
                            if (canRemove) {
                                StudioIconButton(
                                    icon = Icons.Rounded.Close,
                                    contentDescription = "Remove",
                                    onClick = onRemove,
                                    size = 32.dp
                                )
                            }
                        }
                        BatchItemStatus.FAILED -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Failed",
                                tint = colors.error,
                                modifier = Modifier.size(18.dp)
                            )
                            StudioIconButton(
                                icon = Icons.Rounded.Refresh,
                                contentDescription = "Retry item",
                                onClick = onRetry,
                                tint = colors.primary,
                                size = 34.dp,
                                modifier = Modifier.testTag("batch_item_retry_${item.id}")
                            )
                            if (canRemove) {
                                StudioIconButton(
                                    icon = Icons.Rounded.Close,
                                    contentDescription = "Remove",
                                    onClick = onRemove,
                                    size = 32.dp
                                )
                            }
                        }
                        BatchItemStatus.CANCELLED -> {
                            StudioIconButton(
                                icon = Icons.Rounded.Refresh,
                                contentDescription = "Retry item",
                                onClick = onRetry,
                                tint = colors.primary,
                                size = 34.dp,
                                modifier = Modifier.testTag("batch_item_retry_${item.id}")
                            )
                            if (canRemove) {
                                StudioIconButton(
                                    icon = Icons.Rounded.Close,
                                    contentDescription = "Remove",
                                    onClick = onRemove,
                                    size = 32.dp
                                )
                            }
                        }
                        BatchItemStatus.QUEUED -> {
                            if (canMoveUp) {
                                StudioIconButton(
                                    icon = Icons.Rounded.ArrowUpward,
                                    contentDescription = "Move up",
                                    onClick = onMoveUp,
                                    size = 30.dp,
                                    modifier = Modifier.testTag("batch_item_up_${item.id}")
                                )
                            }
                            if (canMoveDown) {
                                StudioIconButton(
                                    icon = Icons.Rounded.ArrowDownward,
                                    contentDescription = "Move down",
                                    onClick = onMoveDown,
                                    size = 30.dp,
                                    modifier = Modifier.testTag("batch_item_down_${item.id}")
                                )
                            }
                            StudioIconButton(
                                icon = Icons.Rounded.Tune,
                                contentDescription = "Item settings",
                                onClick = onEditSettings,
                                tint = if (item.customSettings != null) colors.primary else colors.onSurfaceVariant,
                                size = 30.dp,
                                modifier = Modifier.testTag("batch_item_settings_${item.id}")
                            )
                            if (canRemove) {
                                StudioIconButton(
                                    icon = Icons.Rounded.Close,
                                    contentDescription = "Remove",
                                    onClick = onRemove,
                                    size = 30.dp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Full-width compact specs & output size footer (never squeezed by trailing action icons)
        val completedResult = item.savedResult
        val showEstimateStrip = (item.status == BatchItemStatus.COMPLETED && completedResult != null) ||
            item.estimateState !is BatchItemEstimateState.Idle
        if (showEstimateStrip) {
            Column(
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surfaceContainerHighest.copy(alpha = 0.52f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (item.status == BatchItemStatus.COMPLETED && completedResult != null) {
                    val stats = completedResult.stats
                    val qualityPart = if (stats.outputFormat == OutputFormat.WEBP) {
                        val qLabel = when {
                            stats.webpQualityPreset == WebPQualityPreset.LOSSLESS -> "Lossless"
                            stats.webpQualityPreset != null && stats.webpQuality != null ->
                                "${stats.webpQualityPreset.shortLabel} (${stats.webpQuality})"
                            else -> "WebP"
                        }
                        "WebP $qLabel"
                    } else {
                        "${stats.effectiveColorCount} colors"
                    }
                    Text(
                        text = "${stats.width}×${stats.height} · ${formatEffectiveFps(stats.fps)} · $qualityPart",
                        style = MonoSpecStyle,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val priorEstimate = (item.estimateState as? BatchItemEstimateState.Ready)?.estimate?.estimatedBytes
                    val sizeLine = if (priorEstimate != null && priorEstimate > 0L) {
                        "Actual ${StudioFormatters.formatFileSize(stats.fileSizeBytes)} (est. ~${StudioFormatters.formatFileSize(priorEstimate)})"
                    } else {
                        "Actual ${StudioFormatters.formatFileSize(stats.fileSizeBytes)}"
                    }
                    Text(
                        text = sizeLine,
                        style = MonoSpecStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("batch_item_actual_size_${item.id}")
                    )
                } else {
                    when (val estState = item.estimateState) {
                        is BatchItemEstimateState.Ready -> {
                            val est = estState.estimate
                            val qualityPart = if (est.outputFormat == OutputFormat.WEBP) {
                                val qLabel = when {
                                    est.webpQualityPreset == WebPQualityPreset.LOSSLESS -> "Lossless"
                                    est.webpQualityPreset != null && est.webpQuality != null ->
                                        "${est.webpQualityPreset.shortLabel} (${est.webpQuality})"
                                    est.webpQuality != null -> "Q${est.webpQuality}"
                                    else -> "Balanced"
                                }
                                "WebP $qLabel"
                            } else {
                                "${est.resolvedColorCount} colors"
                            }
                            Text(
                                text = "${est.outputWidth}×${est.outputHeight} · ${formatEffectiveFps(est.effectiveFps)} · $qualityPart",
                                style = MonoSpecStyle,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.testTag("batch_item_estimate_${item.id}")
                            ) {
                                Text(
                                    text = "Est. ~${StudioFormatters.formatFileSize(est.estimatedBytes)}",
                                    style = MonoSpecStyle.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.primary,
                                    maxLines = 1
                                )
                                Text(
                                    text = "(approx.)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        BatchItemEstimateState.Estimating -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("batch_item_estimate_${item.id}")
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 1.6.dp,
                                    color = colors.primary,
                                    strokeCap = StrokeCap.Round,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = "Estimating…",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = colors.primary,
                                    maxLines = 1
                                )
                                if (item.durationMs > 0L) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "${item.width}×${item.height} · ${StudioFormatters.formatDurationShort(item.durationMs)}",
                                        style = MonoSpecStyle,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        is BatchItemEstimateState.Unavailable -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Estimate unavailable",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = AccentAmber,
                                    maxLines = 1,
                                    modifier = Modifier.testTag("batch_item_estimate_${item.id}")
                                )
                                if (item.durationMs > 0L) {
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        text = "${item.width}×${item.height} · ${StudioFormatters.formatDurationShort(item.durationMs)}",
                                        style = MonoSpecStyle,
                                        color = colors.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        BatchItemEstimateState.Idle -> Unit
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = item.status == BatchItemStatus.PROCESSING,
            enter = fadeIn(studioTween(160, reducedMotion)) + expandVertically(studioTween(160, reducedMotion)),
            exit = fadeOut(studioTween(140, reducedMotion)) + shrinkVertically(studioTween(140, reducedMotion))
        ) {
            if (item.isIndeterminate) {
                LinearProgressIndicator(
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .testTag("batch_item_indeterminate_${item.id}")
                )
            } else {
                LinearProgressIndicator(
                    progress = { animatedItemProgress },
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

@Composable
private fun BatchTotalEstimateBanner(
    totalEstimate: BatchTotalSizeEstimate,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val allCompleted = totalEstimate.totalEstimatableCount == 0 && totalEstimate.completedCount > 0
    val borderColor = when {
        allCompleted -> AccentMint.copy(alpha = 0.42f)
        totalEstimate.hasAnyEstimate || totalEstimate.isEstimating -> colors.primary.copy(alpha = 0.36f)
        else -> AccentAmber.copy(alpha = 0.40f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainerHigh)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("batch_total_estimate")
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (allCompleted) "Total Exported Size" else "Estimated Total Output",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!allCompleted) {
                    Surface(
                        color = colors.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Approx.",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
            }

            val subtitle = when {
                allCompleted -> "${totalEstimate.completedCount} item(s) exported"
                totalEstimate.isEstimating && totalEstimate.readyItemCount > 0 ->
                    "Estimating ${totalEstimate.readyItemCount}/${totalEstimate.totalEstimatableCount} item(s)…"
                totalEstimate.isEstimating ->
                    "Calculating estimates for ${totalEstimate.totalEstimatableCount} item(s)…"
                totalEstimate.unavailableCount > 0 && totalEstimate.hasAnyEstimate ->
                    "${totalEstimate.readyItemCount}/${totalEstimate.totalEstimatableCount} estimated · ${totalEstimate.unavailableCount} unavailable"
                !totalEstimate.hasAnyEstimate ->
                    "Estimates unavailable for queued items"
                else ->
                    "${totalEstimate.readyItemCount} item(s) · Sum of individual estimates"
            }

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!allCompleted && totalEstimate.completedCount > 0 && totalEstimate.actualCompletedBytes > 0L) {
                Text(
                    text = "Already exported: ${StudioFormatters.formatFileSize(totalEstimate.actualCompletedBytes)} (${totalEstimate.completedCount} done)",
                    style = MonoSpecStyle,
                    color = AccentMint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("batch_total_actual_completed")
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            when {
                allCompleted -> {
                    Text(
                        text = StudioFormatters.formatFileSize(totalEstimate.actualCompletedBytes),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = AccentMint,
                        maxLines = 1,
                        modifier = Modifier.testTag("batch_total_estimate_value")
                    )
                }
                totalEstimate.hasAnyEstimate -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (totalEstimate.isEstimating) {
                            CircularProgressIndicator(
                                strokeWidth = 1.8.dp,
                                color = colors.primary,
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Text(
                            text = "~${StudioFormatters.formatFileSize(totalEstimate.totalEstimatedBytes)}",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = colors.primary,
                            maxLines = 1,
                            modifier = Modifier.testTag("batch_total_estimate_value")
                        )
                    }
                }
                totalEstimate.isEstimating -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 1.8.dp,
                            color = colors.primary,
                            strokeCap = StrokeCap.Round,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Estimating…",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.primary,
                            maxLines = 1,
                            modifier = Modifier.testTag("batch_total_estimate_value")
                        )
                    }
                }
                else -> {
                    Text(
                        text = "Unavailable",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = AccentAmber,
                        maxLines = 1,
                        modifier = Modifier.testTag("batch_total_estimate_value")
                    )
                }
            }
        }
    }
}

private fun formatEffectiveFps(fps: Float): String {
    if (!fps.isFinite() || fps <= 0f) return "—"
    val rounded = fps.roundToInt()
    return if (abs(fps - rounded) < 0.05f) {
        "$rounded FPS"
    } else {
        String.format(Locale.US, "%.1f FPS", fps)
    }
}

