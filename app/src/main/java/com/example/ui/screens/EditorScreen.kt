package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ConversionPresetEntity
import com.example.engine.AspectRatioPreset
import com.example.engine.GifConversionSettings
import com.example.engine.GifGeometryCalculator
import com.example.engine.OutputFormat
import com.example.engine.StudioFormatters
import com.example.ui.components.ColorAndDitherPanel
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.OutputFormatSelector
import com.example.ui.components.PresetQuickBar
import com.example.ui.components.ResolutionAndTimingPanel
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioIconButton
import com.example.ui.components.StudioPillButton
import com.example.ui.components.TargetFileSizeBottomSheet
import com.example.ui.components.TrimAndCropPanel
import com.example.ui.components.VideoPreviewStage
import com.example.ui.components.VideoTimelineEditor
import com.example.ui.components.rememberReducedMotionEnabled
import com.example.ui.components.studioShimmer
import com.example.ui.components.studioTween
import com.example.ui.theme.MonoSpecStyle
import com.example.ui.theme.PlusJakartaSansFamily
import com.example.viewmodel.EditorTab
import com.example.viewmodel.EditorUiState
import com.example.viewmodel.PreviewMode

@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onExportClick: () -> Unit,
    onPreviewModeChange: (PreviewMode) -> Unit,
    onTabChange: (EditorTab) -> Unit,
    onUpdateSettings: ((GifConversionSettings) -> GifConversionSettings) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStepFrame: (Int) -> Unit,
    onApplyAspectRatio: (AspectRatioPreset) -> Unit,
    onRotate90: () -> Unit,
    onToggleCropOverlay: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestOriginalFrame: (suspend (Long, Int) -> Bitmap?)? = null,
    onRetryGifPreview: () -> Unit = {},
    presets: List<ConversionPresetEntity> = emptyList(),
    onApplyPreset: (ConversionPresetEntity) -> Unit = {},
    onSavePreset: (String) -> Unit = {},
    onRenamePreset: (Long, String) -> Unit = { _, _ -> },
    onDeletePreset: (Long) -> Unit = {},
    onAnalyzeTargetSize: (Long) -> Unit = {},
    onAcceptTargetSizeRecommendation: () -> Unit = {},
    onDismissTargetSizeRecommendation: () -> Unit = {},
    onPreviewViewportChanged: (Int) -> Unit = {}
) {
    BackHandler(onBack = onBack)

    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    val haptics = LocalStudioHaptics.current
    var showTargetSizeSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Editor Top Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            StudioIconButton(
                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                onClick = onBack,
                size = 42.dp,
                modifier = Modifier.testTag("editor_back_button")
            )

            // Original / Live Preview segmented pill
            PreviewModeToggle(
                mode = state.previewMode,
                outputFormat = state.settings.outputFormat,
                onModeChange = { nextMode ->
                    if (nextMode != state.previewMode) {
                        haptics.perform(StudioHapticEvent.SELECTION)
                    }
                    onPreviewModeChange(nextMode)
                },
                modifier = Modifier.weight(1f)
            )

            StudioPillButton(
                text = "Export",
                icon = Icons.Rounded.Download,
                variant = StudioButtonVariant.PRIMARY,
                enabled = state.metadata != null && !state.isLoadingVideo,
                onClick = {
                    haptics.perform(StudioHapticEvent.CONVERSION_START)
                    onExportClick()
                },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.testTag("editor_export_button")
            )
        }

        val screenPhase = when {
            state.isLoadingVideo -> "loading"
            state.metadata == null || state.errorMessage != null -> "error"
            else -> "ready"
        }

        AnimatedContent(
            targetState = screenPhase,
            transitionSpec = {
                fadeIn(animationSpec = studioTween(220, reducedMotion)) togetherWith
                    fadeOut(animationSpec = studioTween(160, reducedMotion))
            },
            label = "editorScreenPhase",
            modifier = Modifier.weight(1f)
        ) { phase ->
            when (phase) {
                "loading" -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                            .testTag("editor_loading_state")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            CircularProgressIndicator(color = colors.primary)
                            Text(
                                text = "Analyzing media frames…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.65f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .studioShimmer(enabled = true)
                            )
                        }
                    }
                }

                "error" -> {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = state.errorMessage ?: "Unable to load selected file.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurface
                            )
                            StudioPillButton(
                                text = "Back to Home",
                                variant = StudioButtonVariant.TONAL,
                                onClick = onBack
                            )
                        }
                    }
                }

                else -> {
                    val metadata = state.metadata ?: return@AnimatedContent

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Prominent Estimated File Size Strip + Selected Format Indicator
                        EstimatedSizeBanner(
                            state = state,
                            onTargetSizeClick = {
                                haptics.perform(StudioHapticEvent.SELECTION)
                                showTargetSizeSheet = true
                            },
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                        Spacer(Modifier.height(6.dp))

                        // Clean Export Format Selector (GIF default vs Animated WebP) always visible before export
                        OutputFormatSelector(
                            selectedFormat = state.settings.outputFormat,
                            onSelectFormat = { format ->
                                onUpdateSettings { it.copy(outputFormat = format) }
                            },
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                        Spacer(Modifier.height(8.dp))

                        val schedule = GifGeometryCalculator.buildFrameSchedule(
                            trimStartMs = state.settings.trimStartMs,
                            trimEndMs = state.settings.trimEndMs,
                            videoDurationMs = metadata.durationMs,
                            fps = state.settings.fps,
                            speed = state.settings.speed,
                            reverse = state.settings.reverse,
                            pingPong = state.settings.pingPong,
                            useOriginalFps = state.settings.useOriginalFps,
                            sourceFps = metadata.frameRate,
                            sourceFrameTimestampsUs = metadata.frameTimestampsUs,
                            sourceFrameDelaysMs = metadata.frameDelaysMs
                        )

                        // Responsive Workspace: Two-pane on tablets/expanded screens, smooth stacked workbench on phones
                        BoxWithConstraints(modifier = Modifier.weight(1f)) {
                            val isWideScreen = this.maxWidth >= 760.dp

                            if (isWideScreen) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 14.dp, vertical = 4.dp)
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .fillMaxHeight()
                                    ) {
                                        VideoPreviewStage(
                                            metadata = metadata,
                                            previewMode = state.previewMode,
                                            isPlaying = state.isPlaying,
                                            currentPositionMs = state.currentPositionMs,
                                            trimStartMs = state.settings.trimStartMs,
                                            trimEndMs = state.settings.trimEndMs,
                                            rotationDegrees = state.settings.rotationDegrees,
                                            cropRect = state.settings.cropRect,
                                            aspectRatioPreset = state.settings.aspectRatioPreset,
                                            showCropOverlay = state.isCropOverlayVisible,
                                            ditherMode = state.settings.ditherMode,
                                            liveGifPreview = state.liveGifPreview,
                                            isRenderingGifPreview = state.isRenderingGifPreview,
                                            onPositionTick = onSeek,
                                            onCropChanged = { rect -> onUpdateSettings { it.copy(cropRect = rect) } },
                                            requestOriginalFrame = onRequestOriginalFrame,
                                            gifPreviewError = state.gifPreviewError,
                                            onRetryGifPreview = onRetryGifPreview,
                                            outputFormat = state.settings.outputFormat,
                                            onPreviewViewportChanged = onPreviewViewportChanged,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                        )
                                        VideoTimelineEditor(
                                            durationMs = metadata.durationMs,
                                            trimStartMs = state.settings.trimStartMs,
                                            trimEndMs = state.settings.trimEndMs,
                                            currentPositionMs = state.currentPositionMs,
                                            isPlaying = state.isPlaying,
                                            totalOutputFrames = schedule.frames.size,
                                            outputDurationMs = schedule.totalDurationMs,
                                            filmstrip = state.filmstrip,
                                            isLoadingFilmstrip = state.isLoadingFilmstrip,
                                            outputFormat = state.settings.outputFormat,
                                            onTrimChanged = { s, e -> onUpdateSettings { it.copy(trimStartMs = s, trimEndMs = e) } },
                                            onSeek = onSeek,
                                            onTogglePlay = onTogglePlay,
                                            onStepPrevFrame = { onStepFrame(-1) },
                                            onStepNextFrame = { onStepFrame(1) },
                                            onEditTimestampsClick = { onTabChange(EditorTab.TRIM_CROP) },
                                            modifier = Modifier.padding(bottom = 10.dp)
                                        )
                                    }

                                    EditorWorkbenchPanel(
                                        state = state,
                                        presets = presets,
                                        onApplyPreset = onApplyPreset,
                                        onSavePreset = onSavePreset,
                                        onRenamePreset = onRenamePreset,
                                        onDeletePreset = onDeletePreset,
                                        onTabChange = onTabChange,
                                        onUpdateSettings = onUpdateSettings,
                                        onApplyAspectRatio = onApplyAspectRatio,
                                        onRotate90 = onRotate90,
                                        onToggleCropOverlay = onToggleCropOverlay,
                                        onResetDefaults = onResetDefaults,
                                        modifier = Modifier
                                            .weight(0.95f)
                                            .fillMaxHeight()
                                    )
                                }
                            } else {
                                // Mobile portrait optimized layout: pinned preview + timeline, scrollable settings sheet below
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 14.dp)
                                ) {
                                    VideoPreviewStage(
                                        metadata = metadata,
                                        previewMode = state.previewMode,
                                        isPlaying = state.isPlaying,
                                        currentPositionMs = state.currentPositionMs,
                                        trimStartMs = state.settings.trimStartMs,
                                        trimEndMs = state.settings.trimEndMs,
                                        rotationDegrees = state.settings.rotationDegrees,
                                        cropRect = state.settings.cropRect,
                                        aspectRatioPreset = state.settings.aspectRatioPreset,
                                        showCropOverlay = state.isCropOverlayVisible,
                                        ditherMode = state.settings.ditherMode,
                                        liveGifPreview = state.liveGifPreview,
                                        isRenderingGifPreview = state.isRenderingGifPreview,
                                        onPositionTick = onSeek,
                                        onCropChanged = { rect -> onUpdateSettings { it.copy(cropRect = rect) } },
                                        requestOriginalFrame = onRequestOriginalFrame,
                                        gifPreviewError = state.gifPreviewError,
                                        onRetryGifPreview = onRetryGifPreview,
                                        outputFormat = state.settings.outputFormat,
                                        onPreviewViewportChanged = onPreviewViewportChanged,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(if (this@BoxWithConstraints.maxHeight < 520.dp) 184.dp else 244.dp)
                                    )

                                    Spacer(Modifier.height(12.dp))

                                    VideoTimelineEditor(
                                        durationMs = metadata.durationMs,
                                        trimStartMs = state.settings.trimStartMs,
                                        trimEndMs = state.settings.trimEndMs,
                                        currentPositionMs = state.currentPositionMs,
                                        isPlaying = state.isPlaying,
                                        totalOutputFrames = schedule.frames.size,
                                        outputDurationMs = schedule.totalDurationMs,
                                        filmstrip = state.filmstrip,
                                        isLoadingFilmstrip = state.isLoadingFilmstrip,
                                        outputFormat = state.settings.outputFormat,
                                        onTrimChanged = { s, e -> onUpdateSettings { it.copy(trimStartMs = s, trimEndMs = e) } },
                                        onSeek = onSeek,
                                        onTogglePlay = onTogglePlay,
                                        onStepPrevFrame = { onStepFrame(-1) },
                                        onStepNextFrame = { onStepFrame(1) },
                                        onEditTimestampsClick = { onTabChange(EditorTab.TRIM_CROP) }
                                    )

                                    Spacer(Modifier.height(14.dp))

                                    EditorWorkbenchPanel(
                                        state = state,
                                        presets = presets,
                                        onApplyPreset = onApplyPreset,
                                        onSavePreset = onSavePreset,
                                        onRenamePreset = onRenamePreset,
                                        onDeletePreset = onDeletePreset,
                                        onTabChange = onTabChange,
                                        onUpdateSettings = onUpdateSettings,
                                        onApplyAspectRatio = onApplyAspectRatio,
                                        onRotate90 = onRotate90,
                                        onToggleCropOverlay = onToggleCropOverlay,
                                        onResetDefaults = onResetDefaults,
                                        scrollable = false,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTargetSizeSheet && state.metadata != null) {
        TargetFileSizeBottomSheet(
            currentEstimatedBytes = state.sizeEstimate?.estimatedBytes,
            isAnalyzing = state.isAnalyzingTargetSize,
            recommendation = state.targetSizeRecommendation,
            errorMessage = state.targetSizeError,
            onAnalyzeTargetBytes = onAnalyzeTargetSize,
            onAcceptRecommendation = {
                onAcceptTargetSizeRecommendation()
                showTargetSizeSheet = false
            },
            onDismiss = {
                onDismissTargetSizeRecommendation()
                showTargetSizeSheet = false
            }
        )
    }
}

@Composable
private fun PreviewModeToggle(
    mode: PreviewMode,
    outputFormat: OutputFormat,
    onModeChange: (PreviewMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    val previewLabel = if (outputFormat == OutputFormat.WEBP) "WebP Preview" else "GIF Preview"
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(3.dp)
    ) {
        listOf(
            PreviewMode.ORIGINAL to "Original",
            PreviewMode.GIF_PREVIEW to previewLabel
        ).forEach { (itemMode, label) ->
            val selected = mode == itemMode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (selected) colors.primary else Color.Transparent)
                    .clickable { onModeChange(itemMode) }
                    .testTag("preview_mode_${itemMode.name.lowercase()}")
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
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

@Composable
private fun EstimatedSizeBanner(
    state: EditorUiState,
    onTargetSizeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    val estimate = state.sizeEstimate
    val format = state.settings.outputFormat
    val shape = RoundedCornerShape(16.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.primary.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("estimated_size_banner")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            AnimatedVisibility(
                visible = state.isEstimatingSize,
                enter = fadeIn(animationSpec = studioTween(160, reducedMotion)),
                exit = fadeOut(animationSpec = studioTween(160, reducedMotion))
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
            val sizeLabel = if (estimate != null) {
                "Est: ${StudioFormatters.formatFileSize(estimate.estimatedBytes)}"
            } else {
                "Estimating…"
            }
            AnimatedContent(
                targetState = sizeLabel,
                transitionSpec = {
                    fadeIn(animationSpec = studioTween(160, reducedMotion)) togetherWith
                        fadeOut(animationSpec = studioTween(120, reducedMotion))
                },
                label = "estimatedSizeLabel"
            ) { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = colors.primaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = format.shortLabel,
                    style = MonoSpecStyle.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }

            if (estimate != null) {
                Surface(
                    color = colors.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${estimate.outputWidth}×${estimate.outputHeight}",
                        style = MonoSpecStyle,
                        color = colors.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            if (format == OutputFormat.GIF) {
                Surface(
                    color = colors.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onTargetSizeClick)
                        .testTag("target_size_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DataUsage,
                            contentDescription = "Target file size",
                            tint = colors.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Target",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorWorkbenchPanel(
    state: EditorUiState,
    presets: List<ConversionPresetEntity>,
    onApplyPreset: (ConversionPresetEntity) -> Unit,
    onSavePreset: (String) -> Unit,
    onRenamePreset: (Long, String) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onTabChange: (EditorTab) -> Unit,
    onUpdateSettings: ((GifConversionSettings) -> GifConversionSettings) -> Unit,
    onApplyAspectRatio: (AspectRatioPreset) -> Unit,
    onRotate90: () -> Unit,
    onToggleCropOverlay: () -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalStudioHaptics.current
    val meta = state.metadata ?: return
    val shape = RoundedCornerShape(22.dp)
    val colorTabLabel = if (state.settings.outputFormat == OutputFormat.WEBP) "Quality" else "Color"

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(14.dp)
    ) {
        // 3 Workbench Category Tabs
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            WorkbenchTabPill(
                label = "Trim & Crop",
                icon = Icons.Rounded.Crop,
                selected = state.activeTab == EditorTab.TRIM_CROP,
                onClick = {
                    if (state.activeTab != EditorTab.TRIM_CROP) haptics.perform(StudioHapticEvent.SELECTION)
                    onTabChange(EditorTab.TRIM_CROP)
                },
                modifier = Modifier.testTag("editor_tab_trim_crop")
            )
            WorkbenchTabPill(
                label = "Size & Motion",
                icon = Icons.Rounded.Tune,
                selected = state.activeTab == EditorTab.SIZE_MOTION,
                onClick = {
                    if (state.activeTab != EditorTab.SIZE_MOTION) haptics.perform(StudioHapticEvent.SELECTION)
                    onTabChange(EditorTab.SIZE_MOTION)
                },
                modifier = Modifier.testTag("editor_tab_size_motion")
            )
            WorkbenchTabPill(
                label = colorTabLabel,
                icon = Icons.Rounded.ColorLens,
                selected = state.activeTab == EditorTab.COLOR_DITHER,
                onClick = {
                    if (state.activeTab != EditorTab.COLOR_DITHER) haptics.perform(StudioHapticEvent.SELECTION)
                    onTabChange(EditorTab.COLOR_DITHER)
                },
                modifier = Modifier.testTag("editor_tab_color")
            )
        }

        Spacer(Modifier.height(12.dp))

        // Saved & Built-in Conversion Presets Quick Bar
        PresetQuickBar(
            presets = presets,
            currentSettings = state.settings,
            onApplyPreset = onApplyPreset,
            onSavePreset = onSavePreset,
            onRenamePreset = onRenamePreset,
            onDeletePreset = onDeletePreset,
            onResetDefaults = onResetDefaults
        )

        Spacer(Modifier.height(12.dp))

        val bodyModifier = if (scrollable) {
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier.fillMaxWidth()
        }

        val reducedMotion = rememberReducedMotionEnabled()
        Box(modifier = bodyModifier) {
            AnimatedContent(
                targetState = state.activeTab,
                transitionSpec = {
                    fadeIn(animationSpec = studioTween(160, reducedMotion)) togetherWith
                        fadeOut(animationSpec = studioTween(120, reducedMotion))
                },
                label = "editorTabContent"
            ) { tab ->
                when (tab) {
                    EditorTab.TRIM_CROP -> TrimAndCropPanel(
                        metadata = meta,
                        settings = state.settings,
                        isCropOverlayVisible = state.isCropOverlayVisible,
                        onUpdateSettings = onUpdateSettings,
                        onApplyAspectRatio = onApplyAspectRatio,
                        onRotate90 = onRotate90,
                        onToggleCropOverlay = onToggleCropOverlay
                    )

                    EditorTab.SIZE_MOTION -> ResolutionAndTimingPanel(
                        sourceWidth = meta.orientedWidth,
                        sourceHeight = meta.orientedHeight,
                        settings = state.settings,
                        onUpdateSettings = onUpdateSettings,
                        sourceFps = meta.frameRate,
                        isVariableFrameRate = meta.isVariableFrameRate
                    )

                    EditorTab.COLOR_DITHER -> ColorAndDitherPanel(
                        settings = state.settings,
                        resolvedColorCount = state.sizeEstimate?.resolvedColorCount,
                        onUpdateSettings = onUpdateSettings,
                        onResetDefaults = onResetDefaults,
                        showFormatSelector = false
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkbenchTabPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(1.dp, colors.primary.copy(alpha = 0.5f), shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = PlusJakartaSansFamily,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.sp
            ),
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
