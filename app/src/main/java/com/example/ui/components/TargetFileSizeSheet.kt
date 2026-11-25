package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.engine.StudioFormatters
import com.example.engine.TargetSizeRecommendation
import com.example.engine.TargetSizeStatus
import com.example.engine.TargetSizeUnit
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentMint
import com.example.ui.theme.MonoSpecStyle
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetFileSizeBottomSheet(
    currentEstimatedBytes: Long?,
    isAnalyzing: Boolean,
    recommendation: TargetSizeRecommendation?,
    errorMessage: String?,
    onAnalyzeTargetBytes: (Long) -> Unit,
    onAcceptRecommendation: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var inputText by remember { mutableStateOf("2") }
    var unit by remember { mutableStateOf(TargetSizeUnit.MB) }

    val parsedValue = inputText.trim().replace(',', '.').toDoubleOrNull()
    val targetBytes = parsedValue
        ?.takeIf { it > 0.0 }
        ?.let { (it * unit.bytesPerUnit).roundToLong() }
        ?.takeIf { it >= 1024L }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("target_size_sheet")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DataUsage,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Target File Size",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.onSurface
                    )
                    if (currentEstimatedBytes != null) {
                        Text(
                            text = "Current estimate: ${StudioFormatters.formatFileSize(currentEstimatedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = "Enter a maximum GIF size. Nepi GIF Lab will analyze representative frames and propose balanced settings for you to review before changing anything.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            // Quick target presets
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf(
                    Triple("500 KB", "500", TargetSizeUnit.KB),
                    Triple("1 MB", "1", TargetSizeUnit.MB),
                    Triple("2 MB", "2", TargetSizeUnit.MB),
                    Triple("5 MB", "5", TargetSizeUnit.MB),
                    Triple("8 MB", "8", TargetSizeUnit.MB)
                ).forEach { (label, valueStr, u) ->
                    StudioSelectableChip(
                        label = label,
                        selected = inputText == valueStr && unit == u,
                        onClick = {
                            inputText = valueStr
                            unit = u
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Custom target numeric input + KB/MB switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() || it == '.' }
                        if (cleaned.count { it == '.' } <= 1 && cleaned.length <= 8) {
                            inputText = cleaned
                        }
                    },
                    label = { Text("Max size") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("target_size_input")
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TargetSizeUnit.entries.forEach { u ->
                        StudioSelectableChip(
                            label = u.label,
                            selected = unit == u,
                            onClick = { unit = u },
                            modifier = Modifier.testTag("target_unit_${u.label.lowercase()}")
                        )
                    }
                }
            }

            StudioPillButton(
                text = if (isAnalyzing) "Analyzing frames…" else "Analyze & Propose Settings",
                icon = Icons.Rounded.AutoFixHigh,
                variant = StudioButtonVariant.PRIMARY,
                enabled = !isAnalyzing && targetBytes != null,
                onClick = { targetBytes?.let(onAnalyzeTargetBytes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("target_size_analyze_button")
            )

            if (isAnalyzing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Measuring representative frame compression…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error
                )
            }

            if (recommendation != null && !isAnalyzing) {
                RecommendationResultCard(
                    recommendation = recommendation,
                    onAccept = onAcceptRecommendation,
                    onReject = onDismiss
                )
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun RecommendationResultCard(
    recommendation: TargetSizeRecommendation,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val accent = when (recommendation.status) {
        TargetSizeStatus.ALREADY_WITHIN_TARGET,
        TargetSizeStatus.RECOMMENDED -> AccentMint
        TargetSizeStatus.DIFFICULT_OR_IMPOSSIBLE -> AccentAmber
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, accent.copy(alpha = 0.55f), shape)
            .padding(14.dp)
            .testTag("target_size_recommendation_card")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (recommendation.status == TargetSizeStatus.DIFFICULT_OR_IMPOSSIBLE) {
                    Icons.Rounded.WarningAmber
                } else {
                    Icons.Rounded.CheckCircle
                },
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = when (recommendation.status) {
                    TargetSizeStatus.ALREADY_WITHIN_TARGET -> "Already under target size"
                    TargetSizeStatus.RECOMMENDED -> "Recommended settings ready"
                    TargetSizeStatus.DIFFICULT_OR_IMPOSSIBLE -> "Target is tight for this clip"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurface
            )
        }

        // Size comparison pill
        Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = "Est. ${StudioFormatters.formatFileSize(recommendation.currentEstimatedBytes)} → " +
                    "${StudioFormatters.formatFileSize(recommendation.recommendedEstimatedBytes)} " +
                    "(Target: ${StudioFormatters.formatFileSize(recommendation.targetBytes)})",
                style = MonoSpecStyle,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        if (recommendation.changesSummary.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Proposed adjustments:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurfaceVariant
                )
                recommendation.changesSummary.forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface
                    )
                }
            }
        }

        if (!recommendation.warningMessage.isNullOrBlank()) {
            Text(
                text = recommendation.warningMessage,
                style = MaterialTheme.typography.bodySmall,
                color = AccentAmber,
                modifier = Modifier.testTag("target_size_warning_text")
            )
        }

        Text(
            text = "Estimates are approximate. Actual final file size is shown after export.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )

        if (recommendation.status == TargetSizeStatus.ALREADY_WITHIN_TARGET) {
            StudioPillButton(
                text = "Close",
                variant = StudioButtonVariant.TONAL,
                onClick = onReject,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StudioPillButton(
                    text = "Keep Current",
                    variant = StudioButtonVariant.OUTLINED,
                    onClick = onReject,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("target_size_reject_button")
                )
                StudioPillButton(
                    text = "Apply Settings",
                    variant = StudioButtonVariant.PRIMARY,
                    onClick = onAccept,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("target_size_accept_button")
                )
            }
        }
    }
}
