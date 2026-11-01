package com.catmusic.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catmusic.app.R
import com.catmusic.app.data.ImportState
import com.catmusic.app.ui.components.AppLogo
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.ui.theme.GlassBorderBright
import kotlin.math.roundToInt

@Composable
fun ImportBottomSheet(
    onDismiss: () -> Unit,
    onPickMixed: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
                .verticalScroll(rememberScrollState())
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
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 46.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "把音乐带回家",
                        style = MaterialTheme.typography.headlineSmall,
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "选择一种方式，猫猫开始整理",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                        .bouncyClickable(pressScale = 0.84f, onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "关闭",
                        tint = CatTextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            ImportOptionCard(
                iconRes = R.drawable.ic_lyrics,
                accent = CatMint,
                title = "一次性导入歌曲、歌词与封面",
                description = "同时多选音频 + LRC/TXT + 图片，同名自动配对",
                onClick = {
                    onDismiss()
                    onPickMixed()
                }
            )
            Spacer(Modifier.height(11.dp))
            ImportOptionCard(
                iconRes = R.drawable.ic_music_note,
                accent = CatViolet,
                title = "仅选择音频文件",
                description = "多选复制 · MP3 / FLAC / WAV / AAC / OGG…",
                onClick = {
                    onDismiss()
                    onPickFiles()
                }
            )
            Spacer(Modifier.height(11.dp))
            ImportOptionCard(
                iconRes = R.drawable.ic_folder,
                accent = CatPeach,
                title = "导入整个文件夹",
                description = "扫描子文件夹里的音频、歌词和封面并自动配对",
                onClick = {
                    onDismiss()
                    onPickFolder()
                }
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(CatMint.copy(alpha = 0.8f))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "音频会复制进软件内。删除手机原文件不影响播放；在软件内删除只会移除软件副本，永远不改动原文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ImportOptionCard(
    iconRes: Int,
    accent: Color,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressScale = 0.98f, onClick = onClick),
        shape = RoundedCornerShape(23.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        borderColors = listOf(accent.copy(alpha = 0.22f), GlassBorder)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(accent.copy(alpha = 0.26f), accent.copy(alpha = 0.08f))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(23.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = CatCream
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = CatTextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun ImportOverlay(
    state: ImportState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is ImportState.Idle || state is ImportState.Finished || state is ImportState.Failed) {
        return
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xA807060C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { },
        contentAlignment = Alignment.Center
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .padding(horizontal = 4.dp),
            shape = RoundedCornerShape(32.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 26.dp),
            borderColors = listOf(
                CatViolet.copy(alpha = 0.3f),
                GlassBorderBright,
                GlassBorder
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ImportOrb()
                Spacer(Modifier.height(22.dp))
                AnimatedContent(
                    targetState = state is ImportState.Scanning,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                    label = "importTitle"
                ) { scanning ->
                    Text(
                        text = if (scanning) "正在扫描文件夹" else "正在整理音乐",
                        style = MaterialTheme.typography.headlineSmall,
                        color = CatCream
                    )
                }
                Spacer(Modifier.height(7.dp))
                when (state) {
                    is ImportState.Scanning -> {
                        Text(
                            text = "会在子文件夹里寻找音频、歌词与封面",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(22.dp))
                        ScanningBar()
                    }

                    is ImportState.Importing -> {
                        val progress = if (state.total > 0) {
                            (state.processed.toFloat() / state.total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val animatedProgress by animateFloatAsState(
                            targetValue = progress,
                            animationSpec = tween(280),
                            label = "importProgress"
                        )
                        Text(
                            text = "正在复制音频，并配对同名歌词与封面",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TinyLabel(
                                text = "${state.processed} / ${state.total}",
                                color = CatViolet,
                                background = CatViolet.copy(alpha = 0.10f)
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "${(animatedProgress * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = CatCream
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.07f))
                        ) {
                            if (animatedProgress > 0.001f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(animatedProgress)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(CatViolet, CatPeach)
                                            )
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "已保存并解析 ${state.parsed} 首音频",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary
                        )
                    }

                    else -> Unit
                }
                Spacer(Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
                        .bouncyClickable(pressScale = 0.95f, onClick = onCancel)
                        .padding(horizontal = 24.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = "取消",
                        style = MaterialTheme.typography.labelLarge,
                        color = CatTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportOrb() {
    val transition = rememberInfiniteTransition(label = "importOrb")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing)),
        label = "orbRotation"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbPulse"
    )
    Box(
        modifier = Modifier
            .size(86.dp)
            .graphicsLayer {
                scaleX = pulse
                scaleY = pulse
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
        ) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        CatViolet,
                        CatPeach,
                        Color.Transparent
                    )
                ),
                startAngle = -90f,
                sweepAngle = 290f,
                useCenter = false,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = size.minDimension * 0.37f,
                style = Stroke(1.dp.toPx())
            )
        }
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(CatViolet.copy(alpha = 0.32f), CatPeach.copy(alpha = 0.18f))
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_music_note),
                contentDescription = null,
                tint = CatCream,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun ScanningBar() {
    val transition = rememberInfiniteTransition(label = "scanningBar")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanningPhase"
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.07f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        )
        val segmentWidth = size.width * 0.34f
        val x = (size.width - segmentWidth) * phase
        drawRoundRect(
            brush = Brush.horizontalGradient(
                listOf(CatViolet.copy(alpha = 0.15f), CatViolet, CatPeach, CatPeach.copy(alpha = 0.15f))
            ),
            topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
            size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
        )
    }
}
