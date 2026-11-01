package com.catmusic.app

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.platform.ComposeView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.catmusic.app.ui.CatMusicApp
import com.catmusic.app.ui.components.GlassBackdrop
import com.catmusic.app.ui.components.GlassBackdropView
import com.catmusic.app.ui.theme.CatMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        hideSystemStatusBar()

        // QWEA0's LiquidGlassView samples a real View, not a composable. The
        // backdrop lives in a sibling branch behind Compose so the lens can
        // read it without re-entering a Compose RenderNode. The app content is
        // drawn above it; only the glass sees this layer.
        val backdrop = GlassBackdropView(this)
        GlassBackdrop.install(backdrop)

        val content = ComposeView(this).apply {
            setContent {
                CatMusicTheme {
                    CatMusicApp()
                }
            }
        }

        val root = FrameLayout(this)
        root.addView(
            backdrop,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemStatusBar()
    }

    /**
     * CatMusic owns a dark, edge-to-edge canvas, so the system clock, battery
     * and signal icons are visual noise. Hide the status bar globally, like a
     * game; a swipe from the top still reveals it transiently.
     */
    private fun hideSystemStatusBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
