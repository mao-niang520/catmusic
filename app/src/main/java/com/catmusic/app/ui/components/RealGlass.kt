package com.catmusic.app.ui.components

import android.content.Context
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import java.lang.ref.WeakReference

/**
 * The optical layer is decoration only. A stock View would still take part in
 * touch dispatch above the Compose UI in some AndroidView placements and eat
 * taps meant for a card, the mini player or the tab bar; this subclass refuses
 * every touch without changing how LiquidGlassView renders.
 */
private class PassiveLiquidGlassView(context: Context) : LiquidGlassView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
}

/**
 * The native view that QWEA0's LiquidGlassView samples. It lives in a sibling
 * branch from the Compose tree (behind it), so BackdropCapture never has to
 * re-enter a Compose RenderNode while the glass is recording.
 */
object GlassBackdrop {
    private var ref: WeakReference<View>? = null

    fun install(view: View) {
        ref = WeakReference(view)
    }

    fun current(): View? = ref?.get()
}

/**
 * Genuine QWEA0/Liquid-Glass-Android lens layer.
 *
 * [LiquidGlassView] is a FrameLayout that samples a real backdrop view with a
 * single-pass AGSL SDF lens: edge compression, per-channel dispersion and a
 * gravity-driven specular. We keep it empty and draw the Compose content above
 * it, so labels and icons stay crisp while the optical glass runs underneath.
 *
 * [cornerRadiusPx] is the measured shape radius; when negative only the Dp
 * value is used. Null lens distances leave the library's tuned defaults, which
 * adapt to small controls through adaptiveLensScale.
 */
@Composable
fun RealLiquidGlassLayer(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 999.dp,
    cornerRadiusPx: Float = -1f,
    refractionHeight: Dp? = null,
    bevelWidth: Dp? = null,
    dispersion: Float = 0.10f,
    tintArgb: Int = 0,
    // LiquidGlassView's saturation unit is percent: 100 = unchanged.
    saturation: Float = 150f,
    sensorSpecular: Boolean = true,
    material: GlassMaterial = GlassMaterial.CLEAR,
    adaptiveLensScale: Boolean = true
) {
    val density = LocalDensity.current
    val radiusPx = if (cornerRadiusPx >= 0f) cornerRadiusPx else with(density) { cornerRadius.toPx() }
    val refractionPx = refractionHeight?.let { with(density) { it.toPx() } }
    val bevelPx = bevelWidth?.let { with(density) { it.toPx() } }

    AndroidView(
        factory = { ctx ->
            PassiveLiquidGlassView(ctx).apply {
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                enablePressEffect = false
                collectFrameStats = false
                enableDynamicBackground = true
                enableSensorHighlight = sensorSpecular
                enableAdaptiveTint = false
                enableShadow = false
                this.adaptiveLensScale = adaptiveLensScale
                this.material = material
                this.cornerRadius = radiusPx
                if (refractionPx != null) this.refractionHeight = refractionPx
                if (bevelPx != null) this.bevelWidth = bevelPx
                this.refractionFalloff = 1.82f
                this.dispersionStrength = dispersion
                this.saturation = saturation
                this.glassTint = tintArgb
                post {
                    val nativeBackdrop = GlassBackdrop.current()
                    if (nativeBackdrop != null && nativeBackdrop !== this) {
                        backdropSource = nativeBackdrop
                    }
                }
            }
        },
        update = { glass ->
            glass.cornerRadius = radiusPx
            if (refractionPx != null) glass.refractionHeight = refractionPx
            if (bevelPx != null) glass.bevelWidth = bevelPx
            glass.dispersionStrength = dispersion
            glass.saturation = saturation
            glass.glassTint = tintArgb
            glass.material = material
            glass.enableSensorHighlight = sensorSpecular
        },
        modifier = modifier
    )
}
