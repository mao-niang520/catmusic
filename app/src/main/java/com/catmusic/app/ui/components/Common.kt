package com.catmusic.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.liquidglass.GlassMaterial
import coil.request.ImageRequest
import com.catmusic.app.R
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatLime
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.ui.theme.GlassBorderBright
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@Composable
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    pressScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressScale else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 650f),
        label = "pressScale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * The genuine QWEA0/Liquid-Glass-Android surface. A native [LiquidGlassView]
 * sits beneath the Compose content and samples the shared GlassBackdropView
 * with the single-pass AGSL SDF lens (refraction, dispersion, sensor-lit rim).
 * Only a thin accent rim is drawn in Compose above it; content stays crisp.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    borderColors: List<Color> = listOf(GlassBorderBright, GlassBorder, GlassBorder),
    sensorSpecular: Boolean = true,
    tint: Color = Color.Transparent,
    content: @Composable BoxScope.() -> Unit
) {
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val cornerPx = remember(surfaceSize, shape, density) {
        if (surfaceSize.width == 0 || surfaceSize.height == 0) {
            0f
        } else {
            shape.topStart.toPx(
                Size(surfaceSize.width.toFloat(), surfaceSize.height.toFloat()),
                density
            )
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .onSizeChanged { surfaceSize = it }
    ) {
        RealLiquidGlassLayer(
            modifier = Modifier.matchParentSize(),
            cornerRadiusPx = cornerPx,
            refractionHeight = 82.dp,
            bevelWidth = 38.dp,
            dispersion = 0.32f,
            saturation = 155f,
            tintArgb = tint.toArgb(),
            sensorSpecular = sensorSpecular,
            // Always the clear iOS-style material. The regular variant adds an
            // adaptive frost that hides the live backdrop on dark wallpapers.
            material = GlassMaterial.CLEAR
        )
        if (borderColors.isNotEmpty()) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        width = 0.7.dp,
                        brush = Brush.linearGradient(
                            borderColors.map { it.copy(alpha = it.alpha * 0.45f) }
                        ),
                        shape = shape
                    )
            )
        }
        Box(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    borderColors: List<Color> = listOf(GlassBorderBright, GlassBorder, GlassBorder),
    content: @Composable BoxScope.() -> Unit
) {
    LiquidGlassSurface(
        modifier = modifier,
        shape = shape,
        contentPadding = contentPadding,
        borderColors = borderColors,
        content = content
    )
}

@Composable
fun GradientTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
    colors: List<Color> = listOf(CatCream, CatViolet, CatPeach)
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.copy(
            brush = Brush.linearGradient(colors)
        )
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = CatCream
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun TinyLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CatViolet,
    background: Color = CatViolet.copy(alpha = 0.12f)
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            ),
            color = color
        )
    }
}

@Composable
fun AppLogo(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(CatLime)
            .border(
                1.dp,
                Color.White.copy(alpha = 0.18f),
                RoundedCornerShape(size * 0.34f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_brand),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.86f)
        )
    }
}

@Composable
fun CatMascot(modifier: Modifier = Modifier) {
    val motionNanos = LocalAdaptiveMotionNanos.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val seconds = motionNanos.value / 1_000_000_000f
            val orbit = (seconds / 24f % 1f) * 360f
            val radius = size.minDimension * 0.38f
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(CatViolet.copy(alpha = 0.18f), Color.Transparent),
                    center = center,
                    radius = size.minDimension * 0.52f
                ),
                radius = size.minDimension * 0.52f,
                center = center
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = radius,
                center = center,
                style = Stroke(1.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.045f),
                radius = radius * 1.16f,
                center = center,
                style = Stroke(1.dp.toPx())
            )
            val angle = orbit * PI.toFloat() / 180f
            val cosAngle = kotlin.math.cos(angle.toDouble()).toFloat()
            val sinAngle = kotlin.math.sin(angle.toDouble()).toFloat()
            val dotCenter = center + androidx.compose.ui.geometry.Offset(
                cosAngle * radius * 1.16f,
                sinAngle * radius * 1.16f
            )
            drawCircle(
                color = CatPeach.copy(alpha = 0.9f),
                radius = 3.dp.toPx(),
                center = dotCenter
            )
        }
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.66f)
        )
    }
}

val CoverPalettes = listOf(
    listOf(Color(0xFF6C58C9), Color(0xFFE7A08B)),
    listOf(Color(0xFF1F6D71), Color(0xFF9FE3C9)),
    listOf(Color(0xFF743456), Color(0xFFE7A0B8)),
    listOf(Color(0xFF34498F), Color(0xFFB6A1F4)),
    listOf(Color(0xFF845337), Color(0xFFFFD3A1)),
    listOf(Color(0xFF46315F), Color(0xFFAD7EA8)),
    listOf(Color(0xFF345A48), Color(0xFFD4E8A3)),
    listOf(Color(0xFF5E3A75), Color(0xFFE2B2D3))
)

fun coverPalette(seed: String): List<Color> {
    val hash = abs(seed.hashCode())
    return CoverPalettes[hash % CoverPalettes.size]
}

@Composable
fun CoverArt(
    artworkFile: String?,
    seed: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    showBorder: Boolean = true
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(cornerRadius)
    val palette = remember(seed) { coverPalette(seed) }
    val model = remember(artworkFile) {
        val file = artworkFile?.let { File(context.filesDir, "artwork/$it") }
        if (file?.exists() == true) {
            ImageRequest.Builder(context)
                .data(file)
                .crossfade(280)
                .build()
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(palette, start = androidx.compose.ui.geometry.Offset.Zero))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.minDimension * 0.42f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.24f)
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.08f),
                radius = size.minDimension * 0.32f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.20f, size.height * 0.86f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.13f),
                radius = size.minDimension * 0.20f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.78f, size.height * 0.24f),
                style = Stroke(1.dp.toPx())
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.62f),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.30f)
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.20f))
                    )
                )
        )
        if (showBorder) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            )
        }
    }
}

@Composable
fun EqualizerBars(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = CatPeach,
    barCount: Int = 3
) {
    val motionNanos = LocalAdaptiveMotionNanos.current
    val activeAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.32f,
        animationSpec = tween(380),
        label = "activeAlpha"
    )
    Canvas(modifier) {
        val seconds = motionNanos.value / 1_000_000_000f
        val phase = (seconds / 1.1f) % 1f
        val gap = size.width * 0.16f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        repeat(barCount) { index ->
            val energy = if (active) {
                val wave = sin((phase * 2f * PI.toFloat() + index * 1.1f).toDouble()).toFloat()
                0.26f + 0.74f * abs(wave)
            } else {
                0.22f
            }
            val barHeight = (size.height * energy).coerceAtLeast(barWidth * 0.6f)
            drawRoundRect(
                color = color.copy(alpha = activeAlpha),
                topLeft = androidx.compose.ui.geometry.Offset(
                    x = index * (barWidth + gap),
                    y = size.height - barHeight
                ),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
fun EmptyHint(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    iconRes: Int = R.drawable.ic_search
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.055f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = CatTextTertiary,
                modifier = Modifier.size(23.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = CatCream,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = CatTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GradientCtaButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    iconRes: Int? = R.drawable.ic_add
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(CatViolet, Color(0xFFE7A6C8), CatPeach)
                )
            )
            .bouncyClickable(pressScale = 0.965f, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color(0xFF27192E),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = Color(0xFF27192E)
        )
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = CatTextTertiary
    )
}

// Keeps the mint palette referenced for thematic accent brushes.
val AccentMint = CatMint
