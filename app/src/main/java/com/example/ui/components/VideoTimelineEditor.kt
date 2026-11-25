package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.engine.OutputFormat
import com.example.engine.StudioFormatters
import com.example.ui.theme.MonoSpecStyle
import com.example.ui.theme.MonoTimestampStyle
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Interactive video filmstrip timeline with draggable start/end trim handles, synchronized
 * playhead scrubber, frame-by-frame stepping, and tappable timestamp badges.
 */
@Composable
fun VideoTimelineEditor(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    currentPositionMs: Long,
    isPlaying: Boolean,
    totalOutputFrames: Int,
    outputDurationMs: Long,
    filmstrip: List<Bitmap>,
    onTrimChanged: (startMs: Long, endMs: Long) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStepPrevFrame: () -> Unit,
    onStepNextFrame: () -> Unit,
    onEditTimestampsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoadingFilmstrip: Boolean = false,
    outputFormat: OutputFormat = OutputFormat.GIF
) {
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    val haptics = LocalStudioHaptics.current
    val safeDuration = durationMs.coerceAtLeast(100L)
    val startFraction = (trimStartMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 0.96f)
    val endFraction = (trimEndMs.toFloat() / safeDuration.toFloat()).coerceIn(startFraction + 0.02f, 1f)
    val playheadFraction = (currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    val latestStartMs by rememberUpdatedState(trimStartMs)
    val latestEndMs by rememberUpdatedState(trimEndMs)
    val latestOnTrim by rememberUpdatedState(onTrimChanged)
    val latestOnSeek by rememberUpdatedState(onSeek)
    val latestHaptics by rememberUpdatedState(haptics)

    Column(modifier = modifier.fillMaxWidth()) {
        // Playback transport bar & precise clip readouts
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StudioIconButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous frame",
                    onClick = {
                        haptics.perform(StudioHapticEvent.STEP_FRAME)
                        onStepPrevFrame()
                    },
                    size = 38.dp,
                    modifier = Modifier.testTag("prev_frame_button")
                )
                StudioIconButton(
                    icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    onClick = {
                        haptics.perform(StudioHapticEvent.SELECTION)
                        onTogglePlay()
                    },
                    containerColor = colors.primary,
                    tint = colors.onPrimary,
                    size = 42.dp,
                    modifier = Modifier.testTag("play_pause_button")
                )
                StudioIconButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = "Next frame",
                    onClick = {
                        haptics.perform(StudioHapticEvent.STEP_FRAME)
                        onStepNextFrame()
                    },
                    size = 38.dp,
                    modifier = Modifier.testTag("next_frame_button")
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = StudioFormatters.formatTimestamp(currentPositionMs),
                    style = MonoTimestampStyle,
                    color = colors.onSurface
                )
                Text(
                    text = "${outputFormat.shortLabel} ${StudioFormatters.formatDurationShort(outputDurationMs)} · $totalOutputFrames frames",
                    style = MonoSpecStyle,
                    color = colors.secondary
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Filmstrip track + trim handles + playhead
        val handleWidth = 18.dp
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            val density = LocalDensity.current
            val totalWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
            val handleWidthPx = with(density) { handleWidth.toPx() }
            val usableWidthPx = (totalWidthPx - handleWidthPx * 2f).coerceAtLeast(1f)

            val leftHandleX = (startFraction * usableWidthPx).roundToInt()
            val rightHandleX = (handleWidthPx + endFraction * usableWidthPx).roundToInt()
            val playheadX = (handleWidthPx + playheadFraction * usableWidthPx).roundToInt()

            // Background thumbnail filmstrip
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = handleWidth)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceContainerHigh)
                    .pointerInput(safeDuration) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val targetMs = (frac * safeDuration).roundToLong()
                                .coerceIn(latestStartMs, latestEndMs.coerceAtLeast(latestStartMs))
                            latestOnSeek(targetMs)
                        }
                    }
                    .pointerInput(safeDuration) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val targetMs = (frac * safeDuration).roundToLong()
                                .coerceIn(latestStartMs, latestEndMs.coerceAtLeast(latestStartMs))
                            latestOnSeek(targetMs)
                        }
                    }
            ) {
                Crossfade(
                    targetState = filmstrip.isNotEmpty(),
                    animationSpec = studioTween(240, reducedMotion),
                    label = "filmstripCrossfade",
                    modifier = Modifier.fillMaxSize()
                ) { hasThumbnails ->
                    if (hasThumbnails) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            filmstrip.forEach { bmp ->
                                if (!bmp.isRecycled) {
                                    val img = remember(bmp) { bmp.asImageBitmap() }
                                    Image(
                                        bitmap = img,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("filmstrip_shimmer_placeholder")
                        ) {
                            repeat(8) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .studioShimmer(enabled = isLoadingFilmstrip || filmstrip.isEmpty())
                                )
                            }
                        }
                    }
                }

                // Dimmed unselected regions outside trim handles
                val startDimFraction = startFraction.coerceIn(0f, 1f)
                val endDimFraction = (1f - endFraction).coerceIn(0f, 1f)
                if (startDimFraction > 0.001f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(startDimFraction)
                            .align(Alignment.CenterStart)
                            .background(Color.Black.copy(alpha = 0.65f))
                    )
                }
                if (endDimFraction > 0.001f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(endDimFraction)
                            .align(Alignment.CenterEnd)
                            .background(Color.Black.copy(alpha = 0.65f))
                    )
                }
            }

            // Top and bottom lavender trim selection rails
            val railWidthDp = with(density) {
                (rightHandleX - leftHandleX - handleWidthPx.roundToInt()).coerceAtLeast(0).toDp()
            }
            Box(
                modifier = Modifier
                    .offset { IntOffset(leftHandleX + handleWidthPx.roundToInt(), 0) }
                    .width(railWidthDp)
                    .fillMaxHeight()
                    .border(2.dp, colors.primary)
            )

            // Playhead vertical needle
            Box(
                modifier = Modifier
                    .offset { IntOffset(playheadX - 2, -2) }
                    .width(3.dp)
                    .height(62.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )

            // Left trim handle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset(leftHandleX, 0) }
                    .width(handleWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp))
                    .background(colors.primary)
                    .pointerInput(safeDuration, usableWidthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { latestHaptics.perform(StudioHapticEvent.HANDLE_GRAB) },
                            onDragEnd = { latestHaptics.perform(StudioHapticEvent.HANDLE_RELEASE) },
                            onDragCancel = { latestHaptics.perform(StudioHapticEvent.HANDLE_RELEASE) }
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaMs = ((dragAmount / usableWidthPx) * safeDuration).roundToLong()
                            val newStart = (latestStartMs + deltaMs)
                                .coerceIn(0L, (latestEndMs - 100L).coerceAtLeast(0L))
                            latestOnTrim(newStart, latestEndMs)
                            latestOnSeek(newStart)
                        }
                    }
                    .testTag("trim_start_handle")
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onPrimary)
                )
            }

            // Right trim handle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset(rightHandleX, 0) }
                    .width(handleWidth)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp))
                    .background(colors.primary)
                    .pointerInput(safeDuration, usableWidthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { latestHaptics.perform(StudioHapticEvent.HANDLE_GRAB) },
                            onDragEnd = { latestHaptics.perform(StudioHapticEvent.HANDLE_RELEASE) },
                            onDragCancel = { latestHaptics.perform(StudioHapticEvent.HANDLE_RELEASE) }
                        ) { change, dragAmount ->
                            change.consume()
                            val deltaMs = ((dragAmount / usableWidthPx) * safeDuration).roundToLong()
                            val newEnd = (latestEndMs + deltaMs)
                                .coerceIn((latestStartMs + 100L).coerceAtMost(safeDuration), safeDuration)
                            latestOnTrim(latestStartMs, newEnd)
                            latestOnSeek(newEnd)
                        }
                    }
                    .testTag("trim_end_handle")
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onPrimary)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Start, Clip Duration, and End timestamp pills (tap to jump to precise inputs)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            TimestampBadge(
                label = "IN",
                value = StudioFormatters.formatTimestamp(trimStartMs),
                onClick = onEditTimestampsClick
            )
            Text(
                text = "Clip ${StudioFormatters.formatDurationShort((trimEndMs - trimStartMs).coerceAtLeast(0L))}",
                style = MonoSpecStyle,
                color = colors.onSurfaceVariant
            )
            TimestampBadge(
                label = "OUT",
                value = StudioFormatters.formatTimestamp(trimEndMs),
                onClick = onEditTimestampsClick
            )
        }
    }
}

@Composable
private fun TimestampBadge(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = value,
                style = MonoSpecStyle,
                color = colors.onSurface
            )
        }
    }
}
