package com.catmusic.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.catmusic.app.ui.theme.CatBg
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatViolet
import kotlin.math.sin
import kotlin.random.Random

private data class Star(
    val x: Float,
    val y: Float,
    val radius: Float,
    val alpha: Float
)

@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val driftA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(19_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftA"
    )
    val driftB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftB"
    )
    val driftC by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(22_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "driftC"
    )
    val stars = remember {
        val random = Random(2026)
        List(52) {
            Star(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 0.35f + random.nextFloat() * 1.15f,
                alpha = 0.03f + random.nextFloat() * 0.11f
            )
        }
    }

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                0f to CatBg,
                0.45f to Color(0xFF0A0711),
                1f to Color(0xFF07060C)
            )
        )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            val violetCenter = Offset(
                x = w * (0.18f + 0.15f * driftA),
                y = h * (0.10f + 0.06f * driftB)
            )
            val violetRadius = w * 0.82f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CatViolet.copy(alpha = 0.19f), Color.Transparent),
                    center = violetCenter,
                    radius = violetRadius
                ),
                radius = violetRadius,
                center = violetCenter
            )

            val peachCenter = Offset(
                x = w * (0.95f - 0.12f * driftC),
                y = h * (0.28f + 0.10f * driftA)
            )
            val peachRadius = w * 0.72f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CatPeach.copy(alpha = 0.12f), Color.Transparent),
                    center = peachCenter,
                    radius = peachRadius
                ),
                radius = peachRadius,
                center = peachCenter
            )

            val mintCenter = Offset(
                x = w * (0.20f + 0.18f * driftB),
                y = h * (0.82f + 0.05f * driftC)
            )
            val mintRadius = w * 0.68f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(CatMint.copy(alpha = 0.075f), Color.Transparent),
                    center = mintCenter,
                    radius = mintRadius
                ),
                radius = mintRadius,
                center = mintCenter
            )

            val glowCenter = Offset(w * 0.75f, h * 0.76f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF7A5BB0).copy(alpha = 0.075f), Color.Transparent),
                    center = glowCenter,
                    radius = w * 0.6f
                ),
                center = glowCenter,
                radius = w * 0.6f
            )

            stars.forEach { star ->
                drawCircle(
                    color = Color.White.copy(alpha = star.alpha),
                    radius = star.radius,
                    center = Offset(star.x * w, star.y * h)
                )
            }

            // A barely visible horizon arc to give the empty space depth.
            val waveY = h * 0.58f
            for (index in 0..2) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(-w * 0.1f, waveY + index * 18f)
                    cubicTo(
                        w * 0.25f,
                        waveY - 26f + sin((driftA * 3.14f).toDouble()).toFloat() * 10f + index * 18f,
                        w * 0.7f,
                        waveY + 38f + sin((driftC * 3.14f).toDouble()).toFloat() * 8f + index * 18f,
                        w * 1.1f,
                        waveY + index * 18f
                    )
                }
                drawPath(
                    path = path,
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.022f - index * 0.005f),
                            Color.Transparent
                        )
                    ),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f)
                )
            }
        }
    }
}
