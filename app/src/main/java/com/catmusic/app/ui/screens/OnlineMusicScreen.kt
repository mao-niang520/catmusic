package com.catmusic.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.catmusic.app.BuildConfig
import com.catmusic.app.online.MusicBridge

/**
 * Full MusicDL online experience, running from bundled local assets inside
 * CatMusic. Search, resolve and playback happen in the local page; the native
 * side supplies the network bridge and the Android DownloadManager.
 */
@Composable
fun OnlineMusicScreen(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
    onOnlinePlaybackStarted: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var bridge by remember { mutableStateOf<MusicBridge?>(null) }

    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bridge?.shutdown()
            bridge = null
            webView?.let { view ->
                try {
                    view.removeJavascriptInterface("Native")
                    view.stopLoading()
                    view.loadUrl("about:blank")
                    view.destroy()
                } catch (t: Throwable) {
                    Log.w("CatMusicOnline", "Unable to destroy the online WebView", t)
                }
            }
            webView = null
        }
    }

    BackHandler {
        val current = webView
        if (current == null) {
            onExit()
            return@BackHandler
        }
        current.evaluateJavascript(
            "(function(){try{return !!(window.AppNative&&window.AppNative.handleBack());}" +
                "catch(e){return false;}})()"
        ) { result ->
            if (result != "true") onExit()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createOnlineWebView(
                    context = ctx,
                    onCreated = { created, createdBridge ->
                        webView = created
                        bridge = createdBridge
                    },
                    onOnlinePlaybackStarted = onOnlinePlaybackStarted
                )
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createOnlineWebView(
    context: Context,
    onCreated: (WebView, MusicBridge) -> Unit,
    onOnlinePlaybackStarted: () -> Unit
): WebView {
    val webView = WebView(context).apply {
        setBackgroundColor(Color.rgb(11, 12, 15))
        setOverScrollMode(View.OVER_SCROLL_NEVER)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
    }

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        loadsImagesAutomatically = true
        builtInZoomControls = false
        displayZoomControls = false
        setSupportZoom(false)
        textZoom = 100
        cacheMode = WebSettings.LOAD_DEFAULT
        defaultTextEncodingName = "utf-8"
    }

    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }

    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }

    val bridge = MusicBridge(context, webView) {
        onOnlinePlaybackStarted()
    }
    webView.addJavascriptInterface(bridge, "Native")

    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(
                "CatMusicOnline",
                "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
            )
            return true
        }
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = openExternally(context, request.url?.toString().orEmpty())

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            openExternally(context, url)
    }

    webView.loadUrl("file:///android_asset/musicdl/www/index.html")
    onCreated(webView, bridge)
    return webView
}

private fun openExternally(context: Context, url: String): Boolean {
    // The bundled page handles its own relative assets; any web navigation is
    // an external link from the About panel and belongs in a real browser.
    if (url.startsWith("http://") || url.startsWith("https://")) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Log.w("CatMusicOnline", "No activity can open $url", it)
        }
        return true
    }
    return !url.startsWith("file://")
}
