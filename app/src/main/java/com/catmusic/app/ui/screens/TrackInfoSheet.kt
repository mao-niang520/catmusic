package com.catmusic.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catmusic.app.R
import com.catmusic.app.data.AudioFileStore
import com.catmusic.app.data.ListeningStats
import com.catmusic.app.data.Track
import com.catmusic.app.data.TrackStats
import com.catmusic.app.ui.components.CoverArt
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.components.coverPalette
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatDanger
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.util.audioFormatLabel
import com.catmusic.app.util.formatAddedAt
import com.catmusic.app.util.formatCompactDuration
import com.catmusic.app.util.formatFileSize
import com.catmusic.app.util.formatSongDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import java.io.File

private data class TrackFileDetails(
    val displayName: String?,
    val path: String?,
    val uri: String,
    val sizeBytes: Long?,
    val mimeType: String?,
    val isManaged: Boolean,
    val exists: Boolean,
    val sourceUri: String?,
    val sourcePath: String?,
    val sourceName: String?
)

@Composable
fun TrackInfoSheet(
    track: Track,
    statsFlow: StateFlow<ListeningStats>,
    onDismiss: () -> Unit,
    onSaveMetadata: (title: String, artist: String, album: String) -> Unit,
    onRequestArtwork: () -> Unit,
    onResetArtwork: () -> Unit,
    onRequestLyrics: () -> Unit,
    onDeleteFromSoftware: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxSheetHeight = (screenHeight * 0.90f).coerceAtLeast(360.dp)

    val stats by statsFlow.collectAsStateWithLifecycle()
    val trackStats = stats.tracks[track.id] ?: TrackStats()
    var fileDetails by remember(track.uri) { mutableStateOf<TrackFileDetails?>(null) }
    var editing by rememberSaveable(track.id) { mutableStateOf(false) }
    var editTitle by remember(track.id, editing) { mutableStateOf(track.title) }
    var editArtist by remember(track.id, editing) { mutableStateOf(track.artist) }
    var editAlbum by remember(track.id, editing) { mutableStateOf(track.album) }
    val accent = remember(track.id) { coverPalette(track.id).last() }

    LaunchedEffect(track.uri, track.mimeType) {
        fileDetails = withContext(Dispatchers.IO) {
            resolveTrackFileDetails(context, track)
        }
    }

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
                .heightIn(max = maxSheetHeight)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp, bottom = 20.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
            )
            Spacer(Modifier.height(18.dp))

            if (editing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "编辑歌曲信息",
                            style = MaterialTheme.typography.headlineSmall,
                            color = CatCream
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "只修改软件内显示，不会写入音频标签",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary
                        )
                    }
                    RoundIconButton(
                        iconRes = R.drawable.ic_close,
                        contentDescription = "取消编辑",
                        tint = CatTextSecondary,
                        onClick = { editing = false }
                    )
                }
                Spacer(Modifier.height(20.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        MetadataField(
                            label = "歌曲名",
                            value = editTitle,
                            accent = accent,
                            onValueChange = { editTitle = it }
                        )
                        MetadataField(
                            label = "歌手",
                            value = editArtist,
                            accent = accent,
                            onValueChange = { editArtist = it }
                        )
                        MetadataField(
                            label = "专辑",
                            value = editAlbum,
                            accent = accent,
                            onValueChange = { editAlbum = it }
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                            .bouncyClickable(pressScale = 0.97f) { editing = false }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelLarge,
                            color = CatTextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.35f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.linearGradient(listOf(CatViolet, CatPeach))
                            )
                            .bouncyClickable(
                                enabled = editTitle.isNotBlank() && editArtist.isNotBlank(),
                                pressScale = 0.97f
                            ) {
                                onSaveMetadata(editTitle, editArtist, editAlbum)
                                editing = false
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "保存修改",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color(0xFF231630)
                        )
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverArt(
                        artworkFile = track.artworkFile,
                        seed = track.id,
                        cornerRadius = 18.dp,
                        modifier = Modifier.size(74.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = CatCream,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CatTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            TinyLabel(
                                text = audioFormatLabel(track),
                                color = accent,
                                background = accent.copy(alpha = 0.10f)
                            )
                            TinyLabel(
                                text = formatSongDuration(track.durationMs),
                                color = CatTextTertiary,
                                background = Color.White.copy(alpha = 0.05f)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    RoundIconButton(
                        iconRes = R.drawable.ic_close,
                        contentDescription = "关闭",
                        tint = CatTextSecondary,
                        onClick = onDismiss
                    )
                }
                Spacer(Modifier.height(18.dp))

                SectionLabel("快捷操作")
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    QuickAction(
                        iconRes = R.drawable.ic_edit,
                        label = "编辑信息",
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onClick = { editing = true }
                    )
                    QuickAction(
                        iconRes = R.drawable.ic_image,
                        label = "更换封面",
                        accent = CatPeach,
                        modifier = Modifier.weight(1f),
                        onClick = onRequestArtwork
                    )
                    QuickAction(
                        iconRes = R.drawable.ic_lyrics,
                        label = if (track.lyricsFile.isNullOrBlank()) "导入歌词" else "更换歌词",
                        accent = CatMint,
                        modifier = Modifier.weight(1f),
                        onClick = onRequestLyrics
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.035f))
                            .bouncyClickable(pressScale = 0.97f, onClick = onResetArtwork)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = null,
                                tint = CatTextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "恢复默认封面",
                                style = MaterialTheme.typography.labelSmall,
                                color = CatTextSecondary
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(0.75f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(CatDanger.copy(alpha = 0.08f))
                            .bouncyClickable(pressScale = 0.97f, onClick = onDeleteFromSoftware)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "从软件删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = CatDanger
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                SectionLabel("歌曲详情")
                Spacer(Modifier.height(9.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        DetailRow("歌曲名", track.title)
                        DetailRow("歌手", track.artist)
                        DetailRow("专辑", track.album)
                        DetailRow("时长", formatSongDuration(track.durationMs))
                        DetailRow("格式", audioFormatLabel(track) + " · " + (track.mimeType ?: "未知 MIME"))
                        DetailRow("加入软件", formatAddedAt(track.addedAt))
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("文件位置")
                Spacer(Modifier.height(9.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 17.dp, vertical = 15.dp)
                ) {
                    Column {
                        val details = fileDetails
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_folder),
                                contentDescription = null,
                                tint = CatPeach,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(9.dp))
                            Text(
                                text = when {
                                    details == null -> "正在读取文件位置…"
                                    details.isManaged -> "软件内音频副本"
                                    else -> "外部授权文件"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = CatCream
                            )
                            Spacer(Modifier.weight(1f))
                            if (details != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(CatViolet.copy(alpha = 0.10f))
                                        .bouncyClickable(pressScale = 0.88f) {
                                            clipboard.setText(
                                                AnnotatedString(details.path ?: details.uri)
                                            )
                                            Toast.makeText(context, "文件位置已复制", Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                        .padding(7.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_copy),
                                        contentDescription = "复制文件位置",
                                        tint = CatViolet,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(11.dp))
                        Text(
                            text = fileDetails?.path ?: fileDetails?.uri ?: track.uri,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 17.sp
                            ),
                            color = CatTextSecondary
                        )
                        if (fileDetails != null &&
                            fileDetails?.path != null &&
                            fileDetails?.path != fileDetails?.uri
                        ) {
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = "URI: ${fileDetails?.uri}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 15.sp
                                ),
                                color = CatTextTertiary
                            )
                        }
                        val source = fileDetails?.sourceUri
                        if (!source.isNullOrBlank() && source != track.uri) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "原文件位置 · 仅作记录",
                                style = MaterialTheme.typography.labelSmall,
                                color = CatTextTertiary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = fileDetails?.sourcePath ?: source,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 15.sp
                                ),
                                color = CatTextTertiary.copy(alpha = 0.8f)
                            )
                            fileDetails?.sourceName?.let { sourceName ->
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = sourceName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CatTextTertiary.copy(alpha = 0.6f)
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val exists = fileDetails?.exists == true
                            TinyLabel(
                                text = when {
                                    fileDetails == null -> "读取文件信息"
                                    exists && fileDetails?.isManaged == true -> "副本可用"
                                    exists -> "原文件可访问"
                                    else -> "文件不可访问"
                                },
                                color = if (exists) CatMint else CatTextTertiary,
                                background = if (exists) {
                                    CatMint.copy(alpha = 0.09f)
                                } else {
                                    Color.White.copy(alpha = 0.05f)
                                }
                            )
                            TinyLabel(
                                text = formatFileSize(fileDetails?.sizeBytes),
                                color = CatTextTertiary,
                                background = Color.White.copy(alpha = 0.05f)
                            )
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(
                            text = "导入的歌曲已复制进 CatMusic。删除手机上的原文件不会影响播放；在软件内删除只会移除软件副本。",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary.copy(alpha = 0.8f),
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                SectionLabel("聆听记录")
                Spacer(Modifier.height(9.dp))
                GlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TinyMetric(
                            "播放次数",
                            "${trackStats.playCount} 次",
                            CatViolet,
                            modifier = Modifier.weight(1f)
                        )
                        TinyMetric(
                            "累计聆听",
                            if (trackStats.listenedMs > 0L) {
                                formatCompactDuration(trackStats.listenedMs)
                            } else {
                                "还未播放"
                            },
                            CatMint,
                            modifier = Modifier.weight(1f)
                        )
                        TinyMetric(
                            "上次播放",
                            if (trackStats.lastPlayedAt > 0L) {
                                formatAddedAt(trackStats.lastPlayedAt).substringBeforeLast(" ")
                            } else {
                                "暂无"
                            },
                            CatPeach,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "提示：歌曲名、歌手与专辑仅保存在 CatMusic 的软件库中，不会写回原始音频文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary.copy(alpha = 0.7f),
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

@Composable
private fun RoundIconButton(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f))
            .bouncyClickable(pressScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp)
        )
    }
}

@Composable
private fun QuickAction(
    iconRes: Int,
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.075f))
            .border(1.dp, accent.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .bouncyClickable(pressScale = 0.96f, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CatTextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = CatTextTertiary,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = CatTextSecondary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun TinyMetric(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = CatCream,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CatTextTertiary
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.SemiBold
        ),
        color = CatTextTertiary,
        modifier = Modifier.padding(start = 3.dp)
    )
}

@Composable
private fun MetadataField(
    label: String,
    value: String,
    accent: Color,
    onValueChange: (String) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = CatTextTertiary
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White.copy(alpha = 0.045f))
                .border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(13.dp))
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = CatCream),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }
    }
}

private suspend fun resolveTrackFileDetails(context: Context, track: Track): TrackFileDetails {
    val uri = runCatching { Uri.parse(track.uri) }.getOrNull()
    val isManaged = AudioFileStore.isManagedUri(context, track.uri)

    val source = track.sourceUri?.takeIf { it.isNotBlank() && it != track.uri }
    val sourceUri = source?.let { runCatching { Uri.parse(it) }.getOrNull() }
    val sourcePath = when {
        sourceUri == null -> null
        sourceUri.scheme == "file" -> sourceUri.path
        else -> resolveDocumentPath(context, sourceUri)
    }

    if (uri != null && uri.scheme == "file") {
        val file = uri.path?.let(::File)
        return TrackFileDetails(
            displayName = file?.name,
            path = file?.absolutePath,
            uri = track.uri,
            sizeBytes = file?.takeIf { it.exists() }?.length(),
            mimeType = track.mimeType,
            isManaged = isManaged,
            exists = file?.exists() == true,
            sourceUri = source,
            sourcePath = sourcePath,
            sourceName = track.sourceName
        )
    }

    var displayName: String? = null
    var size: Long? = null
    if (uri != null) {
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) displayName = cursor.getString(nameColumn)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                        size = cursor.getLong(sizeColumn)
                    }
                }
            }
        }
    }

    val friendlyPath = uri?.let { resolveDocumentPath(context, it) }
    return TrackFileDetails(
        displayName = displayName,
        path = friendlyPath,
        uri = track.uri,
        sizeBytes = size,
        mimeType = track.mimeType ?: uri?.let {
            runCatching { context.contentResolver.getType(it) }.getOrNull()
        },
        isManaged = isManaged,
        exists = displayName != null || friendlyPath != null,
        sourceUri = source,
        sourcePath = sourcePath,
        sourceName = track.sourceName
    )
}

private fun resolveDocumentPath(context: Context, uri: Uri): String? {
    return runCatching {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val documentId = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    val parts = documentId.split(":", limit = 2)
                    if (parts.size == 2) {
                        val volume = parts[0]
                        val relative = parts[1]
                        if (volume.equals("primary", ignoreCase = true)) {
                            File("/storage/emulated/0/$relative").absolutePath
                        } else {
                            File("/storage/$volume/$relative").absolutePath
                        }
                    } else {
                        null
                    }
                }

                "com.android.providers.downloads.documents" -> {
                    if (documentId.startsWith("raw:")) {
                        documentId.removePrefix("raw:")
                    } else {
                        null
                    }
                }

                else -> null
            }
        } else {
            null
        }
    }.getOrNull()
}
