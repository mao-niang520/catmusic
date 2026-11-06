package com.catmusic.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.catmusic.app.playback.AudioEnergy
import com.catmusic.app.playback.AudioReactiveSignal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private data class HazeField(
    val x: Float,
    val y: Float,
    val radius: Float,
    val stretch: Float,
    val rotation: Float,
    val color: Color,
    val alpha: Float,
    val driftX: Float,
    val driftY: Float,
    val phase: Float
)

/**
 * An original, procedural take on the hazy blue/red liquid gradient from the
 * supplied motion reference. It is deliberately made from broad soft fields
 * and a single silky specular fold rather than discrete floating circles, so
 * the colours melt into one another instead of reading as separate blobs.
 *
 * Motion follows the app's adaptive frame budget; the menu ripple is read from
 * LocalSceneRipple so the three-dot menu still visibly disturbs the surface.
 */
@Composable
fun HazyFluidBackdrop(
    accent: Color,
    isPlaying: Boolean,
    effectsEnabled: Boolean,
    landscape: Boolean,
    modifier: Modifier = Modifier
) {
    val motionNanos = LocalAdaptiveMotionNanos.current
    val ripple = LocalSceneRipple.current
    val fields = remember(accent) {
        listOf(
            // Two luminous colour masses: electric blue on the left, crimson
            // on the right. Their overlap naturally produces the magenta seam.
            HazeField(
                x = 0.16f, y = 0.24f, radius = 0.78f, stretch = 0.86f,
                rotation = -17f, color = Color(0xFF2A4BFF), alpha = 0.96f,
                driftX = 0.020f, driftY = 0.028f, phase = 0.0f
            ),
            HazeField(
                x = 0.84f, y = 0.18f, radius = 0.73f, stretch = 0.78f,
                rotation = 16f, color = Color(0xFFFF0B22), alpha = 0.93f,
                driftX = 0.018f, driftY = 0.024f, phase = 2.1f
            ),
            HazeField(
                x = 0.33f, y = 0.68f, radius = 0.58f, stretch = 0.74f,
                rotation = 8f, color = Color(0xFF2E3CFF), alpha = 0.54f,
                driftX = 0.026f, driftY = 0.021f, phase = 4.2f
            ),
            HazeField(
                x = 0.69f, y = 0.48f, radius = 0.56f, stretch = 0.94f,
                rotation = -9f, color = Color(0xFFFF3DB6), alpha = 0.55f,
                driftX = 0.022f, driftY = 0.027f, phase = 1.4f
            ),
            HazeField(
                x = 0.91f, y = 0.74f, radius = 0.46f, stretch = 0.72f,
                rotation = 26f, color = Color(0xFFFF7CDB), alpha = 0.34f,
                driftX = 0.017f, driftY = 0.022f, phase = 3.6f
            ),
            // A washed-out blue held under the cream keeps the lower left
            // coloured instead of falling back to the dark base.
            HazeField(
                x = 0.22f, y = 0.92f, radius = 0.50f, stretch = 0.40f,
                rotation = -8f, color = Color(0xFFC9D9FF), alpha = 0.22f,
                driftX = 0.018f, driftY = 0.016f, phase = 0.9f
            ),
            // The warm, almost solid cream pool that makes the lower right
            // read as lit liquid instead of another coloured circle.
            HazeField(
                x = 0.70f, y = 0.86f, radius = 0.56f, stretch = 0.30f,
                rotation = -11f, color = Color(0xFFFFF6E9), alpha = 0.86f,
                driftX = 0.018f, driftY = 0.014f, phase = 5.0f
            ),
            HazeField(
                x = 0.80f, y = 0.75f, radius = 0.28f, stretch = 0.55f,
                rotation = 18f, color = Color(0xFFFFF9F0), alpha = 0.55f,
                driftX = 0.012f, driftY = 0.018f, phase = 0.6f
            ),
            HazeField(
                x = 0.84f, y = 0.72f, radius = 0.18f, stretch = 0.52f,
                rotation = 16f, color = Color(0xFFFFFFFF), alpha = 0.50f,
                driftX = 0.010f, driftY = 0.012f, phase = 1.7f
            ),
            // A barely-there cover-colour shimmer keeps each song personal
            // without changing the blue/red identity of the scene.
            HazeField(
                x = 0.53f, y = 0.30f, radius = 0.30f, stretch = 1.0f,
                rotation = 0f, color = accent, alpha = 0.10f,
                driftX = 0.030f, driftY = 0.022f, phase = 2.8f
            )
        )
    }

    Canvas(modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val audio = if (isPlaying && effectsEnabled) {
            AudioReactiveSignal.current
        } else {
            AudioEnergy()
        }
        val level = if (audio.active) audio.level else 0f
        val bass = if (audio.active) audio.bass else 0f
        val seconds = motionNanos.value / 1_000_000_000f

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF050B2C),
                    Color(0xFF0B1448),
                    Color(0xFF120718)
                ),
                start = Offset(0f, 0f),
                end = Offset(width, height)
            )
        )

        val twoPi = 2f * PI.toFloat()
        fields.forEach { field ->
            val phase = seconds * twoPi / 46f + field.phase
            val center = Offset(
                width * (field.x + sin(phase) * field.driftX),
                height * (field.y + cos(phase * 0.87f) * field.driftY)
            )
            val radius = max(width, height) * field.radius *
                (1f + level * 0.04f + bass * 0.012f)
            val alpha = (
                field.alpha *
                    (0.92f + 0.08f * sin(phase * 0.73f) + level * 0.10f + bass * 0.04f)
                ).coerceIn(0f, 0.97f)
            withTransform({
                translate(center.x, center.y)
                rotate(field.rotation + sin(phase) * 3f, Offset.Zero)
                scale(1f, field.stretch, Offset.Zero)
            }) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            field.color.copy(alpha = alpha),
                            field.color.copy(alpha = alpha * 0.58f),
                            field.color.copy(alpha = 0f)
                        ),
                        center = Offset.Zero,
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset.Zero
                )
            }
        }

        // A slow diagonal wash of warm light crosses the blue/red seam.
        val sheen = 0.5f + 0.5f * sin(seconds * twoPi / 58f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFFFFF3ED).copy(alpha = 0.06f + level * 0.02f),
                    Color(0xFFFFDCE8).copy(alpha = 0.035f),
                    Color.Transparent
                ),
                start = Offset(width * (0.02f + 0.15f * sheen), height * 0.04f),
                end = Offset(width * (0.74f + 0.12f * sheen), height * 0.98f)
            )
        )

        // One broad silky fold, with only a whisper of a specular edge.
        val foldY = height * (0.77f + 0.018f * sin(seconds * twoPi / 52f)) -
            bass * height * 0.018f
        val fold = Path().apply {
            moveTo(width * 0.22f, foldY + height * 0.045f)
            cubicTo(
                width * 0.42f, foldY - height * 0.09f,
                width * 0.66f, foldY + height * 0.075f,
                width * 1.02f, foldY - height * 0.03f
            )
        }
        val foldShort = minOf(width, height)
        drawPath(
            path = fold,
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.Transparent,
                    Color(0xFFFFF5ED).copy(alpha = 0.08f),
                    Color(0xFFFFDDE9).copy(alpha = 0.13f),
                    Color(0xFFFFF8F1).copy(alpha = 0.10f),
                    Color.Transparent
                ),
                start = Offset(width * 0.04f, foldY),
                end = Offset(width, foldY - height * 0.06f)
            ),
            style = Stroke(width = foldShort * 0.089f, cap = StrokeCap.Round)
        )
        drawPath(
            path = fold,
            color = Color.White.copy(alpha = 0.09f),
            style = Stroke(width = foldShort * 0.036f, cap = StrokeCap.Round)
        )
        drawPath(
            path = fold,
            color = Color.White.copy(alpha = 0.15f),
            style = Stroke(width = foldShort * 0.0056f, cap = StrokeCap.Round)
        )
        // The fold catches a wider pool of light around its lower-right bend.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFFCF6).copy(alpha = 0.22f),
                    Color(0xFFFFE2EB).copy(alpha = 0.10f),
                    Color(0xFFFFE2EB).copy(alpha = 0f)
                ),
                center = Offset(width * 0.73f, height * 0.79f),
                radius = height * 0.30f
            ),
            radius = height * 0.30f,
            center = Offset(width * 0.73f, height * 0.79f)
        )

        // Edge scrims keep the existing typography and glass dock readable
        // while leaving the coloured centre fully visible.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.18f),
                0.20f to Color.Transparent,
                0.72f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.34f)
            )
        )
        if (landscape) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.16f),
                    0.40f to Color.Transparent,
                    0.82f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.06f)
                )
            )
        } else {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.18f)
                    ),
                    center = Offset(width * 0.50f, height * 0.44f),
                    radius = max(width, height) * 0.78f
                )
            )
        }

        drawHazeRipple(accent = accent, progress = ripple.value)
    }
}

private fun DrawScope.drawHazeRipple(accent: Color, progress: Float) {
    if (progress >= 0.999f) return
    val p = progress.coerceIn(0f, 1f)
    val fade = (1f - p) * (1f - p)
    val origin = Offset(size.width * 0.87f, size.height * 0.14f)
    val radius = size.minDimension * (0.035f + p * 0.78f)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.15f * fade),
                accent.copy(alpha = 0.10f * fade),
                accent.copy(alpha = 0f)
            ),
            center = origin,
            radius = radius
        ),
        radius = radius,
        center = origin
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.28f * fade),
        radius = radius,
        center = origin,
        style = Stroke(width = (0.6f + 2.2f * fade).dp.toPx())
    )
    drawCircle(
        color = accent.copy(alpha = 0.20f * fade),
        radius = radius * 1.22f,
        center = origin,
        style = Stroke(width = (0.4f + 1.2f * fade).dp.toPx())
    )
}
