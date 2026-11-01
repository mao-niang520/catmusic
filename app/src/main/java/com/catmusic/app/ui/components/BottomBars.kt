package com.catmusic.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmusic.app.R
import com.catmusic.app.data.Track
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatLime
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder

enum class AppTab(
    val label: String,
    @DrawableRes val iconRes: Int
) {
    Library("音乐馆", R.drawable.ic_library),
    Online("在线", R.drawable.ic_online),
    Playlists("歌单", R.drawable.ic_playlist),
    Stats("统计", R.drawable.ic_chart),
    Settings("设置", R.drawable.ic_settings)
}

@Composable
fun CatBottomBar(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(27.dp)
    Box(modifier = modifier.fillMaxWidth()) {
        RealLiquidGlassLayer(
            modifier = Modifier.matchParentSize(),
            cornerRadius = 27.dp,
            refractionHeight = 44.dp,
            bevelWidth = 28.dp,
            dispersion = 0.20f,
            // Nearly colourless: the live backdrop should read through the bar.
            tintArgb = 0x0D000000
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppTab.entries.forEach { tab ->
            val isSelected = selected == tab
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0f,
                animationSpec = tween(320),
                label = "navIndicator"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(21.dp))
                    .bouncyClickable(pressScale = 0.94f) { onSelect(tab) }
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .alpha(indicatorAlpha)
                ) {
                    LiquidGlassSurface(
                        modifier = Modifier.matchParentSize(),
                        shape = RoundedCornerShape(21.dp),
                        tint = CatLime.copy(alpha = 0.12f),
                        borderColors = listOf(
                            Color.White.copy(alpha = 0.20f),
                            Color.White.copy(alpha = 0.035f),
                            CatLime.copy(alpha = 0.22f)
                        )
                    ) {}
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 3.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(tab.iconRes),
                        contentDescription = tab.label,
                        tint = if (isSelected) CatLime else CatTextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    AnimatedVisibility(
                        visible = isSelected,
                        enter = expandHorizontally(animationSpec = tween(280)) + fadeIn(tween(220)),
                        exit = shrinkHorizontally(animationSpec = tween(200)) + fadeOut(tween(120))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.sp
                                ),
                                color = CatLime,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun MiniPlayer(
    track: Track,
    player: PlayerUiSnapshot,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(25.dp)
    val accent = coverPalette(track.id).last()
    val isPlaying = player.isPlaying
    val durationMs = player.durationMs.takeIf { it > 0L } ?: track.durationMs
    val progress = if (durationMs > 0L) {
        (player.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .bouncyClickable(pressScale = 0.985f, onClick = onOpen),
        shape = shape,
        borderColors = listOf(
            Color.White.copy(alpha = 0.24f),
            GlassBorder,
            GlassBorder
        ),
        sensorSpecular = true
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 11.dp, end = 11.dp, top = 10.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                CoverArt(
                    artworkFile = track.artworkFile,
                    seed = track.id,
                    cornerRadius = 15.dp,
                    modifier = Modifier.size(52.dp)
                )

                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0D0A14)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerBars(
                        active = isPlaying,
                        color = accent,
                        modifier = Modifier.size(9.dp, 8.dp),
                        barCount = 3
                    )
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CatCream,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(43.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                CatViolet.copy(alpha = 0.34f),
                                CatPeach.copy(alpha = 0.22f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                    .bouncyClickable(pressScale = 0.88f, onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = CatCream,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(progress)
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(listOf(CatViolet, accent, CatPeach))
                )
        )
    }
}
