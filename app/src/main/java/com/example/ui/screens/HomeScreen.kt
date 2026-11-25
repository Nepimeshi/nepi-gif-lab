package com.example.ui.screens

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ConversionHistoryEntity
import com.example.engine.StudioFormatters
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.StudioActionRow
import com.example.ui.components.StudioAsyncThumbnail
import com.example.ui.components.StudioButtonVariant
import com.example.ui.components.StudioDashedButton
import com.example.ui.components.StudioHapticEvent
import com.example.ui.components.StudioIconButton
import com.example.ui.components.StudioPillButton
import com.example.ui.theme.AccentBlue
import com.example.ui.theme.AccentLavender
import com.example.ui.theme.AccentMint
import com.example.ui.theme.MonoSpecStyle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    recentHistory: List<ConversionHistoryEntity>,
    onImportSingleVideo: () -> Unit,
    onImportBatchVideos: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGif: (Uri) -> Unit,
    onShareGif: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    onImportSingleGif: () -> Unit = {},
    onOpenMedia: ((Uri, String) -> Unit)? = null,
    onShareMedia: ((Uri, String) -> Unit)? = null,
    onOpenSupport: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalStudioHaptics.current

    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 680.dp)
        ) {
            // Top Studio Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.primaryContainer)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_nepi_mascot),
                            contentDescription = "Nepi GIF Lab mascot",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(36.dp)
                                .padding(1.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nepi GIF Lab",
                            style = MaterialTheme.typography.headlineSmall,
                            color = colors.onBackground
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = AccentMint,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "100% Offline Native Engine",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    StudioIconButton(
                        icon = Icons.Rounded.History,
                        contentDescription = "Conversion History",
                        onClick = onOpenHistory,
                        modifier = Modifier.testTag("home_history_button")
                    )
                    Spacer(Modifier.width(4.dp))
                    StudioIconButton(
                        icon = Icons.Rounded.Settings,
                        contentDescription = "Settings",
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    )
                }
            }

            // Primary Import Studio Action Surface
            item {
                val shape = RoundedCornerShape(24.dp)
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(colors.surfaceContainer)
                        .border(1.dp, colors.outlineVariant.copy(alpha = 0.65f), shape)
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AccentLavender.copy(alpha = 0.16f))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesomeMotion,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Craft Animated GIFs",
                                style = MaterialTheme.typography.titleLarge,
                                color = colors.onSurface
                            )
                            Text(
                                text = "Trim frame-by-frame, crop, tune palette & dithering with real live preview.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }

                    StudioPillButton(
                        text = "Import Video",
                        icon = Icons.Rounded.VideoLibrary,
                        variant = StudioButtonVariant.PRIMARY,
                        onClick = {
                            haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                            onImportSingleVideo()
                        },
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 15.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_video_button")
                    )

                    StudioPillButton(
                        text = "Import GIF",
                        icon = Icons.Rounded.GifBox,
                        variant = StudioButtonVariant.TONAL,
                        onClick = {
                            haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                            onImportSingleGif()
                        },
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_gif_button")
                    )

                    StudioDashedButton(
                        text = "Batch Convert Videos & GIFs",
                        icon = Icons.Rounded.Layers,
                        accentColor = colors.secondary,
                        onClick = {
                            haptics.perform(StudioHapticEvent.PRIMARY_ACTION)
                            onImportBatchVideos()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("batch_convert_button")
                    )
                }
            }

            // Quick Links
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioActionRow(
                        title = "Conversion History",
                        subtitle = if (recentHistory.isEmpty()) "Saved GIFs appear here"
                        else "${recentHistory.size} recent exports ready to view or share",
                        icon = Icons.Rounded.History,
                        iconAccent = AccentBlue,
                        trailingText = "See All",
                        onClick = onOpenHistory,
                        modifier = Modifier.testTag("quick_history_row")
                    )
                    StudioActionRow(
                        title = "Settings",
                        subtitle = "Theme preference, output defaults & storage folder",
                        icon = Icons.Rounded.Settings,
                        iconAccent = AccentLavender,
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("quick_settings_row")
                    )
                }
            }

            // Recent Conversions Section Header
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Recent Conversions",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onBackground
                    )
                    if (recentHistory.isNotEmpty()) {
                        Text(
                            text = "See All",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onOpenHistory)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (recentHistory.isEmpty()) {
                item {
                    val shape = RoundedCornerShape(20.dp)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(colors.surfaceContainerLow)
                            .border(1.dp, colors.outlineVariant.copy(alpha = 0.45f), shape)
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceContainerHigh)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.GifBox,
                                contentDescription = null,
                                tint = colors.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Text(
                            text = "No GIFs crafted yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Pick any video or animated GIF from your phone to start converting offline into Pictures/Nepi GIF Lab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(recentHistory, key = { it.id }) { item ->
                    RecentConversionRow(
                        item = item,
                        onOpen = {
                            val uri = Uri.parse(item.contentUri)
                            val mime = item.parsedOutputFormat.mimeType
                            if (onOpenMedia != null) onOpenMedia(uri, mime) else onOpenGif(uri)
                        },
                        onShare = {
                            val uri = Uri.parse(item.contentUri)
                            val mime = item.parsedOutputFormat.mimeType
                            if (onShareMedia != null) onShareMedia(uri, mime) else onShareGif(uri)
                        }
                    )
                }
            }

            // Subtle, non-intrusive Support Nepi section
            item {
                SupportNepiHomeCard(
                    onSupportClick = {
                        haptics.perform(StudioHapticEvent.SELECTION)
                        onOpenSupport()
                    }
                )
            }

            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SupportNepiHomeCard(
    onSupportClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.5f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("home_support_section")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(AccentLavender.copy(alpha = 0.14f))
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Support Nepi",
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Enjoying Nepi GIF Lab? Support its development. Every contribution helps.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        StudioPillButton(
            text = "Donate",
            variant = StudioButtonVariant.OUTLINED,
            onClick = onSupportClick,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            modifier = Modifier.testTag("home_support_button")
        )
    }
}

@Composable
private fun RecentConversionRow(
    item: ConversionHistoryEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    val dateStr = SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(item.createdAtMillis))
    val format = item.parsedOutputFormat

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainer)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
            .clickable(onClick = onOpen)
            .padding(12.dp)
    ) {
        val thumbModel: Any = item.thumbnailPath?.let { File(it) }?.takeIf { it.exists() }
            ?: Uri.parse(item.contentUri)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerHighest)
        ) {
            StudioAsyncThumbnail(
                model = thumbModel,
                contentDescription = item.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                color = colors.surface.copy(alpha = 0.8f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
            ) {
                Text(
                    text = format.shortLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${item.width}×${item.height} · ${item.fps.toInt()} FPS · ${StudioFormatters.formatFileSize(item.fileSizeBytes)}",
                style = MonoSpecStyle,
                color = colors.secondary,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = dateStr,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        StudioIconButton(
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = "Open GIF",
            onClick = onOpen,
            size = 38.dp
        )
        StudioIconButton(
            icon = Icons.Rounded.Share,
            contentDescription = "Share GIF",
            onClick = onShare,
            tint = colors.primary,
            size = 38.dp
        )
    }
}
