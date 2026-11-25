package com.example.engine

import kotlin.math.abs

/**
 * Square QR code module matrix (without quiet zone).
 * Callers rendering the matrix should surround it with at least a 4-module light quiet zone
 * per ISO/IEC 18004 so camera scanners detect the finder patterns reliably.
 */
data class QrCodeMatrix(
    val version: Int,
    val size: Int,
    val maskPattern: Int,
    val modules: BooleanArray
) {
    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size) {
            "Coordinates ($x, $y) out of bounds for QR size $size"
        }
        return modules[y * size + x]
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrCodeMatrix) return false
        return version == other.version &&
            size == other.size &&
            maskPattern == other.maskPattern &&
            modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + size
        result = 31 * result + maskPattern
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

/**
 * Pure-Kotlin, zero-dependency, 100% offline ISO/IEC 18004 QR Code encoder.
 *
 * Encodes UTF-8 payloads using Byte mode (`0100`) at Error Correction Level M (15% recovery)
 * across QR Versions 1 through 10 (up to 213 UTF-8 bytes — ample for cryptocurrency addresses).
 */
object OfflineQrCodeGenerator {

    private class VersionSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val group1Blocks: Int,
        val group1DataCodewords: Int,
        val group2Blocks: Int,
        val group2DataCodewords: Int,
        val alignmentCenters: IntArray
    ) {
        val size: Int get() = 17 + version * 4
        val byteCountBits: Int get() = if (version <= 9) 8 else 16
        val maxBytePayload: Int
            get() = (totalDataCodewords * 8 - 4 - byteCountBits) / 8
    }

    // ISO/IEC 18004 Table 9 (Error Correction Level M) + Annex E alignment pattern coordinates
    private val VERSION_SPECS = listOf(
        VersionSpec(1, 16, 10, 1, 16, 0, 0, intArrayOf()),
        VersionSpec(2, 28, 16, 1, 28, 0, 0, intArrayOf(6, 18)),
        VersionSpec(3, 44, 26, 1, 44, 0, 0, intArrayOf(6, 22)),
        VersionSpec(4, 64, 18, 2, 32, 0, 0, intArrayOf(6, 26)),
        VersionSpec(5, 86, 24, 2, 43, 0, 0, intArrayOf(6, 30)),
        VersionSpec(6, 108, 16, 4, 27, 0, 0, intArrayOf(6, 34)),
        VersionSpec(7, 124, 18, 4, 31, 0, 0, intArrayOf(6, 22, 38)),
        VersionSpec(8, 154, 22, 2, 38, 2, 39, intArrayOf(6, 24, 42)),
        VersionSpec(9, 182, 22, 3, 36, 2, 37, intArrayOf(6, 26, 46)),
        VersionSpec(10, 216, 26, 4, 43, 1, 44, intArrayOf(6, 28, 50))
    )

    // GF(256) with primitive polynomial x^8 + x^4 + x^3 + x^2 + 1 (0x11D)
    private val GF_EXP = IntArray(512)
    private val GF_LOG = IntArray(256)

    init {
        var value = 1
        for (i in 0 until 255) {
            GF_EXP[i] = value
            GF_LOG[value] = i
            value = value shl 1
            if (value >= 256) {
                value = value xor 0x11D
            }
        }
        for (i in 255 until 512) {
            GF_EXP[i] = GF_EXP[i - 255]
        }
    }

    /**
     * Generates a scannable ISO/IEC 18004 QR code matrix for [content].
     */
    fun encode(content: String): QrCodeMatrix {
        require(content.isNotEmpty()) { "QR code content must not be empty" }
        val payloadBytes = content.encodeToByteArray()
        val spec = VERSION_SPECS.firstOrNull { payloadBytes.size <= it.maxBytePayload }
            ?: throw IllegalArgumentException(
                "QR code content (${payloadBytes.size} bytes) exceeds maximum supported capacity (${VERSION_SPECS.last().maxBytePayload} bytes)"
            )

        val dataCodewords = buildDataCodewords(payloadBytes, spec)
        val allCodewords = interleaveWithErrorCorrection(dataCodewords, spec)

        val size = spec.size
        val baseModules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)

        drawFunctionPatterns(baseModules, isFunction, spec)
        drawCodewords(baseModules, isFunction, size, allCodewords)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = BooleanArray(size * size)

        for (mask in 0..7) {
            val candidate = baseModules.copyOf()
            applyMask(candidate, isFunction, size, mask)
            drawFormatBits(candidate, size, mask)
            if (spec.version >= 7) {
                drawVersionBits(candidate, size, spec.version)
            }
            val penalty = computePenaltyScore(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }

        return QrCodeMatrix(
            version = spec.version,
            size = size,
            maskPattern = bestMask,
            modules = bestModules
        )
    }

    private fun buildDataCodewords(payload: ByteArray, spec: VersionSpec): ByteArray {
        val totalBits = spec.totalDataCodewords * 8
        val bits = BitBuffer(totalBits)

        // Mode indicator: Byte mode = 0100
        bits.append(0b0100, 4)
        // Character count indicator
        bits.append(payload.size, spec.byteCountBits)
        // Raw UTF-8 payload bytes
        for (b in payload) {
            bits.append(b.toInt() and 0xFF, 8)
        }
        // Terminator: up to four 0 bits
        val terminatorBits = minOf(4, totalBits - bits.length)
        if (terminatorBits > 0) {
            bits.append(0, terminatorBits)
        }
        // Pad to byte boundary
        val remToByte = (8 - (bits.length % 8)) % 8
        if (remToByte > 0) {
            bits.append(0, remToByte)
        }
        // Pad alternating 0xEC and 0x11 codewords
        var padByte = 0xEC
        while (bits.length < totalBits) {
            bits.append(padByte, 8)
            padByte = if (padByte == 0xEC) 0x11 else 0xEC
        }

        return bits.toByteArray()
    }

    private fun interleaveWithErrorCorrection(data: ByteArray, spec: VersionSpec): ByteArray {
        val totalBlocks = spec.group1Blocks + spec.group2Blocks
        val dataBlocks = ArrayList<ByteArray>(totalBlocks)
        val ecBlocks = ArrayList<ByteArray>(totalBlocks)
        val generator = reedSolomonGenerator(spec.ecCodewordsPerBlock)

        var offset = 0
        repeat(spec.group1Blocks) {
            val block = data.copyOfRange(offset, offset + spec.group1DataCodewords)
            offset += spec.group1DataCodewords
            dataBlocks.add(block)
            ecBlocks.add(reedSolomonRemainder(block, generator))
        }
        repeat(spec.group2Blocks) {
            val block = data.copyOfRange(offset, offset + spec.group2DataCodewords)
            offset += spec.group2DataCodewords
            dataBlocks.add(block)
            ecBlocks.add(reedSolomonRemainder(block, generator))
        }

        val maxDataLen = maxOf(spec.group1DataCodewords, spec.group2DataCodewords)
        val totalOut = data.size + totalBlocks * spec.ecCodewordsPerBlock
        val result = ByteArray(totalOut)
        var outIndex = 0

        for (i in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (i < block.size) {
                    result[outIndex++] = block[i]
                }
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (ec in ecBlocks) {
                result[outIndex++] = ec[i]
            }
        }
        return result
    }

    internal fun reedSolomonGenerator(degree: Int): IntArray {
        // Full generator polynomial g(x) = prod_{i=0..degree-1} (x - alpha^i)
        // Stored highest-degree-first of length (degree + 1): result[0] = 1 (x^degree).
        var poly = intArrayOf(1)
        var root = 1
        repeat(degree) {
            val next = IntArray(poly.size + 1)
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
            }
            poly = next
            root = gfMul(root, 2)
        }
        return poly
    }

    internal fun reedSolomonRemainder(data: ByteArray, generatorFull: IntArray): ByteArray {
        val ecCount = generatorFull.size - 1
        val remainder = IntArray(ecCount)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ecCount - 1)
            remainder[ecCount - 1] = 0
            if (factor != 0) {
                for (i in 0 until ecCount) {
                    remainder[i] = remainder[i] xor gfMul(generatorFull[i + 1], factor)
                }
            }
        }
        return ByteArray(ecCount) { remainder[it].toByte() }
    }

    internal fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return GF_EXP[GF_LOG[a] + GF_LOG[b]]
    }

    private fun drawFunctionPatterns(
        modules: BooleanArray,
        isFunction: BooleanArray,
        spec: VersionSpec
    ) {
        val size = spec.size

        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                val idx = y * size + x
                modules[idx] = dark
                isFunction[idx] = true
            }
        }

        // Timing patterns (drawn first so finder patterns cleanly overwrite ends)
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }

        // Finder patterns + 1-module white separators
        fun drawFinder(centerX: Int, centerY: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val x = centerX + dx
                    val y = centerY + dy
                    if (x in 0 until size && y in 0 until size) {
                        val dist = maxOf(abs(dx), abs(dy))
                        val dark = dist != 2 && dist != 4
                        setFunction(x, y, dark)
                    }
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Alignment patterns (Versions 2+)
        val centers = spec.alignmentCenters
        for (cy in centers) {
            for (cx in centers) {
                // Skip the three corners occupied by finder patterns
                val overlapsTopLeft = cx <= 8 && cy <= 8
                val overlapsTopRight = cx >= size - 9 && cy <= 8
                val overlapsBottomLeft = cx <= 8 && cy >= size - 9
                if (overlapsTopLeft || overlapsTopRight || overlapsBottomLeft) continue

                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val dist = maxOf(abs(dx), abs(dy))
                        setFunction(cx + dx, cy + dy, dist != 1)
                    }
                }
            }
        }

        // Reserve format information areas (values written per mask candidate later)
        for (i in 0..8) {
            if (i != 6) {
                setFunction(i, 8, false)
                setFunction(8, i, false)
            }
        }
        for (i in 0 until 8) {
            setFunction(size - 1 - i, 8, false)
        }
        for (i in 0 until 7) {
            setFunction(8, size - 1 - i, false)
        }

        // Always-dark module at (8, 4 * version + 9) == (8, size - 8)
        setFunction(8, size - 8, true)

        // Reserve version information areas for Versions >= 7
        if (spec.version >= 7) {
            for (i in 0 until 6) {
                for (j in 0 until 3) {
                    setFunction(i, size - 11 + j, false)
                    setFunction(size - 11 + j, i, false)
                }
            }
        }
    }

    private fun drawCodewords(
        modules: BooleanArray,
        isFunction: BooleanArray,
        size: Int,
        codewords: ByteArray
    ) {
        val totalBits = codewords.size * 8
        var bitIndex = 0
        var upward = true
        var rightCol = size - 1

        while (rightCol >= 1) {
            if (rightCol == 6) {
                rightCol = 5 // Skip vertical timing pattern column
            }
            for (step in 0 until size) {
                val y = if (upward) size - 1 - step else step
                for (colOffset in 0..1) {
                    val x = rightCol - colOffset
                    val idx = y * size + x
                    if (!isFunction[idx]) {
                        val dark = if (bitIndex < totalBits) {
                            val byteVal = codewords[bitIndex ushr 3].toInt() and 0xFF
                            ((byteVal ushr (7 - (bitIndex and 7))) and 1) != 0
                        } else {
                            false
                        }
                        modules[idx] = dark
                        bitIndex++
                    }
                }
            }
            upward = !upward
            rightCol -= 2
        }
    }

    private fun applyMask(
        modules: BooleanArray,
        isFunction: BooleanArray,
        size: Int,
        maskPattern: Int
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val idx = y * size + x
                if (!isFunction[idx] && shouldInvert(maskPattern, x, y)) {
                    modules[idx] = !modules[idx]
                }
            }
        }
    }

    internal fun shouldInvert(maskPattern: Int, x: Int, y: Int): Boolean = when (maskPattern) {
        0 -> (y + x) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (y + x) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (y * x) % 2 + (y * x) % 3 == 0
        6 -> ((y * x) % 2 + (y * x) % 3) % 2 == 0
        7 -> ((y + x) % 2 + (y * x) % 3) % 2 == 0
        else -> throw IllegalArgumentException("Invalid QR mask pattern: $maskPattern")
    }

    /**
     * Computes 15-bit BCH format info for ECC Level M (format indicator `00`) and [maskPattern].
     */
    internal fun computeFormatBitsLevelM(maskPattern: Int): Int {
        // ISO/IEC 18004 Table 12: ECC Level M = 00
        val data = (0b00 shl 3) or (maskPattern and 0x7)
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun drawFormatBits(modules: BooleanArray, size: Int, maskPattern: Int) {
        val bits = computeFormatBitsLevelM(maskPattern)

        fun bit(posFromLsb: Int): Boolean = ((bits ushr posFromLsb) and 1) != 0

        // Copy 1 around top-left finder: MSB (bit 14) at (0, 8) -> LSB (bit 0) at (8, 0)
        for (i in 0..5) modules[8 * size + i] = bit(14 - i)
        modules[8 * size + 7] = bit(8)
        modules[8 * size + 8] = bit(7)
        modules[7 * size + 8] = bit(6)
        for (i in 0..5) modules[(5 - i) * size + 8] = bit(5 - i)

        // Copy 2 split across bottom-left (bits 14..8) and top-right (bits 7..0)
        for (i in 0..6) modules[(size - 1 - i) * size + 8] = bit(14 - i)
        for (i in 0..7) modules[8 * size + (size - 8 + i)] = bit(7 - i)
    }

    private fun drawVersionBits(modules: BooleanArray, size: Int, version: Int) {
        var rem = version
        repeat(12) {
            rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = ((bits ushr i) and 1) != 0
            val a = size - 11 + (i % 3)
            val b = i / 3
            modules[b * size + a] = dark
            modules[a * size + b] = dark
        }
    }

    private fun computePenaltyScore(modules: BooleanArray, size: Int): Int {
        var penalty = 0

        // Rule 1: 5+ consecutive same-color modules in rows and columns
        for (y in 0 until size) {
            var runColor = modules[y * size]
            var runLen = 1
            for (x in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runColor = c
                    runLen = 1
                }
            }
        }
        for (x in 0 until size) {
            var runColor = modules[x]
            var runLen = 1
            for (y in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runColor = c
                    runLen = 1
                }
            }
        }

        // Rule 2: 2x2 blocks of identical color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    penalty += 3
                }
            }
        }

        // Rule 3: Finder-like 1:1:3:1:1 patterns with 4 light modules on either side
        fun matchesFinderLike(getAt: (Int) -> Boolean, start: Int): Boolean {
            val p1 = getAt(start) && !getAt(start + 1) && getAt(start + 2) &&
                getAt(start + 3) && getAt(start + 4) && !getAt(start + 5) && getAt(start + 6) &&
                !getAt(start + 7) && !getAt(start + 8) && !getAt(start + 9) && !getAt(start + 10)
            val p2 = !getAt(start) && !getAt(start + 1) && !getAt(start + 2) && !getAt(start + 3) &&
                getAt(start + 4) && !getAt(start + 5) && getAt(start + 6) &&
                getAt(start + 7) && getAt(start + 8) && !getAt(start + 9) && getAt(start + 10)
            return p1 || p2
        }
        for (y in 0 until size) {
            val rowOffset = y * size
            for (x in 0..size - 11) {
                if (matchesFinderLike({ idx -> modules[rowOffset + idx] }, x)) {
                    penalty += 40
                }
            }
        }
        for (x in 0 until size) {
            for (y in 0..size - 11) {
                if (matchesFinderLike({ idx -> modules[idx * size + x] }, y)) {
                    penalty += 40
                }
            }
        }

        // Rule 4: Deviation from 50% dark module ratio
        var darkCount = 0
        for (m in modules) if (m) darkCount++
        val total = size * size
        val k = (abs(darkCount * 20 - total * 10) + total - 1) / total - 1
        if (k > 0) {
            penalty += k * 10
        }

        return penalty
    }

    private class BitBuffer(capacityBits: Int) {
        private val bytes = ByteArray((capacityBits + 7) / 8)
        var length: Int = 0
            private set

        fun append(value: Int, bitCount: Int) {
            for (i in bitCount - 1 downTo 0) {
                val bit = (value ushr i) and 1
                if (bit == 1) {
                    val byteIdx = length ushr 3
                    val bitOffset = 7 - (length and 7)
                    bytes[byteIdx] = (bytes[byteIdx].toInt() or (1 shl bitOffset)).toByte()
                }
                length++
            }
        }

        fun toByteArray(): ByteArray = bytes
    }
}
