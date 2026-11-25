package com.example

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.CryptoDonationOption
import com.example.data.DonationConfig
import com.example.engine.OfflineQrCodeGenerator
import com.example.engine.QrCodeMatrix
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SupportNepiScreen
import com.example.ui.theme.GifStudioTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class DonationAndQrRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    // -------------------------------------------------------------------------
    // 1. Production DonationConfig integrity tests
    // -------------------------------------------------------------------------

    @Test
    fun `production donation config exposes exactly the five approved crypto options`() {
        val options = DonationConfig.configuredOptions()
        assertEquals(5, options.size)
        assertEquals(DonationConfig.allOptions.size, options.size)
        assertTrue(options.containsAll(DonationConfig.allOptions))

        val expected = listOf(
            Triple("Bitcoin", "BTC", "Bitcoin") to "bc1q94hhvl2el957z5z5y0tuh2umra5gze2p2uyz07",
            Triple("BNB", "BNB", "BNB Smart Chain (BEP20)") to "0xcef301F37CBE1a97C8c9130d8401AE24da1072A2",
            Triple("Ethereum", "ETH", "Ethereum Mainnet (ERC20)") to "0xcef301F37CBE1a97C8c9130d8401AE24da1072A2",
            Triple("Tether", "USDT", "Ethereum Mainnet (ERC20)") to "0xcef301F37CBE1a97C8c9130d8401AE24da1072A2",
            Triple("Tether", "USDT", "TRON (TRC20)") to "TUUsKYeifSGDW8LUXhm1CabfxGSYbmjLvR"
        )

        expected.forEachIndexed { index, (meta, expectedAddress) ->
            val actual = options[index]
            assertEquals(meta.first, actual.currencyName)
            assertEquals(meta.second, actual.ticker)
            assertEquals(meta.third, actual.networkName)
            assertEquals(expectedAddress, actual.address)
            assertTrue(actual.isConfigured)
        }

        // Explicitly confirm ETH BEP20 is not present
        assertFalse(
            "ETH on BEP20 must not be offered",
            options.any { it.ticker == "ETH" && it.networkName.contains("BEP20", ignoreCase = true) }
        )
    }

    @Test
    fun `configuredOptions hides blank and whitespace-only entries`() {
        val mixed = listOf(
            CryptoDonationOption("a", "TestCoin", "TST", "TestNet", "test-only-address-001"),
            CryptoDonationOption("b", "BlankCoin", "BLK", "BlankNet", ""),
            CryptoDonationOption("c", "SpacesCoin", "SPC", "SpacesNet", "   \t\n ")
        )

        val configured = DonationConfig.configuredOptions(mixed)
        assertEquals(1, configured.size)
        assertEquals("a", configured.single().id)
        assertTrue(DonationConfig.configuredOptions(emptyList()).isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. Offline ISO/IEC 18004 QR Code Generator verification & round-trip decoding
    // -------------------------------------------------------------------------

    @Test
    fun `offline QR generator produces valid scannable ISO 18004 matrices for all production addresses`() {
        val matricesByAddress = mutableMapOf<String, QrCodeMatrix>()

        for (option in DonationConfig.configuredOptions()) {
            val matrix = OfflineQrCodeGenerator.encode(option.address)
            // Deterministic check
            assertEquals(matrix, OfflineQrCodeGenerator.encode(option.address))

            // Structural & cryptographic Reed-Solomon + Byte-mode round-trip decode
            val decoded = decodeAndVerifyQrMatrix(matrix)
            assertEquals(
                "Decoded QR payload must match ${option.currencyName} (${option.networkName}) address exactly",
                option.address,
                decoded
            )
            matricesByAddress[option.address] = matrix
        }

        // Distinct addresses produce distinct QR matrices
        val btcMatrix = matricesByAddress.getValue("bc1q94hhvl2el957z5z5y0tuh2umra5gze2p2uyz07")
        val evmMatrix = matricesByAddress.getValue("0xcef301F37CBE1a97C8c9130d8401AE24da1072A2")
        val tronMatrix = matricesByAddress.getValue("TUUsKYeifSGDW8LUXhm1CabfxGSYbmjLvR")
        assertNotEquals(btcMatrix, evmMatrix)
        assertNotEquals(btcMatrix, tronMatrix)
        assertNotEquals(evmMatrix, tronMatrix)
    }

    @Test
    fun `offline QR generator supports multiple QR versions including multi-block Reed-Solomon interleaving`() {
        // Test-only payloads across Versions 1, 2, 3, 4, 5, 7 (never placed in production config)
        val samples = listOf(
            "TEST-V1-ADDR" to 1,                                         // 12 bytes -> V1
            "TEST-V2-SAMPLE-ADDRESS-20" to 2,                            // 25 bytes -> V2
            "TEST-V3-SAMPLE-ADDRESS-0123456789ABCDEF" to 3,              // 40 bytes -> V3
            "TEST-V4-TWO-BLOCK-INTERLEAVE-0123456789ABCDEFGHIJKLMNOP" to 4, // 56 bytes -> V4 (2 RS blocks)
            "TEST-V5-ADDRESS-" + "A".repeat(60) to 5,                    // 76 bytes -> V5 (2 RS blocks)
            "TEST-V7-WITH-VERSION-INFO-" + "B".repeat(90) to 7           // 116 bytes -> V7 (4 RS blocks + version info)
        )

        for ((payload, expectedVersion) in samples) {
            val matrix = OfflineQrCodeGenerator.encode(payload)
            assertEquals("Expected QR version $expectedVersion for len=${payload.length}", expectedVersion, matrix.version)
            assertEquals(17 + expectedVersion * 4, matrix.size)
            assertEquals(payload, decodeAndVerifyQrMatrix(matrix))
        }
    }

    // -------------------------------------------------------------------------
    // 3. Compose UI Robolectric tests (HomeScreen + SupportNepiScreen)
    // -------------------------------------------------------------------------

    @Test
    fun `home screen renders normally with compact Support Nepi section and opens donation flow`() {
        var supportClicked = false

        composeRule.setContent {
            GifStudioTheme {
                HomeScreen(
                    recentHistory = emptyList(),
                    onImportSingleVideo = {},
                    onImportBatchVideos = {},
                    onOpenHistory = {},
                    onOpenSettings = {},
                    onOpenGif = {},
                    onShareGif = {},
                    onOpenSupport = { supportClicked = true },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Core home screen workflow remains intact
        composeRule.onNodeWithTag("import_video_button").assertIsDisplayed()
        composeRule.onNodeWithTag("import_gif_button").assertIsDisplayed()
        composeRule.onNodeWithTag("batch_convert_button").assertIsDisplayed()

        // Scroll to the compact Support Nepi section and click Donate
        composeRule.onNodeWithTag("home_support_section").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Support Nepi").assertIsDisplayed()
        composeRule.onNodeWithTag("home_support_button").performClick()

        assertTrue("Clicking Donate should trigger onOpenSupport", supportClicked)
    }

    @Test
    fun `support screen handles empty and unconfigured address list gracefully`() {
        val unconfiguredOnly = listOf(
            CryptoDonationOption("empty_btc", "Bitcoin", "BTC", "Bitcoin", "")
        )

        composeRule.setContent {
            GifStudioTheme {
                SupportNepiScreen(
                    onBack = {},
                    options = unconfiguredOnly,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        composeRule.onNodeWithTag("donation_empty_state").assertIsDisplayed()
        composeRule.onNodeWithText("No donation addresses configured").assertIsDisplayed()
    }

    @Test
    fun `support screen renders all five crypto options separately with QR codes warnings and working copy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copiedEvents = mutableListOf<CryptoDonationOption>()

        composeRule.setContent {
            GifStudioTheme {
                SupportNepiScreen(
                    onBack = {},
                    onAddressCopied = { copiedEvents.add(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val options = DonationConfig.configuredOptions()
        assertEquals(5, options.size)

        for (option in options) {
            // Scroll the LazyColumn to bring this option's card into view
            composeRule.onNodeWithTag("support_nepi_list")
                .performScrollToNode(hasTestTag("donation_option_${option.id}"))

            composeRule.onNodeWithTag("donation_option_${option.id}").assertIsDisplayed()
            composeRule.onNodeWithTag("donation_network_${option.id}")
                .assertIsDisplayed()
                .assertTextEquals("Network: ${option.networkName}")
            composeRule.onNodeWithTag("donation_qr_${option.id}").assertIsDisplayed()
            composeRule.onNodeWithTag("donation_address_${option.id}")
                .assertIsDisplayed()
                .assertTextEquals(option.address)
            composeRule.onNodeWithTag("donation_warning_${option.id}").assertIsDisplayed()

            // Click copy and verify exact clipboard contents
            composeRule.onNodeWithTag("donation_copy_${option.id}").performClick()
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            assertEquals(
                "Clipboard must match ${option.currencyName} (${option.networkName}) address exactly",
                option.address,
                clipText
            )
            composeRule.onNodeWithText("Address Copied").assertIsDisplayed()
        }

        assertEquals(options.map { it.id }, copiedEvents.map { it.id })
    }

    // -------------------------------------------------------------------------
    // Independent ISO/IEC 18004 QR Decoder / Verifier Helper (Test Only)
    // -------------------------------------------------------------------------

    private class ExpectedSpec(
        val version: Int,
        val totalDataCodewords: Int,
        val ecPerBlock: Int,
        val g1Blocks: Int,
        val g1Data: Int,
        val g2Blocks: Int,
        val g2Data: Int,
        val alignmentCenters: IntArray
    )

    private val VERIFIER_SPECS = mapOf(
        1 to ExpectedSpec(1, 16, 10, 1, 16, 0, 0, intArrayOf()),
        2 to ExpectedSpec(2, 28, 16, 1, 28, 0, 0, intArrayOf(6, 18)),
        3 to ExpectedSpec(3, 44, 26, 1, 44, 0, 0, intArrayOf(6, 22)),
        4 to ExpectedSpec(4, 64, 18, 2, 32, 0, 0, intArrayOf(6, 26)),
        5 to ExpectedSpec(5, 86, 24, 2, 43, 0, 0, intArrayOf(6, 30)),
        6 to ExpectedSpec(6, 108, 16, 4, 27, 0, 0, intArrayOf(6, 34)),
        7 to ExpectedSpec(7, 124, 18, 4, 31, 0, 0, intArrayOf(6, 22, 38))
    )

    private fun decodeAndVerifyQrMatrix(matrix: QrCodeMatrix): String {
        val spec = VERIFIER_SPECS.getValue(matrix.version)
        val size = matrix.size
        assertEquals(17 + spec.version * 4, size)

        // 1. Verify all three 7x7 finder patterns
        fun assertFinder(topLeftX: Int, topLeftY: Int) {
            for (dy in 0..6) {
                for (dx in 0..6) {
                    val dist = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
                    val expectedDark = dist != 2
                    assertEquals(
                        "Finder mismatch at (${topLeftX + dx}, ${topLeftY + dy})",
                        expectedDark,
                        matrix[topLeftX + dx, topLeftY + dy]
                    )
                }
            }
        }
        assertFinder(0, 0)
        assertFinder(size - 7, 0)
        assertFinder(0, size - 7)

        // 2. Verify timing patterns and fixed dark module
        for (i in 8 until size - 8) {
            assertEquals(i % 2 == 0, matrix[i, 6])
            assertEquals(i % 2 == 0, matrix[6, i])
        }
        assertTrue("Dark module at (8, size-8) must be dark", matrix[8, size - 8])

        // 3. Verify both format info copies match and decode to ECC Level M (00) + maskPattern
        var fmt1 = 0
        for (i in 0..5) fmt1 = (fmt1 shl 1) or if (matrix[i, 8]) 1 else 0
        fmt1 = (fmt1 shl 1) or if (matrix[7, 8]) 1 else 0
        fmt1 = (fmt1 shl 1) or if (matrix[8, 8]) 1 else 0
        fmt1 = (fmt1 shl 1) or if (matrix[8, 7]) 1 else 0
        for (i in 5 downTo 0) fmt1 = (fmt1 shl 1) or if (matrix[8, i]) 1 else 0

        var fmt2 = 0
        for (i in 0..6) fmt2 = (fmt2 shl 1) or if (matrix[8, size - 1 - i]) 1 else 0
        for (i in 0..7) fmt2 = (fmt2 shl 1) or if (matrix[size - 8 + i, 8]) 1 else 0

        assertEquals("Both QR format info copies must be identical", fmt1, fmt2)
        assertEquals(OfflineQrCodeGenerator.computeFormatBitsLevelM(matrix.maskPattern), fmt1)
        val unmaskedFmt = fmt1 xor 0x5412
        val eccIndicator = (unmaskedFmt ushr 13) and 0b11
        val maskFromBits = (unmaskedFmt ushr 10) and 0b111
        assertEquals("ECC indicator must be Level M (00)", 0b00, eccIndicator)
        assertEquals(matrix.maskPattern, maskFromBits)

        // 4. Mark function modules to read unmasked zig-zag codewords
        val isFunction = BooleanArray(size * size)
        fun mark(x: Int, y: Int) {
            if (x in 0 until size && y in 0 until size) isFunction[y * size + x] = true
        }
        for (i in 0 until size) {
            mark(6, i)
            mark(i, 6)
        }
        for (y in 0..8) for (x in 0..8) mark(x, y)
        for (y in 0..8) for (x in size - 8 until size) mark(x, y)
        for (y in size - 8 until size) for (x in 0..8) mark(x, y)
        for (cy in spec.alignmentCenters) {
            for (cx in spec.alignmentCenters) {
                if ((cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)) continue
                for (dy in -2..2) for (dx in -2..2) mark(cx + dx, cy + dy)
            }
        }
        if (spec.version >= 7) {
            for (i in 0 until 6) for (j in 0 until 3) {
                mark(i, size - 11 + j)
                mark(size - 11 + j, i)
            }
        }

        val totalCodewords = spec.totalDataCodewords + (spec.g1Blocks + spec.g2Blocks) * spec.ecPerBlock
        val codewords = ByteArray(totalCodewords)
        var bitIdx = 0
        var upward = true
        var rightCol = size - 1
        while (rightCol >= 1) {
            if (rightCol == 6) rightCol = 5
            for (step in 0 until size) {
                val y = if (upward) size - 1 - step else step
                for (colOffset in 0..1) {
                    val x = rightCol - colOffset
                    if (!isFunction[y * size + x] && bitIdx < totalCodewords * 8) {
                        var bit = matrix[x, y]
                        if (OfflineQrCodeGenerator.shouldInvert(matrix.maskPattern, x, y)) {
                            bit = !bit
                        }
                        if (bit) {
                            val bIdx = bitIdx ushr 3
                            val shift = 7 - (bitIdx and 7)
                            codewords[bIdx] = (codewords[bIdx].toInt() or (1 shl shift)).toByte()
                        }
                        bitIdx++
                    }
                }
            }
            upward = !upward
            rightCol -= 2
        }

        // 5. De-interleave blocks and verify Reed-Solomon syndromes == 0
        val totalBlocks = spec.g1Blocks + spec.g2Blocks
        val blockData = Array(totalBlocks) { idx ->
            ByteArray(if (idx < spec.g1Blocks) spec.g1Data else spec.g2Data)
        }
        val blockEc = Array(totalBlocks) { ByteArray(spec.ecPerBlock) }
        val maxData = maxOf(spec.g1Data, spec.g2Data)

        var cursor = 0
        for (i in 0 until maxData) {
            for (b in 0 until totalBlocks) {
                if (i < blockData[b].size) {
                    blockData[b][i] = codewords[cursor++]
                }
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (b in 0 until totalBlocks) {
                blockEc[b][i] = codewords[cursor++]
            }
        }

        // Verify every RS block has zero syndrome for all roots alpha^0 .. alpha^(ecPerBlock-1)
        for (b in 0 until totalBlocks) {
            val fullBlock = blockData[b] + blockEc[b]
            var root = 1
            for (s in 0 until spec.ecPerBlock) {
                var eval = 0
                for (byte in fullBlock) {
                    eval = OfflineQrCodeGenerator.gfMul(eval, root) xor (byte.toInt() and 0xFF)
                }
                assertEquals("Non-zero RS syndrome $s for block $b", 0, eval)
                root = OfflineQrCodeGenerator.gfMul(root, 2)
            }
        }

        // 6. Reassemble data bitstream and decode Byte-mode UTF-8 payload
        val dataBytes = ByteArray(spec.totalDataCodewords)
        var out = 0
        for (block in blockData) {
            System.arraycopy(block, 0, dataBytes, out, block.size)
            out += block.size
        }

        var readBit = 0
        fun readBits(count: Int): Int {
            var v = 0
            repeat(count) {
                val byteVal = dataBytes[readBit ushr 3].toInt() and 0xFF
                val bit = (byteVal ushr (7 - (readBit and 7))) and 1
                v = (v shl 1) or bit
                readBit++
            }
            return v
        }

        val mode = readBits(4)
        assertEquals("Expected Byte mode (0100)", 0b0100, mode)
        val byteLen = readBits(if (spec.version <= 9) 8 else 16)
        val decodedBytes = ByteArray(byteLen) { readBits(8).toByte() }
        return decodedBytes.decodeToString()
    }
}
