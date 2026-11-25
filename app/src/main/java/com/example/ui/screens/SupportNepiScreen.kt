package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CryptoDonationOption
import com.example.data.DonationConfig
import com.example.engine.OfflineQrCodeGenerator
import com.example.engine.QrCodeMatrix
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioIconButton
import com.example.ui.components.StudioPillButton
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.MonoSpecStyle
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SupportNepiScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    options: List<CryptoDonationOption> = DonationConfig.configuredOptions(),
    onAddressCopied: (CryptoDonationOption) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val haptics = LocalStudioHaptics.current
    val configuredOptions = remember(options) { DonationConfig.configuredOptions(options) }
    var copiedOptionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedOptionId) {
        if (copiedOptionId != null) {
            delay(2_200.milliseconds)
            copiedOptionId = null
        }
    }

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
                .testTag("support_nepi_list")
        ) {
            // Top bar
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        onClick = onBack,
                        modifier = Modifier.testTag("support_back_button")
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Support Nepi",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.onBackground
                        )
                        Text(
                            text = "Optional cryptocurrency donations",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // Short appreciative overview card
            item {
                val shape = RoundedCornerShape(20.dp)
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.surfaceContainer)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AccentLavender.copy(alpha = 0.16f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = "100% Free, Offline & Ad-Free",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                    }
                    Text(
                        text = "Nepi GIF Lab has no ads, subscriptions, or paywalls. If it saves you time, you can support ongoing development with a direct cryptocurrency transfer. QR codes are generated locally on your device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (configuredOptions.isEmpty()) {
                item {
                    EmptyDonationStateCard()
                }
            } else {
                items(configuredOptions, key = { it.id }) { option ->
                    val isCopied = copiedOptionId == option.id
                    CryptoDonationCard(
                        option = option,
                        isCopied = isCopied,
                        onCopyAddress = {
                            copyAddressToClipboard(context, option)
                            haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                            copiedOptionId = option.id
                            onAddressCopied(option)
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun EmptyDonationStateCard() {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)
            .padding(horizontal = 24.dp, vertical = 28.dp)
            .testTag("donation_empty_state")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = "No donation addresses configured",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface
        )
        Text(
            text = "Cryptocurrency receiving addresses have not been set in this build yet.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun CryptoDonationCard(
    option: CryptoDonationOption,
    isCopied: Boolean,
    onCopyAddress: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    val qrMatrix = remember(option.address) {
        OfflineQrCodeGenerator.encode(option.address)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(18.dp)
            .testTag("donation_option_${option.id}")
    ) {
        // Asset header: Currency Name (TICKER) + Network pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = option.currencyName,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                    Surface(
                        color = colors.primaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = option.ticker,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Network: ${option.networkName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.secondary,
                    modifier = Modifier.testTag("donation_network_${option.id}")
                )
            }
        }

        // Locally generated offline QR code with high-contrast white quiet zone
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 1.dp,
                modifier = Modifier
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            ) {
                QrCodeCanvas(
                    matrix = qrMatrix,
                    contentDescription = "${option.currencyName} (${option.ticker}) on ${option.networkName} QR code",
                    size = 184.dp,
                    modifier = Modifier
                        .padding(12.dp)
                        .testTag("donation_qr_${option.id}")
                )
            }
        }

        // Selectable monospace receiving address
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Receiving Address",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant
            )
            Surface(
                color = colors.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            ) {
                SelectionContainer {
                    Text(
                        text = option.address,
                        style = MonoSpecStyle.copy(
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = colors.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                            .testTag("donation_address_${option.id}")
                    )
                }
            }
        }

        // Copy button with instant visual confirmation
        StudioPillButton(
            text = if (isCopied) "Address Copied" else "Copy ${option.ticker} Address",
            icon = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            variant = if (isCopied) StudioButtonVariant.PRIMARY else StudioButtonVariant.TONAL,
            onClick = onCopyAddress,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("donation_copy_${option.id}")
        )

        // Explicit network safety warning
        val warningShape = RoundedCornerShape(12.dp)
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(warningShape)
                .background(AccentAmber.copy(alpha = 0.11f))
                .border(1.dp, AccentAmber.copy(alpha = 0.32f), warningShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("donation_warning_${option.id}")
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = AccentAmber,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp)
            )
            Text(
                text = "Send only ${option.ticker} (${option.currencyName}) over ${option.networkName}. Sending other assets or using a different network may result in permanent loss.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QrCodeCanvas(
    matrix: QrCodeMatrix,
    contentDescription: String,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val quietZoneModules = 4
    Canvas(
        modifier = modifier
            .size(size)
            .semantics {
                this.contentDescription = contentDescription
            }
    ) {
        val totalModules = matrix.size + quietZoneModules * 2
        val moduleSize = minOf(this.size.width, this.size.height) / totalModules.toFloat()
        val offsetX = (this.size.width - moduleSize * totalModules) / 2f
        val offsetY = (this.size.height - moduleSize * totalModules) / 2f

        // Crisp white background including ISO quiet zone
        drawRect(
            color = Color.White,
            topLeft = Offset.Zero,
            size = this.size
        )

        // Draw dark modules with slight sub-pixel overlap (+0.35f) to prevent anti-aliasing seams
        val overlap = 0.35f
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(
                            x = offsetX + (x + quietZoneModules) * moduleSize,
                            y = offsetY + (y + quietZoneModules) * moduleSize
                        ),
                        size = Size(moduleSize + overlap, moduleSize + overlap)
                    )
                }
            }
        }
    }
}

private fun copyAddressToClipboard(context: Context, option: CryptoDonationOption) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    val clip = ClipData.newPlainText(
        "${option.currencyName} (${option.networkName}) donation address",
        option.address
    )
    clipboard.setPrimaryClip(clip)
}
