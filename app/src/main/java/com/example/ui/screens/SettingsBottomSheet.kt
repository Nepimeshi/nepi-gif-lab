package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.engine.GalleryExporter
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioPillButton
import com.example.ui.components.StudioSelectableChip
import com.example.ui.theme.AccentMint
import com.example.ui.theme.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    currentThemeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
    hapticsEnabled: Boolean = true,
    onToggleHaptics: (Boolean) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalStudioHaptics.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = colors.onSurface
            )

            // Theme selector
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StudioSelectableChip(
                        label = "Dark",
                        leadingIcon = Icons.Rounded.DarkMode,
                        selected = currentThemeMode == ThemeMode.DARK,
                        onClick = {
                            haptics.perform(StudioHapticEvent.SELECTION)
                            onSelectThemeMode(ThemeMode.DARK)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("theme_dark_chip")
                    )
                    StudioSelectableChip(
                        label = "Light",
                        leadingIcon = Icons.Rounded.LightMode,
                        selected = currentThemeMode == ThemeMode.LIGHT,
                        onClick = {
                            haptics.perform(StudioHapticEvent.SELECTION)
                            onSelectThemeMode(ThemeMode.LIGHT)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("theme_light_chip")
                    )
                    StudioSelectableChip(
                        label = "System",
                        leadingIcon = Icons.Rounded.BrightnessAuto,
                        selected = currentThemeMode == ThemeMode.SYSTEM,
                        onClick = {
                            haptics.perform(StudioHapticEvent.SELECTION)
                            onSelectThemeMode(ThemeMode.SYSTEM)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("theme_system_chip")
                    )
                }
            }

            // Haptic Feedback Preference
            val hapticsShape = RoundedCornerShape(16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(hapticsShape)
                    .background(colors.surfaceContainer)
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), hapticsShape)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Vibration,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Haptic Feedback",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface
                    )
                    Text(
                        text = "Subtle tactile ticks for controls & gestures (respects Android system setting)",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = hapticsEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) haptics.perform(StudioHapticEvent.SELECTION)
                        onToggleHaptics(enabled)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary,
                        checkedTrackColor = colors.primary,
                        uncheckedThumbColor = colors.onSurfaceVariant,
                        uncheckedTrackColor = colors.surfaceContainerHighest
                    ),
                    modifier = Modifier.testTag("settings_haptics_toggle")
                )
            }

            // Output & Offline Engine Info
            val infoShape = RoundedCornerShape(16.dp)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(infoShape)
                    .background(colors.surfaceContainer)
                    .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), infoShape)
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Gallery Folder: ${GalleryExporter.GALLERY_FOLDER_DISPLAY}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurface
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = AccentMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Offline Native GIF89a & Animated WebP Engines · Zero cloud uploads, accounts, or tracking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            // Reset Defaults
            StudioPillButton(
                text = "Reset Default Conversion Settings",
                icon = Icons.Rounded.RestartAlt,
                variant = StudioButtonVariant.OUTLINED,
                onClick = {
                    haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                    onResetDefaults()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_reset_defaults")
            )

            Spacer(Modifier.height(18.dp))
        }
    }
}
