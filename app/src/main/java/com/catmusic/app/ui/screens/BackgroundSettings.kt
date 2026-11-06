package com.catmusic.app.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.catmusic.app.R
import com.catmusic.app.data.HomeBackgroundStyle
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import java.io.File

@Composable
fun HomeBackgroundCard(
    selected: HomeBackgroundStyle,
    customFile: File?,
    onSelect: (HomeBackgroundStyle) -> Unit,
    onPickCustom: () -> Unit,
    onClearCustom: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(39.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    CatViolet.copy(alpha = 0.20f),
                                    CatPeach.copy(alpha = 0.10f)
                                )
                            )
                        )
                        .border(
                            1.dp,
                            Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(13.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_palette),
                        contentDescription = null,
                        tint = CatViolet,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "主界面背景",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = selected.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TinyLabel(
                    text = "DIY",
                    color = CatPeach,
                    background = CatPeach.copy(alpha = 0.10f)
                )
            }

            Spacer(Modifier.height(14.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(HomeBackgroundStyle.entries, key = { it.key }) { style ->
                    BackgroundPresetTile(
                        style = style,
                        selected = style == selected,
                        customFile = customFile,
                        onClick = {
                            if (style == HomeBackgroundStyle.CUSTOM &&
                                customFile == null
                            ) {
                                onPickCustom()
                            } else {
                                onSelect(style)
                            }
                        }
                    )
                }
            }

            if (customFile != null) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onPickCustom) {
                        Text(
                            text = "更换自定义图片",
                            color = CatViolet,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearCustom) {
                        Text(
                            text = "删除自定义图片",
                            color = CatTextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "「熔岩流体」会一直保持品红 / 深紫的 MetaBall 动画，播放歌曲时也不会被封面替换；其他预设和自定义图片在播放时会自动换成当前专辑封面的模糊色彩。预设与图片都在本地绘制/读取，不联网；自定义图片会复制到软件私有目录并压缩保存，删除手机原图不影响显示。",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.78f),
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun BackgroundPresetTile(
    style: HomeBackgroundStyle,
    selected: Boolean,
    customFile: File?,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .width(128.dp)
            .clip(shape)
            .background(
                if (selected) CatViolet.copy(alpha = 0.09f)
                else Color.White.copy(alpha = 0.025f)
            )
            .border(
                1.dp,
                if (selected) CatViolet.copy(alpha = 0.48f)
                else Color.White.copy(alpha = 0.07f),
                shape
            )
            .bouncyClickable(pressScale = 0.96f, onClick = onClick)
            .padding(7.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(13.dp))
        ) {
            when {
                style == HomeBackgroundStyle.CUSTOM && customFile != null -> {
                    AsyncImage(
                        model = customFile,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f))
                                )
                            )
                    )
                }

                style == HomeBackgroundStyle.CUSTOM -> {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        CatMint.copy(alpha = 0.12f),
                                        CatViolet.copy(alpha = 0.10f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            tint = CatMint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                else -> {
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(presetBrush(style))
                    )
                    if (style == HomeBackgroundStyle.HAZE) {
                        Canvas(Modifier.matchParentSize()) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFFF7EE).copy(alpha = 0.60f),
                                        Color(0xFFFFDCE9).copy(alpha = 0.22f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width * 0.76f, size.height * 0.82f),
                                    radius = size.minDimension * 0.54f
                                ),
                                radius = size.minDimension * 0.54f,
                                center = Offset(size.width * 0.76f, size.height * 0.82f)
                            )
                        }
                    }
                    if (style == HomeBackgroundStyle.METABALL) {
                        Canvas(Modifier.matchParentSize()) {
                            val radius = size.minDimension * 0.34f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFE00B87).copy(alpha = 0.78f),
                                        Color(0xFF7A0754).copy(alpha = 0.32f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width * 0.40f, size.height * 0.52f),
                                    radius = radius
                                ),
                                radius = radius,
                                center = Offset(size.width * 0.40f, size.height * 0.52f)
                            )
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF5A1FC8).copy(alpha = 0.74f),
                                        Color(0xFF25084F).copy(alpha = 0.30f),
                                        Color.Transparent
                                    ),
                                    center = Offset(size.width * 0.62f, size.height * 0.54f),
                                    radius = radius * 0.92f
                                ),
                                radius = radius * 0.92f,
                                center = Offset(size.width * 0.62f, size.height * 0.54f)
                            )
                        }
                    }
                    if (style == HomeBackgroundStyle.STARFIELD) {
                        Canvas(Modifier.matchParentSize()) {
                            val points = listOf(
                                0.18f to 0.22f, 0.36f to 0.66f, 0.55f to 0.30f,
                                0.72f to 0.74f, 0.84f to 0.18f, 0.92f to 0.55f,
                                0.12f to 0.78f, 0.47f to 0.86f
                            )
                            points.forEach { (x, y) ->
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.55f),
                                    radius = 1.2.dp.toPx(),
                                    center = Offset(size.width * x, size.height * y)
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.24f))
                                )
                            )
                    )
                }
            }

            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(17.dp)
                        .clip(CircleShape)
                        .background(CatViolet),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = Color(0xFF231834),
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = style.title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) CatCream else CatTextSecondary,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = when (style) {
                HomeBackgroundStyle.METABALL -> "默认"
                HomeBackgroundStyle.HAZE -> "蓝红"
                HomeBackgroundStyle.AURORA -> "极光"
                HomeBackgroundStyle.LIQUID -> "流光"
                HomeBackgroundStyle.STARFIELD -> "星野"
                HomeBackgroundStyle.DUSK -> "暖色"
                HomeBackgroundStyle.MINT -> "清透"
                HomeBackgroundStyle.MINIMAL -> "纯色"
                HomeBackgroundStyle.CUSTOM -> if (customFile != null) "已选择" else "上传图片"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = CatTextTertiary,
            maxLines = 1
        )
    }
}

private fun presetBrush(style: HomeBackgroundStyle): Brush = when (style) {
    HomeBackgroundStyle.METABALL -> Brush.linearGradient(
        listOf(
            Color(0xFF030105),
            Color(0xFF1A042A),
            Color(0xFF07020A)
        )
    )

    HomeBackgroundStyle.HAZE -> Brush.linearGradient(
        listOf(
            Color(0xFF1429A8),
            Color(0xFF3B196F),
            Color(0xFF7E1638),
            Color(0xFF2A1930)
        )
    )

    HomeBackgroundStyle.LIQUID -> Brush.linearGradient(
        listOf(
            Color(0xFF2E2456),
            Color(0xFF1A3A43),
            Color(0xFF4B2736)
        )
    )

    HomeBackgroundStyle.STARFIELD -> Brush.linearGradient(
        listOf(
            Color(0xFF060D22),
            Color(0xFF15235A),
            Color(0xFF050711)
        )
    )

    HomeBackgroundStyle.DUSK -> Brush.linearGradient(
        listOf(
            Color(0xFF3C1C22),
            Color(0xFF7A3B34),
            Color(0xFF160B11)
        )
    )

    HomeBackgroundStyle.MINT -> Brush.linearGradient(
        listOf(
            Color(0xFF08201D),
            Color(0xFF16443F),
            Color(0xFF040B0A)
        )
    )

    HomeBackgroundStyle.MINIMAL -> Brush.linearGradient(
        listOf(Color(0xFF0C0C0E), Color(0xFF040405))
    )

    else -> Brush.linearGradient(
        listOf(
            Color(0xFF2A1A48),
            Color(0xFF231927),
            Color(0xFF132F33)
        )
    )
}
