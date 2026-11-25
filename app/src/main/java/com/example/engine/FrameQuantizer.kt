package com.example.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Perceptual Oklab color quantizer and temporally stable pixel dithering engine.
 *
 * Key properties:
 * - **Exact-24-bit adaptive histogram** (`GlobalPaletteAccumulator`): keeps exact 24-bit RGB
 *   colors up to 49,152 distinct shades before gracefully compacting to 6-bit (64^3) weighted
 *   buckets, eliminating the 5-bit (32-level) stair-step ceiling on smooth gradients, grayscale
 *   ramps, skin tones, and anime skies.
 * - **Weighted Oklab minimum-SSE splitting + Lloyd-Max k-means refinement**: partitions and
 *   refines colors in the perceptually uniform Oklab space with sublinear weighting (`count^0.70`)
 *   so dominant flat backgrounds cannot starve smooth gradients, skin tones, accents, or shorter
 *   scenes in multi-scene clips.
 * - **Shared Global Palette**: one deterministic palette is built across representative frames and
 *   reused across every exported frame to prevent frame-to-frame color flickering.
 * - **Improved dithering modes**:
 *   - [DitherMode.NONE]: Full 24-bit exact perceptual Oklab nearest-color mapping.
 *   - [DitherMode.ORDERED]: Spatially fixed 8x8 Bayer two-candidate perceptual Oklab pair
 *     dithering with a flat-match guard (zero dots on flat or well-matched regions, zero
 *     cross-hue speckles, 100% temporal stability).
 *   - [DitherMode.FLOYD_STEINBERG]: Deterministic serpentine clamped perceptual error diffusion
 *     with floating-point error buffers (eliminates diagonal worms, dark rounding bias, and
 *     runaway complementary-color dots).
 *
 * Used identically by the Live GIF Preview, Size Estimator, and final GIF89a encoder.
 */
object FrameQuantizer {

    // Standard 8x8 Bayer threshold matrix (0..63), spatially fixed across all frames
    private val BAYER_8X8_INT = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
        intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
        intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
        intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
        intArrayOf(63, 31, 55, 23, 61, 29, 53, 21)
    )

    // Precomputed exact IEC 61966-2-1 sRGB <-> linear transfer LUTs
    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val v = i / 255.0
        (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    private val LINEAR_TO_SRGB = IntArray(4097) { i ->
        val lin = i / 4096.0
        val srgb = if (lin <= 0.0031308) lin * 12.92 else 1.055 * lin.pow(1.0 / 2.4) - 0.055
        (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    // Perceptual Oklab flat-match threshold (ΔE_ok <= 0.004 -> already visually exact)
    private const val ORDERED_FLAT_MATCH_EPS_SQ = 0.000016f
    private const val ORDERED_MAX_PAIR_DIST_SQ = 0.045f
    private const val ORDERED_VARIANCE_PENALTY = 0.05f

    // Clamped serpentine Floyd-Steinberg parameters
    private const val FS_DIFFUSION_STRENGTH = 0.85f
    private const val FS_ERROR_CLAMP = 28f

    private const val WEIGHT_EXPONENT = 0.70

    /**
     * Exact L-sorted perceptual Oklab nearest-color search index over a palette of up to 256 RGB
     * entries. Binary search + outward early termination inspects only a small fraction of entries
     * while guaranteeing the exact global minimum Oklab distance.
     */
    internal class OklabPaletteIndex(val palette: IntArray) {
        val size: Int = palette.size
        val palR = IntArray(size)
        val palG = IntArray(size)
        val palB = IntArray(size)

        // Sorted by Oklab L ascending for fast exact branch-and-bound nearest lookup
        val sortedL = FloatArray(size)
        val sortedA = FloatArray(size)
        val sortedB = FloatArray(size)
        val origIndex = IntArray(size)

        // Direct lookup by original palette index
        val origL = FloatArray(size)
        val origA = FloatArray(size)
        val origB = FloatArray(size)

        init {
            val tmpL = FloatArray(size)
            val tmpA = FloatArray(size)
            val tmpB = FloatArray(size)
            val order = Array(size) { it }
            for (i in 0 until size) {
                val rgb = palette[i]
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                palR[i] = r
                palG[i] = g
                palB[i] = b
                val lr = SRGB_TO_LINEAR[r]
                val lg = SRGB_TO_LINEAR[g]
                val lb = SRGB_TO_LINEAR[b]
                val l_ = cbrt((0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb).toDouble()).toFloat()
                val m_ = cbrt((0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb).toDouble()).toFloat()
                val s_ = cbrt((0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb).toDouble()).toFloat()
                val okL = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
                val okA = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
                val okB = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
                tmpL[i] = okL
                tmpA[i] = okA
                tmpB[i] = okB
                origL[i] = okL
                origA[i] = okA
                origB[i] = okB
            }
            order.sortWith { i1, i2 ->
                val cmp = tmpL[i1].compareTo(tmpL[i2])
                if (cmp != 0) cmp else i1.compareTo(i2)
            }
            for (k in 0 until size) {
                val orig = order[k]
                sortedL[k] = tmpL[orig]
                sortedA[k] = tmpA[orig]
                sortedB[k] = tmpB[orig]
                origIndex[k] = orig
            }
        }

        /**
         * Returns the exact nearest original palette index for Oklab `(L, a, b)`.
         */
        fun findNearestIndex(L: Float, a: Float, b: Float): Int {
            if (size <= 1) return 0
            // Binary search for insertion point in sortedL
            var lo = 0
            var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (sortedL[mid] < L) lo = mid + 1 else hi = mid - 1
            }

            var bestOrig = origIndex[lo.coerceIn(0, size - 1)]
            var bestDistSq = Float.POSITIVE_INFINITY

            var left = lo - 1
            var right = lo
            while (left >= 0 || right < size) {
                if (right < size) {
                    val dL = sortedL[right] - L
                    val dLSq = dL * dL
                    if (dLSq >= bestDistSq) {
                        right = size // Stop expanding right
                    } else {
                        val dA = sortedA[right] - a
                        val dB = sortedB[right] - b
                        val distSq = dLSq + dA * dA + dB * dB
                        val idx = origIndex[right]
                        if (distSq < bestDistSq || (distSq == bestDistSq && idx < bestOrig)) {
                            bestDistSq = distSq
                            bestOrig = idx
                        }
                        right++
                    }
                }
                if (left >= 0) {
                    val dL = L - sortedL[left]
                    val dLSq = dL * dL
                    if (dLSq >= bestDistSq) {
                        left = -1 // Stop expanding left
                    } else {
                        val dA = sortedA[left] - a
                        val dB = sortedB[left] - b
                        val distSq = dLSq + dA * dA + dB * dB
                        val idx = origIndex[left]
                        if (distSq < bestDistSq || (distSq == bestDistSq && idx < bestOrig)) {
                            bestDistSq = distSq
                            bestOrig = idx
                        }
                        left--
                    }
                }
            }
            return bestOrig
        }

        /**
         * Finds the best two-color Oklab ordered-dither pair `(idx1, idx2, tLevels)` for `(L, a, b)`.
         * Packed as: `idx1 (bits 0..7) | idx2 (bits 8..15) | tLevels 0..64 (bits 16..22)`.
         */
        fun findBestOrderedPairPacked(L: Float, a: Float, b: Float): Int {
            if (size <= 1) return 0
            val idx1 = findNearestIndex(L, a, b)
            val p1L = origL[idx1]
            val p1A = origA[idx1]
            val p1B = origB[idx1]
            val uL = L - p1L
            val uA = a - p1A
            val uB = b - p1B
            val d1Sq = uL * uL + uA * uA + uB * uB

            // Flat-area / exact-match guard: never add dither dots to already accurate pixels
            if (d1Sq <= ORDERED_FLAT_MATCH_EPS_SQ) {
                return (idx1 and 0xFF) or ((idx1 and 0xFF) shl 8)
            }

            val maxLRadius = min(0.22f, sqrt(d1Sq) * 4.5f + 0.045f)

            // Locate L range [L - maxLRadius, L + maxLRadius] via binary search
            val minL = L - maxLRadius
            val maxL = L + maxLRadius
            var lo = 0
            var hi = size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (sortedL[mid] < minL) lo = mid + 1 else hi = mid - 1
            }

            var bestIdx2 = idx1
            var bestTLevels = 0
            var bestScore = d1Sq

            var k = lo
            while (k < size && sortedL[k] <= maxL) {
                val idx2 = origIndex[k]
                if (idx2 != idx1) {
                    val vL = sortedL[k] - p1L
                    val vA = sortedA[k] - p1A
                    val vB = sortedB[k] - p1B
                    val vLenSq = vL * vL + vA * vA + vB * vB
                    if (vLenSq > 1e-6f && vLenSq <= ORDERED_MAX_PAIR_DIST_SQ) {
                        val dot = uL * vL + uA * vA + uB * vB
                        if (dot > 0f) {
                            val t = (dot / vLenSq).coerceIn(0f, 0.5f)
                            val tLevels = (t * 64f + 0.5f).toInt().coerceIn(0, 64)
                            if (tLevels > 0) {
                                val tEff = tLevels / 64f
                                val rL = uL - tEff * vL
                                val rA = uA - tEff * vA
                                val rB = uB - tEff * vB
                                val resSq = rL * rL + rA * rA + rB * rB
                                val score = resSq + ORDERED_VARIANCE_PENALTY * tEff * (1f - tEff) * vLenSq
                                if (score < bestScore) {
                                    bestScore = score
                                    bestIdx2 = idx2
                                    bestTLevels = tLevels
                                }
                            }
                        }
                    }
                }
                k++
            }

            return (idx1 and 0xFF) or ((bestIdx2 and 0xFF) shl 8) or ((bestTLevels and 0x7F) shl 16)
        }
    }

    /**
     * Reusable exact 24-bit RGB direct-mapped lookup cache shared across frames of a single
     * encoding or preview pass using the exact same [GlobalPalette]. Zero 5-bit truncation.
     */
    class ExactLookupCache {
        private val nearestKeys = IntArray(CACHE_SIZE) { -1 }
        private val nearestVals = IntArray(CACHE_SIZE)

        private val orderedKeys = IntArray(CACHE_SIZE) { -1 }
        private val orderedVals = IntArray(CACHE_SIZE)

        internal fun findNearestRgb(rgb: Int, index: OklabPaletteIndex): Int {
            val clean = rgb and 0x00FFFFFF
            val slot = mix24(clean) and CACHE_MASK
            if (nearestKeys[slot] == clean) {
                return nearestVals[slot]
            }
            val r = (clean ushr 16) and 0xFF
            val g = (clean ushr 8) and 0xFF
            val b = clean and 0xFF
            val lr = SRGB_TO_LINEAR[r]
            val lg = SRGB_TO_LINEAR[g]
            val lb = SRGB_TO_LINEAR[b]
            val l_ = cbrt((0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb).toDouble()).toFloat()
            val m_ = cbrt((0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb).toDouble()).toFloat()
            val s_ = cbrt((0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb).toDouble()).toFloat()
            val okL = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
            val okA = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
            val okB = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
            val idx = index.findNearestIndex(okL, okA, okB)
            nearestKeys[slot] = clean
            nearestVals[slot] = idx
            return idx
        }

        internal fun findOrderedPairPacked(rgb: Int, index: OklabPaletteIndex): Int {
            val clean = rgb and 0x00FFFFFF
            val slot = mix24(clean) and CACHE_MASK
            if (orderedKeys[slot] == clean) {
                return orderedVals[slot]
            }
            val r = (clean ushr 16) and 0xFF
            val g = (clean ushr 8) and 0xFF
            val b = clean and 0xFF
            val lr = SRGB_TO_LINEAR[r]
            val lg = SRGB_TO_LINEAR[g]
            val lb = SRGB_TO_LINEAR[b]
            val l_ = cbrt((0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb).toDouble()).toFloat()
            val m_ = cbrt((0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb).toDouble()).toFloat()
            val s_ = cbrt((0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb).toDouble()).toFloat()
            val okL = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
            val okA = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
            val okB = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
            val packed = index.findBestOrderedPairPacked(okL, okA, okB)
            orderedKeys[slot] = clean
            orderedVals[slot] = packed
            return packed
        }

        private fun mix24(x: Int): Int {
            var h = x
            h = h xor (h ushr 12)
            h *= 0x45d9f3b
            return h xor (h ushr 14)
        }

        companion object {
            private const val CACHE_SIZE = 16384
            private const val CACHE_MASK = CACHE_SIZE - 1
        }
    }

    /**
     * Shared global color palette generated across representative frames of a clip.
     *
     * @property colors Opaque RGB palette entries (`0x00RRGGBB`), deterministically ordered.
     * @property transparentIndex Index reserved for transparent pixels (`colors.size`), or `-1` if opaque.
     * @property isPreservedSourcePalette True when reused verbatim from an imported GIF's original
     *   Global Color Table so unresized GIF-to-GIF edits avoid unnecessary re-quantization and
     *   double-dithering.
     */
    class GlobalPalette(
        val colors: IntArray,
        val transparentIndex: Int = -1,
        val isPreservedSourcePalette: Boolean = false
    ) {
        val hasTransparency: Boolean get() = transparentIndex >= 0
        val effectiveColorCount: Int get() = (colors.size + if (hasTransparency) 1 else 0).coerceAtMost(256)

        internal val oklabIndex: OklabPaletteIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
            OklabPaletteIndex(colors)
        }
    }

    /**
     * Memory-bounded streaming color histogram accumulator that preserves exact 24-bit RGB
     * shades up to [MAX_EXACT_COLORS] (preventing 5-bit banding on smooth gradients) and
     * gracefully compacts into 6-bit (64^3) weighted buckets only on ultra-high-entropy clips.
     */
    class GlobalPaletteAccumulator {
        private var capacity = INITIAL_CAPACITY
        private var mask = capacity - 1
        private var keys = IntArray(capacity) { EMPTY_KEY }
        private var weights = DoubleArray(capacity)

        // Allocated lazily ONLY if distinct 24-bit colors exceed MAX_EXACT_COLORS
        private var compactedTo6Bit = false
        private var sumR: DoubleArray? = null
        private var sumG: DoubleArray? = null
        private var sumB: DoubleArray? = null

        private var entryCount = 0

        var hasTransparency: Boolean = false
            private set

        /**
         * Accumulates color statistics from a single decoded frame without retaining the frame buffer.
         *
         * @param frameWeight Optional scene-balance weight (`>= 1.0`) to keep shorter/rarer scenes
         *   in multi-scene clips well represented in the shared global palette.
         */
        fun addFramePixels(
            pixels: IntArray,
            respectAlpha: Boolean = true,
            frameWeight: Double = 1.0
        ) {
            if (pixels.isEmpty()) return
            val w = frameWeight.coerceIn(0.25, 4.0)
            val frameHasAlpha = respectAlpha && detectTransparency(pixels)
            if (frameHasAlpha) {
                hasTransparency = true
            }

            // Deterministically subsample very large individual frames while keeping <= 160k px 100% exact
            val stride = when {
                pixels.size > 400_000 -> 3
                pixels.size > 160_000 -> 2
                else -> 1
            }

            var i = 0
            while (i < pixels.size) {
                val p = pixels[i]
                i += stride
                if (frameHasAlpha && ((p ushr 24) and 0xFF) < 128) {
                    continue
                }
                val rgb = p and 0x00FFFFFF
                if (!compactedTo6Bit) {
                    insertExact(rgb, w)
                } else {
                    val r = (rgb ushr 16) and 0xFF
                    val g = (rgb ushr 8) and 0xFF
                    val b = rgb and 0xFF
                    val key6 = ((r ushr 2) shl 12) or ((g ushr 2) shl 6) or (b ushr 2)
                    insert6Bit(key6, r.toDouble(), g.toDouble(), b.toDouble(), w)
                }
            }
        }

        private fun insertExact(rgb: Int, w: Double) {
            if (entryCount >= MAX_EXACT_COLORS) {
                compactTo6Bit()
                val r = (rgb ushr 16) and 0xFF
                val g = (rgb ushr 8) and 0xFF
                val b = rgb and 0xFF
                val key6 = ((r ushr 2) shl 12) or ((g ushr 2) shl 6) or (b ushr 2)
                insert6Bit(key6, r.toDouble(), g.toDouble(), b.toDouble(), w)
                return
            }
            if ((entryCount + 1) * 4 >= capacity * 3) {
                growExactTable()
            }
            var slot = hashKey(rgb) and mask
            while (true) {
                val k = keys[slot]
                if (k == EMPTY_KEY) {
                    keys[slot] = rgb
                    weights[slot] = w
                    entryCount++
                    return
                }
                if (k == rgb) {
                    weights[slot] += w
                    return
                }
                slot = (slot + 1) and mask
            }
        }

        private fun growExactTable() {
            val newCap = capacity shl 1
            val newMask = newCap - 1
            val newKeys = IntArray(newCap) { EMPTY_KEY }
            val newWeights = DoubleArray(newCap)
            for (i in 0 until capacity) {
                val k = keys[i]
                if (k != EMPTY_KEY) {
                    var slot = hashKey(k) and newMask
                    while (newKeys[slot] != EMPTY_KEY) {
                        slot = (slot + 1) and newMask
                    }
                    newKeys[slot] = k
                    newWeights[slot] = weights[i]
                }
            }
            capacity = newCap
            mask = newMask
            keys = newKeys
            weights = newWeights
        }

        private fun compactTo6Bit() {
            val oldCap = capacity
            val oldKeys = keys
            val oldWeights = weights

            val newCap = 65536
            capacity = newCap
            mask = newCap - 1
            keys = IntArray(newCap) { EMPTY_KEY }
            weights = DoubleArray(newCap)
            sumR = DoubleArray(newCap)
            sumG = DoubleArray(newCap)
            sumB = DoubleArray(newCap)
            entryCount = 0
            compactedTo6Bit = true

            for (i in 0 until oldCap) {
                val rgb = oldKeys[i]
                if (rgb != EMPTY_KEY) {
                    val w = oldWeights[i]
                    val r = ((rgb ushr 16) and 0xFF).toDouble()
                    val g = ((rgb ushr 8) and 0xFF).toDouble()
                    val b = (rgb and 0xFF).toDouble()
                    val key6 = ((((rgb ushr 16) and 0xFF) ushr 2) shl 12) or
                        ((((rgb ushr 8) and 0xFF) ushr 2) shl 6) or
                        ((rgb and 0xFF) ushr 2)
                    insert6Bit(key6, r, g, b, w)
                }
            }
        }

        private fun insert6Bit(key6: Int, r: Double, g: Double, b: Double, w: Double) {
            if ((entryCount + 1) * 4 >= capacity * 3 && capacity < MAX_6BIT_CAPACITY) {
                grow6BitTable()
            }
            val sR = sumR!!
            val sG = sumG!!
            val sB = sumB!!
            var slot = hashKey(key6) and mask
            while (true) {
                val k = keys[slot]
                if (k == EMPTY_KEY) {
                    keys[slot] = key6
                    weights[slot] = w
                    sR[slot] = r * w
                    sG[slot] = g * w
                    sB[slot] = b * w
                    entryCount++
                    return
                }
                if (k == key6) {
                    weights[slot] += w
                    sR[slot] += r * w
                    sG[slot] += g * w
                    sB[slot] += b * w
                    return
                }
                slot = (slot + 1) and mask
            }
        }

        private fun grow6BitTable() {
            val newCap = (capacity shl 1).coerceAtMost(MAX_6BIT_CAPACITY)
            val newMask = newCap - 1
            val newKeys = IntArray(newCap) { EMPTY_KEY }
            val newWeights = DoubleArray(newCap)
            val newSR = DoubleArray(newCap)
            val newSG = DoubleArray(newCap)
            val newSB = DoubleArray(newCap)
            val oldSR = sumR!!
            val oldSG = sumG!!
            val oldSB = sumB!!
            for (i in 0 until capacity) {
                val k = keys[i]
                if (k != EMPTY_KEY) {
                    var slot = hashKey(k) and newMask
                    while (newKeys[slot] != EMPTY_KEY) {
                        slot = (slot + 1) and newMask
                    }
                    newKeys[slot] = k
                    newWeights[slot] = weights[i]
                    newSR[slot] = oldSR[i]
                    newSG[slot] = oldSG[i]
                    newSB[slot] = oldSB[i]
                }
            }
            capacity = newCap
            mask = newMask
            keys = newKeys
            weights = newWeights
            sumR = newSR
            sumG = newSG
            sumB = newSB
        }

        private fun hashKey(k: Int): Int {
            var h = k
            h = h xor (h ushr 16)
            h *= 0x45d9f3b
            return h xor (h ushr 16)
        }

        /**
         * Finalizes one shared [GlobalPalette] honoring [requestedColorCount] (2..256, or `null` for Auto).
         */
        fun build(requestedColorCount: Int?, forceTransparency: Boolean = false): GlobalPalette {
            val includeTransparency = hasTransparency || forceTransparency
            val targetColors = resolveTargetColorCount(requestedColorCount).coerceIn(2, 256)
            val maxOpaqueColors = if (includeTransparency) (targetColors - 1).coerceIn(1, 255) else targetColors

            if (entryCount == 0) {
                val fallback = intArrayOf(0x000000)
                return GlobalPalette(fallback, if (includeTransparency) 1 else -1)
            }

            // Fast exact path: clip has <= maxOpaqueColors exact 24-bit shades -> ZERO quantization error!
            if (!compactedTo6Bit && entryCount <= maxOpaqueColors) {
                val exact = IntArray(entryCount)
                var pos = 0
                for (i in 0 until capacity) {
                    val k = keys[i]
                    if (k != EMPTY_KEY) exact[pos++] = k
                }
                exact.sort()
                val transparentIndex = if (includeTransparency) exact.size else -1
                return GlobalPalette(colors = exact, transparentIndex = transparentIndex)
            }

            // Extract populated entries into contiguous primitive arrays
            val n = entryCount
            val entryRgb = IntArray(n)
            val entryWeight = DoubleArray(n)
            var pos = 0
            if (!compactedTo6Bit) {
                for (i in 0 until capacity) {
                    val k = keys[i]
                    if (k != EMPTY_KEY) {
                        entryRgb[pos] = k
                        entryWeight[pos] = weights[i]
                        pos++
                    }
                }
            } else {
                val sR = sumR!!
                val sG = sumG!!
                val sB = sumB!!
                for (i in 0 until capacity) {
                    if (keys[i] != EMPTY_KEY) {
                        val w = weights[i].coerceAtLeast(1e-6)
                        val r = (sR[i] / w).roundToInt().coerceIn(0, 255)
                        val g = (sG[i] / w).roundToInt().coerceIn(0, 255)
                        val b = (sB[i] / w).roundToInt().coerceIn(0, 255)
                        entryRgb[pos] = (r shl 16) or (g shl 8) or b
                        entryWeight[pos] = w
                        pos++
                    }
                }
            }

            val colors = buildOklabQuantizedPalette(entryRgb, entryWeight, pos, maxOpaqueColors)
            val transparentIndex = if (includeTransparency) colors.size else -1
            return GlobalPalette(colors = colors, transparentIndex = transparentIndex)
        }

        private fun resolveTargetColorCount(requestedColorCount: Int?): Int {
            if (requestedColorCount != null) return requestedColorCount.coerceIn(2, 256)
            if (compactedTo6Bit) return 256
            return when {
                entryCount <= 48 -> 64
                entryCount <= 112 -> 128
                entryCount <= 192 -> 192
                else -> 256
            }
        }

        companion object {
            private const val EMPTY_KEY = -1
            private const val INITIAL_CAPACITY = 4096
            private const val MAX_EXACT_COLORS = 49_152
            private const val MAX_6BIT_CAPACITY = 262_144
        }
    }

    data class QuantizedFrame(
        val width: Int,
        val height: Int,
        val rgbPixels: IntArray, // 0x00RRGGBB for opaque pixels; 0x00000000 for transparent pixels
        val palette: IntArray,
        val effectiveColorCount: Int,
        val colorIndices: ByteArray = ByteArray(0),
        val transparentIndex: Int = -1
    ) {
        val hasTransparency: Boolean get() = transparentIndex >= 0

        fun toBitmap(): Bitmap {
            val hasIdx = colorIndices.size == rgbPixels.size
            val argb = IntArray(rgbPixels.size) { i ->
                if (transparentIndex >= 0 && hasIdx && (colorIndices[i].toInt() and 0xFF) == transparentIndex) {
                    0x00000000
                } else {
                    (0xFF shl 24) or (rgbPixels[i] and 0x00FFFFFF)
                }
            }
            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * Detects whether [pixels] carries genuine alpha transparency.
     * Safeguards against synthetic RGB-only arrays where all pixels have alpha=0 with non-zero RGB.
     */
    internal fun detectTransparency(pixels: IntArray): Boolean {
        var hasTransparentPixel = false
        var hasOpaquePixel = false
        var hasNonZeroRgb = false
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a < 128) {
                hasTransparentPixel = true
            } else {
                hasOpaquePixel = true
            }
            if ((p and 0x00FFFFFF) != 0) {
                hasNonZeroRgb = true
            }
            if (hasTransparentPixel && hasOpaquePixel) return true
        }
        return hasTransparentPixel && !hasNonZeroRgb
    }

    /**
     * Resolves Auto color quality by counting exact unique colors in a single frame.
     * Simple flat/UI scenes get smaller palettes (64-128) for compact GIFs; smooth gradients and
     * natural video receive 192-256 colors.
     */
    fun resolveColorCount(pixels: IntArray, requestedCount: Int?, respectAlpha: Boolean = true): Int {
        if (requestedCount != null) return requestedCount.coerceIn(2, 256)
        val hasAlpha = respectAlpha && detectTransparency(pixels)
        val uniqueSet = HashSet<Int>(512)
        val step = (pixels.size / 8192).coerceAtLeast(1)
        var i = 0
        while (i < pixels.size) {
            val c = pixels[i]
            if (!hasAlpha || ((c ushr 24) and 0xFF) >= 128) {
                uniqueSet.add(c and 0x00FFFFFF)
                if (uniqueSet.size > 192) return 256
            }
            i += step
        }
        return when {
            uniqueSet.size <= 48 -> 64
            uniqueSet.size <= 112 -> 128
            uniqueSet.size <= 192 -> 192
            else -> 256
        }
    }

    /**
     * Quantizes and dithers a single frame using a pre-computed shared [GlobalPalette].
     * Guarantees temporally stable color mapping across consecutive frames with zero palette shifts.
     *
     * @param lookupCache Optional reusable [ExactLookupCache] shared across frames using the
     *   exact same [globalPalette] to accelerate exact 24-bit lookups deterministically.
     */
    fun quantizeWithPalette(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        globalPalette: GlobalPalette,
        ditherMode: DitherMode,
        lookupCache: ExactLookupCache? = null
    ): QuantizedFrame {
        val index = globalPalette.oklabIndex
        val transparentIndex = globalPalette.transparentIndex
        val cache = lookupCache ?: ExactLookupCache()
        val (outputRgb, colorIndices) = when (ditherMode) {
            DitherMode.NONE -> applyNearest(sourcePixels, index, transparentIndex, cache)
            DitherMode.ORDERED -> applyOrderedBayer8x8(sourcePixels, width, height, index, transparentIndex, cache)
            DitherMode.FLOYD_STEINBERG -> applySerpentineFloydSteinberg(
                sourcePixels, width, height, index, transparentIndex, cache
            )
        }
        return QuantizedFrame(
            width = width,
            height = height,
            rgbPixels = outputRgb,
            palette = globalPalette.colors,
            effectiveColorCount = globalPalette.effectiveColorCount,
            colorIndices = colorIndices,
            transparentIndex = transparentIndex
        )
    }

    /**
     * Quantizes and dithers a single standalone frame by generating a palette from that frame alone.
     * For multi-frame animations, prefer [GlobalPaletteAccumulator] + [quantizeWithPalette] to avoid
     * frame-to-frame color flickering.
     */
    fun quantizeAndDither(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        requestedColorCount: Int?,
        ditherMode: DitherMode
    ): QuantizedFrame {
        val acc = GlobalPaletteAccumulator()
        acc.addFramePixels(sourcePixels, respectAlpha = true)
        val palette = acc.build(requestedColorCount = requestedColorCount)
        return quantizeWithPalette(
            sourcePixels = sourcePixels,
            width = width,
            height = height,
            globalPalette = palette,
            ditherMode = ditherMode
        )
    }

    internal fun buildMedianCutPalette(
        pixels: IntArray,
        maxColors: Int,
        ignoreTransparent: Boolean = false
    ): IntArray {
        val acc = GlobalPaletteAccumulator()
        acc.addFramePixels(pixels, respectAlpha = ignoreTransparent)
        return acc.build(requestedColorCount = maxColors, forceTransparency = false).colors
    }

    // -------------------------------------------------------------------------
    // Perceptual Oklab Variance-Minimizing Quantizer + Lloyd-Max K-Means
    // -------------------------------------------------------------------------

    private fun buildOklabQuantizedPalette(
        entryRgb: IntArray,
        rawWeights: DoubleArray,
        count: Int,
        maxColors: Int
    ): IntArray {
        if (count <= 0) return intArrayOf(0x000000)
        if (count <= maxColors) {
            val out = entryRgb.copyOf(count)
            out.sort()
            return out
        }

        // Convert every unique entry to perceptual Oklab and compute sublinear perceptual weight
        val entryL = FloatArray(count)
        val entryA = FloatArray(count)
        val entryB = FloatArray(count)
        val entryW = DoubleArray(count)
        val order = IntArray(count) { it }

        for (i in 0 until count) {
            val rgb = entryRgb[i]
            val lr = SRGB_TO_LINEAR[(rgb ushr 16) and 0xFF]
            val lg = SRGB_TO_LINEAR[(rgb ushr 8) and 0xFF]
            val lb = SRGB_TO_LINEAR[rgb and 0xFF]
            val l_ = cbrt((0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb).toDouble()).toFloat()
            val m_ = cbrt((0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb).toDouble()).toFloat()
            val s_ = cbrt((0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb).toDouble()).toFloat()
            entryL[i] = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
            entryA[i] = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
            entryB[i] = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
            entryW[i] = rawWeights[i].coerceAtLeast(1e-6).pow(WEIGHT_EXPONENT)
        }

        // Phase 1: Minimum-SSE recursive splitting in Oklab (Wu/Otsu optimal 3D SSE cut)
        val boxes = ArrayList<OklabBox>(maxColors)
        boxes.add(OklabBox(order, 0, count, entryL, entryA, entryB, entryW))

        while (boxes.size < maxColors) {
            var bestIdx = -1
            var bestSse = 1e-12
            for (i in boxes.indices) {
                val box = boxes[i]
                if (box.length > 1 && box.totalSse > bestSse) {
                    bestSse = box.totalSse
                    bestIdx = i
                }
            }
            if (bestIdx < 0) break

            val box = boxes.removeAt(bestIdx)
            val (left, right) = box.splitOptimal(entryL, entryA, entryB, entryW)
            boxes.add(left)
            if (right != null) boxes.add(right)
        }

        val k = boxes.size
        val centRgb = IntArray(k) { i ->
            oklabToRgb(boxes[i].meanL.toFloat(), boxes[i].meanA.toFloat(), boxes[i].meanB.toFloat())
        }

        // Phase 2: Fast weighted Lloyd-Max k-means refinement in Oklab
        val iterations = when {
            k <= 1 -> 0
            count <= 16_384 -> 3
            else -> 2
        }
        val sumW = DoubleArray(k)
        val sumL = DoubleArray(k)
        val sumA = DoubleArray(k)
        val sumB = DoubleArray(k)

        repeat(iterations) {
            val idx = OklabPaletteIndex(centRgb)
            sumW.fill(0.0)
            sumL.fill(0.0)
            sumA.fill(0.0)
            sumB.fill(0.0)

            for (i in 0 until count) {
                val c = idx.findNearestIndex(entryL[i], entryA[i], entryB[i])
                val w = entryW[i]
                sumW[c] += w
                sumL[c] += w * entryL[i]
                sumA[c] += w * entryA[i]
                sumB[c] += w * entryB[i]
            }

            for (c in 0 until k) {
                val w = sumW[c]
                if (w > 0.0) {
                    centRgb[c] = oklabToRgb(
                        (sumL[c] / w).toFloat(),
                        (sumA[c] / w).toFloat(),
                        (sumB[c] / w).toFloat()
                    )
                }
            }
        }

        // Phase 3: Deduplicate collapsed centroids and reallocate any freed slots to the highest
        // residual-error entries (rescues subtle gradient steps and vivid small accents)
        val uniqueList = ArrayList<Int>(maxColors)
        val seen = HashSet<Int>(maxColors * 2)
        for (rgb in centRgb) {
            if (seen.add(rgb)) {
                uniqueList.add(rgb)
            }
        }

        val maxRefills = min(maxColors - uniqueList.size, 32)
        if (maxRefills > 0 && count > uniqueList.size) {
            val initIdx = OklabPaletteIndex(uniqueList.toIntArray())
            val minDistSq = FloatArray(count)
            for (i in 0 until count) {
                val nearest = initIdx.findNearestIndex(entryL[i], entryA[i], entryB[i])
                val dL = entryL[i] - initIdx.origL[nearest]
                val dA = entryA[i] - initIdx.origA[nearest]
                val dB = entryB[i] - initIdx.origB[nearest]
                minDistSq[i] = dL * dL + dA * dA + dB * dB
            }

            repeat(maxRefills) {
                var worstEntry = -1
                var worstScore = 1e-7
                for (i in 0 until count) {
                    if (seen.contains(entryRgb[i])) continue
                    val score = entryW[i] * minDistSq[i].toDouble()
                    if (score > worstScore) {
                        worstScore = score
                        worstEntry = i
                    }
                }
                if (worstEntry < 0) return@repeat

                val newRgb = entryRgb[worstEntry]
                seen.add(newRgb)
                uniqueList.add(newRgb)
                val nL = entryL[worstEntry]
                val nA = entryA[worstEntry]
                val nB = entryB[worstEntry]
                for (i in 0 until count) {
                    val dL = entryL[i] - nL
                    val dA = entryA[i] - nA
                    val dB = entryB[i] - nB
                    val dSq = dL * dL + dA * dA + dB * dB
                    if (dSq < minDistSq[i]) minDistSq[i] = dSq
                }
            }
        }

        val finalPalette = uniqueList.toIntArray()
        finalPalette.sort()
        return if (finalPalette.isNotEmpty()) finalPalette else intArrayOf(0x000000)
    }

    private class OklabBox(
        val order: IntArray,
        val start: Int,
        val end: Int,
        entryL: FloatArray,
        entryA: FloatArray,
        entryB: FloatArray,
        entryW: DoubleArray
    ) {
        val length: Int get() = end - start
        val totalWeight: Double
        val sumL: Double
        val sumA: Double
        val sumB: Double
        val meanL: Double
        val meanA: Double
        val meanB: Double
        val varL: Double
        val varA: Double
        val varB: Double
        val totalSse: Double

        init {
            var tw = 0.0
            var sL = 0.0
            var sA = 0.0
            var sB = 0.0
            var sL2 = 0.0
            var sA2 = 0.0
            var sB2 = 0.0
            for (i in start until end) {
                val idx = order[i]
                val w = entryW[idx]
                val l = entryL[idx].toDouble()
                val a = entryA[idx].toDouble()
                val b = entryB[idx].toDouble()
                tw += w
                sL += w * l
                sA += w * a
                sB += w * b
                sL2 += w * l * l
                sA2 += w * a * a
                sB2 += w * b * b
            }
            totalWeight = tw.coerceAtLeast(1e-9)
            sumL = sL
            sumA = sA
            sumB = sB
            meanL = sL / totalWeight
            meanA = sA / totalWeight
            meanB = sB / totalWeight
            varL = (sL2 - sL * meanL).coerceAtLeast(0.0)
            varA = (sA2 - sA * meanA).coerceAtLeast(0.0)
            varB = (sB2 - sB * meanB).coerceAtLeast(0.0)
            totalSse = varL + varA + varB
        }

        fun splitOptimal(
            entryL: FloatArray,
            entryA: FloatArray,
            entryB: FloatArray,
            entryW: DoubleArray
        ): Pair<OklabBox, OklabBox?> {
            val axisValues = when {
                varL >= varA && varL >= varB -> entryL
                varA >= varL && varA >= varB -> entryA
                else -> entryB
            }

            // Sort slice order[start until end] by chosen Oklab axis in-place (zero boxing)
            quickSortIndices(order, start, end - 1, axisValues)

            // Find optimal cut k in [start, end - 2] minimizing 3D Oklab SSE(left) + SSE(right)
            // Equivalently maximizing (sL^2+sA^2+sB^2)/wL + (rL^2+rA^2+rB^2)/wR
            var wLeft = 0.0
            var lLeft = 0.0
            var aLeft = 0.0
            var bLeft = 0.0
            var bestScore = Double.NEGATIVE_INFINITY
            var bestMid = start + 1

            for (i in start until end - 1) {
                val idx = order[i]
                val w = entryW[idx]
                wLeft += w
                lLeft += w * entryL[idx]
                aLeft += w * entryA[idx]
                bLeft += w * entryB[idx]

                val wRight = totalWeight - wLeft
                if (wLeft > 1e-9 && wRight > 1e-9) {
                    val lRight = sumL - lLeft
                    val aRight = sumA - aLeft
                    val bRight = sumB - bLeft
                    val score = (lLeft * lLeft + aLeft * aLeft + bLeft * bLeft) / wLeft +
                        (lRight * lRight + aRight * aRight + bRight * bRight) / wRight
                    if (score > bestScore) {
                        bestScore = score
                        bestMid = i + 1
                    }
                }
            }

            bestMid = bestMid.coerceIn(start + 1, end - 1)
            val left = OklabBox(order, start, bestMid, entryL, entryA, entryB, entryW)
            val right = OklabBox(order, bestMid, end, entryL, entryA, entryB, entryW)
            return left to right
        }
    }

    private fun quickSortIndices(arr: IntArray, lo: Int, hi: Int, keys: FloatArray) {
        var left = lo
        var right = hi
        while (left < right) {
            if (right - left <= 16) {
                // Insertion sort for small partitions
                for (i in left + 1..right) {
                    val v = arr[i]
                    val kv = keys[v]
                    var j = i - 1
                    while (j >= left && keys[arr[j]] > kv) {
                        arr[j + 1] = arr[j]
                        j--
                    }
                    arr[j + 1] = v
                }
                return
            }
            // Median-of-three pivot
            val mid = (left + right) ushr 1
            val a = keys[arr[left]]
            val b = keys[arr[mid]]
            val c = keys[arr[right]]
            val pivot = when {
                (a <= b && b <= c) || (c <= b && b <= a) -> b
                (b <= a && a <= c) || (c <= a && a <= b) -> a
                else -> c
            }
            var i = left
            var j = right
            while (i <= j) {
                while (keys[arr[i]] < pivot) i++
                while (keys[arr[j]] > pivot) j--
                if (i <= j) {
                    val tmp = arr[i]
                    arr[i] = arr[j]
                    arr[j] = tmp
                    i++
                    j--
                }
            }
            // Recurse on smaller partition to guarantee O(log N) stack depth
            if (j - left < right - i) {
                if (left < j) quickSortIndices(arr, left, j, keys)
                left = i
            } else {
                if (i < right) quickSortIndices(arr, i, right, keys)
                right = j
            }
        }
    }

    private fun oklabToRgb(L: Float, a: Float, b: Float): Int {
        val l_ = L + 0.3963377774f * a + 0.2158037573f * b
        val m_ = L - 0.1055613458f * a - 0.0638541728f * b
        val s_ = L - 0.0894841775f * a - 1.2914855480f * b
        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_

        val lr = (+4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s).coerceIn(0f, 1f)
        val lg = (-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s).coerceIn(0f, 1f)
        val lb = (-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s).coerceIn(0f, 1f)

        val r = LINEAR_TO_SRGB[(lr * 4096f + 0.5f).toInt().coerceIn(0, 4096)]
        val g = LINEAR_TO_SRGB[(lg * 4096f + 0.5f).toInt().coerceIn(0, 4096)]
        val bOut = LINEAR_TO_SRGB[(lb * 4096f + 0.5f).toInt().coerceIn(0, 4096)]
        return (r shl 16) or (g shl 8) or bOut
    }

    // -------------------------------------------------------------------------
    // Pixel Mapping & Dithering Modes
    // -------------------------------------------------------------------------

    private fun applyNearest(
        sourcePixels: IntArray,
        index: OklabPaletteIndex,
        transparentIndex: Int,
        cache: ExactLookupCache
    ): Pair<IntArray, ByteArray> {
        val palette = index.palette
        val output = IntArray(sourcePixels.size)
        val indices = ByteArray(sourcePixels.size)
        val hasAlpha = transparentIndex >= 0
        val transparentByte = transparentIndex.toByte()

        for (i in sourcePixels.indices) {
            val p = sourcePixels[i]
            if (hasAlpha && ((p ushr 24) and 0xFF) < 128) {
                output[i] = 0
                indices[i] = transparentByte
                continue
            }
            val matchedIdx = cache.findNearestRgb(p, index)
            output[i] = palette[matchedIdx]
            indices[i] = matchedIdx.toByte()
        }
        return output to indices
    }

    private fun applyOrderedBayer8x8(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        index: OklabPaletteIndex,
        transparentIndex: Int,
        cache: ExactLookupCache
    ): Pair<IntArray, ByteArray> {
        val palette = index.palette
        val output = IntArray(sourcePixels.size)
        val indices = ByteArray(sourcePixels.size)
        val hasAlpha = transparentIndex >= 0
        val transparentByte = transparentIndex.toByte()

        var idx = 0
        for (y in 0 until height) {
            val bayerRow = BAYER_8X8_INT[y and 7]
            for (x in 0 until width) {
                val p = sourcePixels[idx]
                if (hasAlpha && ((p ushr 24) and 0xFF) < 128) {
                    output[idx] = 0
                    indices[idx] = transparentByte
                    idx++
                    continue
                }
                val packed = cache.findOrderedPairPacked(p, index)
                val tLevels = (packed ushr 16) and 0x7F
                val chosen = if (tLevels > bayerRow[x and 7]) {
                    (packed ushr 8) and 0xFF
                } else {
                    packed and 0xFF
                }
                output[idx] = palette[chosen]
                indices[idx] = chosen.toByte()
                idx++
            }
        }
        return output to indices
    }

    private fun applySerpentineFloydSteinberg(
        sourcePixels: IntArray,
        width: Int,
        height: Int,
        index: OklabPaletteIndex,
        transparentIndex: Int,
        cache: ExactLookupCache
    ): Pair<IntArray, ByteArray> {
        val palette = index.palette
        val palR = index.palR
        val palG = index.palG
        val palB = index.palB

        // Floating-point two-row rolling error buffers (zero negative-shift darkening bias)
        val rowSize = width + 2
        var curErrR = FloatArray(rowSize)
        var curErrG = FloatArray(rowSize)
        var curErrB = FloatArray(rowSize)
        var nextErrR = FloatArray(rowSize)
        var nextErrG = FloatArray(rowSize)
        var nextErrB = FloatArray(rowSize)

        val output = IntArray(sourcePixels.size)
        val indices = ByteArray(sourcePixels.size)
        val hasAlpha = transparentIndex >= 0
        val transparentByte = transparentIndex.toByte()

        val w7 = (7f / 16f) * FS_DIFFUSION_STRENGTH
        val w3 = (3f / 16f) * FS_DIFFUSION_STRENGTH
        val w5 = (5f / 16f) * FS_DIFFUSION_STRENGTH
        val w1 = (1f / 16f) * FS_DIFFUSION_STRENGTH

        for (y in 0 until height) {
            nextErrR.fill(0f)
            nextErrG.fill(0f)
            nextErrB.fill(0f)

            val rowOffset = y * width
            val reverse = (y and 1) != 0
            val dir = if (reverse) -1 else 1
            var x = if (reverse) width - 1 else 0

            while (x in 0 until width) {
                val p = sourcePixels[rowOffset + x]
                if (hasAlpha && ((p ushr 24) and 0xFF) < 128) {
                    output[rowOffset + x] = 0
                    indices[rowOffset + x] = transparentByte
                    x += dir
                    continue
                }

                val ex = x + 1
                val srcR = (p ushr 16) and 0xFF
                val srcG = (p ushr 8) and 0xFF
                val srcB = p and 0xFF

                val fR = srcR + curErrR[ex]
                val fG = srcG + curErrG[ex]
                val fB = srcB + curErrB[ex]

                val r = fR.roundToInt().coerceIn(0, 255)
                val g = fG.roundToInt().coerceIn(0, 255)
                val b = fB.roundToInt().coerceIn(0, 255)

                val rgb = (r shl 16) or (g shl 8) or b
                val nearestIdx = cache.findNearestRgb(rgb, index)
                output[rowOffset + x] = palette[nearestIdx]
                indices[rowOffset + x] = nearestIdx.toByte()

                val errR = (fR - palR[nearestIdx]).coerceIn(-FS_ERROR_CLAMP, FS_ERROR_CLAMP)
                val errG = (fG - palG[nearestIdx]).coerceIn(-FS_ERROR_CLAMP, FS_ERROR_CLAMP)
                val errB = (fB - palB[nearestIdx]).coerceIn(-FS_ERROR_CLAMP, FS_ERROR_CLAMP)

                if (errR != 0f || errG != 0f || errB != 0f) {
                    val fwd = ex + dir
                    val bwd = ex - dir

                    curErrR[fwd] += errR * w7
                    curErrG[fwd] += errG * w7
                    curErrB[fwd] += errB * w7

                    nextErrR[bwd] += errR * w3
                    nextErrG[bwd] += errG * w3
                    nextErrB[bwd] += errB * w3

                    nextErrR[ex] += errR * w5
                    nextErrG[ex] += errG * w5
                    nextErrB[ex] += errB * w5

                    nextErrR[fwd] += errR * w1
                    nextErrG[fwd] += errG * w1
                    nextErrB[fwd] += errB * w1
                }

                x += dir
            }

            // Swap rolling error rows
            val tmpR = curErrR; curErrR = nextErrR; nextErrR = tmpR
            val tmpG = curErrG; curErrG = nextErrG; nextErrG = tmpG
            val tmpB = curErrB; curErrB = nextErrB; nextErrB = tmpB
        }

        return output to indices
    }

    /**
     * Helper to count distinct RGB colors in a frame (useful for tests & verification).
     */
    fun countUniqueColors(pixels: IntArray): Int {
        val set = HashSet<Int>(min(pixels.size, 512))
        for (p in pixels) {
            set.add(p and 0x00FFFFFF)
        }
        return set.size
    }

    /**
     * Computes mean absolute pixel difference between two equal-size pixel buffers.
     */
    fun meanPixelDifference(a: IntArray, b: IntArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var sum = 0L
        for (i in a.indices) {
            val pa = a[i]
            val pb = b[i]
            sum += abs(((pa ushr 16) and 0xFF) - ((pb ushr 16) and 0xFF))
            sum += abs(((pa ushr 8) and 0xFF) - ((pb ushr 8) and 0xFF))
            sum += abs((pa and 0xFF) - (pb and 0xFF))
        }
        return sum.toDouble() / (a.size * 3.0)
    }
}
