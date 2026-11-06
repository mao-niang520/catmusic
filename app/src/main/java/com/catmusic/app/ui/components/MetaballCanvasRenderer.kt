package com.catmusic.app.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 软件 Canvas 版 MetaBall 流体。
 *
 * 它不依赖 RuntimeShader，所有 API 级别都能画，因此也是主界面原生背景
 * （QWEA0 液态玻璃会直接采样这个 View）的可靠底座。效果与 AGSL 版本共享
 * 同一套 28 秒运动相位：品红和深紫两团液体靠近时合并成一个轮廓，带发光
 * 边缘、中心留白和上下暗角。
 *
 * 每帧只生成两条 96 点的路径并做一次 Path 并集，没有逐像素循环，适合在
 * 老的移动 GPU/CPU 上保持流畅。
 */
internal class MetaballCanvasRenderer {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathA = Path()
    private val pathB = Path()
    private val pathUnion = Path()

    fun draw(canvas: Canvas, width: Int, height: Int, seconds: Float) {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val minDim = min(w, h)
        val half = minDim * 0.5f

        // Background: near-black with a very faint plum lower half.
        overlayPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.rgb(3, 1, 5),
                Color.rgb(13, 3, 24),
                Color.rgb(3, 1, 5)
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Same periodic motion as the AGSL painter.
        val phase = TAU * ((seconds / 28f) % 1f)
        val sinA = sin(phase)
        val cosA = cos(phase)
        val breath = 2f * sinA * cosA
        val driftA = floatArrayOf(cosA, sinA)
        // sin(phase + 2.15) / cos(phase + 2.15), pre-expanded.
        val driftB = floatArrayOf(
            sinA * COS_BLOB_PHASE + cosA * SIN_BLOB_PHASE,
            cosA * COS_BLOB_PHASE - sinA * SIN_BLOB_PHASE
        )

        val centerAX = w * 0.5f + (-SEPARATION + driftA[0] * DRIFT_X) * half
        val centerAY = h * 0.5f + (-0.10f + driftA[1] * DRIFT_Y) * half
        val centerBX = w * 0.5f + (SEPARATION + driftB[0] * DRIFT_X) * half
        val centerBY = h * 0.5f + (0.08f + driftB[1] * DRIFT_Y) * half

        val radiusA = RADIUS_A * half * (1f + breath * 0.018f)
        val radiusB = RADIUS_B * half * (1f - breath * 0.018f)

        val deltaX = centerBX - centerAX
        val deltaY = centerBY - centerAY
        val centerDistance = hypot(deltaX, deltaY).coerceAtLeast(0.001f)
        val dirX = deltaX / centerDistance
        val dirY = deltaY / centerDistance
        // 0 = separate, 1 = deeply interpenetrating. The large radii keep the
        // pair touching through most of the loop; this factor adds a directional
        // pull so the near edges stretch toward each other like a viscous neck.
        val overlap = (radiusA + radiusB - centerDistance)
            .coerceAtLeast(0f)
        val adhesion = (overlap / (0.55f * min(radiusA, radiusB)))
            .coerceIn(0f, 1f)

        // Soft coloured atmosphere around the liquid.
        drawAtmosphere(canvas, centerAX, centerAY, radiusA, MAGENTA_GLOW)
        drawAtmosphere(canvas, centerBX, centerBY, radiusB, PURPLE_GLOW)

        buildBlob(pathA, centerAX, centerAY, radiusA, phase, 0.7f,
            dirX, dirY, adhesion)
        buildBlob(pathB, centerBX, centerBY, radiusB, -phase, 2.4f,
            -dirX, -dirY, adhesion)

        // A single silhouette is what makes the two blobs feel like one liquid.
        pathUnion.set(pathA)
        pathUnion.op(pathB, Path.Op.UNION)

        val fluidGradient = LinearGradient(
            centerAX, centerAY, centerBX, centerBY,
            intArrayOf(
                Color.argb(44, 224, 11, 135),
                Color.argb(34, 144, 24, 164),
                Color.argb(48, 74, 13, 150)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = fluidGradient
        canvas.drawPath(pathUnion, fillPaint)

        // Layered strokes = soft glow without a BlurMaskFilter. This is
        // deliberately multiple cheap strokes rather than one expensive blur,
        // and it stays fully software-capture friendly for the glass backdrop.
        val rimGradient = LinearGradient(
            centerAX, h * 0.5f, centerBX, h * 0.5f,
            intArrayOf(
                Color.rgb(255, 47, 154),
                Color.rgb(199, 27, 145),
                Color.rgb(93, 32, 199)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        strokePaint.shader = rimGradient
        val widths = floatArrayOf(0.14f, 0.080f, 0.032f, 0.012f, 0.0045f)
        val alphas = intArrayOf(18, 32, 62, 132, 218)
        for (index in widths.indices) {
            strokePaint.strokeWidth = max(1f, minDim * widths[index])
            strokePaint.alpha = alphas[index]
            canvas.drawPath(pathUnion, strokePaint)
        }
        strokePaint.alpha = 255
        strokePaint.shader = null
        fillPaint.shader = null

        // A soft seam glow hides the union's hard inner cusp and makes the
        // touching area read as a viscous bridge. Its width follows adhesion,
        // so the meniscus brightens exactly when the two liquids press together.
        if (centerDistance < radiusA + radiusB) {
            val midX = (centerAX + centerBX) * 0.5f
            val midY = (centerAY + centerBY) * 0.5f
            val seamRadius = minDim * (0.22f + 0.16f * adhesion)
            overlayPaint.shader = RadialGradient(
                midX, midY, seamRadius,
                intArrayOf(
                    Color.argb((42 + 30 * adhesion).toInt(), 255, 52, 166),
                    Color.argb((16 + 18 * adhesion).toInt(), 120, 30, 190),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, overlayPaint)
        }

        // Center negative space for cover art, lyrics and white labels.
        // Slightly lighter than v1.34.1 so the larger liquid remains visible.
        overlayPaint.shader = RadialGradient(
            w * 0.5f, h * 0.52f, minDim * 0.64f,
            intArrayOf(
                Color.argb(122, 0, 0, 0),
                Color.argb(72, 0, 0, 0),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, overlayPaint)

        // Top and bottom scrims protect titles and the transport controls.
        overlayPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.argb(74, 0, 0, 0),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(92, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.18f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, overlayPaint)
        overlayPaint.shader = null
    }

    private fun drawAtmosphere(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        color: Int
    ) {
        val glowRadius = radius * 2.35f
        overlayPaint.shader = RadialGradient(
            centerX, centerY, glowRadius,
            intArrayOf(
                withAlpha(color, 38),
                withAlpha(color, 14),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, glowRadius, overlayPaint)
        overlayPaint.shader = null
    }

    /**
     * 96-point organic circle. All phase terms use integer multiples of the
     * loop phase so the shape returns exactly when the 28-second loop wraps.
     */
    private fun buildBlob(
        path: Path,
        centerX: Float,
        centerY: Float,
        radius: Float,
        phase: Float,
        seed: Float,
        dirX: Float,
        dirY: Float,
        adhesion: Float
    ) {
        path.reset()
        val steps = 96
        val pull = 1f + 0.20f * adhesion
        val squeeze = 1f - 0.055f * adhesion
        for (index in 0..steps) {
            val angle = TAU * index / steps
            val modulation = 1f +
                0.095f * sin(3f * angle + phase + seed) +
                0.052f * sin(5f * angle - 2f * phase + seed * 2f) +
                0.030f * sin(7f * angle + 3f * phase + seed * 3f)
            val r = radius * modulation
            val localX = cos(angle) * r
            val localY = sin(angle) * r
            // Stretch along the line to the other blob and squeeze the
            // perpendicular axis: the touching side reaches out first.
            val along = localX * dirX + localY * dirY
            val across = -localX * dirY + localY * dirX
            val stretchedAlong = along * pull
            val squeezedAcross = across * squeeze
            val x = centerX + stretchedAlong * dirX - squeezedAcross * dirY
            val y = centerY + stretchedAlong * dirY + squeezedAcross * dirX
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private companion object {
        const val TAU = (PI * 2.0).toFloat()
        const val SEPARATION = 0.36f
        const val DRIFT_X = 0.13f
        const val DRIFT_Y = 0.10f
        const val RADIUS_A = 0.50f
        const val RADIUS_B = 0.485f
        const val SIN_BLOB_PHASE = 0.83665564f
        const val COS_BLOB_PHASE = -0.54772926f

        val MAGENTA_GLOW = Color.rgb(224, 11, 135)
        val PURPLE_GLOW = Color.rgb(76, 9, 158)
    }
}
