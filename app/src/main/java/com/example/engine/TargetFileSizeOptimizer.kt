package com.example.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class TargetSizeUnit(val label: String, val bytesPerUnit: Long) {
    KB("KB", 1024L),
    MB("MB", 1024L * 1024L)
}

enum class TargetSizeStatus {
    ALREADY_WITHIN_TARGET,
    RECOMMENDED,
    DIFFICULT_OR_IMPOSSIBLE
}

data class TargetSizeRecommendation(
    val status: TargetSizeStatus,
    val targetBytes: Long,
    val currentEstimatedBytes: Long,
    val recommendedEstimatedBytes: Long,
    val recommendedSettings: GifConversionSettings,
    val changesSummary: List<String>,
    val warningMessage: String? = null
)

/**
 * Analyzes representative frame encoding via [GifSizeEstimator] to propose balanced,
 * quality-prioritizing conversion settings that bring the estimated GIF size below a
 * user-selected target without silently altering settings.
 */
object TargetFileSizeOptimizer {

    private const val MIN_USABLE_SHORT_EDGE = 96
    private const val SEVERE_SHORT_EDGE_THRESHOLD = 160
    private const val SEVERE_FPS_THRESHOLD = 6f
    private const val SEVERE_COLOR_THRESHOLD = 24

    suspend fun analyze(
        frameSource: VideoFrameSource,
        currentSettings: GifConversionSettings,
        targetBytes: Long
    ): TargetSizeRecommendation = withContext(Dispatchers.Default) {
        val safeTarget = targetBytes.coerceAtLeast(1024L)
        val base = currentSettings.sanitized()
        val metadata = frameSource.metadata

        val currentEstimate = GifSizeEstimator.estimate(frameSource, base)
        if (currentEstimate.estimatedBytes <= safeTarget) {
            return@withContext TargetSizeRecommendation(
                status = TargetSizeStatus.ALREADY_WITHIN_TARGET,
                targetBytes = safeTarget,
                currentEstimatedBytes = currentEstimate.estimatedBytes,
                recommendedEstimatedBytes = currentEstimate.estimatedBytes,
                recommendedSettings = base,
                changesSummary = emptyList(),
                warningMessage = null
            )
        }

        val currentShortEdge = min(currentEstimate.outputWidth, currentEstimate.outputHeight)
            .coerceAtLeast(2)
        val currentSchedule = GifGeometryCalculator.buildFrameSchedule(
            trimStartMs = base.trimStartMs,
            trimEndMs = if (base.trimEndMs > 0L) base.trimEndMs else metadata.durationMs,
            videoDurationMs = metadata.durationMs,
            fps = base.fps,
            speed = base.speed,
            reverse = base.reverse,
            pingPong = base.pingPong,
            useOriginalFps = base.useOriginalFps,
            sourceFps = metadata.frameRate,
            sourceFrameTimestampsUs = metadata.frameTimestampsUs,
            sourceFrameDelaysMs = metadata.frameDelaysMs
        )
        val currentFps = currentSchedule.effectiveFps.coerceIn(1f, 30f)
        val currentColors = currentEstimate.resolvedColorCount.coerceIn(2, 256)

        val candidates = buildQualityPrioritizedCandidates(
            base = base,
            currentShortEdge = currentShortEdge,
            currentFps = currentFps,
            currentColors = currentColors,
            currentBytes = currentEstimate.estimatedBytes,
            targetBytes = safeTarget
        )

        var bestFitting: Pair<GifConversionSettings, SizeEstimate>? = null
        var smallestOverall: Pair<GifConversionSettings, SizeEstimate> = base to currentEstimate

        for (candidate in candidates) {
            coroutineContext.ensureActive()
            val est = GifSizeEstimator.estimate(frameSource, candidate)
            if (est.estimatedBytes < smallestOverall.second.estimatedBytes) {
                smallestOverall = candidate to est
            }
            if (est.estimatedBytes <= safeTarget) {
                // Candidates are ordered from highest visual quality to most aggressive,
                // so the first fitting candidate preserves the highest quality.
                bestFitting = candidate to est
                break
            }
        }

        if (bestFitting != null) {
            val (chosenSettings, chosenEstimate) = bestFitting
            val chosenShortEdge = min(chosenEstimate.outputWidth, chosenEstimate.outputHeight)
            val chosenFps = if (chosenSettings.useOriginalFps) currentFps else chosenSettings.fps
            val chosenColors = chosenEstimate.resolvedColorCount

            val isSevere = (chosenShortEdge < SEVERE_SHORT_EDGE_THRESHOLD && chosenShortEdge < currentShortEdge) ||
                (chosenFps < SEVERE_FPS_THRESHOLD && chosenFps < currentFps) ||
                (chosenColors <= SEVERE_COLOR_THRESHOLD && chosenColors < currentColors)

            val changes = describeChanges(
                before = base,
                beforeEstimate = currentEstimate,
                beforeFps = currentFps,
                after = chosenSettings,
                afterEstimate = chosenEstimate
            )

            return@withContext TargetSizeRecommendation(
                status = if (isSevere) TargetSizeStatus.DIFFICULT_OR_IMPOSSIBLE else TargetSizeStatus.RECOMMENDED,
                targetBytes = safeTarget,
                currentEstimatedBytes = currentEstimate.estimatedBytes,
                recommendedEstimatedBytes = chosenEstimate.estimatedBytes,
                recommendedSettings = chosenSettings,
                changesSummary = changes,
                warningMessage = if (isSevere) {
                    "Reaching ${StudioFormatters.formatFileSize(safeTarget)} requires heavy reductions " +
                        "in resolution, frame rate, or colors. Consider trimming the clip shorter for better visual quality."
                } else {
                    null
                }
            )
        }

        // Could not reach target even with aggressive compression settings
        val (bestEffortSettings, bestEffortEstimate) = smallestOverall
        val changes = describeChanges(
            before = base,
            beforeEstimate = currentEstimate,
            beforeFps = currentFps,
            after = bestEffortSettings,
            afterEstimate = bestEffortEstimate
        )

        TargetSizeRecommendation(
            status = TargetSizeStatus.DIFFICULT_OR_IMPOSSIBLE,
            targetBytes = safeTarget,
            currentEstimatedBytes = currentEstimate.estimatedBytes,
            recommendedEstimatedBytes = bestEffortEstimate.estimatedBytes,
            recommendedSettings = bestEffortSettings,
            changesSummary = changes,
            warningMessage = "Target ${StudioFormatters.formatFileSize(safeTarget)} is very tight for this clip. " +
                "Lowest reasonable estimate is ~${StudioFormatters.formatFileSize(bestEffortEstimate.estimatedBytes)}. " +
                "Trim the clip shorter or raise the target size to avoid severe quality loss."
        )
    }

    private fun buildQualityPrioritizedCandidates(
        base: GifConversionSettings,
        currentShortEdge: Int,
        currentFps: Float,
        currentColors: Int,
        currentBytes: Long,
        targetBytes: Long
    ): List<GifConversionSettings> {
        val ratio = (targetBytes.toDouble() / currentBytes.toDouble()).coerceIn(0.02, 0.99)
        val result = ArrayList<GifConversionSettings>(12)

        fun applyShortEdge(s: GifConversionSettings, desiredEdge: Int): GifConversionSettings {
            val evenEdge = max(MIN_USABLE_SHORT_EDGE, (min(desiredEdge, currentShortEdge) / 2) * 2)
                .coerceAtMost(currentShortEdge)
            if (evenEdge >= currentShortEdge) return s
            return when (evenEdge) {
                480 -> s.copy(resolutionPreset = ResolutionPreset.P480, customResolutionShortEdge = 480)
                360 -> s.copy(resolutionPreset = ResolutionPreset.P360, customResolutionShortEdge = 360)
                240 -> s.copy(resolutionPreset = ResolutionPreset.P240, customResolutionShortEdge = 240)
                else -> s.copy(resolutionPreset = ResolutionPreset.CUSTOM, customResolutionShortEdge = evenEdge)
            }
        }

        fun applyColorCount(s: GifConversionSettings, desiredColors: Int): GifConversionSettings {
            val clamped = desiredColors.coerceIn(16, currentColors)
            if (clamped >= currentColors) return s
            val preset = ColorQualityPreset.entries.firstOrNull { it.colorCount == clamped }
            return if (preset != null) {
                s.copy(colorQualityPreset = preset, customColorCount = clamped)
            } else {
                s.copy(colorQualityPreset = ColorQualityPreset.CUSTOM, customColorCount = clamped)
            }
        }

        fun applyFps(s: GifConversionSettings, desiredFps: Float): GifConversionSettings {
            val targetFps = desiredFps.coerceIn(5f, currentFps)
            if (targetFps >= currentFps - 0.5f) return s
            return s.copy(useOriginalFps = false, fps = targetFps)
        }

        fun addUnique(candidate: GifConversionSettings) {
            val clean = candidate.sanitized()
            if (clean != base && result.none { it == clean }) {
                result.add(clean)
            }
        }

        // Tier 1: Preserve resolution & FPS — tune colors & dithering first
        if (ratio >= 0.55) {
            addUnique(
                applyColorCount(base, min(currentColors, 128)).copy(
                    ditherMode = if (base.ditherMode == DitherMode.FLOYD_STEINBERG) DitherMode.ORDERED else base.ditherMode
                )
            )
            addUnique(
                applyColorCount(base, min(currentColors, 96)).copy(ditherMode = DitherMode.ORDERED)
            )
        }

        // Tier 2: Gentle FPS + moderate color reduction (keeps full resolution)
        if (ratio >= 0.35) {
            val gentleFps = min(currentFps, max(12f, (currentFps * 0.78f).roundToInt().toFloat()))
            addUnique(
                applyFps(applyColorCount(base, min(currentColors, 128)), gentleFps)
            )
            addUnique(
                applyFps(applyColorCount(base, min(currentColors, 96)), min(currentFps, 12f))
                    .copy(ditherMode = DitherMode.ORDERED)
            )
        }

        // Tier 3: Analytical scale estimate guided by ratio (balanced resolution + FPS + colors)
        val linearScale = sqrt(ratio).coerceIn(0.32, 0.90)
        val balancedEdge = (currentShortEdge * linearScale.pow(0.75)).roundToInt()
        val balancedFps = min(currentFps, max(10f, (currentFps * 0.75f).roundToInt().toFloat()))
        addUnique(
            applyShortEdge(
                applyFps(applyColorCount(base, min(currentColors, 128)), balancedFps),
                balancedEdge
            )
        )

        // Tier 4: Progressive resolution + FPS + palette ladder
        val steps = listOf(
            Triple(0.80, 12f, 96),
            Triple(0.68, 12f, 64),
            Triple(0.58, 10f, 64),
            Triple(0.48, 10f, 48),
            Triple(0.40, 8f, 32),
            Triple(0.32, 6f, 24)
        )
        for ((edgeFactor, stepFps, stepColors) in steps) {
            val edge = (currentShortEdge * edgeFactor).roundToInt()
            val dither = when {
                stepColors >= 64 -> if (base.ditherMode == DitherMode.NONE) DitherMode.NONE else DitherMode.ORDERED
                else -> DitherMode.NONE
            }
            addUnique(
                applyShortEdge(
                    applyFps(applyColorCount(base, min(currentColors, stepColors)), min(currentFps, stepFps)),
                    edge
                ).copy(ditherMode = dither)
            )
        }

        // Tier 5: Hard floor fallback (checks feasibility & provides smallest reasonable estimate)
        addUnique(
            applyShortEdge(
                applyFps(applyColorCount(base, min(currentColors, 16)), min(currentFps, 5f)),
                MIN_USABLE_SHORT_EDGE
            ).copy(ditherMode = DitherMode.NONE)
        )

        return result
    }

    private fun describeChanges(
        before: GifConversionSettings,
        beforeEstimate: SizeEstimate,
        beforeFps: Float,
        after: GifConversionSettings,
        afterEstimate: SizeEstimate
    ): List<String> {
        val changes = mutableListOf<String>()

        if (beforeEstimate.outputWidth != afterEstimate.outputWidth ||
            beforeEstimate.outputHeight != afterEstimate.outputHeight
        ) {
            changes += "Resolution: ${beforeEstimate.outputWidth}×${beforeEstimate.outputHeight} → " +
                "${afterEstimate.outputWidth}×${afterEstimate.outputHeight}"
        }

        val afterFps = if (after.useOriginalFps) beforeFps else after.fps
        if (before.useOriginalFps != after.useOriginalFps || abs(beforeFps - afterFps) >= 0.5f) {
            val beforeLabel = if (before.useOriginalFps) "Original (${beforeFps.roundToInt()} FPS)" else "${before.fps.roundToInt()} FPS"
            val afterLabel = if (after.useOriginalFps) "Original (${afterFps.roundToInt()} FPS)" else "${after.fps.roundToInt()} FPS"
            changes += "Frame rate: $beforeLabel → $afterLabel"
        }

        if (beforeEstimate.resolvedColorCount != afterEstimate.resolvedColorCount) {
            changes += "Colors: ${beforeEstimate.resolvedColorCount} → ${afterEstimate.resolvedColorCount}"
        }

        if (before.ditherMode != after.ditherMode) {
            changes += "Dithering: ${before.ditherMode.label} → ${after.ditherMode.label}"
        }

        return changes
    }
}
