package com.catmusic.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.catmusic.app.data.AdaptiveMotion
import com.catmusic.app.data.HomeBackgroundStyle
import com.catmusic.app.data.LyricFrameRate
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Native backdrop for the genuine QWEA0 LiquidGlassView. It is the visible
 * background of the main tabs (and switches to a blurred cover on the player)
 * and the View that every glass samples.
 *
 * A smooth gradient is invisible through a lens, so the scene deliberately
 * carries fine stars, coloured bokeh, orbital arcs and grain. Those give the
 * SDF refraction and dispersion actual high-frequency content to bend.
 */
class GlassBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Palette(
        val top: Int,
        val middle: Int,
        val bottom: Int,
        val glowA: Int,
        val glowB: Int,
        val glowC: Int,
        val starCount: Int,
        val starDensity: Float
    )

    private data class Scene(
        val bitmap: Bitmap,
        val colors: IntArray,
        val motes: FloatArray,
        val hazeLive: Boolean = false
    )

    /** One broad colour mass in the hazy-fluid home scene. */
    private data class HazeField(
        val x: Float,
        val y: Float,
        val radius: Float,
        val stretch: Float,
        val rotation: Float,
        val color: Int,
        val alpha: Float,
        val driftX: Float,
        val driftY: Float,
        val phase: Float
    )

    private var style: HomeBackgroundStyle = HomeBackgroundStyle.METABALL
    private val metaballRenderer = MetaballCanvasRenderer()
    private var customFile: File? = null
    private var coverFile: File? = null
    private var albumColors: IntArray? = null

    @Volatile
    private var scene: Scene? = null
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowShaders: Array<Shader> = emptyArray()
    private var highlightShader: Shader? = null
    private var frameSeconds = 0f
    private var lastFrameNanos = 0L
    private var nextFrameNanos = 0L
    private var animating = false
    private val choreographer = Choreographer.getInstance()

    @Volatile
    private var motionRate = LyricFrameRate.ADAPTIVE

    @Volatile
    private var motionPlaying = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val targetFps = AdaptiveMotion.targetFps(motionRate, motionPlaying)
            val intervalNanos = if (targetFps > 0) {
                1_000_000_000L / targetFps
            } else {
                0L
            }
            if (intervalNanos > 0L) {
                if (nextFrameNanos == 0L) nextFrameNanos = frameTimeNanos
                // A fresh interaction can tighten the budget while an old
                // deadline is still far away.
                if (nextFrameNanos - frameTimeNanos > intervalNanos * 2) {
                    nextFrameNanos = frameTimeNanos + intervalNanos
                }
                if (frameTimeNanos < nextFrameNanos) {
                    val delayMs = ((nextFrameNanos - frameTimeNanos) / 1_000_000L)
                        .coerceAtLeast(1L)
                    choreographer.postFrameCallbackDelayed(this, delayMs)
                    return
                }
                nextFrameNanos += intervalNanos
                if (nextFrameNanos <= frameTimeNanos) {
                    nextFrameNanos = frameTimeNanos + intervalNanos
                }
            } else {
                nextFrameNanos = 0L
            }
            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos
            } else {
                val delta = (frameTimeNanos - lastFrameNanos) / 1_000_000_000.0
                frameSeconds += delta.toFloat().coerceIn(0f, 0.05f)
                lastFrameNanos = frameTimeNanos
            }
            invalidate()
            if (intervalNanos > 0L) {
                val waitMs = ((nextFrameNanos - frameTimeNanos) / 1_000_000L)
                    .coerceAtLeast(1L)
                choreographer.postFrameCallbackDelayed(this, waitMs)
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    /**
     * The hazy home scene deliberately uses a handful of very large, soft
     * colour fields instead of stars and bokeh. Their overlap creates the
     * saturated blue → magenta → crimson seam while a cream pool and a silky
     * specular fold anchor the lower half of the screen.
     */
    private val hazeFields = listOf(
        HazeField(0.16f, 0.24f, 0.78f, 0.86f, -17f, Color.rgb(42, 75, 255), 0.96f, 0.020f, 0.028f, 0.0f),
        HazeField(0.84f, 0.18f, 0.73f, 0.78f, 16f, Color.rgb(255, 11, 34), 0.93f, 0.018f, 0.024f, 2.1f),
        HazeField(0.33f, 0.68f, 0.58f, 0.74f, 8f, Color.rgb(46, 60, 255), 0.54f, 0.026f, 0.021f, 4.2f),
        HazeField(0.69f, 0.48f, 0.56f, 0.94f, -9f, Color.rgb(255, 61, 182), 0.55f, 0.022f, 0.027f, 1.4f),
        HazeField(0.91f, 0.74f, 0.46f, 0.72f, 26f, Color.rgb(255, 124, 219), 0.34f, 0.017f, 0.022f, 3.6f),
        HazeField(0.22f, 0.92f, 0.50f, 0.40f, -8f, Color.rgb(201, 217, 255), 0.22f, 0.018f, 0.016f, 0.9f),
        HazeField(0.70f, 0.86f, 0.56f, 0.30f, -11f, Color.rgb(255, 246, 233), 0.86f, 0.018f, 0.014f, 5.0f),
        HazeField(0.80f, 0.75f, 0.28f, 0.55f, 18f, Color.rgb(255, 249, 240), 0.55f, 0.012f, 0.018f, 0.6f),
        HazeField(0.84f, 0.72f, 0.18f, 0.52f, 16f, Color.WHITE, 0.50f, 0.010f, 0.012f, 1.7f)
    )

    /**
     * Applies the global refresh-rate policy. Called on the main thread by the
     * Compose shell; the actual scene keeps animating at the chosen cadence so
     * every glass lens still has live content to refract.
     */
    fun setMotionProfile(rate: LyricFrameRate, playing: Boolean) {
        if (motionRate == rate && motionPlaying == playing) return
        motionRate = rate
        motionPlaying = playing
        nextFrameNanos = 0L
        if (animating) {
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private val builder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "glass-backdrop").apply { isDaemon = true }
    }
    private val generation = AtomicInteger(0)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun setHome(style: HomeBackgroundStyle, customFile: File?) {
        if (this.style == style && this.customFile == customFile &&
            coverFile == null && albumColors == null
        ) return
        this.style = style
        this.customFile = customFile
        this.coverFile = null
        this.albumColors = null
        requestRebuild()
    }

    /**
     * True when the native scene is already configured for this preset. The
     * activity uses this to force a preset switch even while a track is
     * playing; previously the cover path bypassed setHome and could leave the
     * old METABALL style active forever.
     */
    fun matchesHome(style: HomeBackgroundStyle, customFile: File?): Boolean =
        this.style == style && this.customFile == customFile

    fun setCover(file: File?) {
        if (coverFile == file && albumColors == null) return
        coverFile = file
        albumColors = null
        requestRebuild()
    }

    /** Fallback album look for tracks without embedded/custom artwork. */
    fun setAlbumColors(colors: IntArray?) {
        if (coverFile == null && albumColors?.contentEquals(colors) == true) return
        coverFile = null
        albumColors = colors?.copyOf()
        requestRebuild()
    }

    fun clearCover() {
        if (coverFile == null && albumColors == null) return
        coverFile = null
        albumColors = null
        requestRebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) requestRebuild()
    }

    override fun onDraw(canvas: Canvas) {
        // The native Canvas liquid is the reliable base on every API level.
        // It is what QWEA0's capture records, so the glass refracts this
        // scene rather than a possibly-empty RuntimeShader draw.
        if (style == HomeBackgroundStyle.METABALL) {
            metaballRenderer.draw(canvas, width, height, frameSeconds)
            return
        }
        val current = scene
        if (current != null && !current.bitmap.isRecycled) {
            drawLiveScene(canvas, current)
        } else {
            canvas.drawColor(Color.rgb(7, 6, 12))
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimating()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isShown) startAnimating() else stopAnimating()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isShown) startAnimating() else stopAnimating()
    }

    private fun startAnimating() {
        if (animating || !isAttachedToWindow || !isShown || windowVisibility != VISIBLE) return
        animating = true
        lastFrameNanos = 0L
        nextFrameNanos = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopAnimating() {
        if (!animating) return
        animating = false
        lastFrameNanos = 0L
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimating()
        generation.incrementAndGet()
        builder.shutdownNow()
        scene = null
    }

    private fun requestRebuild() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val currentGeneration = generation.incrementAndGet()
        val sceneStyle = style
        val sceneCustom = customFile
        val sceneCover = coverFile
        val sceneAlbumColors = albumColors?.copyOf()
        builder.execute {
            val built = try {
                buildBackdrop(w, h, sceneStyle, sceneCustom, sceneCover, sceneAlbumColors)
            } catch (_: Throwable) {
                null
            }
            if (built == null) return@execute
            mainHandler.post {
                if (currentGeneration == generation.get() && isAttachedToWindow) {
                    installScene(built)
                } else {
                    built.bitmap.recycle()
                }
            }
        }
    }

    private fun installScene(newScene: Scene) {
        scene = newScene
        glowShaders = arrayOf(
            radialShader(newScene.colors.getOrElse(1) { newScene.colors.firstOrNull() ?: 0xFF9E82FF.toInt() }, 86),
            radialShader(newScene.colors.getOrElse(2) { newScene.colors.firstOrNull() ?: 0xFFB6A8FF.toInt() }, 58),
            radialShader(newScene.colors.getOrElse(0) { 0xFF6C5CE7.toInt() }, 52)
        )
        highlightShader = radialShader(Color.WHITE, 34)
        // The replaced bitmap may still be referenced by the render thread's
        // last display list, so it is intentionally left to GC rather than
        // recycled under a frame that is still in flight.
        invalidate()
    }

    private fun radialShader(color: Int, centerAlpha: Int): Shader = RadialGradient(
        0f,
        0f,
        1f,
        intArrayOf(
            withAlpha(color, centerAlpha),
            withAlpha(color, (centerAlpha * 0.34f).toInt().coerceAtLeast(2)),
            Color.TRANSPARENT
        ),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP
    )

    private fun buildBackdrop(
        width: Int,
        height: Int,
        style: HomeBackgroundStyle,
        customFile: File?,
        coverFile: File?,
        albumColors: IntArray?
    ): Scene {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val seed = style.key.hashCode() * 31 + (coverFile?.path?.hashCode() ?: 0) +
            (albumColors?.contentHashCode() ?: 0) + 2026
        val random = Random(seed)
        val palette = paletteFor(style)
        var albumColorScene = false
        var hazeLive = false
        var sceneColors: IntArray? = null

        when {
            coverFile != null && coverFile.exists() -> {
                sceneColors = drawCoverColorScene(canvas, width, height, coverFile)
                albumColorScene = true
            }

            albumColors != null && albumColors.isNotEmpty() -> {
                drawAlbumColorScene(canvas, width, height, albumColors)
                sceneColors = albumColors.copyOf()
                albumColorScene = true
            }

            style == HomeBackgroundStyle.HAZE -> {
                drawHazeScene(canvas, width, height, random)
                sceneColors = intArrayOf(palette.glowA, palette.glowB, palette.glowC)
                hazeLive = true
            }

            style == HomeBackgroundStyle.CUSTOM && customFile != null && customFile.exists() -> {
                drawImageScene(canvas, width, height, customFile, 420, 110, 0.10f)
                sceneColors = intArrayOf(palette.glowA, palette.glowB, palette.glowC)
            }

            else -> {
                drawGradientScene(canvas, width, height, palette)
                sceneColors = intArrayOf(palette.glowA, palette.glowB, palette.glowC)
            }
        }

        // Album backgrounds are colour only: no stars, bokeh or texture that
        // could read as leftover artwork. HAZE carries its own fine mist, and
        // the METABALL fallback should stay clean for the shader silhouette.
        if (!albumColorScene && !hazeLive &&
            style != HomeBackgroundStyle.HAZE &&
            style != HomeBackgroundStyle.METABALL
        ) {
            drawDetail(canvas, width, height, palette, random, style, false)
        }
        return Scene(
            bitmap = bitmap,
            colors = sceneColors ?: intArrayOf(palette.glowA, palette.glowB, palette.glowC),
            motes = buildMotes(seed),
            hazeLive = hazeLive
        )
    }

    /**
     * Tiny drifting motes baked into the sampled backdrop. They are what make
     * the glass read as live: a static colour field would look like a painted
     * panel even while the view is re-captured every frame.
     */
    private fun buildMotes(seed: Int): FloatArray {
        val random = Random(seed xor 0x5F3759DF)
        val count = 28
        val density = resources.displayMetrics.density
        val motes = FloatArray(count * 5)
        for (index in 0 until count) {
            motes[index * 5] = random.nextFloat()
            motes[index * 5 + 1] = random.nextFloat()
            motes[index * 5 + 2] = (1.0f + random.nextFloat() * 2.1f) * density
            motes[index * 5 + 3] = random.nextFloat() * 6.28318f
            motes[index * 5 + 4] = 0.0018f + random.nextFloat() * 0.0072f
        }
        return motes
    }

    /**
     * Real-time base for [com.example.liquidglass.LiquidGlassView].
     *
     * The static bitmap supplies the palette; every frame adds a slow zoom and
     * parallax drift, three wandering colour blooms, a travelling specular
     * highlight and rising motes. Because this View is the glass backdrop
     * source, all of that motion is refracted by the lens instead of sitting
     * on top of it as a flat picture.
     */
    private fun drawLiveScene(canvas: Canvas, current: Scene) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val t = frameSeconds
        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val zoom = 1.075f + 0.014f * sin(t * 0.032f)
        val driftX = width * 0.011f * sin(t * 0.027f + 0.6f)
        val driftY = height * 0.009f * cos(t * 0.023f + 1.3f)

        canvas.save()
        canvas.translate(centerX + driftX, centerY + driftY)
        canvas.scale(zoom, zoom)
        canvas.translate(-centerX, -centerY)
        canvas.drawBitmap(current.bitmap, 0f, 0f, drawPaint)
        canvas.restore()

        if (current.hazeLive) {
            drawLiveHaze(canvas, width, height, t)
            drawHazeScrims(canvas, width, height)
            return
        }

        if (glowShaders.size < 3) return
        drawBloom(
            canvas,
            width * (0.17f + 0.075f * sin(t * 0.051f + 0.4f)),
            height * (0.21f + 0.055f * cos(t * 0.043f + 1.1f)),
            width * (0.56f + 0.05f * sin(t * 0.034f)),
            glowShaders[0],
            0.72f + 0.24f * sin(t * 0.041f)
        )
        drawBloom(
            canvas,
            width * (0.84f + 0.065f * cos(t * 0.046f + 2.2f)),
            height * (0.33f + 0.06f * sin(t * 0.039f)),
            width * (0.46f + 0.06f * cos(t * 0.031f + 0.8f)),
            glowShaders[1],
            0.62f + 0.26f * sin(t * 0.047f + 1.7f)
        )
        drawBloom(
            canvas,
            width * (0.47f + 0.09f * sin(t * 0.037f + 3.3f)),
            height * (0.79f + 0.05f * cos(t * 0.045f + 0.3f)),
            width * (0.62f + 0.05f * sin(t * 0.029f + 2.4f)),
            glowShaders[2],
            0.58f + 0.24f * cos(t * 0.043f + 0.6f)
        )

        highlightShader?.let { shader ->
            drawBloom(
                canvas,
                width * (0.48f + 0.34f * sin(t * 0.033f + 1.9f)),
                height * (0.16f + 0.09f * cos(t * 0.028f + 0.5f)),
                width * (0.33f + 0.04f * sin(t * 0.026f + 1.2f)),
                shader,
                0.40f + 0.22f * sin(t * 0.038f + 2.8f)
            )
        }

        val motes = current.motes
        val accent = current.colors.getOrElse(2) { Color.WHITE }
        var index = 0
        while (index + 4 < motes.size) {
            val baseX = motes[index]
            val baseY = motes[index + 1]
            val radius = motes[index + 2]
            val phase = motes[index + 3]
            val speed = motes[index + 4]
            val x = baseX + 0.013f * sin(t * 0.034f + phase)
            val y = ((baseY - t * speed) % 1f + 1f) % 1f
            val twinkle = 0.55f + 0.45f * sin(t * 0.52f + phase * 2.7f)
            val alpha = (9f + 25f * twinkle).toInt().coerceIn(3, 38)
            motePaint.color = if (index % 15 == 0) {
                withAlpha(accent, alpha)
            } else {
                Color.argb(alpha, 255, 255, 255)
            }
            canvas.drawCircle(
                x * width,
                y * height,
                radius * (0.82f + 0.18f * sin(t * 0.39f + phase)),
                motePaint
            )
            index += 5
        }
    }

    private val hazeMagentaShader: Shader =
        radialShader(Color.rgb(255, 61, 182), 76)
    private val hazeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val hazeFoldPath = Path()
    private val hazeScrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hazeScrimWidth = 0f
    private var hazeScrimHeight = 0f
    private var hazeVerticalScrim: Shader? = null
    private var hazeEdgeScrim: Shader? = null

    /**
     * Static base for the hazy-fluid preset. It is built off the UI thread and
     * becomes the high-frequency layer sampled by the glass lens; the broad
     * fields themselves are then gently moved by [drawLiveHaze].
     */
    private fun drawHazeScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        random: Random
    ) {
        val w = width.toFloat()
        val h = height.toFloat()
        val span = max(w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.rgb(5, 11, 44), Color.rgb(11, 20, 72), Color.rgb(18, 7, 24)),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        hazeFields.forEach { field ->
            val radius = span * field.radius
            val centerAlpha = (field.alpha * 255f).toInt().coerceIn(0, 255)
            paint.shader = RadialGradient(
                0f, 0f, radius,
                intArrayOf(
                    withAlpha(field.color, centerAlpha),
                    withAlpha(field.color, (centerAlpha * 0.58f).toInt().coerceAtLeast(1)),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.save()
            canvas.translate(w * field.x, h * field.y)
            canvas.rotate(field.rotation)
            canvas.scale(1f, field.stretch)
            canvas.drawCircle(0f, 0f, radius, paint)
            canvas.restore()
        }
        paint.shader = null

        // A whisper of fixed mist gives the lens something to refract without
        // turning into a star field. Large, soft fields alone look painted.
        repeat((width * height / 9000).coerceIn(140, 420)) {
            paint.color = Color.argb(
                4 + random.nextInt(9),
                255,
                243,
                247
            )
            canvas.drawCircle(
                random.nextFloat() * w,
                random.nextFloat() * h,
                0.45f + random.nextFloat() * 1.05f,
                paint
            )
        }
    }

    private fun drawLiveHaze(canvas: Canvas, width: Float, height: Float, t: Float) {
        if (glowShaders.size < 3) return
        val twoPi = 6.28318f

        // The big colour masses drift slowly enough to feel liquid rather than
        // animated. The cream pool stays at the lower right and catches a
        // travelling sheen, just like the player's fluid cover scene.
        drawHazeBloom(canvas, width, height, t, hazeFields[0], glowShaders[0],
            0.34f + 0.08f * sin(t * twoPi / 58f))
        drawHazeBloom(canvas, width, height, t, hazeFields[1], glowShaders[1],
            0.30f + 0.07f * cos(t * twoPi / 61f))
        drawHazeBloom(canvas, width, height, t, hazeFields[3], hazeMagentaShader,
            0.17f + 0.05f * sin(t * twoPi / 63f + 1.1f))
        drawHazeBloom(canvas, width, height, t, hazeFields[6], glowShaders[2],
            0.22f + 0.07f * sin(t * twoPi / 59f + 0.9f))

        val sheen = 0.5f + 0.5f * sin(t * twoPi / 58f)
        drawPaint.shader = LinearGradient(
            width * (0.02f + 0.15f * sheen), height * 0.04f,
            width * (0.74f + 0.12f * sheen), height * 0.98f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(15, 255, 243, 237),
                Color.argb(9, 255, 220, 232),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.36f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, drawPaint)
        drawPaint.shader = null

        val foldY = height * (0.77f + 0.018f * sin(t * twoPi / 52f))
        val short = min(width, height)
        hazeFoldPath.reset()
        hazeFoldPath.moveTo(width * 0.22f, foldY + height * 0.045f)
        hazeFoldPath.cubicTo(
            width * 0.42f, foldY - height * 0.09f,
            width * 0.66f, foldY + height * 0.075f,
            width * 1.02f, foldY - height * 0.03f
        )
        hazeStrokePaint.strokeWidth = short * 0.089f
        hazeStrokePaint.shader = LinearGradient(
            width * 0.04f, foldY,
            width, foldY - height * 0.06f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.argb(20, 255, 245, 237),
                Color.argb(33, 255, 221, 233),
                Color.argb(26, 255, 248, 241),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.22f, 0.50f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(hazeFoldPath, hazeStrokePaint)
        hazeStrokePaint.shader = null
        hazeStrokePaint.strokeWidth = short * 0.036f
        hazeStrokePaint.color = Color.argb(23, 255, 255, 255)
        canvas.drawPath(hazeFoldPath, hazeStrokePaint)
        hazeStrokePaint.strokeWidth = short * 0.0056f
        hazeStrokePaint.color = Color.argb(38, 255, 255, 255)
        canvas.drawPath(hazeFoldPath, hazeStrokePaint)
    }

    private fun drawHazeBloom(
        canvas: Canvas,
        width: Float,
        height: Float,
        t: Float,
        field: HazeField,
        shader: Shader,
        alpha: Float
    ) {
        val phase = t * 6.28318f / 46f + field.phase
        val centerX = width * (field.x + sin(phase) * field.driftX)
        val centerY = height * (field.y + cos(phase * 0.87f) * field.driftY)
        val radius = max(width, height) * field.radius
        glowPaint.shader = shader
        glowPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(field.rotation + sin(phase) * 3f)
        canvas.scale(1f, field.stretch)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    private fun drawHazeScrims(canvas: Canvas, width: Float, height: Float) {
        if (width != hazeScrimWidth || height != hazeScrimHeight) {
            hazeScrimWidth = width
            hazeScrimHeight = height
            hazeVerticalScrim = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(
                    Color.argb(46, 0, 0, 0),
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(87, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.20f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            hazeEdgeScrim = if (width > height * 1.02f) {
                LinearGradient(
                    0f, 0f, width, 0f,
                    intArrayOf(
                        Color.argb(41, 0, 0, 0),
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.argb(15, 0, 0, 0)
                    ),
                    floatArrayOf(0f, 0.40f, 0.82f, 1f),
                    Shader.TileMode.CLAMP
                )
            } else {
                RadialGradient(
                    width * 0.50f, height * 0.44f, max(width, height) * 0.78f,
                    intArrayOf(Color.TRANSPARENT, Color.argb(46, 0, 0, 0)),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        hazeScrimPaint.shader = hazeVerticalScrim
        canvas.drawRect(0f, 0f, width, height, hazeScrimPaint)
        hazeScrimPaint.shader = hazeEdgeScrim
        canvas.drawRect(0f, 0f, width, height, hazeScrimPaint)
        hazeScrimPaint.shader = null
    }

    private fun drawBloom(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        shader: Shader,
        alpha: Float
    ) {
        glowPaint.shader = shader
        glowPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(radius, radius)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    private fun drawGradientScene(canvas: Canvas, width: Int, height: Int, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, width * 0.8f, height.toFloat(),
            intArrayOf(palette.top, palette.middle, palette.bottom),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val blobs = listOf(
            Triple(0.16f, 0.16f, palette.glowA),
            Triple(0.88f, 0.26f, palette.glowB),
            Triple(0.64f, 0.54f, palette.glowC),
            Triple(0.16f, 0.68f, palette.glowA),
            Triple(0.72f, 0.88f, palette.glowC)
        )
        blobs.forEachIndexed { index, (fx, fy, color) ->
            val cx = width * fx
            val cy = height * fy
            val radius = width * (0.34f + (index % 3) * 0.08f)
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    withAlpha(color, 46),
                    withAlpha(color, 15),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null
    }

    /**
     * Cover-driven backdrop: extract only a small palette from the artwork and
     * paint a soft colour field. The artwork itself is deliberately never
     * drawn — no sleeve text, faces or stripes leak into the UI background.
     */
    private fun drawCoverColorScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        file: File
    ): IntArray? {
        val colors = runCatching { loadCoverPalette(file) }.getOrNull()
        if (colors == null || colors.isEmpty()) {
            drawGradientScene(canvas, width, height, paletteFor(style))
            return null
        }
        drawAlbumColorScene(canvas, width, height, colors)
        return colors
    }

    private fun loadCoverPalette(file: File): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 72) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
        val tiny = Bitmap.createScaledBitmap(decoded, 32, 32, true)
        if (tiny !== decoded) decoded.recycle()
        val pixels = IntArray(32 * 32)
        tiny.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        tiny.recycle()
        return extractPalette(pixels)
    }

    /**
     * Reduce a 32×32 cover sample to three representative colours:
     * a dark base, the most colourful accent, and the lightest highlight.
     * No image pixels are retained for rendering.
     */
    private fun extractPalette(pixels: IntArray): IntArray? {
        val visible = pixels.filter { Color.alpha(it) >= 160 }
        if (visible.isEmpty()) return null

        fun luma(pixel: Int): Float {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }

        fun saturation(pixel: Int): Float {
            val max = maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            val min = minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            return if (max > 0) (max - min) / max.toFloat() else 0f
        }

        fun average(source: List<Int>): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            for (pixel in source) {
                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
            }
            val count = source.size.coerceAtLeast(1)
            return Color.rgb(
                (r / count).toInt().coerceIn(0, 255),
                (g / count).toInt().coerceIn(0, 255),
                (b / count).toInt().coerceIn(0, 255)
            )
        }

        fun enrich(color: Int, minValue: Float, maxValue: Float, satBoost: Float): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = (hsv[1] * satBoost).coerceIn(0f, 1f)
            hsv[2] = hsv[2].coerceIn(minValue, maxValue)
            return Color.HSVToColor(hsv)
        }

        val darkCount = (visible.size * 0.42f).toInt().coerceAtLeast(1)
        val base = average(visible.sortedBy { luma(it) }.take(darkCount))

        val accentCount = (visible.size * 0.12f).toInt().coerceAtLeast(12)
        val accent = average(
            visible.sortedByDescending {
                saturation(it) * 0.82f + luma(it) * 0.18f
            }.take(accentCount)
        )

        val highlight = average(
            visible.sortedByDescending {
                luma(it) * 0.72f + saturation(it) * 0.28f
            }.take(accentCount)
        )

        return intArrayOf(
            enrich(base, 0.16f, 0.34f, 1.06f),
            enrich(accent, 0.44f, 0.90f, 1.16f),
            enrich(highlight, 0.58f, 0.95f, 1.06f)
        )
    }

    /**
     * A soft, saturated colour field built only from a small palette.
     */
    private fun drawAlbumColorScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        colors: IntArray
    ) {
        val c1 = colors.getOrElse(0) { Color.rgb(92, 72, 168) }
        val c2 = colors.getOrElse(1) { Color.rgb(198, 122, 126) }
        val c3 = colors.getOrElse(2) { c1 }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, width * 0.55f, height.toFloat(),
            intArrayOf(
                darken(c1, 0.30f),
                darken(c2, 0.42f),
                Color.rgb(6, 6, 11)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val blooms = listOf(
            Triple(0.18f, 0.16f, c1),
            Triple(0.84f, 0.30f, c2),
            Triple(0.18f, 0.82f, c3),
            Triple(0.70f, 0.88f, c2)
        )
        blooms.forEachIndexed { index, (fx, fy, color) ->
            val cx = width * fx
            val cy = height * fy
            val radius = width * (0.50f + index * 0.04f)
            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    withAlpha(color, 118),
                    withAlpha(color, 44),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }
        paint.shader = null

        // A soft cross-screen wash keeps the palette present even in the
        // middle of the screen instead of only around the glow centres.
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(c2, 34),
                withAlpha(c3, 24),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.36f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                Color.argb(0x58, 5, 6, 10),
                Color.argb(0x6E, 5, 6, 10),
                Color.argb(0x9A, 5, 6, 10),
                Color.argb(0xC4, 5, 6, 10)
            ),
            floatArrayOf(0f, 0.36f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(
            width * 0.5f,
            height * 0.4f,
            width * 0.95f,
            intArrayOf(Color.TRANSPARENT, Color.argb(0x7C, 6, 6, 14)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun drawImageScene(
        canvas: Canvas,
        width: Int,
        height: Int,
        file: File,
        targetWidth: Int,
        blurWidth: Int,
        detail: Float
    ) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            drawGradientScene(canvas, width, height, paletteFor(style))
            return
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: run {
            drawGradientScene(canvas, width, height, paletteFor(style))
            return
        }
        val dominant = extractVibrantColor(decoded)
        // A tiny copy upscaled with bilinear filtering is a cheap, reliable
        // blur: big enough to keep the album's colour regions, too small for
        // its sleeve text or faces to compete with the UI.
        val blurHeight = (decoded.height.toFloat() * blurWidth / decoded.width)
            .toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createScaledBitmap(decoded, blurWidth, blurHeight, true)
        if (bitmap !== decoded) decoded.recycle()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Tint the whole canvas with the album's own dominant colour first.
        // A dark or black-and-white sleeve would otherwise collapse to a
        // near-black backdrop even though its cover has recognisable colour.
        paint.shader = LinearGradient(
            0f, 0f, width * 0.45f, height.toFloat(),
            intArrayOf(
                darken(dominant, 0.38f),
                darken(dominant, 0.58f),
                Color.rgb(5, 5, 9)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(
            width * 0.22f,
            height * 0.16f,
            width * 0.9f,
            intArrayOf(
                withAlpha(dominant, 92),
                withAlpha(dominant, 26),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = RadialGradient(
            width * 0.86f,
            height * 0.62f,
            width * 0.72f,
            intArrayOf(withAlpha(dominant, 54), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // Layer the heavily blurred cover over that colour field.
        val matrix = ColorMatrix().apply { setSaturation(1.34f) }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        paint.alpha = 198
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), paint)
        paint.colorFilter = null
        paint.alpha = 255
        bitmap.recycle()

        val scrim = Paint(Paint.ANTI_ALIAS_FLAG)
        scrim.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(
                Color.argb(0x84, 5, 6, 10),
                Color.argb(0x94, 5, 6, 10),
                Color.argb(0xB8, 5, 6, 10),
                Color.argb(0xD8, 5, 6, 10)
            ),
            floatArrayOf(0f, 0.36f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        if (detail > 0f) {
            scrim.shader = null
            scrim.color = Color.argb((detail * 76).toInt(), 7, 7, 12)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        }
    }

    /**
     * Average the most colourful, readable pixels of a tiny copy of the
     * cover. This gives a rich base tone for dark sleeves without needing a
     * full palette-extraction dependency.
     */
    private fun extractVibrantColor(source: Bitmap): Int {
        val sampleW = 24
        val sampleH = 24
        val tiny = Bitmap.createScaledBitmap(source, sampleW, sampleH, true)
        val pixels = IntArray(sampleW * sampleH)
        tiny.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
        if (tiny !== source) tiny.recycle()

        val candidates = ArrayList<Int>(pixels.size)
        var best = Color.rgb(94, 76, 154)
        var bestScore = -1f
        for (pixel in pixels) {
            if (Color.alpha(pixel) < 160) continue
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val saturation = if (max > 0) (max - min) / max.toFloat() else 0f
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            val score = saturation * 0.72f + luminance * 0.28f
            if (score > bestScore) {
                bestScore = score
                best = Color.rgb(r, g, b)
            }
            if (saturation > 0.22f && luminance > 0.10f) {
                candidates += pixel
            }
        }
        if (candidates.isEmpty()) return best

        candidates.sortByDescending { pixel ->
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val saturation = if (max > 0) (max - min) / max.toFloat() else 0f
            val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            saturation * 0.72f + luminance * 0.28f
        }
        val count = (candidates.size * 0.28f).toInt().coerceAtLeast(1)
        var r = 0
        var g = 0
        var b = 0
        for (index in 0 until count) {
            val pixel = candidates[index]
            r += Color.red(pixel)
            g += Color.green(pixel)
            b += Color.blue(pixel)
        }
        val color = Color.rgb(r / count, g / count, b / count)
        // Very dark sleeves still deserve a visible glow.
        val luminance = (0.299f * Color.red(color) + 0.587f * Color.green(color) +
            0.114f * Color.blue(color)) / 255f
        return if (luminance < 0.16f) {
            Color.rgb(
                (Color.red(color) * 1.45f).toInt().coerceIn(0, 255),
                (Color.green(color) * 1.45f).toInt().coerceIn(0, 255),
                (Color.blue(color) * 1.45f).toInt().coerceIn(0, 255)
            )
        } else {
            color
        }
    }

    private fun darken(color: Int, amount: Float): Int {
        val factor = (1f - amount).coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun drawDetail(
        canvas: Canvas,
        width: Int,
        height: Int,
        palette: Palette,
        random: Random,
        style: HomeBackgroundStyle,
        coverMode: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val minimal = style == HomeBackgroundStyle.MINIMAL && !coverMode
        val density = if (minimal) 0.28f else if (coverMode) 0.5f else 1f

        // Fine star field.
        val starTarget = (width * height / 2600) * palette.starDensity * density
        repeat(starTarget.toInt().coerceAtMost(2800)) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val radius = 0.55f + random.nextFloat() * 1.5f
            val alpha = ((40 + random.nextInt(150)) * palette.starDensity).toInt().coerceIn(8, 190)
            paint.color = if (random.nextFloat() < 0.22f) {
                Color.argb(alpha, 255, 210, 178)
            } else {
                Color.argb(alpha, 225, 232, 255)
            }
            canvas.drawCircle(x, y, radius, paint)
        }

        // Coloured micro bokeh and a few larger out-of-focus orbs. The larger
        // ones are what make the compression ring visible at the lens rim.
        repeat((54 * density).toInt()) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val radius = 2f + random.nextFloat() * 7f
            val paletteColors = intArrayOf(
                Color.rgb(255, 182, 156),
                Color.rgb(166, 143, 255),
                Color.rgb(133, 232, 206)
            )
            val c = paletteColors[random.nextInt(paletteColors.size)]
            paint.color = Color.argb(
                ((18 + random.nextInt(34)) * density).toInt().coerceIn(4, 52),
                Color.red(c), Color.green(c), Color.blue(c)
            )
            canvas.drawCircle(x, y, radius, paint)
        }
        repeat((26 * density).toInt()) {
            val x = random.nextFloat() * width
            val y = random.nextFloat() * height
            val r = 14f + random.nextFloat() * (if (coverMode) 46f else 34f)
            paint.shader = RadialGradient(
                x, y, r,
                intArrayOf(
                    Color.argb((18 * density).toInt().coerceAtLeast(4), 255, 236, 224),
                    Color.argb((8 * density).toInt().coerceAtLeast(2), 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, r, paint)
        }
        paint.shader = null

        // Sparse bright glints.
        repeat((16 * density).toInt()) {
            val gx = random.nextFloat() * width
            val gy = random.nextFloat() * height
            val r = 10f + random.nextFloat() * 22f
            paint.shader = RadialGradient(
                gx, gy, r,
                intArrayOf(Color.argb((30 * density).toInt().coerceAtLeast(6), 255, 255, 255), Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(gx, gy, r, paint)
        }
        paint.shader = null
    }

    private fun paletteFor(style: HomeBackgroundStyle): Palette = when (style) {
        HomeBackgroundStyle.METABALL -> Palette(
            top = Color.rgb(2, 1, 5),
            middle = Color.rgb(10, 3, 18),
            bottom = Color.rgb(3, 1, 6),
            glowA = Color.rgb(196, 10, 116),
            glowB = Color.rgb(76, 9, 158),
            glowC = Color.rgb(255, 59, 179),
            starCount = 0,
            starDensity = 0.35f
        )

        HomeBackgroundStyle.HAZE -> Palette(
            top = Color.rgb(5, 11, 44),
            middle = Color.rgb(11, 20, 72),
            bottom = Color.rgb(18, 7, 24),
            glowA = Color.rgb(42, 75, 255),
            glowB = Color.rgb(255, 11, 34),
            glowC = Color.rgb(255, 246, 233),
            starCount = 0,
            starDensity = 0.8f
        )

        HomeBackgroundStyle.LIQUID -> Palette(
            top = Color.rgb(8, 7, 17),
            middle = Color.rgb(12, 10, 22),
            bottom = Color.rgb(7, 6, 12),
            glowA = Color.rgb(112, 82, 210),
            glowB = Color.rgb(195, 108, 122),
            glowC = Color.rgb(98, 194, 184),
            starCount = 52,
            starDensity = 1.0f
        )

        HomeBackgroundStyle.STARFIELD -> Palette(
            top = Color.rgb(2, 4, 12),
            middle = Color.rgb(5, 10, 23),
            bottom = Color.rgb(2, 3, 9),
            glowA = Color.rgb(59, 91, 255),
            glowB = Color.rgb(139, 76, 255),
            glowC = Color.rgb(45, 215, 232),
            starCount = 150,
            starDensity = 1.35f
        )

        HomeBackgroundStyle.DUSK -> Palette(
            top = Color.rgb(22, 11, 18),
            middle = Color.rgb(32, 16, 23),
            bottom = Color.rgb(8, 6, 10),
            glowA = Color.rgb(255, 163, 132),
            glowB = Color.rgb(230, 128, 162),
            glowC = Color.rgb(125, 91, 180),
            starCount = 24,
            starDensity = 0.7f
        )

        HomeBackgroundStyle.MINT -> Palette(
            top = Color.rgb(3, 16, 15),
            middle = Color.rgb(6, 20, 18),
            bottom = Color.rgb(4, 8, 7),
            glowA = Color.rgb(126, 227, 195),
            glowB = Color.rgb(72, 201, 194),
            glowC = Color.rgb(145, 120, 210),
            starCount = 28,
            starDensity = 0.75f
        )

        HomeBackgroundStyle.MINIMAL -> Palette(
            top = Color.rgb(9, 9, 11),
            middle = Color.rgb(6, 6, 7),
            bottom = Color.rgb(4, 4, 5),
            glowA = Color.rgb(255, 255, 255),
            glowB = Color.rgb(140, 120, 190),
            glowC = Color.rgb(0, 0, 0),
            starCount = 0,
            starDensity = 0.25f
        )

        else -> Palette(
            top = Color.rgb(8, 7, 17),
            middle = Color.rgb(12, 10, 22),
            bottom = Color.rgb(7, 6, 12),
            glowA = Color.rgb(112, 82, 210),
            glowB = Color.rgb(195, 108, 122),
            glowC = Color.rgb(98, 194, 184),
            starCount = 52,
            starDensity = 1.0f
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
