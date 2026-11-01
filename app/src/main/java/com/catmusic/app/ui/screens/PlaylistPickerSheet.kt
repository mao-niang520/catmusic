package com.catmusic.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmusic.app.R
import com.catmusic.app.data.Playlist
import com.catmusic.app.data.Track
import com.catmusic.app.ui.components.GradientCtaButton
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet

@Composable
fun PlaylistPickerSheet(
    track: Track,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onToggleMembership: (Playlist, Boolean) -> Unit,
    onGoToPlaylists: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val maxListHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.48f)
        .coerceIn(170.dp, 430.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF15101D),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 18.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "添加到歌单",
                        style = MaterialTheme.typography.headlineSmall,
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "《${track.title}》 · 轻点即可加入或移出",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .bouncyClickable(pressScale = 0.84f, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "关闭",
                        tint = CatTextSecondary,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            Spacer(Modifier.height(15.dp))

            if (playlists.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(CatViolet.copy(alpha = 0.09f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_playlist),
                            contentDescription = null,
                            tint = CatViolet.copy(alpha = 0.85f),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "还没有可加入的歌单",
                        style = MaterialTheme.typography.titleMedium,
                        color = CatCream
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "先创建一个歌单，再把它收进喜欢的心情里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                    Spacer(Modifier.height(16.dp))
                    GradientCtaButton(
                        text = "去创建歌单",
                        iconRes = R.drawable.ic_playlist,
                        onClick = onGoToPlaylists
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    contentPadding = PaddingValues(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val isMember = track.id in playlist.trackIds
                        val palette = coverPalette(playlist.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(17.dp))
                                .background(
                                    if (isMember) CatViolet.copy(alpha = 0.08f)
                                    else Color.White.copy(alpha = 0.025f)
                                )
                                .border(
                                    1.dp,
                                    if (isMember) CatViolet.copy(alpha = 0.18f)
                                    else Color.White.copy(alpha = 0.045f),
                                    RoundedCornerShape(17.dp)
                                )
                                .bouncyClickable(pressScale = 0.985f) {
                                    onToggleMembership(playlist, isMember)
                                }
                                .padding(horizontal = 9.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(43.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(palette.first(), palette.last())
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_playlist),
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = CatCream,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = "${playlist.trackIds.size} 首 · ${if (isMember) "已在歌单" else "轻点加入"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CatTextTertiary
                                )
                            }
                            if (isMember) {
                                TinyLabel(
                                    text = "已加入",
                                    color = CatMint,
                                    background = CatMint.copy(alpha = 0.09f)
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .size(23.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isMember) CatViolet else Color.White.copy(alpha = 0.055f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isMember) CatViolet else Color.White.copy(alpha = 0.10f),
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isMember) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = "已在歌单",
                                        tint = Color(0xFF1D1427),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "更改会实时保存，可以直接连续加入多个歌单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary.copy(alpha = 0.72f),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
