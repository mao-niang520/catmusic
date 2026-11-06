package com.catmusic.app.ui.components

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.liquidglass.GlassMaterial

/**
 * The same DropdownMenu mechanics as Material3, but with a real liquid-glass
 * panel instead of an opaque menu surface. The Material surface itself is made
 * transparent while the popup window still handles anchoring, focus, outside
 * taps and menu animation.
 */
@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    revealBackdrop: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            surface = Color.Transparent,
            surfaceTint = Color.Transparent
        ),
        shapes = MaterialTheme.shapes.copy(extraSmall = shape)
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier
        ) {
            GlassMenuSurface(
                shape = shape,
                revealBackdrop = revealBackdrop,
                content = content
            )
        }
    }
}

@Composable
private fun GlassMenuSurface(
    shape: RoundedCornerShape,
    revealBackdrop: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    val popupView = LocalView.current
    val density = LocalDensity.current
    DisposableEffect(popupView, revealBackdrop) {
        if (revealBackdrop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            enableSystemBackdropBlur(popupView, with(density) { 22.dp.roundToPx() })
        }
        onDispose { }
    }

    // The popup window itself is transparent, so a lighter panel lets the real
    // album art / lyric stage beneath it show through. The player menu uses
    // this variant; menus over lists keep the darker, more legible glass.
    val panel = if (revealBackdrop) {
        Brush.verticalGradient(
            0f to Color(0xAC0C1218),
            0.55f to Color(0x940A1015),
            1f to Color(0x73070A0E)
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color(0xE61A202A),
                Color(0xD9090B12)
            )
        )
    }
    Box(
        modifier = Modifier
            .widthIn(min = 222.dp, max = 322.dp)
            .shadow(22.dp, shape, clip = false)
            .clip(shape)
            .background(panel)
            .border(
                if (revealBackdrop) 0.9.dp else 0.8.dp,
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = if (revealBackdrop) 0.42f else 0.30f),
                        Color.White.copy(alpha = 0.07f),
                        Color.White.copy(alpha = 0.15f)
                    )
                ),
                shape
            )
    ) {
        if (!revealBackdrop) {
            RealLiquidGlassLayer(
                modifier = Modifier.matchParentSize(),
                cornerRadius = 20.dp,
                refractionHeight = 68.dp,
                bevelWidth = 32.dp,
                dispersion = 0.34f,
                saturation = 160f,
                tintArgb = 0x08000000,
                material = GlassMaterial.CLEAR
            )
        }
        // A very light top sheen keeps the glass readable over bright artwork.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = if (revealBackdrop) 0.60f else 0.42f),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = if (revealBackdrop) 0.055f else 0.035f),
                            Color.Transparent,
                            Color.Black.copy(alpha = if (revealBackdrop) 0.07f else 0.10f)
                        )
                    )
                )
        )
        if (revealBackdrop) {
            // A diagonal glass reflection; it does not sample the scene, so it
            // stays cheap and leaves the artwork clearly visible.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.07f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.06f)
                            )
                        )
                    )
            )
        }
        Column(
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp),
            content = content
        )
    }
}

/**
 * Android 12+ can blur the window behind the popup. Material's popup window is
 * deliberately transparent; applying the platform blur gives the player menu
 * the frosted, see-through look of liquid glass without capturing a bitmap.
 * Older versions and OEMs that reject the flag keep the clear translucent
 * panel, so this is a progressive enhancement and never blocks the menu.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun enableSystemBackdropBlur(view: View, radiusPx: Int) {
    view.post {
        runCatching {
            val root = view.rootView
            val windowManager = view.context
                .getSystemService(WindowManager::class.java) ?: return@runCatching
            val params = root.layoutParams as? WindowManager.LayoutParams
                ?: return@runCatching
            // Never apply window blur to the Activity itself if a Compose
            // version ever exposes the main view instead of the popup root.
            if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_PANEL &&
                params.type != WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL
            ) {
                return@runCatching
            }
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.setBlurBehindRadius(radiusPx)
            windowManager.updateViewLayout(root, params)
        }
    }
}
