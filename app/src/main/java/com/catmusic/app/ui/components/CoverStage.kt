package com.catmusic.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.catmusic.app.data.Track
import com.catmusic.app.playback.AudioEnergy
import com.catmusic.app.playback.AudioReactiveSignal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class CoverDust(
    val angle: Float,
    val distance: Float,
    val size: Float,
    val phase: Float,
    val alpha: Float,
    val warm: Boolean,
    val sway: Float,
    val depth: Float
)

/**
 * A restrained, cover-first stage. The artwork is the only loud element;
 * a thin ring of dust keeps the picture alive and follows the decoded audio.
 * There is no scenery, no fake vinyl and no full-screen typography.
 */
@Composable
fun CoverStage(
    track: Track?,
    accent: Color,
    isPlaying: Boolean,
    effectsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val dust = remember(track?.id) {
        val random = Random((track?.id ?: "catmusic").hashCode() * 31 + 17)
        List(58) { index ->
            val baseAngle = (index / 58f) * 2f * PI.toFloat()
            CoverDust(
                angle = baseAngle + (random.nextFloat() - 0.5f) * 0.18f,
                distance = 1.012f + random.nextFloat() * 0.071f,
                size = 0.45f + random.nextFloat() * 1.15f,
                phase = random.nextFloat(),
                alpha = 0.075f + random.nextFloat() * 0.24f,
                warm = random.nextFloat() < 0.22f,
                sway = 0.015f + random.nextFloat() * 0.05f,
                depth = 0.25f + random.nextFloat() * 0.75f
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "coverDust")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(48_000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        ),
        label = "coverDustTime"
    )
    val twoPi = 2f * PI.toFloat()

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val stage = minOf(maxWidth, maxHeight).coerceAtLeast(150.dp)
        val artSize = stage * 0.86f
        val corner = RoundedCornerShape(22.dp)
        Box(
            modifier = Modifier.size(stage),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val half = artSize.toPx() / 2f
                val middle = center
                val audio = if (isPlaying && effectsEnabled) {
                    AudioReactiveSignal.current
                } else {
                    AudioEnergy()
                }
                val level = if (audio.active) audio.level else 0f
                val bass = if (audio.active) audio.bass else 0f
                val treble = if (audio.active) audio.treble else 0f
                val beat = if (audio.active) audio.beat else 0f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.13f + level * 0.10f),
                            Color.Transparent
                        ),
                        center = middle,
                        radius = half * 1.52f
                    ),
                    radius = half * 1.52f,
                    center = middle
                )

                dust.forEach { particle ->
                    val drift = sin(
                        ((time + particle.phase) * twoPi).toDouble()
                    ).toFloat()
                    val angle = particle.angle + drift * particle.sway
                    val distance = half * (
                        particle.distance +
                            (bass * 0.040f + beat * 0.025f) * particle.depth +
                            drift * 0.004f
                        )
                    val px = middle.x + cos(angle.toDouble()).toFloat() * distance
                    val py = middle.y +
                        sin(angle.toDouble()).toFloat() * distance * 0.975f +
                        sin(((time * 0.8f + particle.phase) * twoPi).toDouble())
                            .toFloat() * 2.2.dp.toPx()
                    val twinkle = 0.58f + 0.42f * (
                        sin(((time * 2.1f + particle.phase) * twoPi).toDouble())
                            .toFloat() * 0.5f + 0.5f
                        )
                    val alpha = (
                        particle.alpha * twinkle *
                            (0.64f + level * 0.70f + beat * 0.55f + treble * 0.18f)
                        ).coerceIn(0f, 0.62f)
                    if (alpha > 0.012f) {
                        val radius = particle.size.dp.toPx() *
                            (1f + bass * 0.45f + beat * 0.50f)
                        drawCircle(
                            color = if (particle.warm) {
                                accent.copy(alpha = alpha * 0.78f)
                            } else {
                                Color.White.copy(alpha = alpha)
                            },
                            radius = radius,
                            center = Offset(px, py)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(artSize)
                    .shadow(
                        elevation = 26.dp,
                        shape = corner,
                        ambientColor = Color.Black,
                        spotColor = Color.Black
                    )
                    .clip(corner)
                    .background(Color(0xFF0A0B0D))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), corner)
                    .graphicsLayer {
                        val audio = if (isPlaying && effectsEnabled) {
                            AudioReactiveSignal.current
                        } else {
                            AudioEnergy()
                        }
                        val scale = 1f +
                            (if (audio.active) audio.level else 0f) * 0.014f +
                            (if (audio.active) audio.beat else 0f) * 0.011f
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                CoverArt(
                    artworkFile = track?.artworkFile,
                    seed = track?.id ?: "catmusic",
                    cornerRadius = 22.dp,
                    showBorder = false,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.045f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.16f)
                                )
                            )
                        )
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(1.dp, Color.White.copy(alpha = 0.075f), corner)
                )
            }
        }
    }
}
