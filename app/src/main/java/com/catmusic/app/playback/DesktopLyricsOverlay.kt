package com.catmusic.app.playback

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.catmusic.app.data.DesktopLyricsConfig
import com.catmusic.app.data.DesktopLyricsStyle
import com.catmusic.app.util.LyricLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * QQ-Music-style floating lyrics.
 *
 * The idle window contains only the lyric and its translation: no permanent
 * card, header or unlock button. Tapping the lyric reveals a compact toolbar;
 * it auto-hides again so the overlay stays quiet over games. When locked, the
 * lyric window itself is not touchable. A transparent tap zone follows only
 * the text; tapping it reveals a temporary "解锁" pill. The lock therefore no
 * longer leaves a visible button hanging beside the lyrics.
 */
@SuppressLint("ClickableViewAccessibility")
class DesktopLyricsOverlay(
    private val context: Context,
    initialConfig: DesktopLyricsConfig,
    private val onConfigChanged: (DesktopLyricsConfig) -> Unit,
    private val onCloseRequested: () -> Unit
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var config = initialConfig.normalized()
    private var mainWindow: LinearLayout? = null
    private var mainParams: WindowManager.LayoutParams? = null
    private var header: LinearLayout? = null
    private var lockButton: TextView? = null
    private var styleButton: TextView? = null
    private var colorButton: TextView? = null
    private var sizeDownButton: TextView? = null
    private var sizeUpButton: TextView? = null
    private var closeButton: TextView? = null
    private var paletteRow: LinearLayout? = null
    private var paletteOpen = false
    private var controlsVisible = false
    private var lyricView: OutlineTextView? = null
    private var translationView: OutlineTextView? = null
    private var swatches: List<TextView> = emptyList()
    private var lastLine: String? = null
    private var lastTranslation: String? = null

    private var hotspotView: View? = null
    private var hotspotParams: WindowManager.LayoutParams? = null
    private var hotspotUpdateScheduled = false
    private var unlockHint: TextView? = null
    private var unlockHintParams: WindowManager.LayoutParams? = null

    private val hideChromeRunnable = Runnable { hideControls() }
    private val hideUnlockRunnable = Runnable { removeUnlockHint() }

    val isShowing: Boolean get() = mainWindow != null

    fun show(line: LyricLine) {
        if (!Settings.canDrawOverlays(context)) return
        ensureWindow()
        updateContent(line)
        if (config.locked) scheduleHotspotUpdate()
    }

    /** Called when the appearance changes in the app or in this window. */
    fun applyConfig(newConfig: DesktopLyricsConfig) {
        val normalized = newConfig.normalized()
        if (normalized == config) return
        val lockChanged = normalized.locked != config.locked
        config = normalized
        if (mainWindow == null) return
        applyAppearance()
        if (lockChanged) {
            applyLockState()
        } else {
            updateWindowFlags()
            if (config.locked) scheduleHotspotUpdate()
            if (controlsVisible && !config.locked) scheduleHideControls()
        }
    }

    /** Re-fit the window when the game rotates between portrait and landscape. */
    fun onScreenChanged() {
        val manager = windowManager ?: return
        val window = mainWindow ?: return
        val params = mainParams ?: return
        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels - dp(32f)).coerceAtLeast(dp(240f))
        params.width = width
        params.x = params.x.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(
            0,
            (metrics.heightPixels - window.height).coerceAtLeast(0)
        )
        runCatching { manager.updateViewLayout(window, params) }
        if (config.locked) scheduleHotspotUpdate() else positionUnlockHint()
    }

    fun hide() {
        mainHandler.removeCallbacks(hideChromeRunnable)
        mainHandler.removeCallbacks(hideUnlockRunnable)
        hotspotUpdateScheduled = false
        removeUnlockHint()
        removeHotspot()
        paletteOpen = false
        controlsVisible = false
        val view = mainWindow ?: return
        mainWindow = null
        mainParams = null
        header = null
        lockButton = null
        styleButton = null
        colorButton = null
        sizeDownButton = null
        sizeUpButton = null
        closeButton = null
        paletteRow = null
        lyricView = null
        translationView = null
        swatches = emptyList()
        lastLine = null
        lastTranslation = null
        runCatching { windowManager?.removeView(view) }
    }

    private fun setConfigLocally(next: DesktopLyricsConfig) {
        val normalized = next.normalized()
        if (normalized == config) return
        val lockChanged = normalized.locked != config.locked
        config = normalized
        applyAppearance()
        if (lockChanged) applyLockState()
        onConfigChanged(normalized)
        if (controlsVisible && !config.locked) scheduleHideControls()
    }

    private fun setLocked(locked: Boolean) {
        setConfigLocally(config.copy(locked = locked))
    }

    private fun ensureWindow() {
        if (mainWindow != null) return
        hotspotUpdateScheduled = false
        val manager = windowManager ?: return
        if (!Settings.canDrawOverlays(context)) return

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val lock = controlPill("锁定") { setLocked(true) }
        val style = controlPill("样式") {
            setConfigLocally(config.copy(style = config.nextStyle()))
        }
        val color = controlPill("调色") {
            paletteOpen = !paletteOpen
            applyAppearance()
            scheduleHideControls()
        }
        val smaller = controlPill("A-") {
            setConfigLocally(
                config.copy(
                    fontSizeSp = (config.fontSizeSp -
                        DesktopLyricsConfig.FONT_SIZE_STEP_SP)
                        .coerceAtLeast(DesktopLyricsConfig.MIN_FONT_SIZE_SP)
                )
            )
        }
        val larger = controlPill("A+") {
            setConfigLocally(
                config.copy(
                    fontSizeSp = (config.fontSizeSp +
                        DesktopLyricsConfig.FONT_SIZE_STEP_SP)
                        .coerceAtMost(DesktopLyricsConfig.MAX_FONT_SIZE_SP)
                )
            )
        }
        val close = controlPill("×") { onCloseRequested() }
        close.contentDescription = "关闭桌面歌词"

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(lock, pillParams())
            addView(style, pillParams())
            addView(color, pillParams())
            addView(smaller, pillParams())
            addView(larger, pillParams())
            addView(close, pillParams())
        }
        root.addView(
            controls,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val palette = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val paletteViews = ArrayList<TextView>()
        DesktopLyricsConfig.palette.forEach { entry ->
            val swatch = TextView(context).apply {
                contentDescription = entry.title
                setOnClickListener {
                    setConfigLocally(config.copy(colorArgb = entry.argb))
                    paletteOpen = false
                    applyAppearance()
                    scheduleHideControls()
                }
            }
            palette.addView(swatch, swatchParams())
            paletteViews.add(swatch)
        }
        root.addView(
            palette,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5f) }
        )

        val lyric = OutlineTextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, 1.05f)
            setPadding(dp(4f), dp(8f), dp(4f), 0)
        }
        root.addView(
            lyric,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val translation = OutlineTextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(0f, 1.10f)
            setPadding(dp(4f), dp(4f), dp(4f), 0)
            visibility = View.GONE
        }
        root.addView(
            translation,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val touchListener = LyricTouchListener()
        lyric.setOnTouchListener(touchListener)
        translation.setOnTouchListener(touchListener)

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels - dp(32f)).coerceAtLeast(dp(240f))
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags() or if (config.locked) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                0
            },
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(16f))
                .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = prefs.getInt(KEY_Y, (metrics.heightPixels * 0.12f).toInt())
                .coerceIn(0, (metrics.heightPixels - dp(120f)).coerceAtLeast(0))
        }

        val added = runCatching {
            manager.addView(root, params)
            true
        }.getOrElse { false }
        if (!added) return

        mainWindow = root
        mainParams = params
        header = controls
        lockButton = lock
        styleButton = style
        colorButton = color
        sizeDownButton = smaller
        sizeUpButton = larger
        closeButton = close
        paletteRow = palette
        lyricView = lyric
        translationView = translation
        swatches = paletteViews
        applyAppearance()
        applyLockState()
    }

    private fun updateContent(line: LyricLine) {
        val main = line.text.trim()
        if (main.isEmpty()) {
            hide()
            return
        }
        val translated = line.translation?.trim().orEmpty()
        val lyric = lyricView ?: return
        if (lastLine != main) {
            lyric.text = main
            lastLine = main
        }
        val translation = translationView ?: return
        if (lastTranslation != translated) {
            translation.text = translated
            translation.visibility = if (translated.isEmpty()) View.GONE else View.VISIBLE
            lastTranslation = translated
        }
        if (config.locked) scheduleHotspotUpdate()
    }

    private fun showControls() {
        if (config.locked || mainWindow == null) return
        controlsVisible = true
        paletteOpen = false
        applyAppearance()
        scheduleHideControls()
    }

    private fun hideControls() {
        mainHandler.removeCallbacks(hideChromeRunnable)
        if (!controlsVisible && !paletteOpen) return
        controlsVisible = false
        paletteOpen = false
        applyAppearance()
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideChromeRunnable)
        if (controlsVisible && !config.locked) {
            mainHandler.postDelayed(hideChromeRunnable, 6_000L)
        }
    }

    private fun applyAppearance() {
        val root = mainWindow ?: return
        val color = config.colorArgb
        val locked = config.locked
        val showChrome = controlsVisible && !locked
        val showPalette = showChrome && paletteOpen
        val card = config.style == DesktopLyricsStyle.CARD && showChrome

        header?.visibility = if (showChrome) View.VISIBLE else View.GONE
        paletteRow?.visibility = if (showPalette) View.VISIBLE else View.GONE
        styleButton?.text = "样式·${config.style.title}"
        colorButton?.text = if (paletteOpen) "收起" else "调色"

        root.setPadding(
            if (card) dp(17f) else dp(8f),
            if (card) dp(12f) else dp(4f),
            if (card) dp(17f) else dp(8f),
            if (card) dp(14f) else dp(8f)
        )
        root.clipToOutline = card
        root.background = if (card) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22f).toFloat()
                setColor(Color.argb(238, 9, 10, 15))
                setStroke(dp(1f), withAlpha(color, 112))
            }
        } else {
            null
        }

        styleControls()
        styleSwatches()

        val lyric = lyricView ?: return
        lyric.setTextSize(TypedValue.COMPLEX_UNIT_SP, config.fontSizeSp)
        lyric.setTextColor(color)
        lyric.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        lyric.setShadowLayer(
            when (config.style) {
                DesktopLyricsStyle.NEON -> dp(11f).toFloat()
                DesktopLyricsStyle.CLEAR -> dp(7f).toFloat()
                DesktopLyricsStyle.CARD -> dp(5f).toFloat()
                DesktopLyricsStyle.OUTLINE -> dp(1.5f).toFloat()
            },
            0f,
            if (config.style == DesktopLyricsStyle.CLEAR ||
                config.style == DesktopLyricsStyle.CARD
            ) {
                dp(2f).toFloat()
            } else {
                0f
            },
            if (config.style == DesktopLyricsStyle.NEON) {
                withAlpha(color, 185)
            } else {
                Color.argb(215, 0, 0, 0)
            }
        )
        lyric.strokeWidthPx = when (config.style) {
            DesktopLyricsStyle.OUTLINE -> dp(2.2f).toFloat()
            DesktopLyricsStyle.NEON -> dp(1.1f).toFloat()
            else -> 0f
        }
        lyric.strokeColor = Color.argb(235, 0, 0, 0)
        lyric.invalidate()

        val translation = translationView ?: return
        translation.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (config.fontSizeSp * 0.58f).coerceAtLeast(11f)
        )
        translation.setTextColor(withAlpha(color, 214))
        translation.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
        translation.strokeWidthPx = when (config.style) {
            DesktopLyricsStyle.OUTLINE -> dp(1.2f).toFloat()
            DesktopLyricsStyle.NEON -> dp(0.7f).toFloat()
            else -> 0f
        }
        translation.strokeColor = Color.argb(220, 0, 0, 0)
        translation.setShadowLayer(
            dp(5f).toFloat(),
            0f,
            if (card) dp(1f).toFloat() else dp(2f).toFloat(),
            Color.argb(200, 0, 0, 0)
        )
        translation.invalidate()

        if (locked) scheduleHotspotUpdate()
    }

    private fun styleControls() {
        val color = config.colorArgb
        listOf(
            lockButton,
            styleButton,
            colorButton,
            sizeDownButton,
            sizeUpButton,
            closeButton
        ).filterNotNull().forEach { button ->
            button.background = pillBackground(color)
            button.setTextColor(withAlpha(color, 235))
        }
        sizeDownButton?.alpha = if (
            config.fontSizeSp <= DesktopLyricsConfig.MIN_FONT_SIZE_SP + 0.01f
        ) {
            0.45f
        } else {
            1f
        }
        sizeUpButton?.alpha = if (
            config.fontSizeSp >= DesktopLyricsConfig.MAX_FONT_SIZE_SP - 0.01f
        ) {
            0.45f
        } else {
            1f
        }
    }

    private fun styleSwatches() {
        swatches.forEachIndexed { index, swatch ->
            val entry = DesktopLyricsConfig.palette[index]
            val selected = entry.argb == config.colorArgb
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(entry.argb)
                setStroke(
                    if (selected) dp(2.5f) else dp(1f),
                    if (selected) Color.WHITE else Color.argb(68, 255, 255, 255)
                )
            }
            swatch.scaleX = if (selected) 1.12f else 1f
            swatch.scaleY = if (selected) 1.12f else 1f
        }
    }

    private fun applyLockState() {
        if (config.locked) {
            controlsVisible = false
            paletteOpen = false
            mainHandler.removeCallbacks(hideChromeRunnable)
        }
        updateWindowFlags()
        applyAppearance()
        if (config.locked) {
            scheduleHotspotUpdate()
        } else {
            removeHotspot()
            removeUnlockHint()
        }
    }

    private fun updateWindowFlags() {
        val root = mainWindow ?: return
        val params = mainParams ?: return
        params.flags = baseFlags() or if (config.locked) {
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            0
        }
        runCatching { windowManager?.updateViewLayout(root, params) }
    }

    private fun scheduleHotspotUpdate() {
        if (!config.locked || mainWindow == null || hotspotUpdateScheduled) return
        hotspotUpdateScheduled = true
        mainWindow?.post {
            hotspotUpdateScheduled = false
            updateLockHotspot()
        }
    }

    private fun updateLockHotspot() {
        if (!config.locked || mainWindow == null) {
            removeHotspot()
            removeUnlockHint()
            return
        }
        val bounds = visibleTextBounds() ?: return
        val manager = windowManager ?: return
        val params = mainParams ?: return
        val metrics = context.resources.displayMetrics
        val width = (bounds.right - bounds.left).roundToInt().coerceAtLeast(dp(44f))
        val height = (bounds.bottom - bounds.top).roundToInt().coerceAtLeast(dp(28f))
        val x = (params.x + bounds.left.roundToInt())
            .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        val y = (params.y + bounds.top.roundToInt())
            .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))

        val existing = hotspotView
        if (existing == null) {
            val view = View(context).apply {
                isClickable = true
                contentDescription = "点击歌词显示解锁"
                setOnClickListener {
                    if (config.locked) showUnlockHint()
                }
            }
            val layout = WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                baseFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x
                this.y = y
            }
            val added = runCatching {
                manager.addView(view, layout)
                true
            }.getOrElse { false }
            if (added) {
                hotspotView = view
                hotspotParams = layout
            }
        } else {
            val layout = hotspotParams ?: return
            if (layout.width == width &&
                layout.height == height &&
                layout.x == x &&
                layout.y == y
            ) {
                if (unlockHint != null) positionUnlockHint()
                return
            }
            layout.width = width
            layout.height = height
            layout.x = x
            layout.y = y
            runCatching { manager.updateViewLayout(existing, layout) }
        }
        if (unlockHint != null) positionUnlockHint()
    }

    private fun visibleTextBounds(): TextBounds? {
        val lyric = lyricView ?: return null
        val lyricLayout = lyric.layout ?: return null
        if (lyric.width <= 0 || lyric.height <= 0) return null

        val location = IntArray(2)
        lyric.getLocationInWindow(location)
        val lyricMaxWidth = (lyric.width - lyric.totalPaddingLeft - lyric.totalPaddingRight)
            .coerceAtLeast(1)
            .toFloat()
        val lyricTextWidth = if (lyricLayout.lineCount > 0) {
            (0 until lyricLayout.lineCount)
                .maxOf { lyricLayout.getLineWidth(it) }
                .coerceIn(1f, lyricMaxWidth)
        } else {
            lyricMaxWidth
        }
        val lyricLeft = location[0] + (lyric.width - lyricTextWidth) / 2f
        val lyricTop = location[1] + lyric.paddingTop.toFloat()
        val lyricBottom = lyricTop + lyricLayout.height

        var left = lyricLeft
        var right = lyricLeft + lyricTextWidth
        var top = lyricTop
        var bottom = lyricBottom

        val translation = translationView
        if (translation != null &&
            translation.visibility == View.VISIBLE &&
            translation.layout != null
        ) {
            val translationLayout = translation.layout ?: return null
            val translationLocation = IntArray(2)
            translation.getLocationInWindow(translationLocation)
            val translationMaxWidth = (
                translation.width -
                    translation.totalPaddingLeft -
                    translation.totalPaddingRight
                ).coerceAtLeast(1).toFloat()
            val translationTextWidth = if (translationLayout.lineCount > 0) {
                (0 until translationLayout.lineCount)
                    .maxOf { translationLayout.getLineWidth(it) }
                    .coerceIn(1f, translationMaxWidth)
            } else {
                translationMaxWidth
            }
            val translationLeft =
                translationLocation[0] + (translation.width - translationTextWidth) / 2f
            val translationTop =
                translationLocation[1] + translation.paddingTop.toFloat()
            left = min(left, translationLeft)
            right = max(right, translationLeft + translationTextWidth)
            top = min(top, translationTop)
            bottom = max(bottom, translationTop + translationLayout.height)
        }

        return TextBounds(
            left = left - dp(12f),
            top = top - dp(7f),
            right = right + dp(12f),
            bottom = bottom + dp(10f)
        )
    }

    private fun showUnlockHint() {
        if (!config.locked) return
        val hotspot = hotspotParams ?: return
        removeUnlockHint()

        val hint = TextView(context).apply {
            text = "解锁"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            contentDescription = "解锁桌面歌词"
            setOnClickListener {
                removeUnlockHint()
                setLocked(false)
                showControls()
            }
        }
        styleUnlockHint(hint)

        val metrics = context.resources.displayMetrics
        val hintWidth = dp(48f)
        val hintHeight = dp(25f)
        val x = (hotspot.x + hotspot.width - hintWidth)
            .coerceIn(0, (metrics.widthPixels - hintWidth).coerceAtLeast(0))
        val above = hotspot.y - hintHeight - dp(6f)
        val y = if (above >= 0) {
            above
        } else {
            (hotspot.y + hotspot.height + dp(6f))
                .coerceIn(0, (metrics.heightPixels - hintHeight).coerceAtLeast(0))
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        val added = runCatching {
            windowManager?.addView(hint, layout)
            true
        }.getOrElse { false }
        if (!added) return
        unlockHint = hint
        unlockHintParams = layout
        hint.post { positionUnlockHint() }
        mainHandler.removeCallbacks(hideUnlockRunnable)
        mainHandler.postDelayed(hideUnlockRunnable, 4_000L)
    }

    private fun styleUnlockHint(hint: TextView) {
        val color = config.colorArgb
        hint.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(50f).toFloat()
            setColor(Color.argb(170, 9, 10, 15))
            setStroke(dp(1f), withAlpha(color, 150))
        }
        hint.setTextColor(withAlpha(color, 230))
    }

    private fun positionUnlockHint() {
        val hint = unlockHint ?: return
        val layout = unlockHintParams ?: return
        val hotspot = hotspotParams ?: return
        val metrics = context.resources.displayMetrics
        val width = hint.width.takeIf { it > 0 } ?: dp(48f)
        val height = hint.height.takeIf { it > 0 } ?: dp(25f)
        val x = (hotspot.x + hotspot.width - width)
            .coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
        val above = hotspot.y - height - dp(6f)
        val y = if (above >= 0) {
            above
        } else {
            (hotspot.y + hotspot.height + dp(6f))
                .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
        }
        if (layout.x == x && layout.y == y) return
        layout.x = x
        layout.y = y
        runCatching { windowManager?.updateViewLayout(hint, layout) }
    }

    private fun removeUnlockHint() {
        mainHandler.removeCallbacks(hideUnlockRunnable)
        val hint = unlockHint ?: return
        unlockHint = null
        unlockHintParams = null
        runCatching { windowManager?.removeView(hint) }
    }

    private fun removeHotspot() {
        val hotspot = hotspotView ?: return
        hotspotView = null
        hotspotParams = null
        runCatching { windowManager?.removeView(hotspot) }
    }

    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun controlPill(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minHeight = dp(26f)
            minWidth = dp(28f)
            setPadding(dp(7f), dp(3f), dp(7f), dp(3f))
            setOnClickListener { onClick() }
        }

    private fun pillParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4f) }

    private fun swatchParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dp(24f), dp(24f)).apply {
            marginStart = dp(8f)
            marginEnd = dp(2f)
        }

    private fun pillBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(50f).toFloat()
            setColor(Color.argb(150, 12, 13, 18))
            setStroke(dp(1f), withAlpha(color, 92))
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private data class TextBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private inner class LyricTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val layout = mainParams ?: return false
            val manager = windowManager ?: return false
            val metrics = context.resources.displayMetrics
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mainHandler.removeCallbacks(hideChromeRunnable)
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = layout.x
                    startY = layout.y
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (abs(dx) > dp(3f).toFloat() || abs(dy) > dp(3f).toFloat()) {
                        moved = true
                    }
                    if (moved) {
                        val maxX = (metrics.widthPixels - layout.width).coerceAtLeast(0)
                        val maxY = (metrics.heightPixels -
                            (mainWindow?.height ?: dp(100f))).coerceAtLeast(0)
                        layout.x = (startX + dx).roundToInt().coerceIn(0, maxX)
                        layout.y = (startY + dy).roundToInt().coerceIn(0, maxY)
                        runCatching { manager.updateViewLayout(mainWindow, layout) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit()
                            .putInt(KEY_X, layout.x)
                            .putInt(KEY_Y, layout.y)
                            .apply()
                        if (controlsVisible) scheduleHideControls()
                    } else {
                        if (controlsVisible) hideControls() else showControls()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (controlsVisible) scheduleHideControls()
                    return true
                }
            }
            return false
        }
    }

    companion object {
        private const val PREFS_NAME = "catmusic_desktop_lyrics"
        private const val KEY_X = "window_x"
        private const val KEY_Y = "window_y"
    }
}

/**
 * TextView that can paint a rounded outline behind its normal fill. Android
 * has no built-in text outline, and a shadow alone looks too soft for the
 * QQ-style "描边" look.
 */
private class OutlineTextView(context: Context) : TextView(context) {
    var strokeWidthPx: Float = 0f
    var strokeColor: Int = Color.BLACK

    override fun onDraw(canvas: Canvas) {
        val width = strokeWidthPx
        if (width > 0f) {
            val textPaint = paint
            val oldStyle = textPaint.style
            val oldColor = textPaint.color
            val oldStrokeWidth = textPaint.strokeWidth
            val oldJoin = textPaint.strokeJoin
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = width
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.color = strokeColor
            super.onDraw(canvas)
            textPaint.style = oldStyle
            textPaint.color = oldColor
            textPaint.strokeWidth = oldStrokeWidth
            textPaint.strokeJoin = oldJoin
        }
        super.onDraw(canvas)
    }
}
