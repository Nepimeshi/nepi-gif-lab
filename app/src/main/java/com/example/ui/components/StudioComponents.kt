package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class StudioButtonVariant {
    PRIMARY,
    TONAL,
    OUTLINED,
    DANGER
}

/**
 * Tactile pill button inspired by the reference visual system with full animated state feedback:
 * Enabled, Disabled, Hovered, Focused (double outer ring), and Pressed (subtle corner morph & scale).
 */
@Composable
fun StudioPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    variant: StudioButtonVariant = StudioButtonVariant.PRIMARY,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    val colors = MaterialTheme.colorScheme
    val baseBg = when (variant) {
        StudioButtonVariant.PRIMARY -> colors.primary
        StudioButtonVariant.TONAL -> colors.surfaceContainerHigh
        StudioButtonVariant.OUTLINED -> Color.Transparent
        StudioButtonVariant.DANGER -> colors.errorContainer
    }
    val baseFg = when (variant) {
        StudioButtonVariant.PRIMARY -> colors.onPrimary
        StudioButtonVariant.TONAL -> colors.onSurface
        StudioButtonVariant.OUTLINED -> colors.onSurface
        StudioButtonVariant.DANGER -> colors.onErrorContainer
    }

    val targetBg = when {
        !enabled -> colors.surfaceContainerHigh.copy(alpha = 0.55f)
        isPressed && variant == StudioButtonVariant.PRIMARY -> baseBg.copy(alpha = 0.88f)
        isHovered && variant == StudioButtonVariant.PRIMARY -> baseBg.copy(alpha = 0.94f)
        isPressed && variant == StudioButtonVariant.TONAL -> colors.surfaceContainerHighest
        isHovered && variant == StudioButtonVariant.TONAL -> colors.surfaceContainerHighest.copy(alpha = 0.85f)
        isPressed && variant == StudioButtonVariant.OUTLINED -> colors.surfaceContainerHigh.copy(alpha = 0.6f)
        isHovered && variant == StudioButtonVariant.OUTLINED -> colors.surfaceContainerHigh.copy(alpha = 0.35f)
        else -> baseBg
    }

    val targetFg = if (enabled) baseFg else colors.onSurfaceVariant.copy(alpha = 0.45f)
    val animatedBg by animateColorAsState(targetBg, tween(140), label = "btnBg")
    val animatedFg by animateColorAsState(targetFg, tween(140), label = "btnFg")
    val cornerRadius by animateDpAsState(
        targetValue = if (isPressed && enabled) 14.dp else 24.dp,
        animationSpec = spring(stiffness = 700f),
        label = "btnRadius"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.975f else 1f,
        animationSpec = spring(stiffness = 750f),
        label = "btnScale"
    )
    val shape = RoundedCornerShape(cornerRadius)
    val outerRingShape = RoundedCornerShape(cornerRadius + 3.dp)

    val borderStroke = when {
        variant == StudioButtonVariant.OUTLINED && enabled -> BorderStroke(1.2.dp, colors.outline)
        variant == StudioButtonVariant.OUTLINED -> BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.4f))
        else -> null
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .scale(scale)
            .then(
                if (isFocused && enabled) {
                    Modifier
                        .border(2.dp, colors.primary, outerRingShape)
                        .padding(3.dp)
                } else {
                    Modifier
                }
            )
    ) {
        Surface(
            color = animatedBg,
            contentColor = animatedFg,
            shape = shape,
            border = borderStroke,
            shadowElevation = if (variant == StudioButtonVariant.PRIMARY && enabled) {
                if (isHovered) 4.dp else 2.dp
            } else 0.dp,
            modifier = Modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(shape)
                .hoverable(interactionSource, enabled = enabled)
                .focusable(enabled = enabled, interactionSource = interactionSource)
                .clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    role = Role.Button,
                    onClick = onClick
                )
        ) {
            Row(
                modifier = Modifier.padding(contentPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Circular icon button with crisp enabled/disabled/hovered/focused/pressed states and >=48dp touch target.
 */
@Composable
fun StudioIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    size: Dp = 44.dp
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surfaceContainerHigh.copy(alpha = 0.4f)
            isPressed -> colors.surfaceContainerHighest
            isHovered -> containerColor.copy(alpha = 0.85f)
            else -> containerColor
        },
        animationSpec = tween(120),
        label = "iconBtnBg"
    )
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.93f else 1f,
        animationSpec = spring(stiffness = 750f),
        label = "iconBtnScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .scale(scale)
            .then(
                if (isFocused && enabled) {
                    Modifier
                        .border(2.dp, colors.primary, CircleShape)
                        .padding(2.dp)
                } else Modifier
            )
            .size(size)
            .clip(CircleShape)
            .background(bg)
            .hoverable(interactionSource, enabled = enabled)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else colors.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Selectable pill chip with animated lavender selection fill, focus ring, and press feedback.
 */
@Composable
fun StudioSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme

    val targetBg = when {
        !enabled -> colors.surfaceContainer.copy(alpha = 0.4f)
        selected && isPressed -> colors.primary.copy(alpha = 0.88f)
        selected -> colors.primary
        isPressed -> colors.surfaceContainerHighest
        isHovered -> colors.surfaceContainerHigh
        else -> colors.surfaceContainer
    }
    val targetFg = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
        selected -> colors.onPrimary
        else -> colors.onSurface
    }
    val borderColor = when {
        isFocused && enabled -> colors.primary
        selected -> Color.Transparent
        else -> colors.outlineVariant
    }

    val bg by animateColorAsState(targetBg, tween(130), label = "chipBg")
    val fg by animateColorAsState(targetFg, tween(130), label = "chipFg")
    val radius by animateDpAsState(if (isPressed) 11.dp else 18.dp, spring(stiffness = 700f), label = "chipRad")
    val shape = RoundedCornerShape(radius)

    Surface(
        color = bg,
        contentColor = fg,
        shape = shape,
        border = if (borderColor != Color.Transparent) BorderStroke(if (isFocused) 1.8.dp else 1.dp, borderColor) else null,
        modifier = modifier
            .defaultMinSize(minHeight = 40.dp)
            .clip(shape)
            .hoverable(interactionSource, enabled = enabled)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                role = Role.RadioButton,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * Dashed-border secondary action button inspired by Reference 2 (ideal for Add Videos / Batch Queue).
 */
@Composable
fun StudioDashedButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.secondary
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)

    val strokeColor = if (enabled) accentColor.copy(alpha = if (isPressed) 0.95f else 0.72f)
    else colors.outlineVariant.copy(alpha = 0.4f)
    val fillColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            isPressed -> accentColor.copy(alpha = 0.14f)
            isHovered -> accentColor.copy(alpha = 0.08f)
            else -> accentColor.copy(alpha = 0.04f)
        },
        animationSpec = tween(120),
        label = "dashedBg"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .clip(shape)
            .background(fillColor)
            .drawBehind {
                val stroke = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                )
                drawRoundRect(
                    color = strokeColor,
                    cornerRadius = CornerRadius(18.dp.toPx(), 18.dp.toPx()),
                    style = stroke
                )
            }
            .hoverable(interactionSource, enabled = enabled)
            .focusable(enabled = enabled, interactionSource = interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accentColor else colors.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) accentColor else colors.onSurfaceVariant.copy(alpha = 0.45f)
        )
    }
}

/**
 * Clean navigation/action row with colorful circular icon badge and chevron (inspired by Reference 2).
 */
@Composable
fun StudioActionRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    iconAccent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingText: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)

    val bg by animateColorAsState(
        targetValue = when {
            isPressed -> colors.surfaceContainerHighest
            isHovered -> colors.surfaceContainerHigh
            else -> colors.surfaceContainer
        },
        animationSpec = tween(120),
        label = "rowBg"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.55f), shape)
            .hoverable(interactionSource)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(iconAccent.copy(alpha = 0.16f))
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconAccent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}
