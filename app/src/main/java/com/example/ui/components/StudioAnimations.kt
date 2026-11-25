package com.example.ui.components

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * Checks Android's system reduced-motion / animator duration scale accessibility setting.
 * When animations are disabled at the system level, transitions become instant and
 * shimmer/pulse effects degrade gracefully to static indicators.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scaleZero = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Throwable) {
            false
        }
        val animatorsDisabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                !ValueAnimator.areAnimatorsEnabled()
            } catch (_: Throwable) {
                false
            }
        } else {
            false
        }
        scaleZero || animatorsDisabled
    }
}

/**
 * Returns a smooth [TweenSpec] that automatically collapses to an instant transition
 * when the user has enabled Android reduced motion.
 */
fun <T> studioTween(
    durationMillis: Int = 220,
    reducedMotion: Boolean = false,
    easing: Easing = FastOutSlowInEasing
): TweenSpec<T> = tween(
    durationMillis = if (reducedMotion) 0 else durationMillis,
    easing = easing
)

/**
 * Lightweight dark-lavender shimmer modifier drawn directly in the draw phase so it
 * avoids per-frame recompositions. Respects system reduced-motion settings.
 */
fun Modifier.studioShimmer(
    enabled: Boolean = true,
    baseColor: Color? = null,
    highlightColor: Color? = null
): Modifier = composed {
    if (!enabled) return@composed this

    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    val resolvedBase = baseColor ?: colors.surfaceContainerHigh
    val resolvedHighlight = highlightColor ?: colors.primary.copy(alpha = 0.16f)

    if (reducedMotion) {
        return@composed this.background(resolvedBase)
    }

    val transition = rememberInfiniteTransition(label = "studioShimmer")
    val progress by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerProgress"
    )

    this.drawBehind {
        val width = size.width.coerceAtLeast(1f)
        val height = size.height.coerceAtLeast(1f)
        val startX = width * progress
        val bandWidth = (width * 0.55f).coerceAtLeast(48f)
        val brush = Brush.linearGradient(
            colors = listOf(resolvedBase, resolvedHighlight, resolvedBase),
            start = Offset(startX - bandWidth, 0f),
            end = Offset(startX + bandWidth, height * 0.35f)
        )
        drawRect(brush = brush)
    }
}

/**
 * Compact translucent lavender loading pill for non-blocking background regeneration states.
 */
@Composable
fun StudioLoadingPill(
    text: String,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh.copy(alpha = 0.92f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = colors.primary,
                strokeCap = StrokeCap.Round,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface
            )
        }
    }
}

/**
 * Async image/thumbnail container with subtle shimmer placeholder while loading,
 * smooth crossfade when decoded, and a clean fallback icon on failure.
 */
@Composable
fun StudioAsyncThumbnail(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    imageLoader: ImageLoader? = null,
    crossfadeEnabled: Boolean = true
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val reducedMotion = rememberReducedMotionEnabled()
    var loadState by remember(model) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    val request = remember(context, model, crossfadeEnabled, reducedMotion) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(if (crossfadeEnabled && !reducedMotion) 220 else 0)
            .build()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        if (loadState is AsyncImagePainter.State.Loading || loadState is AsyncImagePainter.State.Empty) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .studioShimmer()
            )
        }

        if (imageLoader != null) {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = contentDescription,
                contentScale = contentScale,
                filterQuality = FilterQuality.High,
                onState = { loadState = it },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                filterQuality = FilterQuality.High,
                onState = { loadState = it },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (loadState is AsyncImagePainter.State.Error) {
            Icon(
                imageVector = Icons.Rounded.GifBox,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
