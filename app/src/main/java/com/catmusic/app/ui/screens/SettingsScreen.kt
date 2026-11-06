package com.catmusic.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catmusic.app.BuildConfig
import com.catmusic.app.R
import com.catmusic.app.data.AudioVisualLevel
import com.catmusic.app.data.HomeBackgroundStyle
import com.catmusic.app.data.ListeningStats
import com.catmusic.app.data.LyricFrameRate
import com.catmusic.app.data.LyricTranslationMode
import com.catmusic.app.data.QuickLink
import com.catmusic.app.ui.components.AppLogo
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.GlassDropdownMenu
import com.catmusic.app.ui.components.GradientTitle
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatDanger
import com.catmusic.app.ui.theme.CatLime
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import com.catmusic.app.ui.theme.GlassBorder
import com.catmusic.app.util.formatCompactDuration
import java.io.File
import kotlinx.coroutines.flow.StateFlow

private enum class ConfirmAction { ClearLibrary, ResetStats }

@Composable
fun SettingsScreen(
    trackCount: Int,
    statsFlow: StateFlow<ListeningStats>,
    quickLinks: List<QuickLink>,
    onSaveQuickLink: (QuickLink) -> Unit,
    onDeleteQuickLink: (String) -> Unit,
    homeBackgroundStyle: HomeBackgroundStyle,
    customBackgroundFile: File?,
    onSelectHomeBackground: (HomeBackgroundStyle) -> Unit,
    onPickCustomBackground: () -> Unit,
    onClearCustomBackground: () -> Unit,
    audioVisualLevel: AudioVisualLevel,
    onSelectAudioVisualLevel: (AudioVisualLevel) -> Unit,
    lyricFrameRate: LyricFrameRate,
    onSelectLyricFrameRate: (LyricFrameRate) -> Unit,
    lyricTranslationMode: LyricTranslationMode,
    onSelectLyricTranslationMode: (LyricTranslationMode) -> Unit,
    desktopLyricsEnabled: Boolean,
    onToggleDesktopLyrics: (Boolean) -> Unit,
    onPickMixed: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onClearLibrary: () -> Unit,
    onResetStats: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by statsFlow.collectAsStateWithLifecycle()
    val totalListeningMs = stats.totalMs
    var confirmAction by remember { mutableStateOf<ConfirmAction?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    var showDonation by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = Settings.canDrawOverlays(context)
        onToggleDesktopLyrics(granted)
        if (!granted) {
            Toast.makeText(
                context,
                "需要允许 CatMusic 显示在其他应用上层，才能挂桌面歌词",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val requestDesktopLyrics: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            onToggleDesktopLyrics(false)
        } else if (Settings.canDrawOverlays(context)) {
            onToggleDesktopLyrics(true)
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            runCatching { overlayPermissionLauncher.launch(intent) }.onFailure {
                Toast.makeText(
                    context,
                    "当前系统无法打开悬浮窗权限设置",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "header") {
            Column {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = CatViolet
                )
                Spacer(Modifier.height(8.dp))
                GradientTitle(
                    text = "设置",
                    style = MaterialTheme.typography.headlineLarge,
                    colors = listOf(CatCream, CatViolet)
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "关于音乐、数据，还有这只爱听歌的猫。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CatTextSecondary
                )
            }
        }

        item(key = "brand") {
            BrandCard()
        }

        item(key = "import-label") {
            GroupLabel("导入与管理")
        }
        item(key = "import-group") {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp)
            ) {
                Column {
                    SettingRow(
                        iconRes = R.drawable.ic_lyrics,
                        title = "一次性导入",
                        subtitle = "同时多选歌曲、LRC/TXT 歌词与封面，按文件名自动配对",
                        accent = CatMint,
                        onClick = onPickMixed
                    )
                    GroupDivider()
                    SettingRow(
                        iconRes = R.drawable.ic_music_note,
                        title = "仅导入音频",
                        subtitle = "多选歌曲并复制到软件内",
                        accent = CatViolet,
                        onClick = onPickFiles
                    )
                    GroupDivider()
                    SettingRow(
                        iconRes = R.drawable.ic_folder,
                        title = "导入音乐文件夹",
                        subtitle = "扫描子文件夹里的音频、歌词和封面并自动配对",
                        accent = CatPeach,
                        onClick = onPickFolder
                    )
                }
            }
        }

        item(key = "quick-links-label") {
            GroupLabel("快速打开网页")
        }
        item(key = "quick-links") {
            QuickLinksCard(
                links = quickLinks,
                onSave = onSaveQuickLink,
                onDelete = onDeleteQuickLink
            )
        }

        item(key = "lyrics-label") {
            GroupLabel("歌词与桌面")
        }
        item(key = "translation-card") {
            LyricTranslationCard(
                selected = lyricTranslationMode,
                onSelect = onSelectLyricTranslationMode
            )
        }
        item(key = "desktop-lyrics-card") {
            DesktopLyricsCard(
                enabled = desktopLyricsEnabled,
                onToggle = requestDesktopLyrics
            )
        }

        item(key = "render-label") {
            GroupLabel("画面与性能")
        }
        item(key = "render-group") {
            PerformanceCard(
                audioLevel = audioVisualLevel,
                onSelectAudioLevel = onSelectAudioVisualLevel,
                frameRate = lyricFrameRate,
                onSelectFrameRate = onSelectLyricFrameRate
            )
        }

        item(key = "background-label") {
            GroupLabel("DIY 主界面")
        }
        item(key = "background-card") {
            HomeBackgroundCard(
                selected = homeBackgroundStyle,
                customFile = customBackgroundFile,
                onSelect = onSelectHomeBackground,
                onPickCustom = onPickCustomBackground,
                onClearCustom = onClearCustomBackground
            )
        }

        item(key = "data-label") {
            GroupLabel("数据")
        }
        item(key = "data-group") {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp)
            ) {
                Column {
                    SettingRow(
                        iconRes = R.drawable.ic_delete,
                        title = "清空音乐库",
                        subtitle = "$trackCount 首歌曲 · 删除软件内副本与歌单",
                        accent = CatDanger,
                        danger = true,
                        onClick = { confirmAction = ConfirmAction.ClearLibrary }
                    )
                    GroupDivider()
                    SettingRow(
                        iconRes = R.drawable.ic_refresh,
                        title = "重置听歌统计",
                        subtitle = "当前累计 ${formatCompactDuration(totalListeningMs)}",
                        accent = CatDanger,
                        danger = true,
                        onClick = { confirmAction = ConfirmAction.ResetStats }
                    )
                }
            }
        }

        item(key = "format-label") {
            GroupLabel("播放格式")
        }
        item(key = "formats") {
            FormatsCard()
        }

        item(key = "privacy") {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 17.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        modifier = Modifier
                            .size(37.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CatMint.copy(alpha = 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            tint = CatMint,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(13.dp))
                    Column {
                        Text(
                            text = "只属于你的本地音乐",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = CatCream
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "导入时会把音频副本保存进 CatMusic 的私有空间，之后删除手机上的原文件不影响播放。在软件中删除只会移除软件副本、封面、歌词和歌单记录；手机原文件永远不会被软件删除。\n\n" +
                                "“在线”页会联网检索、试听和下载公开音乐，只向接口发送搜索关键词；本地曲库、封面、歌单与统计始终留在设备上，不会上传。歌词默认也只在本机读取；只有手动开启「自动补全」时，缺少译文的外语歌词文本才会发送到公共翻译服务。下载时默认使用无损，也可选择标准 / 较高音质（以来源实际支持为准），并选择存入播放器（加入音乐馆，可一并保存歌词）或仅保存到「音乐 / CatMusic」；两种保存位置彼此独立，也都不影响设备上的原文件。\n\n" +
                                "“音浪”声场直接分析解码后的本地音频电平、频段与节拍，不需要麦克风权限，不保存音频，也不会联网上传。可在播放页底部控制坞或右上角菜单随时开启或关闭。",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        item(key = "support-label") {
            GroupLabel("支持 CatMusic")
        }
        item(key = "support") {
            SupportCard(onClick = { showDonation = true })
        }

        item(key = "legal-label") {
            GroupLabel("关于")
        }
        item(key = "legal") {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(25.dp)
            ) {
                Column {
                    SettingRow(
                        iconRes = R.drawable.ic_music_note,
                        title = "开源许可",
                        subtitle = "Outfit、MusicDL 与开源组件声明",
                        accent = CatViolet,
                        onClick = { showLicenses = true }
                    )
                }
            }
        }

        item(key = "footer") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "CatMusic · ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = CatTextTertiary
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "用耳朵生活，用心收藏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary.copy(alpha = 0.65f)
                )
            }
        }
    }

    if (showLicenses) {
        val outfitLicense = remember {
            runCatching {
                context.assets.open("licenses/OUTFIT-OFL.txt")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "许可文本读取失败。" }
        }
        val glassLicense = remember {
            runCatching {
                context.assets.open("licenses/LIQUID-GLASS-ANDROID-MIT.txt")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "许可文本读取失败。" }
        }
        val musicdlLicense = remember {
            runCatching {
                context.assets.open("licenses/MUSICDL-POLYFORM-NONCOMMERCIAL.txt")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "许可文本读取失败。" }
        }
        val musicSquareLicense = remember {
            runCatching {
                context.assets.open("licenses/MUSICSQUARE-APACHE-2.0.txt")
                    .bufferedReader()
                    .use { it.readText() }
            }.getOrElse { "许可文本读取失败。" }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            containerColor = Color(0xFF1A1424),
            titleContentColor = CatCream,
            textContentColor = CatTextSecondary,
            title = {
                Text(
                    text = "开源许可",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "CatMusic 使用 Jetpack Compose、Media3 / ExoPlayer、Coil 等开源组件；液态玻璃直接使用 QWEA0/Liquid-Glass-Android（MIT）；字体使用 Outfit（SIL Open Font License 1.1）。\n\n" +
                            "「在线」功能源自 MusicDL 轻听与 musicdl（PolyForm Noncommercial License 1.0.0），公开平台检索桥接参考 MusicSquare（Apache License 2.0）。这些许可仅覆盖相关代码与界面，不改变第三方音乐内容的权利归属。\n\n" +
                            "OUTFIT 字体许可全文：",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextSecondary,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = outfitLicense,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "LIQUID GLASS 许可全文：",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextSecondary,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = glassLicense,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "MUSICDL / POLYFORM NONCOMMERCIAL 许可全文：",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextSecondary,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = musicdlLicense,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "MUSICSQUARE / APACHE 2.0 许可全文：",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextSecondary,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = musicSquareLicense,
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text("关闭", color = CatViolet)
                }
            }
        )
    }

    if (showDonation) {
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
        val shape = RoundedCornerShape(28.dp)
        Dialog(
            onDismissRequest = { showDonation = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xF51A1624),
                                Color(0xF50C0912)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(
                            listOf(
                                Color.White.copy(alpha = 0.24f),
                                CatPeach.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        ),
                        shape
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(CatPeach.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_heart),
                            contentDescription = null,
                            tint = CatPeach,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "支持 CatMusic",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = CatCream
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "微信扫码赞赏 · 完全自愿",
                            style = MaterialTheme.typography.bodySmall,
                            color = CatTextTertiary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .bouncyClickable(pressScale = 0.88f) {
                                showDonation = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "关闭",
                            tint = CatTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Image(
                    painter = painterResource(R.drawable.donation_wechat),
                    contentDescription = "赞赏二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "感谢每一位愿意支持这只猫的人。赞赏不会解锁额外功能，也不会改变任何本地数据；请在自己方便的时候量力而行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    lineHeight = 18.sp
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDonation = false }) {
                        Text("关闭", color = CatPeach)
                    }
                }
            }
        }
    }

    if (confirmAction != null) {
        val isClear = confirmAction == ConfirmAction.ClearLibrary
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            containerColor = Color(0xFF1A1424),
            titleContentColor = CatCream,
            textContentColor = CatTextSecondary,
            title = {
                Text(
                    text = if (isClear) "清空音乐库？" else "重置听歌统计？",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = if (isClear) {
                        "将删除软件内的音频副本、自定义封面、歌词缓存以及全部歌单。手机里的原始音频文件不会被删除。"
                    } else {
                        "累计聆听时长、单曲记录和 7 日趋势都会归零，此操作无法撤销。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isClear) onClearLibrary() else onResetStats()
                        confirmAction = null
                    }
                ) {
                    Text(
                        text = if (isClear) "清空" else "重置",
                        color = CatDanger,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) {
                    Text("取消", color = CatTextSecondary)
                }
            }
        )
    }
}

@Composable
private fun AudioLevelCard(
    selected: AudioVisualLevel,
    onSelect: (AudioVisualLevel) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CatViolet.copy(alpha = 0.11f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chart),
                        contentDescription = null,
                        tint = CatViolet,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "音浪等级",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "全屏声场歌词页的粒子密度与波层数量",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                AudioVisualLevel.entries.forEach { level ->
                    val active = level == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) CatViolet.copy(alpha = 0.14f)
                                else Color.White.copy(alpha = 0.035f)
                            )
                            .border(
                                1.dp,
                                if (active) CatViolet.copy(alpha = 0.30f)
                                else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(12.dp)
                            )
                            .bouncyClickable(pressScale = 0.94f) { onSelect(level) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = level.title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                            ),
                            color = if (active) CatViolet else CatTextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = selected.description,
                style = MaterialTheme.typography.bodySmall,
                color = CatTextSecondary,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "「极致」会拉满星潮粒子、辉光与回声波层，建议在旗舰设备上使用。声场使用 CatMusic 原创 WebGL 渲染，并会根据等级调整细腻度与内部渲染分辨率。",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.72f),
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "画面全部在本机生成，不联网，也不包含任何第三方音浪场景代码。",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.52f),
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(15.dp))
        }
    }
}

/**
 * Compact replacement for the old audio-level and refresh-rate cards. Both
 * choices live in one short card and open only when tapped, so the settings
 * page no longer becomes a long stack of six-option grids.
 */
@Composable
private fun PerformanceCard(
    audioLevel: AudioVisualLevel,
    onSelectAudioLevel: (AudioVisualLevel) -> Unit,
    frameRate: LyricFrameRate,
    onSelectFrameRate: (LyricFrameRate) -> Unit
) {
    var audioMenuOpen by remember { mutableStateOf(false) }
    var frameMenuOpen by remember { mutableStateOf(false) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CatMint.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chart),
                        contentDescription = null,
                        tint = CatMint,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "声场与动效",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "音浪密度与自适应刷新率 · 默认省电",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
            }

            CompactChoiceRow(
                iconRes = R.drawable.ic_chart,
                title = "音浪等级",
                value = audioLevel.title,
                accent = CatViolet,
                menuOpen = audioMenuOpen,
                onOpenMenu = { audioMenuOpen = true },
                onDismissMenu = { audioMenuOpen = false },
                options = AudioVisualLevel.entries.map { it.title },
                selectedIndex = AudioVisualLevel.entries.indexOf(audioLevel),
                onSelect = { index ->
                    onSelectAudioLevel(AudioVisualLevel.entries[index])
                }
            )
            Text(
                text = audioLevel.description,
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 45.dp, end = 17.dp, bottom = 8.dp)
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 45.dp, end = 17.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.055f))
            )
            CompactChoiceRow(
                iconRes = R.drawable.ic_refresh,
                title = "动效刷新率",
                value = frameRate.title,
                accent = CatMint,
                menuOpen = frameMenuOpen,
                onOpenMenu = { frameMenuOpen = true },
                onDismissMenu = { frameMenuOpen = false },
                options = LyricFrameRate.entries.map { it.title },
                selectedIndex = LyricFrameRate.entries.indexOf(frameRate),
                onSelect = { index ->
                    onSelectFrameRate(LyricFrameRate.entries[index])
                }
            )
            Text(
                text = when (frameRate) {
                    LyricFrameRate.ADAPTIVE ->
                        "默认：背景、歌词和音浪静止约 24fps、播放 30fps；滑动、切歌或歌词换行时短暂升到 60fps。"

                    LyricFrameRate.UNLIMITED ->
                        "跟随屏幕刷新率；最顺滑，耗电也最高。"

                    else ->
                        "${frameRate.description}；自动模式会更省电。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 45.dp, end = 17.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun CompactChoiceRow(
    iconRes: Int,
    title: String,
    value: String,
    accent: Color,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(pressScale = 0.985f, onClick = onOpenMenu)
                .padding(horizontal = 17.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = accent.copy(alpha = 0.86f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = CatCream.copy(alpha = 0.88f)
            )
            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accent.copy(alpha = 0.10f))
                    .padding(start = 10.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = accent
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.75f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        GlassDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.widthIn(min = 196.dp)
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == selectedIndex) {
                                accent
                            } else {
                                Color.White.copy(alpha = 0.82f)
                            }
                        )
                    },
                    trailingIcon = if (index == selectedIndex) {
                        {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(index)
                        onDismissMenu()
                    }
                )
            }
        }
    }
}

@Composable
private fun LyricFrameRateCard(
    selected: LyricFrameRate,
    onSelect: (LyricFrameRate) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CatMint.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chart),
                        contentDescription = null,
                        tint = CatMint,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "声场帧率",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "限制声场歌词页的渲染速率",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
            }

            val rows = LyricFrameRate.entries.chunked(3)
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 17.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    row.forEach { rate ->
                        val active = rate == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (active) CatMint.copy(alpha = 0.13f)
                                    else Color.White.copy(alpha = 0.035f)
                                )
                                .border(
                                    1.dp,
                                    if (active) CatMint.copy(alpha = 0.28f)
                                    else Color.White.copy(alpha = 0.06f),
                                    RoundedCornerShape(12.dp)
                                )
                                .bouncyClickable(pressScale = 0.94f) { onSelect(rate) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = rate.title,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (active) FontWeight.SemiBold
                                    else FontWeight.Medium
                                ),
                                color = if (active) CatMint else CatTextSecondary
                            )
                        }
                    }
                }
                if (rowIndex != rows.lastIndex) Spacer(Modifier.height(7.dp))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = selected.description,
                style = MaterialTheme.typography.bodySmall,
                color = CatTextSecondary,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "「自适应」静止约 24fps、播放 30fps，交互或歌词换行时升到 60fps；「无限制」跟随屏幕。低帧率更省电，高帧率更顺滑。",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.72f),
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(15.dp))
        }
    }
}

@Composable
private fun LyricTranslationCard(
    selected: LyricTranslationMode,
    onSelect: (LyricTranslationMode) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CatMint.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics),
                        contentDescription = null,
                        tint = CatMint,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "歌词翻译",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "外语歌词可开启翻译，译文固定排在原文下方",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 17.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                LyricTranslationMode.entries.forEach { mode ->
                    val active = mode == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (active) CatMint.copy(alpha = 0.13f)
                                else Color.White.copy(alpha = 0.035f)
                            )
                            .border(
                                1.dp,
                                if (active) CatMint.copy(alpha = 0.28f)
                                else Color.White.copy(alpha = 0.06f),
                                RoundedCornerShape(12.dp)
                            )
                            .bouncyClickable(pressScale = 0.94f) { onSelect(mode) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (active) FontWeight.SemiBold
                                else FontWeight.Medium
                            ),
                            color = if (active) CatMint else CatTextSecondary
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = selected.description,
                style = MaterialTheme.typography.bodySmall,
                color = CatTextSecondary,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = if (selected == LyricTranslationMode.ONLINE) {
                    "开启后，仅把缺少翻译的外语歌词文本发送到公共翻译服务；音频、封面、歌单与统计始终留在本机。译文会自动缓存，网络不佳时仍显示原文。"
                } else {
                    "歌词里的括号译文会自动拆成独立译文行，显示在原文下方；不再出现“原文（翻译）”这种挤在一起的排版。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.72f),
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 17.dp)
            )
            Spacer(Modifier.height(15.dp))
        }
    }
}

@Composable
private fun DesktopLyricsCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bouncyClickable(pressScale = 0.985f) { onToggle(!enabled) }
                    .padding(horizontal = 17.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CatLime.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lyrics),
                        contentDescription = null,
                        tint = CatLime,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "桌面歌词",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "播放页点「浮」或媒体通知按钮即可打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF11140A),
                        checkedTrackColor = CatLime,
                        uncheckedThumbColor = CatTextSecondary,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.07f),
                        uncheckedBorderColor = Color.White.copy(alpha = 0.16f)
                    )
                )
            }
            Text(
                text = "需要系统悬浮窗权限；只在 CatMusic 退到后台时出现。悬浮窗上可切样式、调颜色和字号；锁定后完全点击穿透，不影响打游戏，点小「解锁」恢复控制。译文跟随上面的歌词翻译设置。",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.7f),
                lineHeight = 17.sp,
                modifier = Modifier.padding(start = 17.dp, end = 17.dp, bottom = 15.dp)
            )
        }
    }
}

@Composable
private fun BrandCard() {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        borderColors = listOf(
            CatViolet.copy(alpha = 0.32f),
            GlassBorder
        )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(size = 60.dp)
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "CatMusic",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = CatCream
                    )
                    Spacer(Modifier.width(9.dp))
                    TinyLabel(
                        text = "LOCAL + ONLINE",
                        color = CatLime,
                        background = CatLime.copy(alpha = 0.10f)
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "把每一次心动，都记成旋律。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary
                )
            }
        }
    }
}

@Composable
private fun SupportCard(onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        borderColors = listOf(
            CatPeach.copy(alpha = 0.22f),
            GlassBorder
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bouncyClickable(pressScale = 0.985f, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                CatPeach.copy(alpha = 0.20f),
                                CatViolet.copy(alpha = 0.10f)
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
                    painter = painterResource(R.drawable.ic_heart),
                    contentDescription = null,
                    tint = CatPeach,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "赞赏支持",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = CatCream
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "如果 CatMusic 陪你听过一些夜晚，可以请这只猫吃一袋小鱼干。",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatTextTertiary,
                    lineHeight = 17.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = CatTextTertiary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(CatViolet.copy(alpha = 0.85f), CatMint.copy(alpha = 0.55f))
                    )
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 2.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = CatTextTertiary
        )
    }
}

@Composable
private fun GroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 68.dp, end = 18.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.055f))
    )
}

@Composable
private fun SettingRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    accent: Color,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bouncyClickable(pressScale = 0.985f, onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = if (danger) 0.09f else 0.11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = if (danger) CatDanger else CatCream
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = CatTextTertiary.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun FormatsCard() {
    val formats = listOf(
        "MP3", "FLAC", "WAV", "AAC", "M4A", "OGG",
        "Opus", "AMR", "AIFF", "MIDI", "MKA", "WEBM"
    )
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 17.dp)
    ) {
        Column {
            Text(
                text = "常见音频格式几乎都能直接播放",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = CatCream
            )
            Spacer(Modifier.height(11.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                formats.forEach { format ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(
                                1.dp,
                                Color.White.copy(alpha = 0.07f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = format,
                            style = MaterialTheme.typography.labelSmall,
                            color = CatTextSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "由 Media3 / ExoPlayer 提供解码。WMA、APE 等少数专有格式的可用性取决于文件编码与设备解码器。",
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary,
                lineHeight = 18.sp
            )
        }
    }
}
