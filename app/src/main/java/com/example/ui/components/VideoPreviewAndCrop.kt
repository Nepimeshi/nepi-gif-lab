package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import com.example.engine.FrameTransformer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.engine.AspectRatioPreset
import com.example.engine.DitherMode
import com.example.engine.GifGeometryCalculator
import com.example.engine.LiveGifPreviewData
import com.example.engine.NormalizedCropRect
import com.example.engine.OutputFormat
import com.example.engine.VideoMetadata
import com.example.ui.theme.MonoSpecStyle
import com.example.viewmodel.PreviewMode
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Combined preview stage supporting:
 * 1. Original Video playback via native [MediaPlayer] + [TextureView], synchronized to trim bounds
 *    and overlaid with a draggable/resizable interactive crop rectangle.
 * 2. Real animated Live GIF / Animated WebP Preview rendering actual encoded bitmaps at real timing.
 */
@Composable
fun VideoPreviewStage(
    metadata: VideoMetadata,
    previewMode: PreviewMode,
    isPlaying: Boolean,
    currentPositionMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    rotationDegrees: Int,
    cropRect: NormalizedCropRect,
    aspectRatioPreset: AspectRatioPreset,
    showCropOverlay: Boolean,
    ditherMode: DitherMode,
    liveGifPreview: LiveGifPreviewData?,
    isRenderingGifPreview: Boolean,
    onPositionTick: (Long) -> Unit,
    onCropChanged: (NormalizedCropRect) -> Unit,
    modifier: Modifier = Modifier,
    requestOriginalFrame: (suspend (Long, Int) -> Bitmap?)? = null,
    gifPreviewError: String? = null,
    onRetryGifPreview: () -> Unit = {},
    outputFormat: OutputFormat = OutputFormat.GIF,
    onPreviewViewportChanged: (Int) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val rotatedDims = GifGeometryCalculator.rotatedDimensions(
        metadata.orientedWidth,
        metadata.orientedHeight,
        rotationDegrees
    )
    val videoAspect = (rotatedDims.width.toFloat() / rotatedDims.height.toFloat().coerceAtLeast(1f))
        .coerceIn(0.2f, 5.0f)

    var viewportMaxEdgePx by remember { mutableIntStateOf(0) }
    val latestOnViewportChanged by rememberUpdatedState(onPreviewViewportChanged)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0C0B10))
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            .onSizeChanged { size ->
                val edge = maxOf(size.width, size.height)
                if (edge > 0 && edge != viewportMaxEdgePx) {
                    viewportMaxEdgePx = edge
                    latestOnViewportChanged(edge)
                }
            }
    ) {
        when (previewMode) {
            PreviewMode.ORIGINAL -> {
                BoxWithConstraints(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                ) {
                    val containerAspect = if (this.maxHeight.value > 0f) this.maxWidth.value / this.maxHeight.value else 1f
                    val stageModifier = if (videoAspect >= containerAspect) {
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(videoAspect, matchHeightConstraintsFirst = false)
                    } else {
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(videoAspect, matchHeightConstraintsFirst = true)
                    }

                    Box(
                        modifier = stageModifier
                            .background(Color.Black, RoundedCornerShape(10.dp))
                    ) {
                        if (metadata.isGifInput) {
                            AnimatedGifOriginalPlayer(
                                metadata = metadata,
                                isPlaying = isPlaying,
                                seekPositionMs = currentPositionMs,
                                trimStartMs = trimStartMs,
                                trimEndMs = trimEndMs,
                                rotationDegrees = rotationDegrees,
                                requestFrame = requestOriginalFrame,
                                onPositionTick = onPositionTick,
                                targetDecodeEdgePx = viewportMaxEdgePx.coerceIn(720, 1440),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        } else {
                            NativeVideoTexturePlayer(
                                uri = metadata.uri,
                                isPlaying = isPlaying,
                                seekPositionMs = currentPositionMs,
                                trimStartMs = trimStartMs,
                                trimEndMs = trimEndMs,
                                rotationDegrees = rotationDegrees,
                                onPositionTick = onPositionTick,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                            )
                        }

                        if (showCropOverlay) {
                            InteractiveCropOverlay(
                                cropRect = cropRect,
                                sourceWidth = rotatedDims.width,
                                sourceHeight = rotatedDims.height,
                                aspectRatioPreset = aspectRatioPreset,
                                accentColor = colors.primary,
                                onCropChanged = onCropChanged,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            PreviewMode.GIF_PREVIEW -> {
                LiveGifPreviewPlayer(
                    previewData = liveGifPreview,
                    isRendering = isRenderingGifPreview,
                    errorMessage = gifPreviewError,
                    onRetry = onRetryGifPreview,
                    isPlaying = isPlaying,
                    currentPositionMs = currentPositionMs,
                    trimStartMs = trimStartMs,
                    trimEndMs = trimEndMs,
                    ditherMode = ditherMode,
                    outputFormat = outputFormat,
                    showTransparencyGrid = metadata.hasTransparency,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun TransparencyCheckerboard(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val tilePx = 10.dp.toPx().coerceAtLeast(6f)
        val darkA = Color(0xFF15141C)
        val darkB = Color(0xFF23212E)
        drawRect(darkA)
        val cols = (size.width / tilePx).toInt() + 1
        val rows = (size.height / tilePx).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if ((r + c) and 1 == 1) {
                    drawRect(
                        color = darkB,
                        topLeft = Offset(c * tilePx, r * tilePx),
                        size = Size(tilePx, tilePx)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedGifOriginalPlayer(
    metadata: VideoMetadata,
    isPlaying: Boolean,
    seekPositionMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    rotationDegrees: Int,
    requestFrame: (suspend (Long, Int) -> Bitmap?)?,
    onPositionTick: (Long) -> Unit,
    targetDecodeEdgePx: Int = 960,
    modifier: Modifier = Modifier
) {
    var displayedBitmap by remember(metadata.uri) { mutableStateOf<Bitmap?>(null) }
    val latestOnTick by rememberUpdatedState(onPositionTick)
    val latestTrimStart by rememberUpdatedState(trimStartMs)
    val latestTrimEnd by rememberUpdatedState(trimEndMs)
    val latestRotation by rememberUpdatedState(rotationDegrees)
    val latestRequestFrame by rememberUpdatedState(requestFrame)
    val latestDecodeEdgePx by rememberUpdatedState(targetDecodeEdgePx.coerceIn(640, 1440))

    DisposableEffect(metadata.uri) {
        onDispose {
            displayedBitmap?.let { if (!it.isRecycled) it.recycle() }
            displayedBitmap = null
        }
    }

    suspend fun loadAndDisplayFrame(timeMs: Long) {
        val loader = latestRequestFrame ?: return
        val raw = loader(timeMs * 1000L, latestDecodeEdgePx) ?: return
        val normRot = ((latestRotation % 360) + 360) % 360
        val finalBmp = if (normRot != 0) {
            val rotatedDims = GifGeometryCalculator.rotatedDimensions(raw.width, raw.height, normRot)
            try {
                FrameTransformer.transformFrame(
                    source = raw,
                    extraRotationDegrees = normRot,
                    cropRect = NormalizedCropRect.FULL,
                    targetWidth = rotatedDims.width,
                    targetHeight = rotatedDims.height,
                    highQualityDownscale = true
                )
            } finally {
                if (!raw.isRecycled) raw.recycle()
            }
        } else {
            raw
        }
        val prev = displayedBitmap
        displayedBitmap = finalBmp
        if (prev != null && prev !== finalBmp && !prev.isRecycled) {
            prev.recycle()
        }
    }

    // Animated playback loop synchronized to real GIF frame delays inside trim bounds
    LaunchedEffect(metadata.uri, isPlaying, trimStartMs, trimEndMs, rotationDegrees, targetDecodeEdgePx) {
        if (!isPlaying) return@LaunchedEffect
        val timestampsUs = metadata.frameTimestampsUs
        val delaysMs = metadata.frameDelaysMs
        if (timestampsUs.isEmpty() || delaysMs.isEmpty()) {
            loadAndDisplayFrame(latestTrimStart)
            return@LaunchedEffect
        }

        val start = latestTrimStart
        val end = latestTrimEnd.coerceAtLeast(start + 50L)
        val inRangeIndices = timestampsUs.indices.filter { idx ->
            val fStart = timestampsUs[idx] / 1000L
            val fEnd = fStart + (delaysMs.getOrNull(idx) ?: 100)
            fEnd > start && fStart < end
        }.ifEmpty { listOf(0) }

        var posInList = inRangeIndices.indexOfFirst { idx ->
            (timestampsUs[idx] / 1000L) >= seekPositionMs
        }.coerceAtLeast(0)

        while (true) {
            val frameIdx = inRangeIndices[posInList]
            val frameMs = (timestampsUs[frameIdx] / 1000L).coerceIn(start, end)
            loadAndDisplayFrame(frameMs)
            latestOnTick(frameMs)
            val delayMs = (delaysMs.getOrNull(frameIdx) ?: 100).toLong().coerceAtLeast(20L)
            delay(delayMs)
            posInList = (posInList + 1) % inRangeIndices.size
            if (inRangeIndices.size == 1) {
                // Single-frame GIF: keep displayed without busy-looping
                break
            }
        }
    }

    // Paused / manual scrub / frame-step sync
    LaunchedEffect(metadata.uri, isPlaying, seekPositionMs, rotationDegrees, targetDecodeEdgePx) {
        if (isPlaying) return@LaunchedEffect
        loadAndDisplayFrame(seekPositionMs)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        if (metadata.hasTransparency) {
            TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
        }
        val bmp = displayedBitmap
        if (bmp != null && !bmp.isRecycled) {
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = "Original Animated GIF Preview",
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .studioShimmer()
            )
        }
    }
}

@Composable
private fun NativeVideoTexturePlayer(
    uri: Uri,
    isPlaying: Boolean,
    seekPositionMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    rotationDegrees: Int,
    onPositionTick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var lastReportedPos by remember { mutableStateOf(-1L) }

    val latestTrimStart by rememberUpdatedState(trimStartMs)
    val latestTrimEnd by rememberUpdatedState(trimEndMs)
    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestOnTick by rememberUpdatedState(onPositionTick)

    DisposableEffect(uri) {
        onDispose {
            try {
                mediaPlayer?.release()
            } catch (_: Throwable) {
            }
            mediaPlayer = null
            isPrepared = false
        }
    }

    // Play / pause sync
    LaunchedEffect(isPrepared, isPlaying) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        try {
            if (isPlaying && !mp.isPlaying) {
                mp.start()
            } else if (!isPlaying && mp.isPlaying) {
                mp.pause()
            }
        } catch (_: Throwable) {
        }
    }

    // External seek sync (timeline scrub or frame step)
    LaunchedEffect(isPrepared, seekPositionMs) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        try {
            val currentMpPos = mp.currentPosition.toLong()
            if (abs(currentMpPos - seekPositionMs) > 90L && abs(lastReportedPos - seekPositionMs) > 60L) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mp.seekTo(seekPositionMs, MediaPlayer.SEEK_CLOSEST)
                } else {
                    mp.seekTo(seekPositionMs.toInt())
                }
            }
        } catch (_: Throwable) {
        }
    }

    // Playback polling loop that keeps video inside trim bounds
    LaunchedEffect(isPrepared, isPlaying, trimStartMs, trimEndMs) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPrepared) return@LaunchedEffect
        while (latestIsPlaying) {
            try {
                val pos = mp.currentPosition.toLong()
                val end = latestTrimEnd.coerceAtLeast(latestTrimStart + 50L)
                if (pos >= end || pos < latestTrimStart - 80L) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        mp.seekTo(latestTrimStart, MediaPlayer.SEEK_CLOSEST)
                    } else {
                        mp.seekTo(latestTrimStart.toInt())
                    }
                    lastReportedPos = latestTrimStart
                    latestOnTick(latestTrimStart)
                } else {
                    lastReportedPos = pos
                    latestOnTick(pos)
                }
            } catch (_: Throwable) {
                break
            }
            delay(40L)
        }
    }

    val reducedMotion = rememberReducedMotionEnabled()
    val videoAlpha by animateFloatAsState(
        targetValue = if (isPrepared) 1f else 0f,
        animationSpec = studioTween(220, reducedMotion),
        label = "videoPreparedFade"
    )

    Box(modifier = modifier) {
        if (!isPrepared) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .studioShimmer()
            )
        }
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            applyRotationTransform(this@apply, width, height, rotationDegrees)
                            val mp = MediaPlayer()
                            try {
                                mp.setDataSource(ctx, uri)
                                mp.setSurface(Surface(surface))
                                mp.isLooping = true
                                mp.setVolume(0f, 0f) // Silent GIF preview feel
                                mp.setOnPreparedListener { prepared ->
                                    isPrepared = true
                                    val startTarget = latestTrimStart.toInt()
                                    if (startTarget > 0) prepared.seekTo(startTarget)
                                    if (latestIsPlaying) prepared.start()
                                }
                                mp.prepareAsync()
                                mediaPlayer = mp
                            } catch (_: Throwable) {
                                try {
                                    mp.release()
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            applyRotationTransform(this@apply, width, height, rotationDegrees)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            try {
                                mediaPlayer?.release()
                            } catch (_: Throwable) {
                            }
                            mediaPlayer = null
                            isPrepared = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }
            },
            update = { view ->
                if (view.width > 0 && view.height > 0) {
                    applyRotationTransform(view, view.width, view.height, rotationDegrees)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .alpha(videoAlpha)
        )
    }
}

private fun applyRotationTransform(view: TextureView, width: Int, height: Int, rotationDegrees: Int) {
    val norm = ((rotationDegrees % 360) + 360) % 360
    val matrix = Matrix()
    val cx = width / 2f
    val cy = height / 2f
    if (norm != 0) {
        matrix.postRotate(norm.toFloat(), cx, cy)
        if (norm == 90 || norm == 270) {
            val scaleX = width.toFloat() / height.toFloat().coerceAtLeast(1f)
            val scaleY = height.toFloat() / width.toFloat().coerceAtLeast(1f)
            matrix.postScale(scaleY, scaleX, cx, cy)
        }
    }
    view.setTransform(matrix)
}

internal enum class CropDragHandle {
    NONE,
    MOVE,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP,
    BOTTOM,
    LEFT,
    RIGHT
}

internal fun detectCropHandle(
    offset: Offset,
    cropRect: NormalizedCropRect,
    widthPx: Float,
    heightPx: Float,
    touchTargetPx: Float = 48f
): CropDragHandle {
    val leftPx = cropRect.left * widthPx
    val topPx = cropRect.top * heightPx
    val rightPx = cropRect.right * widthPx
    val bottomPx = cropRect.bottom * heightPx
    val rectW = (rightPx - leftPx).coerceAtLeast(1f)
    val rectH = (bottomPx - topPx).coerceAtLeast(1f)

    val halfTarget = touchTargetPx / 2f
    val cornerTouchRadius = touchTargetPx.coerceAtMost(minOf(rectW, rectH) * 0.45f)
    val edgeTouchBand = (touchTargetPx * 0.8f).coerceAtMost(minOf(rectW, rectH) * 0.35f)

    val distTL = (offset - Offset(leftPx, topPx)).getDistance()
    val distTR = (offset - Offset(rightPx, topPx)).getDistance()
    val distBL = (offset - Offset(leftPx, bottomPx)).getDistance()
    val distBR = (offset - Offset(rightPx, bottomPx)).getDistance()

    fun inCornerBox(cornerX: Float, cornerY: Float, signX: Float, signY: Float): Boolean {
        val xMin = if (signX > 0) cornerX - halfTarget else cornerX - cornerTouchRadius
        val xMax = if (signX > 0) cornerX + cornerTouchRadius else cornerX + halfTarget
        val yMin = if (signY > 0) cornerY - halfTarget else cornerY - cornerTouchRadius
        val yMax = if (signY > 0) cornerY + cornerTouchRadius else cornerY + halfTarget
        return offset.x in xMin..xMax && offset.y in yMin..yMax
    }

    val isNearTL = distTL <= cornerTouchRadius || inCornerBox(leftPx, topPx, 1f, 1f)
    val isNearTR = distTR <= cornerTouchRadius || inCornerBox(rightPx, topPx, -1f, 1f)
    val isNearBL = distBL <= cornerTouchRadius || inCornerBox(leftPx, bottomPx, 1f, -1f)
    val isNearBR = distBR <= cornerTouchRadius || inCornerBox(rightPx, bottomPx, -1f, -1f)

    return when {
        isNearTL || isNearTR || isNearBL || isNearBR -> {
            val distances = mutableListOf<Pair<CropDragHandle, Float>>()
            if (isNearTL) distances.add(CropDragHandle.TOP_LEFT to distTL)
            if (isNearTR) distances.add(CropDragHandle.TOP_RIGHT to distTR)
            if (isNearBL) distances.add(CropDragHandle.BOTTOM_LEFT to distBL)
            if (isNearBR) distances.add(CropDragHandle.BOTTOM_RIGHT to distBR)
            distances.minByOrNull { it.second }?.first ?: CropDragHandle.NONE
        }
        offset.x in (leftPx - halfTarget)..(rightPx + halfTarget) && abs(offset.y - topPx) <= edgeTouchBand / 2f -> CropDragHandle.TOP
        offset.x in (leftPx - halfTarget)..(rightPx + halfTarget) && abs(offset.y - bottomPx) <= edgeTouchBand / 2f -> CropDragHandle.BOTTOM
        offset.y in (topPx - halfTarget)..(bottomPx + halfTarget) && abs(offset.x - leftPx) <= edgeTouchBand / 2f -> CropDragHandle.LEFT
        offset.y in (topPx - halfTarget)..(bottomPx + halfTarget) && abs(offset.x - rightPx) <= edgeTouchBand / 2f -> CropDragHandle.RIGHT
        offset.x in leftPx..rightPx && offset.y in topPx..bottomPx -> CropDragHandle.MOVE
        else -> CropDragHandle.NONE
    }
}

internal fun resizeCropCorner(
    c: NormalizedCropRect,
    handle: CropDragHandle,
    dx: Float,
    dy: Float,
    lockedRatio: Float?,
    srcAspect: Float
): NormalizedCropRect {
    val rNorm = ((lockedRatio ?: srcAspect) / srcAspect).coerceIn(0.05f, 20f)
    val minW = maxOf(0.08f, 0.08f * rNorm).coerceAtMost(0.5f)

    if (lockedRatio == null) {
        return when (handle) {
            CropDragHandle.TOP_LEFT -> NormalizedCropRect(
                left = (c.left + dx).coerceIn(0f, c.right - 0.08f),
                top = (c.top + dy).coerceIn(0f, c.bottom - 0.08f),
                right = c.right,
                bottom = c.bottom
            )
            CropDragHandle.TOP_RIGHT -> NormalizedCropRect(
                left = c.left,
                top = (c.top + dy).coerceIn(0f, c.bottom - 0.08f),
                right = (c.right + dx).coerceIn(c.left + 0.08f, 1f),
                bottom = c.bottom
            )
            CropDragHandle.BOTTOM_LEFT -> NormalizedCropRect(
                left = (c.left + dx).coerceIn(0f, c.right - 0.08f),
                top = c.top,
                right = c.right,
                bottom = (c.bottom + dy).coerceIn(c.top + 0.08f, 1f)
            )
            CropDragHandle.BOTTOM_RIGHT -> NormalizedCropRect(
                left = c.left,
                top = c.top,
                right = (c.right + dx).coerceIn(c.left + 0.08f, 1f),
                bottom = (c.bottom + dy).coerceIn(c.top + 0.08f, 1f)
            )
            else -> c
        }
    }

    val currentW = c.right - c.left
    return when (handle) {
        CropDragHandle.TOP_LEFT -> {
            val deltaW = if (abs(dx) >= abs(dy * rNorm)) -dx else -dy * rNorm
            val maxW = minOf(c.right, c.bottom * rNorm)
            val newW = (currentW + deltaW).coerceIn(minW, maxW)
            val newH = newW / rNorm
            NormalizedCropRect(
                left = c.right - newW,
                top = c.bottom - newH,
                right = c.right,
                bottom = c.bottom
            )
        }
        CropDragHandle.TOP_RIGHT -> {
            val deltaW = if (abs(dx) >= abs(dy * rNorm)) dx else -dy * rNorm
            val maxW = minOf(1f - c.left, c.bottom * rNorm)
            val newW = (currentW + deltaW).coerceIn(minW, maxW)
            val newH = newW / rNorm
            NormalizedCropRect(
                left = c.left,
                top = c.bottom - newH,
                right = c.left + newW,
                bottom = c.bottom
            )
        }
        CropDragHandle.BOTTOM_LEFT -> {
            val deltaW = if (abs(dx) >= abs(dy * rNorm)) -dx else dy * rNorm
            val maxW = minOf(c.right, (1f - c.top) * rNorm)
            val newW = (currentW + deltaW).coerceIn(minW, maxW)
            val newH = newW / rNorm
            NormalizedCropRect(
                left = c.right - newW,
                top = c.top,
                right = c.right,
                bottom = c.top + newH
            )
        }
        CropDragHandle.BOTTOM_RIGHT -> {
            val deltaW = if (abs(dx) >= abs(dy * rNorm)) dx else dy * rNorm
            val maxW = minOf(1f - c.left, (1f - c.top) * rNorm)
            val newW = (currentW + deltaW).coerceIn(minW, maxW)
            val newH = newW / rNorm
            NormalizedCropRect(
                left = c.left,
                top = c.top,
                right = c.left + newW,
                bottom = c.top + newH
            )
        }
        else -> c
    }
}

internal fun resizeCropEdge(
    c: NormalizedCropRect,
    handle: CropDragHandle,
    dx: Float,
    dy: Float,
    lockedRatio: Float?,
    srcAspect: Float
): NormalizedCropRect {
    val rNorm = ((lockedRatio ?: srcAspect) / srcAspect).coerceIn(0.05f, 20f)
    val minW = maxOf(0.08f, 0.08f * rNorm).coerceAtMost(0.5f)
    val minH = minW / rNorm

    if (lockedRatio == null) {
        return when (handle) {
            CropDragHandle.TOP -> NormalizedCropRect(
                left = c.left,
                top = (c.top + dy).coerceIn(0f, c.bottom - 0.08f),
                right = c.right,
                bottom = c.bottom
            )
            CropDragHandle.BOTTOM -> NormalizedCropRect(
                left = c.left,
                top = c.top,
                right = c.right,
                bottom = (c.bottom + dy).coerceIn(c.top + 0.08f, 1f)
            )
            CropDragHandle.LEFT -> NormalizedCropRect(
                left = (c.left + dx).coerceIn(0f, c.right - 0.08f),
                top = c.top,
                right = c.right,
                bottom = c.bottom
            )
            CropDragHandle.RIGHT -> NormalizedCropRect(
                left = c.left,
                top = c.top,
                right = (c.right + dx).coerceIn(c.left + 0.08f, 1f),
                bottom = c.bottom
            )
            else -> c
        }
    }

    val currentH = c.bottom - c.top
    val currentW = c.right - c.left
    val centerX = (c.left + c.right) / 2f
    val centerY = (c.top + c.bottom) / 2f

    return when (handle) {
        CropDragHandle.TOP -> {
            val deltaH = -dy
            val maxH = minOf(c.bottom, 1f / rNorm)
            val newH = (currentH + deltaH).coerceIn(minH, maxH)
            val newW = minOf(newH * rNorm, 1f)
            var newL = centerX - newW / 2f
            var newR = centerX + newW / 2f
            if (newL < 0f) {
                newR += (0f - newL)
                newL = 0f
            }
            if (newR > 1f) {
                newL -= (newR - 1f)
                newR = 1f
            }
            NormalizedCropRect(newL.coerceAtLeast(0f), c.bottom - newH, newR.coerceAtMost(1f), c.bottom)
        }
        CropDragHandle.BOTTOM -> {
            val deltaH = dy
            val maxH = minOf(1f - c.top, 1f / rNorm)
            val newH = (currentH + deltaH).coerceIn(minH, maxH)
            val newW = minOf(newH * rNorm, 1f)
            var newL = centerX - newW / 2f
            var newR = centerX + newW / 2f
            if (newL < 0f) {
                newR += (0f - newL)
                newL = 0f
            }
            if (newR > 1f) {
                newL -= (newR - 1f)
                newR = 1f
            }
            NormalizedCropRect(newL.coerceAtLeast(0f), c.top, newR.coerceAtMost(1f), c.top + newH)
        }
        CropDragHandle.LEFT -> {
            val deltaW = -dx
            val maxW = minOf(c.right, 1f * rNorm)
            val newW = (currentW + deltaW).coerceIn(minW, maxW)
            val newH = minOf(newW / rNorm, 1f)
            var newT = centerY - newH / 2f
            var newB = centerY + newH / 2f
            if (newT < 0f) {
                newB += (0f - newT)
                newT = 0f
            }
            if (newB > 1f) {
                newT -= (newB - 1f)
                newB = 1f
            }
            NormalizedCropRect(c.right - newW, newT.coerceAtLeast(0f), c.right, newB.coerceAtMost(1f))
        }
        CropDragHandle.RIGHT -> {
            val deltaW = dx
            val maxW = minOf(1f - c.left, 1f * rNorm)
            val newW = (currentW + deltaW).coerceIn(minW, maxW)
            val newH = minOf(newW / rNorm, 1f)
            var newT = centerY - newH / 2f
            var newB = centerY + newH / 2f
            if (newT < 0f) {
                newB += (0f - newT)
                newT = 0f
            }
            if (newB > 1f) {
                newT -= (newB - 1f)
                newB = 1f
            }
            NormalizedCropRect(c.left, newT.coerceAtLeast(0f), c.left + newW, newB.coerceAtMost(1f))
        }
        else -> c
    }
}

@Composable
private fun InteractiveCropOverlay(
    cropRect: NormalizedCropRect,
    sourceWidth: Int,
    sourceHeight: Int,
    aspectRatioPreset: AspectRatioPreset,
    accentColor: Color,
    onCropChanged: (NormalizedCropRect) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestCrop by rememberUpdatedState(cropRect)
    val latestAspect by rememberUpdatedState(aspectRatioPreset)
    val latestOnCrop by rememberUpdatedState(onCropChanged)
    val latestHaptics by rememberUpdatedState(LocalStudioHaptics.current)
    var activeHandle by remember { mutableStateOf(CropDragHandle.NONE) }

    Canvas(
        modifier = modifier.pointerInput(sourceWidth, sourceHeight) {
            detectDragGestures(
                onDragStart = { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val touchTargetPx = 48.dp.toPx()
                    activeHandle = detectCropHandle(
                        offset = offset,
                        cropRect = latestCrop,
                        widthPx = w,
                        heightPx = h,
                        touchTargetPx = touchTargetPx
                    )
                    if (activeHandle != CropDragHandle.NONE) {
                        latestHaptics.perform(StudioHapticEvent.HANDLE_GRAB)
                    }
                },
                onDragEnd = {
                    if (activeHandle != CropDragHandle.NONE) {
                        latestHaptics.perform(StudioHapticEvent.HANDLE_RELEASE)
                    }
                    activeHandle = CropDragHandle.NONE
                },
                onDragCancel = {
                    if (activeHandle != CropDragHandle.NONE) {
                        latestHaptics.perform(StudioHapticEvent.HANDLE_RELEASE)
                    }
                    activeHandle = CropDragHandle.NONE
                },
                onDrag = { change, dragAmount ->
                    if (activeHandle == CropDragHandle.NONE) return@detectDragGestures
                    change.consume()
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    val dx = dragAmount.x / w
                    val dy = dragAmount.y / h
                    val c = latestCrop
                    val srcAspect = sourceWidth.toFloat() / sourceHeight.toFloat().coerceAtLeast(1f)
                    val lockedRatio = latestAspect.ratio ?: if (latestAspect == AspectRatioPreset.ORIGINAL) srcAspect else null

                    val updated = when (activeHandle) {
                        CropDragHandle.MOVE -> {
                            val cw = c.right - c.left
                            val ch = c.bottom - c.top
                            val newL = (c.left + dx).coerceIn(0f, 1f - cw)
                            val newT = (c.top + dy).coerceIn(0f, 1f - ch)
                            NormalizedCropRect(newL, newT, newL + cw, newT + ch)
                        }
                        CropDragHandle.TOP_LEFT,
                        CropDragHandle.TOP_RIGHT,
                        CropDragHandle.BOTTOM_LEFT,
                        CropDragHandle.BOTTOM_RIGHT -> resizeCropCorner(
                            c = c,
                            handle = activeHandle,
                            dx = dx,
                            dy = dy,
                            lockedRatio = lockedRatio,
                            srcAspect = srcAspect
                        )
                        CropDragHandle.TOP,
                        CropDragHandle.BOTTOM,
                        CropDragHandle.LEFT,
                        CropDragHandle.RIGHT -> resizeCropEdge(
                            c = c,
                            handle = activeHandle,
                            dx = dx,
                            dy = dy,
                            lockedRatio = lockedRatio,
                            srcAspect = srcAspect
                        )
                        CropDragHandle.NONE -> c
                    }
                    latestOnCrop(updated.clamped())
                }
            )
        }
    ) {
        val w = size.width
        val h = size.height
        val left = cropRect.left * w
        val top = cropRect.top * h
        val right = cropRect.right * w
        val bottom = cropRect.bottom * h
        val rectW = (right - left).coerceAtLeast(1f)
        val rectH = (bottom - top).coerceAtLeast(1f)

        // Dim regions outside the crop box
        val scrim = Color.Black.copy(alpha = 0.56f)
        drawRect(scrim, Offset.Zero, Size(w, top))
        drawRect(scrim, Offset(0f, bottom), Size(w, h - bottom))
        drawRect(scrim, Offset(0f, top), Size(left, rectH))
        drawRect(scrim, Offset(right, top), Size(w - right, rectH))

        // Crisp border & subtle rule-of-thirds grid
        drawRect(
            color = accentColor,
            topLeft = Offset(left, top),
            size = Size(rectW, rectH),
            style = Stroke(width = 2.dp.toPx())
        )

        val gridColor = Color.White.copy(alpha = 0.28f)
        for (i in 1..2) {
            val gx = left + rectW * (i / 3f)
            val gy = top + rectH * (i / 3f)
            drawLine(gridColor, Offset(gx, top), Offset(gx, bottom), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
        }

        // Tactile corner L-brackets positioned slightly inside the crop rectangle boundaries
        val cornerLen = 18.dp.toPx().coerceAtMost(minOf(rectW, rectH) / 3f)
        val cornerStroke = 4.dp.toPx()
        val handleInset = 3.dp.toPx().coerceAtMost(minOf(rectW, rectH) / 8f)

        val corners = listOf(
            // Top-Left
            Triple(
                Offset(left + handleInset, top + handleInset),
                Offset(left + handleInset + cornerLen, top + handleInset),
                Offset(left + handleInset, top + handleInset + cornerLen)
            ),
            // Top-Right
            Triple(
                Offset(right - handleInset, top + handleInset),
                Offset(right - handleInset - cornerLen, top + handleInset),
                Offset(right - handleInset, top + handleInset + cornerLen)
            ),
            // Bottom-Left
            Triple(
                Offset(left + handleInset, bottom - handleInset),
                Offset(left + handleInset + cornerLen, bottom - handleInset),
                Offset(left + handleInset, bottom - handleInset - cornerLen)
            ),
            // Bottom-Right
            Triple(
                Offset(right - handleInset, bottom - handleInset),
                Offset(right - handleInset - cornerLen, bottom - handleInset),
                Offset(right - handleInset, bottom - handleInset - cornerLen)
            )
        )
        corners.forEach { (origin, hEnd, vEnd) ->
            drawLine(accentColor, origin, hEnd, strokeWidth = cornerStroke, cap = StrokeCap.Round)
            drawLine(accentColor, origin, vEnd, strokeWidth = cornerStroke, cap = StrokeCap.Round)
        }

        // Subtle edge handle indicators when rectangle is large enough
        if (rectW >= 64.dp.toPx() && rectH >= 64.dp.toPx()) {
            val edgePillLen = 16.dp.toPx().coerceAtMost(minOf(rectW, rectH) / 4f)
            val edgeStroke = 3.dp.toPx()
            val midX = (left + right) / 2f
            val midY = (top + bottom) / 2f
            val edgeColor = accentColor.copy(alpha = 0.9f)
            drawLine(edgeColor, Offset(midX - edgePillLen / 2f, top + handleInset), Offset(midX + edgePillLen / 2f, top + handleInset), strokeWidth = edgeStroke, cap = StrokeCap.Round)
            drawLine(edgeColor, Offset(midX - edgePillLen / 2f, bottom - handleInset), Offset(midX + edgePillLen / 2f, bottom - handleInset), strokeWidth = edgeStroke, cap = StrokeCap.Round)
            drawLine(edgeColor, Offset(left + handleInset, midY - edgePillLen / 2f), Offset(left + handleInset, midY + edgePillLen / 2f), strokeWidth = edgeStroke, cap = StrokeCap.Round)
            drawLine(edgeColor, Offset(right - handleInset, midY - edgePillLen / 2f), Offset(right - handleInset, midY + edgePillLen / 2f), strokeWidth = edgeStroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun LiveGifPreviewPlayer(
    previewData: LiveGifPreviewData?,
    isRendering: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    isPlaying: Boolean,
    currentPositionMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    ditherMode: DitherMode,
    outputFormat: OutputFormat = OutputFormat.GIF,
    showTransparencyGrid: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    val renderingCopy = if (outputFormat == OutputFormat.WEBP) {
        "Rendering WebP preview…"
    } else {
        "Rendering GIF preview…"
    }
    var frameIndex by remember(previewData) { mutableIntStateOf(0) }

    LaunchedEffect(previewData, isPlaying) {
        val data = previewData ?: return@LaunchedEffect
        if (data.frames.size <= 1 || !isPlaying) return@LaunchedEffect
        while (true) {
            val currentDelay = data.frameDelaysMs.getOrNull(frameIndex) ?: data.frameDelayMs
            delay(currentDelay.toLong().coerceAtLeast(20L))
            frameIndex = (frameIndex + 1) % data.frames.size
        }
    }

    LaunchedEffect(previewData, isPlaying, currentPositionMs, trimStartMs, trimEndMs) {
        val data = previewData ?: return@LaunchedEffect
        if (isPlaying || data.frames.size <= 1) return@LaunchedEffect
        val clipDuration = (trimEndMs - trimStartMs).coerceAtLeast(1L)
        val frac = ((currentPositionMs - trimStartMs).toFloat() / clipDuration.toFloat()).coerceIn(0f, 1f)
        frameIndex = (frac * (data.frames.size - 1)).toInt().coerceIn(0, data.frames.size - 1)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.padding(8.dp)
    ) {
        // Keep previous valid preview visible while regenerating; crossfade smoothly when a new preview arrives
        Crossfade(
            targetState = previewData,
            animationSpec = studioTween(220, reducedMotion),
            label = "liveGifPreviewCrossfade",
            modifier = Modifier.fillMaxSize()
        ) { activeData ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                val safeIndex = if (activeData != null && activeData.frames.isNotEmpty()) {
                    frameIndex.coerceIn(0, activeData.frames.lastIndex)
                } else {
                    0
                }
                val currentBitmap = activeData?.frames?.getOrNull(safeIndex)

                if (activeData != null && currentBitmap != null && !currentBitmap.isRecycled) {
                    val imageBitmap = remember(currentBitmap) { currentBitmap.asImageBitmap() }
                    val contentDesc = if (activeData.outputFormat == OutputFormat.WEBP) {
                        "Live Animated WebP Preview"
                    } else {
                        "Live GIF Preview"
                    }
                    val showAlphaGrid = showTransparencyGrid || activeData.hasTransparency
                    val previewAspect = (activeData.outputWidth.toFloat() / activeData.outputHeight.toFloat().coerceAtLeast(1f))
                        .coerceIn(0.15f, 6.5f)
                    BoxWithConstraints(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        val containerAspect = if (this.maxHeight.value > 0f) {
                            this.maxWidth.value / this.maxHeight.value
                        } else {
                            1f
                        }
                        val frameModifier = if (previewAspect >= containerAspect) {
                            Modifier
                                .fillMaxSize()
                                .aspectRatio(previewAspect, matchHeightConstraintsFirst = false)
                        } else {
                            Modifier
                                .fillMaxSize()
                                .aspectRatio(previewAspect, matchHeightConstraintsFirst = true)
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = frameModifier.clip(RoundedCornerShape(10.dp))
                        ) {
                            if (showAlphaGrid) {
                                TransparencyCheckerboard(modifier = Modifier.fillMaxSize())
                            }
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = contentDesc,
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.High,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Live preview specs pill overlay
                    val effectiveDither = activeData.renderedDitherMode ?: ditherMode
                    val specText = if (activeData.outputFormat == OutputFormat.WEBP) {
                        val qLabel = activeData.webpQualityLabel ?: "Balanced"
                        "WebP · ${activeData.outputWidth}×${activeData.outputHeight} · $qLabel"
                    } else {
                        "GIF · ${activeData.outputWidth}×${activeData.outputHeight} · ${activeData.resolvedColorCount}c · ${effectiveDither.label}"
                    }
                    Surface(
                        color = colors.surface.copy(alpha = 0.84f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = specText,
                            style = MonoSpecStyle,
                            color = colors.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                } else if (errorMessage != null && !isRendering) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .testTag("gif_preview_error")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(10.dp))
                        StudioPillButton(
                            text = "Retry Preview",
                            icon = Icons.Rounded.Refresh,
                            variant = StudioButtonVariant.TONAL,
                            onClick = onRetry,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                            modifier = Modifier.testTag("gif_preview_retry_button")
                        )
                    }
                } else {
                    // Initial loading placeholder before first preview frame is ready
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .studioShimmer(enabled = true)
                            .testTag("gif_preview_loading")
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                color = colors.primary,
                                strokeCap = StrokeCap.Round,
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = renderingCopy,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Non-blocking regenerating indicator pill while previous valid preview remains visible
        AnimatedVisibility(
            visible = isRendering && previewData != null,
            enter = fadeIn(animationSpec = studioTween(160, reducedMotion)),
            exit = fadeOut(animationSpec = studioTween(160, reducedMotion)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            StudioLoadingPill(
                text = renderingCopy,
                modifier = Modifier.testTag("gif_preview_updating_pill")
            )
        }

        // Non-blocking error recovery bar when regenerating over an existing preview fails
        AnimatedVisibility(
            visible = errorMessage != null && !isRendering && previewData != null,
            enter = fadeIn(animationSpec = studioTween(160, reducedMotion)),
            exit = fadeOut(animationSpec = studioTween(160, reducedMotion)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            Surface(
                color = colors.errorContainer.copy(alpha = 0.94f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.testTag("gif_preview_error")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Preview failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onErrorContainer
                    )
                    StudioPillButton(
                        text = "Retry Preview",
                        variant = StudioButtonVariant.TONAL,
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("gif_preview_retry_button")
                    )
                }
            }
        }
    }
}
