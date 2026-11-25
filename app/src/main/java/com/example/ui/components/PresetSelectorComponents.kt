package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.ConversionPresetEntity
import com.example.engine.GifConversionSettings
import com.example.ui.theme.MonoSpecStyle

@Composable
fun PresetQuickBar(
    presets: List<ConversionPresetEntity>,
    currentSettings: GifConversionSettings,
    onApplyPreset: (ConversionPresetEntity) -> Unit,
    onSavePreset: (String) -> Unit,
    onRenamePreset: (Long, String) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var showSaveDialog by remember { mutableStateOf(false) }
    var showManageSheet by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Presets",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = colors.primaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showSaveDialog = true }
                        .testTag("preset_save_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BookmarkAdd,
                            contentDescription = "Save preset",
                            tint = colors.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onPrimaryContainer
                        )
                    }
                }

                Surface(
                    color = colors.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showManageSheet = true }
                        .testTag("preset_manage_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bookmarks,
                            contentDescription = "Manage presets",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Manage",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (presets.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .testTag("preset_chip_row")
            ) {
                presets.forEach { preset ->
                    val isMatching = presetMatchesSettings(preset, currentSettings)
                    val chipShape = RoundedCornerShape(12.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(chipShape)
                            .background(
                                if (isMatching) colors.primaryContainer
                                else colors.surfaceContainerHigh
                            )
                            .border(
                                width = 1.dp,
                                color = if (isMatching) colors.primary.copy(alpha = 0.7f)
                                else colors.outlineVariant.copy(alpha = 0.55f),
                                shape = chipShape
                            )
                            .clickable { onApplyPreset(preset) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                            .testTag("preset_chip_${preset.id}")
                    ) {
                        if (!preset.isBuiltIn) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colors.primary)
                            )
                        }
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isMatching) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (isMatching) colors.onPrimaryContainer else colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            currentSettings = currentSettings,
            onDismiss = { showSaveDialog = false },
            onConfirmSave = { name ->
                onSavePreset(name)
                showSaveDialog = false
            }
        )
    }

    if (showManageSheet) {
        ManagePresetsBottomSheet(
            presets = presets,
            currentSettings = currentSettings,
            onApplyPreset = { preset ->
                onApplyPreset(preset)
                showManageSheet = false
            },
            onSaveNewClick = {
                showManageSheet = false
                showSaveDialog = true
            },
            onRenamePreset = onRenamePreset,
            onDeletePreset = onDeletePreset,
            onResetDefaults = {
                onResetDefaults()
                showManageSheet = false
            },
            onDismiss = { showManageSheet = false }
        )
    }
}

private fun presetMatchesSettings(
    preset: ConversionPresetEntity,
    settings: GifConversionSettings
): Boolean {
    val applied = preset.applyTo(settings)
    return applied.resolutionPreset == settings.resolutionPreset &&
        applied.customResolutionShortEdge == settings.customResolutionShortEdge &&
        applied.useOriginalFps == settings.useOriginalFps &&
        (settings.useOriginalFps || kotlin.math.abs(applied.fps - settings.fps) < 0.05f) &&
        applied.colorQualityPreset == settings.colorQualityPreset &&
        applied.customColorCount == settings.customColorCount &&
        applied.ditherMode == settings.ditherMode &&
        kotlin.math.abs(applied.speed - settings.speed) < 0.01f &&
        applied.reverse == settings.reverse &&
        applied.pingPong == settings.pingPong &&
        applied.loopCount == settings.loopCount
}

@Composable
private fun SavePresetDialog(
    currentSettings: GifConversionSettings,
    onDismiss: () -> Unit,
    onConfirmSave: (String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    val previewSummary = remember(currentSettings) {
        ConversionPresetEntity.fromSettings("tmp", currentSettings).summaryText()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Conversion Preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Saves resolution, FPS, color palette, dithering, speed, reverse, ping-pong, and loop count locally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Surface(
                    color = colors.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = previewSummary,
                        style = MonoSpecStyle,
                        color = colors.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    label = { Text("Preset name") },
                    placeholder = { Text("e.g. Crisp 480p Loops") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_preset_name_input")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmSave(name.trim()) },
                enabled = name.trim().isNotEmpty(),
                modifier = Modifier.testTag("save_preset_confirm_button")
            ) {
                Text("Save Preset")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManagePresetsBottomSheet(
    presets: List<ConversionPresetEntity>,
    currentSettings: GifConversionSettings,
    onApplyPreset: (ConversionPresetEntity) -> Unit,
    onSaveNewClick: () -> Unit,
    onRenamePreset: (Long, String) -> Unit,
    onDeletePreset: (Long) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val customPresets = presets.filter { !it.isBuiltIn }
    val builtInPresets = presets.filter { it.isBuiltIn }
    var renamingPreset by remember { mutableStateOf<ConversionPresetEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Conversion Presets",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onSurface
                )
                StudioPillButton(
                    text = "Save Current",
                    icon = Icons.Rounded.BookmarkAdd,
                    variant = StudioButtonVariant.PRIMARY,
                    onClick = onSaveNewClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            if (customPresets.isNotEmpty()) {
                Text(
                    text = "My Saved Presets",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.primary
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    customPresets.forEach { preset ->
                        PresetListCard(
                            preset = preset,
                            isActive = presetMatchesSettings(preset, currentSettings),
                            onApply = { onApplyPreset(preset) },
                            onRename = {
                                renamingPreset = preset
                                renameText = preset.name
                            },
                            onDelete = { onDeletePreset(preset.id) }
                        )
                    }
                }
            } else {
                Text(
                    text = "No custom presets yet. Tap Save Current to store your favorite settings offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }

            if (builtInPresets.isNotEmpty()) {
                Text(
                    text = "Built-in Presets",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    builtInPresets.forEach { preset ->
                        PresetListCard(
                            preset = preset,
                            isActive = presetMatchesSettings(preset, currentSettings),
                            onApply = { onApplyPreset(preset) },
                            onRename = null,
                            onDelete = null
                        )
                    }
                }
            }

            StudioPillButton(
                text = "Restore Default Settings",
                icon = Icons.Rounded.RestartAlt,
                variant = StudioButtonVariant.OUTLINED,
                onClick = onResetDefaults,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Restoring default settings keeps all of your saved custom presets intact.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
        }
    }

    val targetRename = renamingPreset
    if (targetRename != null) {
        AlertDialog(
            onDismissRequest = { renamingPreset = null },
            title = { Text("Rename Preset") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { if (it.length <= 40) renameText = it },
                    label = { Text("New name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenamePreset(targetRename.id, renameText.trim())
                        renamingPreset = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingPreset = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PresetListCard(
    preset: ConversionPresetEntity,
    isActive: Boolean,
    onApply: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (isActive) colors.primaryContainer.copy(alpha = 0.5f) else colors.surfaceContainer)
            .border(
                1.dp,
                if (isActive) colors.primary.copy(alpha = 0.65f) else colors.outlineVariant.copy(alpha = 0.55f),
                shape
            )
            .clickable(onClick = onApply)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (preset.isBuiltIn) {
                    Surface(
                        color = colors.secondaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Built-in",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                    }
                }
            }
            Text(
                text = preset.summaryText(),
                style = MonoSpecStyle,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onRename != null) {
            IconButton(
                onClick = onRename,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = "Rename preset",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete preset",
                    tint = colors.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
