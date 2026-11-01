package com.catmusic.app.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.catmusic.app.data.AudioVisualLevel
import com.catmusic.app.playback.AudioEnergy
import com.catmusic.app.playback.AudioReactiveSignal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A terrain of thousands of tiny voxel columns, inspired by Sonic Topography.
 * The eight decoded frequency bands lift different regions of the field: the
 * sub-bass raises the glowing center, bass creates blocky clusters, mids flow
 * like a river and the high bands scatter sparks across the edges. Everything
 * is drawn in the Canvas draw phase and reads the PCM signal directly, so the
 * field never needs a microphone, a WebView or per-frame recomposition.
 */
@Composable
fun SoundField(
    level: AudioVisualLevel,
    active: Boolean,
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val rank = level.rank
    val gridSize = when (rank) {
        1 -> 32
        2 -> 48
        3 -> 64
        else -> 88
    }
    val columns = remember(gridSize) { buildColumns(gridSize) }
    val moteCount = when (rank) {
        1 -> 20
        2 -> 48
        3 -> 90
        else -> 150
    }
    val motes = remember(moteCount) { buildMotes(moteCount) }
    val frontMesh = remember(columns.size) { TerrainMesh(columns.size) }
    val topMesh = remember(columns.size) { TerrainMesh(columns.size) }
    val topPalette = remember { buildTopRgbPalette() }
    val frontPalette = remember { buildFrontRgbPalette() }
    val paint = remember {
        Paint().apply {
            isAntiAlias = false
            isDither = true
            color = 0xFFFFFFFF.toInt()
        }
    }
    val transition = rememberInfiniteTransition(label = "sonicTopography")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(48_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonicTopographyTime"
    )
    val twoPi = (2f * PI).toFloat()
    val levelGain = when (rank) {
        1 -> 0.82f
        2 -> 0.96f
        3 -> 1.08f
        else -> 1.22f
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        if (!active) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.055f), Color.Transparent),
                    center = Offset(w * 0.50f, h * 0.70f),
                    radius = h * 0.46f
                ),
                radius = h * 0.46f,
                center = Offset(w * 0.50f, h * 0.70f)
            )
            return@Canvas
        }

        val audio = if (isPlaying) AudioReactiveSignal.current else AudioEnergy()
        val audioOn = audio.active
        val bands = audio.bands
        val levelEnergy = if (audioOn) audio.level else 0f
        val beat = if (audioOn) audio.beat else 0f
        val treble = if (audioOn) audio.treble else 0f
        val bassFallback = if (audioOn) audio.bass else 0f
        val sub = if (audioOn) bands[AudioEnergy.SUB_BASS].ifZero(bassFallback * 0.82f) else 0f
        val bass = if (audioOn) bands[AudioEnergy.BASS].ifZero(bassFallback) else 0f
        val lowMid = if (audioOn) {
            bands[AudioEnergy.LOW_MID].ifZero(bassFallback * 0.62f)
        } else {
            0f
        }
        val mid = if (audioOn) {
            bands[AudioEnergy.MID].ifZero(levelEnergy * 0.55f)
        } else {
            0f
        }
        val highMid = if (audioOn) {
            bands[AudioEnergy.HIGH_MID].ifZero(levelEnergy * 0.45f)
        } else {
            0f
        }
        val presence = if (audioOn) {
            bands[AudioEnergy.PRESENCE].ifZero(treble * 0.72f)
        } else {
            0f
        }
        val brilliance = if (audioOn) {
            bands[AudioEnergy.BRILLIANCE].ifZero(treble * 0.66f)
        } else {
            0f
        }
        val air = if (audioOn) {
            bands[AudioEnergy.AIR].ifZero(treble)
        } else {
            0f
        }
        val motion = if (isPlaying) 1f else 0.28f
        val t = time * twoPi
        val cx = w * 0.50f
        val fieldUnit = minOf(h, w)
        val portraitBoost = (minOf(1.20f, h / w.coerceAtLeast(1f) * 0.54f))
            .coerceIn(1f, 1.20f)
        val deepWarm = Color(0xFF9A3512)
        val cool = Color(0xFF3B5A79)

        // The backdrop stays deep and quiet so the glowing terrain owns the scene.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color(0xFF04060B),
                0.36f to Color(0xFF060A12),
                0.72f to Color(0xFF050810),
                1f to Color(0xFF020305)
            )
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(cool.copy(alpha = 0.055f + air * 0.055f), Color.Transparent),
                center = Offset(w * 0.78f, h * 0.18f),
                radius = w * 0.62f
            ),
            radius = w * 0.62f,
            center = Offset(w * 0.78f, h * 0.18f)
        )
        val warmRadius = w * (0.56f + sub * 0.10f + beat * 0.05f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    deepWarm.copy(alpha = 0.20f + sub * 0.14f + beat * 0.06f),
                    Color.Transparent
                ),
                center = Offset(cx, h * 0.58f),
                radius = warmRadius
            ),
            radius = warmRadius,
            center = Offset(cx, h * 0.58f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF28506B).copy(alpha = 0.05f),
                    Color.Transparent
                ),
                center = Offset(w * 0.16f, h * 0.62f),
                radius = w * 0.46f
            ),
            radius = w * 0.46f,
            center = Offset(w * 0.16f, h * 0.62f)
        )

        frontMesh.reset()
        topMesh.reset()

        for (column in columns) {
            val depth = column.depth
            val spread = w * (0.32f + depth * 0.32f) * portraitBoost
            val baseY = h * (0.28f + depth * 0.77f)
            val perspective = 0.62f + depth * 0.55f
            val sx = cx + column.x * spread + (depth - 0.5f) * w * 0.045f
            if (sx < -w * 0.12f || sx > w * 1.12f) continue

            val center = (1f - column.radius).coerceIn(0f, 1f)
            val dome = smoothstep(0.98f, 0.34f, column.radius)
            val idleWave = 0.5f + 0.5f * sin(
                column.phase * 6.1f + t * motion + column.radius * 4.8f
            )
            val clusterWave = 0.5f + 0.5f * sin(
                column.x * 5.2f + depth * 3.4f - t * 1.25f * motion +
                    column.phase * 2.1f
            )
            val flowWave = 0.5f + 0.5f * sin(
                column.x * 3.0f - depth * 6.0f + t * 0.62f * motion +
                    column.staticNoise * 4f
            )
            val gate = (column.random - 0.78f).coerceAtLeast(0f) * 4.6f

            // A broad plateau rather than a single peak: a permanent mound,
            // per-column jitter and the decoded bands together build the range.
            val ridge = 0.5f + 0.5f * sin(
                column.x * 9.0f + depth * 11.0f + column.phase * 2.4f
            )
            var elevation = 0.030f +
                dome * 0.42f +
                ridge * dome * 0.16f +
                (column.random - 0.5f) * (0.055f + dome * 0.38f) +
                (0.025f + idleWave * 0.075f) *
                (0.45f + 0.55f * center) * (0.72f + depth * 0.28f)
            elevation += levelEnergy * dome * 0.14f
            elevation += sub * dome * 0.66f
            elevation += bass * dome * (0.20f + clusterWave * 0.80f) * 0.56f
            elevation += lowMid * (0.25f + flowWave * 0.75f) * 0.34f
            elevation += mid * (0.20f + clusterWave * 0.80f) * 0.36f *
                (0.35f + 0.65f * dome)
            elevation += highMid * gate * 0.36f * (0.35f + 0.65f * dome)
            elevation += presence * gate * 0.14f * (0.35f + 0.65f * dome)
            elevation += beat * dome * 0.20f
            if (beat > 0.02f) {
                val ringRadius = (1f - beat) * 0.94f
                val ringDistance = (column.radius - ringRadius) / 0.14f
                elevation += beat * exp(-ringDistance * ringDistance) *
                    0.11f * dome
            }
            if (column.random > 0.985f) {
                elevation += levelEnergy * 0.28f
            }
            elevation *= levelGain
            val edgeFade = (1.02f - column.radius).coerceIn(0f, 1f)
            elevation *= 0.28f + 0.72f * edgeFade
            elevation = elevation.coerceIn(0.012f, 1.25f)

            val cellWidth = (spread * 2f / gridSize) * 0.72f
            if (cellWidth < 0.55f) continue
            val heightPx = (elevation * fieldUnit * 0.30f * perspective)
                .coerceAtLeast(0.8f)
            val topY = baseY - heightPx
            val topDepth = ((fieldUnit * 0.72f / gridSize) * 0.48f)
                .coerceAtLeast(1.1f)
            if (topY > h + topDepth || baseY < -topDepth) continue

            val glow = (
                0.05f + elevation * 0.62f + dome * 0.16f +
                    levelEnergy * 0.08f + treble * 0.06f + brilliance * 0.04f
                ).coerceIn(0f, 1f)
            val paletteIndex = (glow * 63f).toInt().coerceIn(0, 63)
            var topRgb = topPalette[paletteIndex]
            if (rank >= 3 && column.random > 0.995f &&
                (presence + brilliance) > 0.16f
            ) {
                topRgb = blendRgb(topRgb, 0xFFFFF3D6.toInt(), 0.58f)
            }
            val frontRgb = frontPalette[paletteIndex]
            val frontRimRgb = blendRgb(frontRgb, topRgb, 0.24f)
            val bottomRgb = frontPalette[(paletteIndex * 0.60f).toInt()]

            val distanceFade = 0.48f + depth * 0.52f
            val edgeAlpha = (0.38f + 0.62f * dome).coerceIn(0f, 1f)
            val columnAlpha = 0.90f + column.random * 0.16f
            val topAlpha = (
                distanceFade * edgeAlpha * columnAlpha *
                    (0.82f + levelEnergy * 0.22f + beat * 0.06f)
                ).coerceIn(0.12f, 0.97f)
            val frontAlpha = (topAlpha * 0.94f).coerceIn(0.10f, 0.95f)

            val topColor = withAlpha(topRgb, (topAlpha * 255f).toInt())
            val frontTopColor = withAlpha(
                frontRimRgb,
                (frontAlpha * 255f).toInt()
            )
            val frontBottomColor = withAlpha(
                bottomRgb,
                (frontAlpha * 0.82f * 255f).toInt()
            )

            val halfWidth = cellWidth * 0.5f
            val frontHalfWidth = halfWidth * 0.82f
            val bottomY = baseY + topDepth * 0.22f
            frontMesh.addQuad(
                sx - frontHalfWidth, topY, frontTopColor,
                sx + frontHalfWidth, topY, frontTopColor,
                sx + frontHalfWidth, bottomY, frontBottomColor,
                sx - frontHalfWidth, bottomY, frontBottomColor
            )

            // A short oblique top face gives every column its voxel silhouette.
            val skew = cellWidth * 0.20f
            val backHalf = halfWidth * 0.84f
            topMesh.addQuad(
                sx - halfWidth, topY, topColor,
                sx + halfWidth, topY, topColor,
                sx + backHalf - skew, topY - topDepth, topColor,
                sx - backHalf - skew, topY - topDepth, topColor
            )
        }

        if (frontMesh.faceCount > 0) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawVertices(
                    Canvas.VertexMode.TRIANGLES,
                    frontMesh.vertexCount,
                    frontMesh.verts,
                    0,
                    null,
                    0,
                    frontMesh.colors,
                    0,
                    frontMesh.indices,
                    0,
                    frontMesh.faceCount * 6,
                    paint
                )
                if (topMesh.faceCount > 0) {
                    canvas.nativeCanvas.drawVertices(
                        Canvas.VertexMode.TRIANGLES,
                        topMesh.vertexCount,
                        topMesh.verts,
                        0,
                        null,
                        0,
                        topMesh.colors,
                        0,
                        topMesh.indices,
                        0,
                        topMesh.faceCount * 6,
                        paint
                    )
                }
            }
        }

        // Mist and embers make the field feel like it is breathing.
        val bloomRadius = w * (0.32f + sub * 0.10f + beat * 0.05f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFFB359).copy(
                        alpha = 0.075f + sub * 0.09f + beat * 0.04f
                    ),
                    Color.Transparent
                ),
                center = Offset(cx, h * 0.54f),
                radius = bloomRadius
            ),
            radius = bloomRadius,
            center = Offset(cx, h * 0.54f)
        )

        motes.forEachIndexed { index, mote ->
            val y = (mote.y - time * mote.speed * motion + 1f) % 1f
            val x = mote.x +
                sin((t * 0.9f + mote.phase) * 1f) * mote.drift
            val twinkle = 0.55f + 0.45f * sin(t * 2.2f + mote.phase * 9f)
            val moteAlpha = (
                mote.alpha * twinkle *
                    (0.35f + levelEnergy * 0.55f + air * 0.40f + beat * 0.35f)
                ).coerceIn(0f, 0.60f)
            if (moteAlpha < 0.012f) return@forEachIndexed
            val center = Offset(x * w, h * (0.16f + y * 0.62f))
            val radius = mote.size.dp.toPx() *
                (1f + bass * 0.30f + beat * 0.25f)
            val color = if (mote.warm) {
                Color(0xFFFFC06A)
            } else {
                Color(0xFFD6ECF7)
            }
            if (rank >= 3 && index % 7 == 0) {
                drawCircle(
                    color = color.copy(alpha = moteAlpha * 0.10f),
                    radius = radius * 4.2f,
                    center = center
                )
            }
            if (rank >= 4 && index % 9 == 0 && treble > 0.12f) {
                drawLine(
                    color = color.copy(alpha = moteAlpha * 0.24f),
                    start = center,
                    end = Offset(center.x, center.y + radius * 7f),
                    strokeWidth = radius * 0.6f
                )
            }
            drawCircle(
                color = color.copy(alpha = moteAlpha),
                radius = radius,
                center = center
            )
        }

        // A quiet vignette keeps the lyrics and controls readable at the edges.
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.26f),
                0.20f to Color.Transparent,
                0.74f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.30f)
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f)),
                center = Offset(cx, h * 0.50f),
                radius = maxOf(w, h) * 0.78f
            )
        )
    }
}

/**
 * A single GPU-friendly triangle mesh holding every visible voxel face. Four
 * vertices and six indices per quad mean even the densest field is submitted
 * with one draw call instead of thousands of per-column composable draws.
 */
private class TerrainMesh(maxColumns: Int) {
    private val maxVertices = minOf(maxColumns * 4, 65_532)
    private val maxFaces = maxVertices / 4

    val verts = FloatArray(maxVertices * 2)
    val colors = IntArray(maxVertices)
    val indices = ShortArray(maxFaces * 6)

    var vertexCount = 0
        private set
    var faceCount = 0
        private set

    init {
        for (face in 0 until maxFaces) {
            val vertex = face * 4
            val index = face * 6
            indices[index] = vertex.toShort()
            indices[index + 1] = (vertex + 1).toShort()
            indices[index + 2] = (vertex + 2).toShort()
            indices[index + 3] = vertex.toShort()
            indices[index + 4] = (vertex + 2).toShort()
            indices[index + 5] = (vertex + 3).toShort()
        }
    }

    fun reset() {
        vertexCount = 0
        faceCount = 0
    }

    fun addQuad(
        x0: Float, y0: Float, c0: Int,
        x1: Float, y1: Float, c1: Int,
        x2: Float, y2: Float, c2: Int,
        x3: Float, y3: Float, c3: Int
    ) {
        if (faceCount >= maxFaces) return
        val vertex = vertexCount
        val array = verts
        array[vertex * 2] = x0
        array[vertex * 2 + 1] = y0
        array[vertex * 2 + 2] = x1
        array[vertex * 2 + 3] = y1
        array[vertex * 2 + 4] = x2
        array[vertex * 2 + 5] = y2
        array[vertex * 2 + 6] = x3
        array[vertex * 2 + 7] = y3
        colors[vertex] = c0
        colors[vertex + 1] = c1
        colors[vertex + 2] = c2
        colors[vertex + 3] = c3
        vertexCount += 4
        faceCount++
    }
}

private fun withAlpha(rgb: Int, alpha: Int): Int {
    return (rgb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

private fun blendRgb(base: Int, overlay: Int, amount: Float): Int {
    val t = amount.coerceIn(0f, 1f)
    val inverse = 1f - t
    val r = (((base ushr 16) and 0xFF) * inverse +
        ((overlay ushr 16) and 0xFF) * t).toInt().coerceIn(0, 255)
    val g = (((base ushr 8) and 0xFF) * inverse +
        ((overlay ushr 8) and 0xFF) * t).toInt().coerceIn(0, 255)
    val b = ((base and 0xFF) * inverse + (overlay and 0xFF) * t)
        .toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private data class TerrainColumn(
    val x: Float,
    val depth: Float,
    val radius: Float,
    val random: Float,
    val phase: Float,
    val staticNoise: Float
)

private data class SonicMote(
    val x: Float,
    val y: Float,
    val speed: Float,
    val size: Float,
    val phase: Float,
    val alpha: Float,
    val drift: Float,
    val warm: Boolean
)

private fun Float.ifZero(fallback: Float): Float {
    return if (this > 0.001f) this else fallback
}

/** Smooth transition; edge0 may be greater than edge1 for an inverted ramp. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun buildColumns(gridSize: Int): List<TerrainColumn> {
    val yaw = -0.36f
    val cosYaw = cos(yaw)
    val sinYaw = sin(yaw)
    val random = Random(0x5EED + gridSize * 7919)
    val columns = ArrayList<TerrainColumn>(gridSize * gridSize)
    for (row in 0 until gridSize) {
        val gz = row / (gridSize - 1f) * 2f - 1f
        for (col in 0 until gridSize) {
            val gx = col / (gridSize - 1f) * 2f - 1f
            val radius = sqrt(gx * gx + gz * gz)
            if (radius > 1.02f) continue
            val rx = gx * cosYaw - gz * sinYaw
            val rz = gx * sinYaw + gz * cosYaw
            val depth = ((rz / 1.30f + 1f) * 0.5f).coerceIn(0f, 1f)
            columns.add(
                TerrainColumn(
                    x = rx / 1.30f,
                    depth = depth,
                    radius = radius,
                    random = random.nextFloat(),
                    phase = random.nextFloat() * 6.2831855f,
                    staticNoise = (
                        sin(gx * 7.1f + cos(gz * 5.3f) * 2.2f) * 0.5f + 0.5f
                        )
                )
            )
        }
    }
    columns.sortBy { it.depth }
    return columns
}

private fun buildMotes(count: Int): List<SonicMote> {
    val random = Random(0xC0FFEE + count * 131)
    return List(count) {
        SonicMote(
            x = 0.10f + random.nextFloat() * 0.80f,
            y = random.nextFloat(),
            speed = 0.008f + random.nextFloat() * 0.026f,
            size = 0.32f + random.nextFloat() * 1.05f,
            phase = random.nextFloat() * 6.2831855f,
            alpha = 0.10f + random.nextFloat() * 0.30f,
            drift = 0.002f + random.nextFloat() * 0.013f,
            warm = random.nextFloat() < 0.72f
        )
    }
}

private fun buildTopRgbPalette(): IntArray {
    return IntArray(64) { index ->
        val f = index / 63f
        when {
            f < 0.30f -> blendRgb(
                Color(0xFF120A08).toArgb(),
                Color(0xFF4A180B).toArgb(),
                f / 0.30f
            )
            f < 0.62f -> blendRgb(
                Color(0xFF4A180B).toArgb(),
                Color(0xFFC34410).toArgb(),
                (f - 0.30f) / 0.32f
            )
            f < 0.86f -> blendRgb(
                Color(0xFFC34410).toArgb(),
                Color(0xFFFF8A1E).toArgb(),
                (f - 0.62f) / 0.24f
            )
            f < 0.96f -> blendRgb(
                Color(0xFFFF8A1E).toArgb(),
                Color(0xFFFFC15A).toArgb(),
                (f - 0.86f) / 0.10f
            )
            else -> blendRgb(
                Color(0xFFFFC15A).toArgb(),
                Color(0xFFFFE8B0).toArgb(),
                (f - 0.96f) / 0.04f
            )
        }
    }
}

private fun buildFrontRgbPalette(): IntArray {
    return IntArray(64) { index ->
        val f = index / 63f
        when {
            f < 0.38f -> blendRgb(
                Color(0xFF07080D).toArgb(),
                Color(0xFF21100B).toArgb(),
                f / 0.38f
            )
            f < 0.72f -> blendRgb(
                Color(0xFF21100B).toArgb(),
                Color(0xFF5A1B0A).toArgb(),
                (f - 0.38f) / 0.34f
            )
            else -> blendRgb(
                Color(0xFF5A1B0A).toArgb(),
                Color(0xFF9A300D).toArgb(),
                (f - 0.72f) / 0.28f
            )
        }
    }
}

