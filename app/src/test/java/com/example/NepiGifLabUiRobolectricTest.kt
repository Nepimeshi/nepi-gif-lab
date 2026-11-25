package com.example

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.example.engine.AspectRatioPreset
import com.example.engine.ColorQualityPreset
import com.example.engine.ConversionProgress
import com.example.engine.DitherMode
import com.example.engine.EncodedGifStats
import com.example.engine.GifConversionSettings
import com.example.engine.GifGeometryCalculator
import com.example.engine.LiveGifPreviewData
import com.example.engine.MediaInputType
import com.example.engine.NormalizedCropRect
import com.example.engine.ResolutionPreset
import com.example.engine.SavedGifResult
import com.example.engine.VideoMetadata
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import com.example.ui.components.CropDragHandle
import com.example.ui.components.detectCropHandle
import com.example.ui.components.resizeCropCorner
import com.example.ui.components.resizeCropEdge
import com.example.ui.components.ResolutionAndTimingPanel
import com.example.ui.components.TrimAndCropPanel
import com.example.ui.screens.BatchConversionScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.ExportScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.GifStudioTheme
import com.example.viewmodel.BatchItemStatus
import com.example.viewmodel.BatchQueueItem
import com.example.viewmodel.BatchUiState
import com.example.viewmodel.EditorTab
import com.example.viewmodel.EditorUiState
import com.example.viewmodel.ExportUiState
import com.example.viewmodel.PreviewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class NepiGifLabUiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `export completion Done Back to Home button is horizontally centered`() {
        val result = SavedGifResult(
            contentUri = Uri.parse("content://media/external/images/media/101"),
            fileName = "NepiGifLab_demo_20261121.gif",
            relativeFolder = "Pictures/Nepi GIF Lab",
            thumbnailPath = null,
            stats = EncodedGifStats(
                width = 480,
                height = 270,
                fps = 24f,
                durationMs = 2000L,
                frameCount = 48,
                fileSizeBytes = 512_000L,
                effectiveColorCount = 128,
                ditherMode = DitherMode.FLOYD_STEINBERG
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                ExportScreen(
                    state = ExportUiState.Success(result = result, estimatedBytes = 500_000L),
                    onCancelExport = {},
                    onRetryExport = {},
                    onShareGif = {},
                    onOpenGif = {},
                    onDone = {},
                    onBackToEditor = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val doneButton = composeRule.onNodeWithTag("export_done_button")
        doneButton.performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Done, Back to Home").assertIsDisplayed()

        val buttonBounds = doneButton.getUnclippedBoundsInRoot()
        val openBounds = composeRule.onNodeWithTag("export_open_button").getUnclippedBoundsInRoot()
        val shareBounds = composeRule.onNodeWithTag("export_share_button").getUnclippedBoundsInRoot()

        val actionRowCenterX = (openBounds.left.value + shareBounds.right.value) / 2f
        val doneCenterX = (buttonBounds.left.value + buttonBounds.right.value) / 2f
        assertTrue(
            "Done button center ($doneCenterX) should align with action row center ($actionRowCenterX)",
            abs(doneCenterX - actionRowCenterX) <= 3f
        )
    }

    @Test
    fun `trim and crop panel removes manual checkmark buttons and auto-applies valid inputs`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/clip.mp4"),
            displayName = "clip.mp4",
            rawWidth = 1000,
            rawHeight = 500,
            metadataRotation = 0,
            durationMs = 10_000L,
            frameRate = 30f,
            fileSizeBytes = 1_000_000L
        )
        var currentSettings by mutableStateOf(
            GifConversionSettings.DEFAULT.copy(trimStartMs = 0L, trimEndMs = 10_000L)
        )

        composeRule.setContent {
            GifStudioTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TrimAndCropPanel(
                        metadata = metadata,
                        settings = currentSettings,
                        isCropOverlayVisible = true,
                        onUpdateSettings = { reducer -> currentSettings = reducer(currentSettings) },
                        onApplyAspectRatio = { preset ->
                            currentSettings = currentSettings.copy(aspectRatioPreset = preset)
                        },
                        onRotate90 = {},
                        onToggleCropOverlay = {}
                    )
                }
            }
        }

        // Verify manual Apply/checkmark buttons are gone
        composeRule.onNodeWithTag("apply_trim_button").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Apply trim timestamps").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Apply custom crop").assertDoesNotExist()

        // Edit trim start timestamp and verify debounced auto-apply
        composeRule.onNodeWithTag("trim_start_input").performTextReplacement("00:02.50")
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()
        assertEquals(2500L, currentSettings.trimStartMs)

        // Incomplete/invalid trim end input should NOT crash or corrupt trimEndMs
        composeRule.onNodeWithTag("trim_end_input").performTextReplacement("00:")
        composeRule.mainClock.advanceTimeBy(600L)
        composeRule.waitForIdle()
        assertEquals(10_000L, currentSettings.trimEndMs)

        // Valid trim end input auto-applies
        composeRule.onNodeWithTag("trim_end_input").performTextReplacement("00:06.00")
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()
        assertEquals(6000L, currentSettings.trimEndMs)

        // Valid custom crop width auto-applies without any checkmark button
        composeRule.onNodeWithTag("crop_width_input").performScrollTo().performTextReplacement("500")
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()
        val cropped = GifGeometryCalculator.croppedSourceDimensions(
            metadata.orientedWidth,
            metadata.orientedHeight,
            currentSettings.rotationDegrees,
            currentSettings.cropRect
        )
        assertEquals(500, cropped.width)
        assertEquals(AspectRatioPreset.ORIGINAL, currentSettings.aspectRatioPreset)
    }

    @Test
    fun `fps panel defaults to Original Source with detected fps and allows manual override`() {
        var currentSettings by mutableStateOf(GifConversionSettings.DEFAULT)

        composeRule.setContent {
            GifStudioTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ResolutionAndTimingPanel(
                        sourceWidth = 1280,
                        sourceHeight = 720,
                        settings = currentSettings,
                        onUpdateSettings = { reducer -> currentSettings = reducer(currentSettings) },
                        sourceFps = 29.97f,
                        isVariableFrameRate = true
                    )
                }
            }
        }

        // Original (Source) chip and detected FPS info are displayed
        composeRule.onNodeWithTag("fps_chip_original").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Original (29.97 FPS)").assertIsDisplayed()
        composeRule.onNodeWithTag("fps_source_info").performScrollTo().assertIsDisplayed()
        assertTrue(currentSettings.useOriginalFps)

        // All 8 manual FPS presets remain present
        listOf(5, 8, 10, 12, 15, 20, 24, 30).forEach { preset ->
            composeRule.onNodeWithTag("fps_chip_$preset").assertExists()
        }

        // Tapping a manual preset overrides Original (Source)
        composeRule.onNodeWithTag("fps_chip_24").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(currentSettings.useOriginalFps)
        assertEquals(24f, currentSettings.fps, 0.01f)

        // Tapping Original restores Original (Source)
        composeRule.onNodeWithTag("fps_chip_original").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(currentSettings.useOriginalFps)
    }

    @Test
    fun `resolution panel accepts custom resolutions above 480 and updates settings`() {
        var currentSettings by mutableStateOf(GifConversionSettings.DEFAULT)

        composeRule.setContent {
            GifStudioTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ResolutionAndTimingPanel(
                        sourceWidth = 1920,
                        sourceHeight = 1080,
                        settings = currentSettings,
                        onUpdateSettings = { reducer -> currentSettings = reducer(currentSettings) },
                        sourceFps = 30f
                    )
                }
            }
        }

        // Enter custom resolution 720 (> 480)
        composeRule.onNodeWithTag("resolution_custom_input").performScrollTo().performTextReplacement("720")
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()

        assertEquals(ResolutionPreset.CUSTOM, currentSettings.resolutionPreset)
        assertEquals(720, currentSettings.customResolutionShortEdge)

        // Trailing badge should reflect 1280 x 720 px
        composeRule.onNodeWithText("1280 × 720 px").assertIsDisplayed()

        // Enter custom resolution 1080
        composeRule.onNodeWithTag("resolution_custom_input").performScrollTo().performTextReplacement("1080")
        composeRule.mainClock.advanceTimeBy(400L)
        composeRule.waitForIdle()

        assertEquals(ResolutionPreset.CUSTOM, currentSettings.resolutionPreset)
        assertEquals(1080, currentSettings.customResolutionShortEdge)
        composeRule.onNodeWithText("1920 × 1080 px").assertIsDisplayed()
    }

    @Test
    fun `cancel conversion button is horizontally centered during conversion progress`() {
        var cancelled = false
        val convertingState = ExportUiState.Converting(
            progress = ConversionProgress(
                percent = 0.45f,
                currentFrame = 45,
                totalFrames = 100,
                stage = "Encoding frame 45 of 100"
            ),
            workId = java.util.UUID.randomUUID()
        )

        composeRule.setContent {
            GifStudioTheme {
                ExportScreen(
                    state = convertingState,
                    onCancelExport = { cancelled = true },
                    onRetryExport = {},
                    onShareGif = {},
                    onOpenGif = {},
                    onDone = {},
                    onBackToEditor = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val cancelButton = composeRule.onNodeWithTag("cancel_export_button")
        cancelButton.performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cancel Conversion").assertIsDisplayed()

        val buttonBounds = cancelButton.getUnclippedBoundsInRoot()
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()

        val screenCenterX = (rootBounds.left.value + rootBounds.right.value) / 2f
        val buttonCenterX = (buttonBounds.left.value + buttonBounds.right.value) / 2f
        assertTrue(
            "Cancel button center ($buttonCenterX) must align with screen center ($screenCenterX)",
            abs(buttonCenterX - screenCenterX) <= 3f
        )

        cancelButton.performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `editor tab labels are fully visible without truncation`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/clip.mp4"),
            displayName = "clip.mp4",
            rawWidth = 1280,
            rawHeight = 720,
            metadataRotation = 0,
            durationMs = 5000L,
            frameRate = 30f,
            fileSizeBytes = 1_000_000L
        )
        var activeTab by mutableStateOf(EditorTab.TRIM_CROP)
        val editorState = EditorUiState(
            metadata = metadata,
            settings = GifConversionSettings.DEFAULT,
            activeTab = activeTab
        )

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = editorState.copy(activeTab = activeTab),
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = { activeTab = it },
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Verify full labels are displayed without truncation
        composeRule.onNodeWithText("Trim & Crop").assertIsDisplayed()
        composeRule.onNodeWithText("Size & Motion").assertIsDisplayed()
        composeRule.onNodeWithText("Color").assertIsDisplayed()

        // Tab selection works
        composeRule.onNodeWithTag("editor_tab_size_motion").performClick()
        assertEquals(EditorTab.SIZE_MOTION, activeTab)

        composeRule.onNodeWithTag("editor_tab_color").performClick()
        assertEquals(EditorTab.COLOR_DITHER, activeTab)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `editor tab layout on narrow 320dp screen displays full labels without truncation`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/clip.mp4"),
            displayName = "clip.mp4",
            rawWidth = 1280,
            rawHeight = 720,
            metadataRotation = 0,
            durationMs = 5000L,
            frameRate = 30f,
            fileSizeBytes = 1_000_000L
        )
        val editorState = EditorUiState(
            metadata = metadata,
            settings = GifConversionSettings.DEFAULT,
            activeTab = EditorTab.TRIM_CROP
        )

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = editorState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = {},
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithText("Trim & Crop").assertIsDisplayed()
        composeRule.onNodeWithText("Size & Motion").assertIsDisplayed()
        composeRule.onNodeWithText("Color").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `editor tab layout on wide 800dp screen displays full labels without truncation`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/clip.mp4"),
            displayName = "clip.mp4",
            rawWidth = 1280,
            rawHeight = 720,
            metadataRotation = 0,
            durationMs = 5000L,
            frameRate = 30f,
            fileSizeBytes = 1_000_000L
        )
        val editorState = EditorUiState(
            metadata = metadata,
            settings = GifConversionSettings.DEFAULT,
            activeTab = EditorTab.SIZE_MOTION
        )

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = editorState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = {},
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithText("Trim & Crop").assertIsDisplayed()
        composeRule.onNodeWithText("Size & Motion").assertIsDisplayed()
        composeRule.onNodeWithText("Color").assertIsDisplayed()
    }

    @Test
    fun `full-frame crop handle detection at all four corners and edges with 48dp target on narrow screen`() {
        // Full frame 16:9 on a narrow screen (320px wide, 180px high)
        val w = 320f
        val h = 180f
        val full = NormalizedCropRect(0f, 0f, 1f, 1f)
        val touchTarget = 48f

        // Top-Left corner at (0, 0) and slightly inside
        assertEquals(CropDragHandle.TOP_LEFT, detectCropHandle(Offset(0f, 0f), full, w, h, touchTarget))
        assertEquals(CropDragHandle.TOP_LEFT, detectCropHandle(Offset(20f, 20f), full, w, h, touchTarget))

        // Top-Right corner at (w, 0) and slightly inside
        assertEquals(CropDragHandle.TOP_RIGHT, detectCropHandle(Offset(w, 0f), full, w, h, touchTarget))
        assertEquals(CropDragHandle.TOP_RIGHT, detectCropHandle(Offset(w - 20f, 20f), full, w, h, touchTarget))

        // Bottom-Left corner at (0, h) and slightly inside
        assertEquals(CropDragHandle.BOTTOM_LEFT, detectCropHandle(Offset(0f, h), full, w, h, touchTarget))
        assertEquals(CropDragHandle.BOTTOM_LEFT, detectCropHandle(Offset(20f, h - 20f), full, w, h, touchTarget))

        // Bottom-Right corner at (w, h) and slightly inside
        assertEquals(CropDragHandle.BOTTOM_RIGHT, detectCropHandle(Offset(w, h), full, w, h, touchTarget))
        assertEquals(CropDragHandle.BOTTOM_RIGHT, detectCropHandle(Offset(w - 20f, h - 20f), full, w, h, touchTarget))

        // Edges
        assertEquals(CropDragHandle.TOP, detectCropHandle(Offset(w / 2f, 0f), full, w, h, touchTarget))
        assertEquals(CropDragHandle.BOTTOM, detectCropHandle(Offset(w / 2f, h), full, w, h, touchTarget))
        assertEquals(CropDragHandle.LEFT, detectCropHandle(Offset(0f, h / 2f), full, w, h, touchTarget))
        assertEquals(CropDragHandle.RIGHT, detectCropHandle(Offset(w, h / 2f), full, w, h, touchTarget))

        // Center interior -> MOVE
        assertEquals(CropDragHandle.MOVE, detectCropHandle(Offset(w / 2f, h / 2f), full, w, h, touchTarget))
    }

    @Test
    fun `resizing Original aspect ratio on 16-9 landscape video preserves aspect ratio and anchors opposite corner`() {
        val srcW = 1920
        val srcH = 1080
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val full = NormalizedCropRect(0f, 0f, 1f, 1f)

        // Drag TOP_LEFT inward (dx = 0.15, dy = 0.15)
        val resizedTL = resizeCropCorner(
            c = full,
            handle = CropDragHandle.TOP_LEFT,
            dx = 0.15f,
            dy = 0.15f,
            lockedRatio = srcAspect,
            srcAspect = srcAspect
        )

        // Anchor is bottom-right (1f, 1f)
        assertEquals(1f, resizedTL.right, 0.001f)
        assertEquals(1f, resizedTL.bottom, 0.001f)
        assertTrue("left should have moved inward", resizedTL.left > 0f)
        assertTrue("top should have moved inward", resizedTL.top > 0f)

        // Physical aspect ratio preserved
        val actualRatio = (resizedTL.widthFraction * srcW) / (resizedTL.heightFraction * srcH)
        assertEquals(srcAspect, actualRatio, 0.01f)

        // Drag BOTTOM_RIGHT inward (dx = -0.2, dy = -0.2)
        val resizedBR = resizeCropCorner(
            c = full,
            handle = CropDragHandle.BOTTOM_RIGHT,
            dx = -0.2f,
            dy = -0.2f,
            lockedRatio = srcAspect,
            srcAspect = srcAspect
        )

        // Anchor is top-left (0f, 0f)
        assertEquals(0f, resizedBR.left, 0.001f)
        assertEquals(0f, resizedBR.top, 0.001f)
        assertTrue("right should have moved inward", resizedBR.right < 1f)
        assertTrue("bottom should have moved inward", resizedBR.bottom < 1f)

        val actualRatioBR = (resizedBR.widthFraction * srcW) / (resizedBR.heightFraction * srcH)
        assertEquals(srcAspect, actualRatioBR, 0.01f)
    }

    @Test
    fun `resizing Original aspect ratio on 9-16 portrait video preserves aspect ratio and anchors opposite corner`() {
        val srcW = 1080
        val srcH = 1920
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val full = NormalizedCropRect(0f, 0f, 1f, 1f)

        // Drag TOP_RIGHT inward (dx = -0.1, dy = 0.1)
        val resizedTR = resizeCropCorner(
            c = full,
            handle = CropDragHandle.TOP_RIGHT,
            dx = -0.1f,
            dy = 0.1f,
            lockedRatio = srcAspect,
            srcAspect = srcAspect
        )

        // Anchor is bottom-left (left = 0f, bottom = 1f)
        assertEquals(0f, resizedTR.left, 0.001f)
        assertEquals(1f, resizedTR.bottom, 0.001f)
        assertTrue("right moved inward", resizedTR.right < 1f)
        assertTrue("top moved inward", resizedTR.top > 0f)

        val actualRatio = (resizedTR.widthFraction * srcW) / (resizedTR.heightFraction * srcH)
        assertEquals(srcAspect, actualRatio, 0.01f)
    }

    @Test
    fun `resizing 1-1 square crop maintains 1-1 ratio`() {
        val srcW = 1920
        val srcH = 1080
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val squareCrop = NormalizedCropRect.centeredForAspectRatio(srcW, srcH, 1f)

        // Drag BOTTOM_LEFT inward
        val resized = resizeCropCorner(
            c = squareCrop,
            handle = CropDragHandle.BOTTOM_LEFT,
            dx = 0.1f,
            dy = -0.1f,
            lockedRatio = 1f,
            srcAspect = srcAspect
        )

        // Anchor is top-right (right = squareCrop.right, top = squareCrop.top)
        assertEquals(squareCrop.right, resized.right, 0.001f)
        assertEquals(squareCrop.top, resized.top, 0.001f)

        val pixelRatio = (resized.widthFraction * srcW) / (resized.heightFraction * srcH)
        assertEquals(1f, pixelRatio, 0.01f)
    }

    @Test
    fun `resizing 4-3 crop maintains 4-3 ratio`() {
        val srcW = 1920
        val srcH = 1080
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val targetRatio = 4f / 3f
        val rect = NormalizedCropRect.centeredForAspectRatio(srcW, srcH, targetRatio)

        val resized = resizeCropCorner(
            c = rect,
            handle = CropDragHandle.BOTTOM_RIGHT,
            dx = -0.1f,
            dy = -0.1f,
            lockedRatio = targetRatio,
            srcAspect = srcAspect
        )

        val pixelRatio = (resized.widthFraction * srcW) / (resized.heightFraction * srcH)
        assertEquals(targetRatio, pixelRatio, 0.01f)
    }

    @Test
    fun `resizing 9-16 crop on landscape video maintains 9-16 ratio`() {
        val srcW = 1920
        val srcH = 1080
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val targetRatio = 9f / 16f
        val rect = NormalizedCropRect.centeredForAspectRatio(srcW, srcH, targetRatio)

        val resized = resizeCropCorner(
            c = rect,
            handle = CropDragHandle.TOP_LEFT,
            dx = 0.05f,
            dy = 0.05f,
            lockedRatio = targetRatio,
            srcAspect = srcAspect
        )

        val pixelRatio = (resized.widthFraction * srcW) / (resized.heightFraction * srcH)
        assertEquals(targetRatio, pixelRatio, 0.01f)
    }

    @Test
    fun `dragging outward stays strictly within video boundaries`() {
        val srcW = 1920
        val srcH = 1080
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val rect = NormalizedCropRect(0.1f, 0.1f, 0.9f, 0.9f)

        // Drag TOP_LEFT outward aggressively (dx = -0.5, dy = -0.5)
        val resized = resizeCropCorner(
            c = rect,
            handle = CropDragHandle.TOP_LEFT,
            dx = -0.5f,
            dy = -0.5f,
            lockedRatio = srcAspect,
            srcAspect = srcAspect
        )

        assertTrue(resized.left >= 0f)
        assertTrue(resized.top >= 0f)
        assertTrue(resized.right <= 1f)
        assertTrue(resized.bottom <= 1f)
    }

    @Test
    fun `edge handle resizing maintains aspect ratio and clamps within video bounds`() {
        val srcW = 1920
        val srcH = 1080
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val full = NormalizedCropRect(0f, 0f, 1f, 1f)

        // Drag TOP edge downward
        val resizedTop = resizeCropEdge(
            c = full,
            handle = CropDragHandle.TOP,
            dx = 0f,
            dy = 0.2f,
            lockedRatio = srcAspect,
            srcAspect = srcAspect
        )

        assertEquals(1f, resizedTop.bottom, 0.001f)
        assertTrue(resizedTop.top > 0f)
        assertTrue(resizedTop.left >= 0f)
        assertTrue(resizedTop.right <= 1f)

        val pixelRatio = (resizedTop.widthFraction * srcW) / (resizedTop.heightFraction * srcH)
        assertEquals(srcAspect, pixelRatio, 0.01f)
    }

    @Test
    fun `home screen exposes both Import Video and Import GIF actions alongside mixed batch import`() {
        var videoClicks = 0
        var gifClicks = 0
        var batchClicks = 0

        composeRule.setContent {
            GifStudioTheme {
                HomeScreen(
                    recentHistory = emptyList(),
                    onImportSingleVideo = { videoClicks++ },
                    onImportSingleGif = { gifClicks++ },
                    onImportBatchVideos = { batchClicks++ },
                    onOpenHistory = {},
                    onOpenSettings = {},
                    onOpenGif = {},
                    onShareGif = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithTag("import_video_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("import_gif_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("batch_convert_button").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Import Video").assertIsDisplayed()
        composeRule.onNodeWithText("Import GIF").assertIsDisplayed()
        composeRule.onNodeWithText("Batch Convert Videos & GIFs").assertIsDisplayed()

        assertEquals(1, videoClicks)
        assertEquals(1, gifClicks)
        assertEquals(1, batchClicks)
    }

    @Test
    fun `editor screen supports animated GIF input with full tabs and original preview`() {
        val gifMetadata = VideoMetadata(
            uri = Uri.parse("content://test/reaction.gif"),
            displayName = "reaction.gif",
            rawWidth = 480,
            rawHeight = 360,
            metadataRotation = 0,
            durationMs = 1200L,
            frameRate = 10f,
            fileSizeBytes = 250_000L,
            frameTimestampsUs = listOf(0L, 100_000L, 300_000L, 700_000L),
            isVariableFrameRate = true,
            inputType = MediaInputType.GIF,
            frameDelaysMs = listOf(100, 200, 400, 500),
            loopCount = 0,
            hasTransparency = true
        )
        val editorState = EditorUiState(
            metadata = gifMetadata,
            settings = GifConversionSettings.DEFAULT.copy(trimStartMs = 0L, trimEndMs = 1200L),
            activeTab = EditorTab.TRIM_CROP
        )

        var requestedFrameCount = 0
        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = editorState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = {},
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    onRequestOriginalFrame = { _, _ ->
                        requestedFrameCount++
                        null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.waitForIdle()
        assertTrue("Animated GIF original preview must request frames via callback", requestedFrameCount >= 1)
        composeRule.onNodeWithText("GIF Preview").assertIsDisplayed()
        composeRule.onNodeWithTag("editor_export_button").assertIsDisplayed()
        composeRule.onNodeWithText("Trim & Crop").assertIsDisplayed()
        composeRule.onNodeWithText("Size & Motion").assertIsDisplayed()
        composeRule.onNodeWithText("Color").assertIsDisplayed()
    }

    @Test
    fun `batch conversion screen displays mixed video and GIF queue items cleanly`() {
        val mixedState = BatchUiState(
            items = listOf(
                BatchQueueItem(
                    uri = Uri.parse("content://test/clip.mp4"),
                    displayName = "clip.mp4",
                    durationMs = 3000L,
                    width = 1280,
                    height = 720,
                    inputType = MediaInputType.VIDEO
                ),
                BatchQueueItem(
                    uri = Uri.parse("content://test/anim.gif"),
                    displayName = "anim.gif",
                    durationMs = 1400L,
                    width = 400,
                    height = 300,
                    inputType = MediaInputType.GIF
                )
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                BatchConversionScreen(
                    state = mixedState,
                    onBack = {},
                    onAddVideos = {},
                    onRemoveItem = {},
                    onClearQueue = {},
                    onUpdateSharedSettings = {},
                    onResetDefaults = {},
                    onStartBatch = {},
                    onCancelBatch = {},
                    onOpenGif = {},
                    onShareGif = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithText("2 item(s) in queue").assertIsDisplayed()
        composeRule.onNodeWithText("clip.mp4").assertIsDisplayed()
        composeRule.onNodeWithText("anim.gif").assertIsDisplayed()
        composeRule.onNodeWithText("GIF").assertIsDisplayed()
    }

    @Test
    fun `live gif preview displays initial loading, preserves previous preview while updating, and supports error retry`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/clip.mp4"),
            displayName = "clip.mp4",
            rawWidth = 640,
            rawHeight = 360,
            metadataRotation = 0,
            durationMs = 2000L,
            frameRate = 24f,
            fileSizeBytes = 500_000L
        )
        val previewFrame = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
        val readyPreview = LiveGifPreviewData(
            frames = listOf(previewFrame),
            frameDelayMs = 80,
            outputWidth = 320,
            outputHeight = 180,
            previewWidth = 64,
            previewHeight = 36,
            resolvedColorCount = 64,
            totalClipFrames = 1,
            isSampledPreview = false,
            globalPaletteColors = listOf(Color.MAGENTA)
        )
        var uiState by mutableStateOf(
            EditorUiState(
                metadata = metadata,
                settings = GifConversionSettings.DEFAULT.copy(
                    colorQualityPreset = ColorQualityPreset.C64,
                    ditherMode = DitherMode.ORDERED
                ),
                previewMode = PreviewMode.GIF_PREVIEW,
                isRenderingGifPreview = true,
                liveGifPreview = null,
                gifPreviewError = null
            )
        )
        var retryClicks = 0

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = uiState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = {},
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    onRetryGifPreview = { retryClicks++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 1. Initial loading state before first preview arrives
        composeRule.onNodeWithTag("gif_preview_loading").assertIsDisplayed()
        composeRule.onNodeWithText("Rendering GIF preview…").assertIsDisplayed()

        // 2. Preview completes -> specs pill shown, initial loading disappears
        composeRule.runOnIdle {
            uiState = uiState.copy(isRenderingGifPreview = false, liveGifPreview = readyPreview)
        }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GIF · 320×180 · 64c · Ordered").assertIsDisplayed()
        composeRule.onNodeWithTag("gif_preview_loading").assertDoesNotExist()

        // 3. Regenerating after a settings change keeps previous valid preview visible + shows non-blocking pill
        composeRule.runOnIdle {
            uiState = uiState.copy(isRenderingGifPreview = true)
        }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GIF · 320×180 · 64c · Ordered").assertIsDisplayed()
        composeRule.onNodeWithTag("gif_preview_updating_pill").assertIsDisplayed()

        // 4. Error state displays message and Retry Preview triggers callback
        composeRule.runOnIdle {
            uiState = uiState.copy(
                isRenderingGifPreview = false,
                liveGifPreview = null,
                gifPreviewError = "Preview render failed"
            )
        }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("gif_preview_error").assertIsDisplayed()
        composeRule.onNodeWithTag("gif_preview_retry_button").assertIsDisplayed().performClick()
        assertEquals(1, retryClicks)

        previewFrame.recycle()
    }

    @Test
    fun `export screen handles indeterminate stage, caps determinate progress below 100 percent, and transitions to completion`() {
        val workId = java.util.UUID.randomUUID()
        var exportState by mutableStateOf<ExportUiState>(
            ExportUiState.Converting(
                progress = ConversionProgress(
                    percent = 0f,
                    currentFrame = 0,
                    totalFrames = 0,
                    stage = "Preparing video frames…",
                    isIndeterminate = true
                ),
                workId = workId
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                ExportScreen(
                    state = exportState,
                    onCancelExport = {},
                    onRetryExport = {},
                    onShareGif = {},
                    onOpenGif = {},
                    onDone = {},
                    onBackToEditor = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Indeterminate stage: never fabricates 100% or a fake percent
        composeRule.onNodeWithTag("export_indeterminate_progress").assertIsDisplayed()
        composeRule.onNodeWithText("Preparing video frames…").assertIsDisplayed()
        composeRule.onNodeWithText("100%").assertDoesNotExist()

        // Determinate encoding stage
        composeRule.runOnIdle {
            exportState = ExportUiState.Converting(
                progress = ConversionProgress(
                    percent = 0.62f,
                    currentFrame = 31,
                    totalFrames = 50,
                    stage = "Encoding GIF frame 31 of 50",
                    isIndeterminate = false
                ),
                workId = workId
            )
        }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_progress_percent").assertIsDisplayed()
        composeRule.onNodeWithText("62%").assertIsDisplayed()
        composeRule.onNodeWithText("Encoding GIF frame 31 of 50").assertIsDisplayed()

        // Even at 0.999f while still Converting, never shows 100% before Success
        composeRule.runOnIdle {
            exportState = ExportUiState.Converting(
                progress = ConversionProgress(
                    percent = 0.999f,
                    currentFrame = 50,
                    totalFrames = 50,
                    stage = "Saving to Pictures/Nepi GIF Lab…",
                    isIndeterminate = false
                ),
                workId = workId
            )
        }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("100%").assertDoesNotExist()
        composeRule.onNodeWithText("99%").assertIsDisplayed()

        // Smooth transition to Success state showing actual file size & gallery banner
        val result = SavedGifResult(
            contentUri = Uri.parse("content://media/external/images/media/202"),
            fileName = "NepiGifLab_smooth_20261125.gif",
            relativeFolder = "Pictures/Nepi GIF Lab",
            thumbnailPath = null,
            stats = EncodedGifStats(
                width = 480,
                height = 270,
                fps = 24f,
                durationMs = 2000L,
                frameCount = 48,
                fileSizeBytes = 768_000L,
                effectiveColorCount = 128,
                ditherMode = DitherMode.ORDERED
            )
        )
        composeRule.runOnIdle {
            exportState = ExportUiState.Success(result = result, estimatedBytes = 740_000L)
        }
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("export_success_banner").assertIsDisplayed()
        composeRule.onNodeWithText("GIF Saved to Gallery").assertIsDisplayed()
    }

    @Test
    fun `batch conversion screen renders queue preparation and distinct item states with progress`() {
        val batchState = BatchUiState(
            isPreparingQueue = true,
            isRunning = true,
            overallProgress = 0.35f,
            completedCount = 1,
            failedCount = 1,
            items = listOf(
                BatchQueueItem(
                    id = "item_done",
                    uri = Uri.parse("content://test/done.mp4"),
                    displayName = "done.mp4",
                    durationMs = 1500L,
                    width = 640,
                    height = 360,
                    status = BatchItemStatus.COMPLETED,
                    progress = 1f,
                    stage = "Saved (480×270)"
                ),
                BatchQueueItem(
                    id = "item_proc_indet",
                    uri = Uri.parse("content://test/proc.mp4"),
                    displayName = "proc.mp4",
                    durationMs = 2000L,
                    width = 640,
                    height = 360,
                    status = BatchItemStatus.PROCESSING,
                    progress = 0f,
                    isIndeterminate = true,
                    stage = "Quantizing colors…"
                ),
                BatchQueueItem(
                    id = "item_queued",
                    uri = Uri.parse("content://test/queued.gif"),
                    displayName = "queued.gif",
                    durationMs = 1000L,
                    width = 320,
                    height = 240,
                    inputType = MediaInputType.GIF,
                    status = BatchItemStatus.QUEUED,
                    stage = "Waiting…"
                ),
                BatchQueueItem(
                    id = "item_failed",
                    uri = Uri.parse("content://test/bad.mp4"),
                    displayName = "bad.mp4",
                    durationMs = 0L,
                    width = 0,
                    height = 0,
                    status = BatchItemStatus.FAILED,
                    stage = "Unsupported file",
                    errorMessage = "Unsupported file"
                ),
                BatchQueueItem(
                    id = "item_cancelled",
                    uri = Uri.parse("content://test/cancelled.mp4"),
                    displayName = "cancelled.mp4",
                    durationMs = 1200L,
                    width = 640,
                    height = 360,
                    status = BatchItemStatus.CANCELLED,
                    stage = "Cancelled"
                )
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                BatchConversionScreen(
                    state = batchState,
                    onBack = {},
                    onAddVideos = {},
                    onRemoveItem = {},
                    onClearQueue = {},
                    onUpdateSharedSettings = {},
                    onResetDefaults = {},
                    onStartBatch = {},
                    onCancelBatch = {},
                    onOpenGif = {},
                    onShareGif = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithTag("batch_preparing_queue").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_overall_progress").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_item_processing_item_proc_indet").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_item_indeterminate_item_proc_indet").assertIsDisplayed()
        composeRule.onNodeWithText("Saved (480×270)").assertIsDisplayed()
        composeRule.onNodeWithText("Quantizing colors…").assertIsDisplayed()
    }

    @Test
    fun `editor filmstrip shows shimmer placeholder while loading and transitions to thumbnails`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/film.mp4"),
            displayName = "film.mp4",
            rawWidth = 640,
            rawHeight = 360,
            metadataRotation = 0,
            durationMs = 3000L,
            frameRate = 30f,
            fileSizeBytes = 600_000L
        )
        val thumb = Bitmap.createBitmap(48, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        var uiState by mutableStateOf(
            EditorUiState(
                metadata = metadata,
                settings = GifConversionSettings.DEFAULT.copy(trimStartMs = 0L, trimEndMs = 3000L),
                isLoadingFilmstrip = true,
                filmstrip = emptyList()
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = uiState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = {},
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithTag("filmstrip_shimmer_placeholder").assertIsDisplayed()

        composeRule.runOnIdle {
            uiState = uiState.copy(isLoadingFilmstrip = false, filmstrip = listOf(thumb))
        }
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("filmstrip_shimmer_placeholder").assertDoesNotExist()

        thumb.recycle()
    }

    @Test
    fun `rapid consecutive settings changes in gif preview mode settle cleanly without blank flash`() {
        val metadata = VideoMetadata(
            uri = Uri.parse("content://test/rapid.mp4"),
            displayName = "rapid.mp4",
            rawWidth = 640,
            rawHeight = 360,
            metadataRotation = 0,
            durationMs = 2000L,
            frameRate = 24f,
            fileSizeBytes = 500_000L
        )
        val frameA = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val frameB = Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val previewA = LiveGifPreviewData(
            frames = listOf(frameA),
            frameDelayMs = 80,
            outputWidth = 320,
            outputHeight = 180,
            previewWidth = 64,
            previewHeight = 36,
            resolvedColorCount = 32,
            totalClipFrames = 1,
            isSampledPreview = false,
            globalPaletteColors = listOf(Color.BLUE)
        )
        val previewB = LiveGifPreviewData(
            frames = listOf(frameB),
            frameDelayMs = 80,
            outputWidth = 320,
            outputHeight = 180,
            previewWidth = 64,
            previewHeight = 36,
            resolvedColorCount = 128,
            totalClipFrames = 1,
            isSampledPreview = false,
            globalPaletteColors = listOf(Color.GREEN)
        )
        var uiState by mutableStateOf(
            EditorUiState(
                metadata = metadata,
                settings = GifConversionSettings.DEFAULT.copy(
                    colorQualityPreset = ColorQualityPreset.C32,
                    ditherMode = DitherMode.NONE
                ),
                previewMode = PreviewMode.GIF_PREVIEW,
                isRenderingGifPreview = false,
                liveGifPreview = previewA
            )
        )

        composeRule.setContent {
            GifStudioTheme {
                EditorScreen(
                    state = uiState,
                    onBack = {},
                    onExportClick = {},
                    onPreviewModeChange = {},
                    onTabChange = {},
                    onUpdateSettings = {},
                    onSeek = {},
                    onTogglePlay = {},
                    onStepFrame = {},
                    onApplyAspectRatio = {},
                    onRotate90 = {},
                    onToggleCropOverlay = {},
                    onResetDefaults = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Simulate rapid consecutive settings tweaks while preview regenerates
        composeRule.runOnIdle {
            uiState = uiState.copy(
                settings = uiState.settings.copy(colorQualityPreset = ColorQualityPreset.C64, ditherMode = DitherMode.ORDERED),
                isRenderingGifPreview = true
            )
        }
        composeRule.mainClock.advanceTimeBy(60L)
        composeRule.runOnIdle {
            uiState = uiState.copy(
                settings = uiState.settings.copy(colorQualityPreset = ColorQualityPreset.C128, ditherMode = DitherMode.FLOYD_STEINBERG),
                isRenderingGifPreview = true
            )
        }
        composeRule.mainClock.advanceTimeBy(120L)
        composeRule.waitForIdle()

        // Previous preview stays visible (no blank loading screen flash) with non-blocking updating pill
        composeRule.onNodeWithTag("gif_preview_loading").assertDoesNotExist()
        composeRule.onNodeWithTag("gif_preview_updating_pill").assertIsDisplayed()

        // Latest preview arrives and settles cleanly
        composeRule.runOnIdle {
            uiState = uiState.copy(isRenderingGifPreview = false, liveGifPreview = previewB)
        }
        composeRule.mainClock.advanceTimeBy(350L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GIF · 320×180 · 128c · Floyd–Steinberg").assertIsDisplayed()
        composeRule.onNodeWithTag("gif_preview_updating_pill").assertDoesNotExist()

        frameA.recycle()
        frameB.recycle()
    }
}
