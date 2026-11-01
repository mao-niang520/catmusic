package com.catmusic.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.util.Base64
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.catmusic.app.BuildConfig
import com.catmusic.app.data.AudioVisualLevel
import com.catmusic.app.data.LyricFrameRate
import com.catmusic.app.data.Track
import com.catmusic.app.playback.AudioEnergy
import com.catmusic.app.playback.AudioReactiveSignal
import com.catmusic.app.ui.components.PlayerUiSnapshot
import com.catmusic.app.util.LrcParser
import com.catmusic.app.util.ParsedLyrics
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SoundField"
private const val JS_BRIDGE_NAME = "CatMusicBridge"
private const val MISSING_LYRICS_JSON = "{\"hasTiming\":false,\"lines\":[],\"missing\":true}"
private const val LOADING_LYRICS_JSON = "{\"loading\":true}"

/**
 * The full-screen CatMusic sound field stage.
 *
 * The page is CatMusic's own HTML/CSS/WebGL implementation. It owns the lyric
 * UI so the two WebGL effects can sit below every control without native
 * SurfaceView z-order workarounds. The bridge carries decoded bands, artwork
 * and transport state in, and only simple commands out. The page never loads
 * remote content and the app still has no network or microphone permission.
 */
// The bridge methods are all annotated with @JavascriptInterface (lint cannot
// see through the private Kotlin bridge class) and the WebView only ever loads
// local, first-party assets. Keeping JavaScript on is required for the WebGL
// sound field stage.
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun SoundFieldScreen(
    track: Track?,
    player: PlayerUiSnapshot,
    durationMs: Long,
    audioVisualLevel: AudioVisualLevel,
    frameRate: LyricFrameRate,
    coverAccent: Color,
    onCloseLyrics: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    lyricOffsetMs: Long,
    onLyricOffsetChange: (Long) -> Unit,
    onRequestLyrics: (Track) -> Unit,
    onToggleAudioReactive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPlayer = rememberUpdatedState(player)
    val appContext = context.applicationContext

    var pageReady by remember { mutableStateOf(false) }
    val holder = remember { SoundFieldWebHolder() }
    val jsBridge = remember { SoundFieldBridge(Handler(Looper.getMainLooper())) }

    fun evaluateJavascript(script: String) {
        if (!pageReady) return
        val webView = holder.webView ?: return
        runCatching { webView.evaluateJavascript(script, null) }
            .onFailure { Log.w(TAG, "evaluateJavascript failed", it) }
    }

    val sensorHandler = remember { Handler(Looper.getMainLooper()) }
    val sensorManager = remember {
        appContext.getSystemService(SensorManager::class.java)
    }
    val rotationSensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }
    var gyroEnabled by remember { mutableStateOf(false) }
    val gyroEnabledState = rememberUpdatedState(gyroEnabled)
    val orientationListener = remember {
        object : SensorEventListener {
            private var lastSentAt = 0L
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSentAt < 33L) return
                lastSentAt = now
                if (event.values.size < 3) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val pitch = orientation[1].toDouble()
                val roll = orientation[2].toDouble()
                sensorHandler.post {
                    if (!gyroEnabledState.value) return@post
                    val webView = holder.webView ?: return@post
                    runCatching {
                        webView.evaluateJavascript(
                            "window.catmusic&&window.catmusic.orientation({p:$pitch,r:$roll})",
                            null
                        )
                    }.onFailure { Log.w(TAG, "orientation update failed", it) }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
    }

    // Local assets should always load. If the WebView never reaches the page's
    // ready handshake, fall back to the native lyric stage rather than leaving
    // the player on a blank boot screen.
    LaunchedEffect(pageReady) {
        if (pageReady) return@LaunchedEffect
        delay(12_000L)
        if (!pageReady) {
            Toast.makeText(
                appContext,
                "音浪加载失败，已切换到普通歌词页",
                Toast.LENGTH_LONG
            ).show()
            onToggleAudioReactive()
        }
    }

    val insets = WindowInsets.safeDrawing
    val safeTop = insets.getTop(density) / density.density
    val safeBottom = insets.getBottom(density) / density.density
    val safeLeft = insets.getLeft(density, layoutDirection) / density.density
    val safeRight = insets.getRight(density, layoutDirection) / density.density

    // Let the page dismiss its menu or calibration panel before leaving the
    // lyric stage, so Android back behaves like the in-page Escape handler.
    BackHandler {
        val webView = holder.webView
        if (!pageReady || webView == null) {
            onCloseLyrics()
            return@BackHandler
        }
        runCatching {
            webView.evaluateJavascript(
                "(function(){try{return !!(window.catmusic&&window.catmusic.back&&window.catmusic.back());}" +
                    "catch(e){return false;}})()"
            ) { result ->
                if (result != "true") onCloseLyrics()
            }
        }.onFailure { onCloseLyrics() }
    }

    SideEffect {
        jsBridge.onReady = { pageReady = true }
        jsBridge.onClose = onCloseLyrics
        jsBridge.onToggle = onToggle
        jsBridge.onSeek = { positionMs -> onSeek(positionMs.coerceAtLeast(0L)) }
        jsBridge.onOffset = { offsetMs -> onLyricOffsetChange(offsetMs) }
        jsBridge.onImportLyrics = { track?.let(onRequestLyrics) }
        jsBridge.onToggleAudioMode = onToggleAudioReactive
        jsBridge.onGyroToggle = { requested ->
            val available = rotationSensor != null
            gyroEnabled = requested && available
            val json = JSONObject()
                .put("enabled", gyroEnabled)
                .put("available", available)
                .put("reset", true)
                .toString()
            evaluateJavascript("window.catmusic&&window.catmusic.gyroState($json)")
        }
    }

    var lyricsState by remember(track?.id, track?.lyricsFile) {
        mutableStateOf<SoundFieldState>(
            if (track?.lyricsFile.isNullOrBlank()) {
                SoundFieldState.Ready(MISSING_LYRICS_JSON)
            } else {
                SoundFieldState.Loading
            }
        )
    }

    LaunchedEffect(track?.id, track?.lyricsFile) {
        val key = track?.lyricsFile
        if (key.isNullOrBlank()) {
            lyricsState = SoundFieldState.Ready(MISSING_LYRICS_JSON)
            return@LaunchedEffect
        }
        lyricsState = SoundFieldState.Loading
        val json = withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "lyrics/$key")
            if (!file.exists()) return@withContext MISSING_LYRICS_JSON
            runCatching {
                val parsed = LrcParser.parse(file.readText())
                if (parsed.lines.isEmpty()) MISSING_LYRICS_JSON else lyricsJson(parsed)
            }.getOrElse {
                Log.w(TAG, "Unable to read lyrics for the sound field stage", it)
                MISSING_LYRICS_JSON
            }
        }
        lyricsState = SoundFieldState.Ready(json)
    }

    LaunchedEffect(pageReady, lyricsState) {
        if (!pageReady) return@LaunchedEffect
        val json = when (val state = lyricsState) {
            SoundFieldState.Loading -> LOADING_LYRICS_JSON
            is SoundFieldState.Ready -> state.json
        }
        evaluateJavascript("window.catmusic&&window.catmusic.lyrics($json)")
    }

    LaunchedEffect(pageReady, track?.id, track?.title, track?.artist) {
        if (!pageReady) return@LaunchedEffect
        val current = track ?: return@LaunchedEffect
        val json = JSONObject()
            .put("title", current.title)
            .put("artist", current.artist)
            .toString()
        evaluateJavascript("window.catmusic&&window.catmusic.track($json)")
    }

    // The accent is sampled from the current local cover (or its deterministic
    // fallback when the song has no artwork). Both effects blend toward a
    // palette derived from that hue, so the stage belongs to the cover.
    LaunchedEffect(pageReady, coverAccent) {
        if (!pageReady) return@LaunchedEffect
        val rgb = coverAccent.toArgb() and 0xFFFFFF
        val hex = String.format(Locale.US, "#%06X", rgb)
        val json = JSONObject().put("accent", hex).toString()
        evaluateJavascript("window.catmusic&&window.catmusic.palette($json)")
    }

    // The particle sphere paints itself from the album cover, so send a small
    // compressed preview over the same local bridge. No network is involved.
    LaunchedEffect(pageReady, track?.id, track?.artworkFile) {
        if (!pageReady) return@LaunchedEffect
        val dataUrl = withContext(Dispatchers.IO) {
            artworkPreviewDataUrl(context, track?.artworkFile)
        }
        val json = JSONObject().put("data", dataUrl ?: JSONObject.NULL).toString()
        evaluateJavascript("window.catmusic&&window.catmusic.artwork($json)")
    }

    LaunchedEffect(
        pageReady,
        track?.id,
        player.positionMs,
        player.isPlaying,
        durationMs,
        lyricOffsetMs
    ) {
        if (!pageReady) return@LaunchedEffect
        val json = JSONObject()
            .put("position", player.positionMs.coerceAtLeast(0L))
            .put("duration", durationMs.coerceAtLeast(0L))
            .put("playing", player.isPlaying)
            .put("offset", lyricOffsetMs)
            .toString()
        evaluateJavascript("window.catmusic&&window.catmusic.playback($json)")
        // Safety net in addition to the 20 Hz audio loop: playback updates
        // also carry the latest energy, so the field never stays frozen if
        // a dedicated audio tick is delayed.
        val energyJson = audioJson(freshAudioEnergy(), player.isPlaying)
        evaluateJavascript("window.catmusic&&window.catmusic.audio($energyJson)")
    }

    LaunchedEffect(pageReady, safeTop, safeBottom, safeLeft, safeRight) {
        if (!pageReady) return@LaunchedEffect
        val json = JSONObject()
            .put("top", safeTop.toDouble())
            .put("bottom", safeBottom.toDouble())
            .put("left", safeLeft.toDouble())
            .put("right", safeRight.toDouble())
            .toString()
        evaluateJavascript("window.catmusic&&window.catmusic.safeArea($json)")
    }

    LaunchedEffect(pageReady, lifecycleOwner) {
        if (!pageReady) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val playing = currentPlayer.value.isPlaying
                val energy = freshAudioEnergy()
                val json = audioJson(energy, playing)
                evaluateJavascript("window.catmusic&&window.catmusic.audio($json)")
                delay(50L)
            }
        }
    }

    LaunchedEffect(pageReady, gyroEnabled, rotationSensor, lifecycleOwner) {
        if (!pageReady || !gyroEnabled) return@LaunchedEffect
        val manager = sensorManager ?: return@LaunchedEffect
        val sensor = rotationSensor ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            manager.registerListener(
                orientationListener,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            // Re-anchor the neutral pose after a resume or a fresh enable.
            evaluateJavascript(
                "window.catmusic&&window.catmusic.gyroState(" +
                    "{enabled:true,available:true,reset:true})"
            )
            try {
                awaitCancellation()
            } finally {
                manager.unregisterListener(orientationListener)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF05060A))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(AndroidColor.BLACK)
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false

                    @Suppress("DEPRECATION")
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        // The page and its script/style live side by side in
                        // assets. Enabling same-scheme file reads keeps that
                        // loading path reliable across WebView versions; the
                        // content is first-party and network loads are blocked.
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = false
                        allowContentAccess = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        mediaPlaybackRequiresUserGesture = false
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setGeolocationEnabled(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        blockNetworkLoads = true
                        textZoom = 100
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            @Suppress("DEPRECATION")
                            forceDark = WebSettings.FORCE_DARK_OFF
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView?,
                            url: String?,
                            favicon: Bitmap?
                        ) {
                            super.onPageStarted(view, url, favicon)
                            pageReady = false
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = true
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            consoleMessage?.let {
                                Log.d(TAG, "${it.message()} (${it.sourceId()}:${it.lineNumber()})")
                            }
                            return true
                        }
                    }

                    addJavascriptInterface(jsBridge, JS_BRIDGE_NAME)
                    holder.webView = this
                    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                    loadUrl(
                        "file:///android_asset/soundfield/index.html" +
                            "?quality=${audioVisualLevel.key}&fps=${frameRate.key}"
                    )
                }
            },
            update = { },
            onRelease = { webView ->
                holder.webView = null
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.destroy()
            }
        )
    }
}

private sealed interface SoundFieldState {
    data object Loading : SoundFieldState
    data class Ready(val json: String) : SoundFieldState
}

private class SoundFieldWebHolder {
    var webView: WebView? = null
}

class SoundFieldBridge(
    private val mainHandler: Handler
) {
    var onReady: () -> Unit = {}
    var onClose: () -> Unit = {}
    var onToggle: () -> Unit = {}
    var onSeek: (Long) -> Unit = {}
    var onOffset: (Long) -> Unit = {}
    var onImportLyrics: () -> Unit = {}
    var onToggleAudioMode: () -> Unit = {}
    var onGyroToggle: (Boolean) -> Unit = {}

    @JavascriptInterface
    fun ready() {
        mainHandler.post { onReady() }
    }

    @JavascriptInterface
    fun close() {
        mainHandler.post { onClose() }
    }

    @JavascriptInterface
    fun toggle() {
        mainHandler.post { onToggle() }
    }

    @JavascriptInterface
    fun seek(positionMs: Double) {
        val value = positionMs.toLong().coerceAtLeast(0L)
        mainHandler.post { onSeek(value) }
    }

    @JavascriptInterface
    fun setOffset(offsetMs: Double) {
        val value = offsetMs.toLong().coerceIn(-5_000L, 5_000L)
        mainHandler.post { onOffset(value) }
    }

    @JavascriptInterface
    fun importLyrics() {
        mainHandler.post { onImportLyrics() }
    }

    @JavascriptInterface
    fun toggleAudioMode() {
        mainHandler.post { onToggleAudioMode() }
    }

    @JavascriptInterface
    fun setGyroEnabled(enabled: Boolean) {
        mainHandler.post { onGyroToggle(enabled) }
    }
}

private fun lyricsJson(parsed: ParsedLyrics): String {
    val lines = JSONArray()
    parsed.lines.forEach { line ->
        val item = JSONObject()
            .put("t", line.timeMs)
            .put("text", line.text)
        if (!line.translation.isNullOrBlank()) item.put("tr", line.translation)
        lines.put(item)
    }
    return JSONObject()
        .put("hasTiming", parsed.hasTiming)
        .put("lines", lines)
        .toString()
}

/**
 * The PCM sink publishes at ~25 Hz. Treat the snapshot as live only while it
 * is fresh: the tap has no buffers to process while paused, and PlaybackService
 * also resets it. This keeps both effects responsive even if a playback-state
 * UI snapshot arrives a frame late, without leaving a frozen last frame.
 */
private fun freshAudioEnergy(): AudioEnergy {
    val energy = AudioReactiveSignal.current
    val age = if (AudioReactiveSignal.updatedAt > 0L) {
        SystemClock.elapsedRealtime() - AudioReactiveSignal.updatedAt
    } else {
        Long.MAX_VALUE
    }
    return if (energy.active && age in 0L..450L) energy else AudioEnergy()
}

/**
 * A tiny JPEG data URL is enough for the cover-coloured field and avoids
 * exposing arbitrary app files to the WebView. The cover never touches the
 * network here: the page runs with blockNetworkLoads.
 */
private fun artworkPreviewDataUrl(context: android.content.Context, name: String?): String? {
    val artworkName = name ?: return null
    val file = File(context.filesDir, "artwork/$artworkName")
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 96 ||
            bounds.outHeight / (sample * 2) >= 96
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@runCatching null
        val preview = Bitmap.createScaledBitmap(decoded, 96, 96, true)
        if (preview !== decoded) decoded.recycle()
        val output = ByteArrayOutputStream()
        preview.compress(Bitmap.CompressFormat.JPEG, 82, output)
        preview.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(
            output.toByteArray(), Base64.NO_WRAP
        )
    }.getOrNull()
}

private fun safeLevel(value: Float): Double {
    val finite = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    return String.format(Locale.US, "%.3f", finite).toDouble()
}

private fun audioJson(energy: AudioEnergy, playing: Boolean): String {
    val bands = JSONArray()
    for (index in 0 until AudioEnergy.BAND_COUNT) {
        bands.put(safeLevel(energy.bands.getOrElse(index) { 0f }))
    }
    return JSONObject()
        .put("bands", bands)
        .put("beat", safeLevel(energy.beat))
        .put("level", safeLevel(energy.level))
        .put("playing", playing)
        .toString()
}
