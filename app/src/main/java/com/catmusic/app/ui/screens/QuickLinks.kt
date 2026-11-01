package com.catmusic.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.catmusic.app.R
import com.catmusic.app.data.MAX_QUICK_LINKS
import com.catmusic.app.data.QuickLink
import com.catmusic.app.ui.components.GlassCard
import com.catmusic.app.ui.components.TinyLabel
import com.catmusic.app.ui.components.bouncyClickable
import com.catmusic.app.ui.theme.CatCream
import com.catmusic.app.ui.theme.CatDanger
import com.catmusic.app.ui.theme.CatMint
import com.catmusic.app.ui.theme.CatPeach
import com.catmusic.app.ui.theme.CatRose
import com.catmusic.app.ui.theme.CatTextSecondary
import com.catmusic.app.ui.theme.CatTextTertiary
import com.catmusic.app.ui.theme.CatViolet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

private data class BrowserOption(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap?
)

@Composable
fun QuickLinksCard(
    links: List<QuickLink>,
    onSave: (QuickLink) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickLink?>(null) }

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
                                    CatMint.copy(alpha = 0.18f),
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
                        painter = painterResource(R.drawable.ic_web),
                        contentDescription = null,
                        tint = CatMint,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "网页快捷栏",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${links.size} / $MAX_QUICK_LINKS · 点击后用外部浏览器打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
                if (links.isNotEmpty()) {
                    TinyLabel(
                        text = "快捷",
                        color = CatMint,
                        background = CatMint.copy(alpha = 0.10f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(links, key = { it.id }) { link ->
                    QuickLinkTile(
                        link = link,
                        onClick = { openQuickLink(context, link) },
                        onEdit = {
                            editing = link
                            editorOpen = true
                        }
                    )
                }
                if (links.size < MAX_QUICK_LINKS) {
                    item(key = "add-quick-link") {
                        AddQuickLinkTile(
                            onClick = {
                                editing = null
                                editorOpen = true
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = if (links.size >= MAX_QUICK_LINKS) {
                    "已达到 10 个上限。点击卡片右上角的铅笔可以修改或删除。"
                } else {
                    "最多 10 个。网址只交给所选浏览器打开；CatMusic 不会读取网页内容。联网仅用于「在线」页的音乐检索与试听。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = CatTextTertiary.copy(alpha = 0.78f),
                lineHeight = 17.sp
            )
        }
    }

    if (editorOpen) {
        QuickLinkEditorDialog(
            initial = editing,
            onDismiss = { editorOpen = false },
            onSave = { link ->
                onSave(link)
                editorOpen = false
            },
            onDelete = { id ->
                onDelete(id)
                editorOpen = false
            }
        )
    }
}

@Composable
private fun QuickLinkTile(
    link: QuickLink,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val tint = quickLinkTint(link.id)
    val host = remember(link.url) { hostOf(link.url) }
    val initial = remember(link.title) {
        link.title.trim().firstOrNull()?.toString()?.uppercase() ?: "W"
    }

    Column(
        modifier = Modifier
            .width(158.dp)
            .heightIn(min = 118.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        tint.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.035f),
                        Color(0x06000000)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.20f),
                        tint.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.045f)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .bouncyClickable(pressScale = 0.965f, onClick = onClick)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                tint.copy(alpha = 0.34f),
                                tint.copy(alpha = 0.12f)
                            )
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CatCream
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(25.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.055f))
                    .bouncyClickable(pressScale = 0.86f, onClick = onEdit),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = "编辑 ${link.title}",
                    tint = CatTextTertiary.copy(alpha = 0.86f),
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = link.title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = CatCream,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = host,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = CatTextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = link.browserLabel ?: "系统默认",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
            color = tint.copy(alpha = 0.80f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AddQuickLinkTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(158.dp)
            .heightIn(min = 118.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        CatMint.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.025f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        CatMint.copy(alpha = 0.26f),
                        Color.White.copy(alpha = 0.06f)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .bouncyClickable(pressScale = 0.965f, onClick = onClick)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CatMint.copy(alpha = 0.12f))
                .border(1.dp, CatMint.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = null,
                tint = CatMint,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = "添加网页",
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            color = CatMint
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "自定义网址与浏览器",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = CatTextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QuickLinkEditorDialog(
    initial: QuickLink?,
    onDismiss: () -> Unit,
    onSave: (QuickLink) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var title by remember(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var url by remember(initial?.id) { mutableStateOf(initial?.url.orEmpty()) }
    var browserPackage by remember(initial?.id) {
        mutableStateOf(initial?.browserPackage)
    }
    var browserLabel by remember(initial?.id) {
        mutableStateOf(initial?.browserLabel ?: "系统默认")
    }
    val normalizedUrl = remember(url) { normalizeQuickLinkUrl(url) }
    val browsers by produceState(
        initialValue = emptyList<BrowserOption>(),
        context
    ) {
        value = withContext(Dispatchers.IO) { discoverBrowsers(context) }
    }

    LaunchedEffect(browsers) {
        if (browsers.isNotEmpty() &&
            browserPackage != null &&
            browsers.none { it.packageName == browserPackage }
        ) {
            browserPackage = null
            browserLabel = "系统默认"
        }
    }

    val cardShape = RoundedCornerShape(30.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    val canSave = normalizedUrl != null
    val save = {
        val validUrl = normalizeQuickLinkUrl(url)
        if (validUrl != null) {
            val host = hostOf(validUrl)
            onSave(
                QuickLink(
                    id = initial?.id ?: UUID.randomUUID().toString(),
                    title = title.trim().ifBlank { host },
                    url = validUrl,
                    browserPackage = browserPackage,
                    browserLabel = if (browserPackage == null) {
                        "系统默认"
                    } else {
                        browserLabel
                    }
                )
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(max = maxHeight)
                .clip(cardShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xF41B1625),
                            Color(0xF40C0913)
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.24f),
                            CatViolet.copy(alpha = 0.16f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    cardShape
                )
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    CatViolet.copy(alpha = 0.24f),
                                    CatMint.copy(alpha = 0.12f)
                                )
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_web),
                        contentDescription = null,
                        tint = CatCream,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (initial == null) "添加快捷网页" else "编辑快捷网页",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = CatCream
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = if (initial == null) {
                            "保存后出现在设置里的网页快捷栏"
                        } else {
                            "修改网址或指定浏览器"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = CatTextTertiary
                    )
                }
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .bouncyClickable(pressScale = 0.88f, onClick = onDismiss),
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

            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 64) title = it },
                label = { Text("名称（可选）") },
                placeholder = { Text("例如：豆瓣电影") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = quickLinkFieldColors()
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { if (it.length <= 2048) url = it },
                label = { Text("网址") },
                placeholder = { Text("https://example.com") },
                singleLine = true,
                isError = url.isNotBlank() && normalizedUrl == null,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (canSave) save() }
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = quickLinkFieldColors()
            )

            if (url.isNotBlank() && normalizedUrl == null) {
                Text(
                    text = "请输入有效的 http / https 网址",
                    style = MaterialTheme.typography.bodySmall,
                    color = CatDanger,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp)
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                text = "打开方式",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = CatTextTertiary
            )
            Spacer(Modifier.height(9.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BrowserChip(
                    label = "系统默认",
                    icon = null,
                    selected = browserPackage == null,
                    onClick = {
                        browserPackage = null
                        browserLabel = "系统默认"
                    }
                )
                browsers.forEach { browser ->
                    BrowserChip(
                        label = browser.label,
                        icon = browser.icon,
                        selected = browserPackage == browser.packageName,
                        onClick = {
                            browserPackage = browser.packageName
                            browserLabel = browser.label
                        }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial != null) {
                    TextButton(
                        onClick = { onDelete(initial.id) }
                    ) {
                        Text(
                            text = "删除",
                            color = CatDanger,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text("取消", color = CatTextSecondary)
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (canSave) {
                                Brush.linearGradient(
                                    listOf(
                                        CatViolet.copy(alpha = 0.86f),
                                        CatPeach.copy(alpha = 0.72f)
                                    )
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.07f),
                                        Color.White.copy(alpha = 0.05f)
                                    )
                                )
                            }
                        )
                        .border(
                            1.dp,
                            if (canSave) {
                                Color.White.copy(alpha = 0.24f)
                            } else {
                                Color.White.copy(alpha = 0.06f)
                            },
                            RoundedCornerShape(14.dp)
                        )
                        .bouncyClickable(enabled = canSave, pressScale = 0.94f) {
                            save()
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "保存",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (canSave) Color(0xFF231834) else CatTextTertiary
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserChip(
    label: String,
    icon: ImageBitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(
                if (selected) {
                    CatViolet.copy(alpha = 0.16f)
                } else {
                    Color.White.copy(alpha = 0.04f)
                }
            )
            .border(
                1.dp,
                if (selected) {
                    CatViolet.copy(alpha = 0.38f)
                } else {
                    Color.White.copy(alpha = 0.07f)
                },
                RoundedCornerShape(13.dp)
            )
            .bouncyClickable(pressScale = 0.945f, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(5.dp))
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_web),
                contentDescription = null,
                tint = if (selected) CatViolet else CatTextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            ),
            color = if (selected) CatCream else CatTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun quickLinkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = CatCream,
    unfocusedTextColor = CatCream,
    cursorColor = CatViolet,
    focusedBorderColor = CatViolet.copy(alpha = 0.62f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
    focusedLabelColor = CatViolet,
    unfocusedLabelColor = CatTextTertiary,
    focusedPlaceholderColor = CatTextTertiary.copy(alpha = 0.55f),
    unfocusedPlaceholderColor = CatTextTertiary.copy(alpha = 0.55f),
    errorBorderColor = CatDanger.copy(alpha = 0.65f),
    errorLabelColor = CatDanger,
    errorCursorColor = CatDanger
)

private fun quickLinkTint(seed: String): Color {
    val palette = listOf(CatViolet, CatPeach, CatMint, CatRose)
    val index = (seed.hashCode() and 0x7fffffff) % palette.size
    return palette[index]
}

private fun normalizeQuickLinkUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val candidate = if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else if (trimmed.contains("://")) {
        return null
    } else {
        "https://$trimmed"
    }
    val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.trim().orEmpty()
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    return candidate
}

private fun hostOf(url: String): String {
    return runCatching {
        Uri.parse(url).host
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: url
}

private fun openQuickLink(context: Context, link: QuickLink) {
    val uri = runCatching { Uri.parse(link.url) }.getOrNull() ?: return
    val base = Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val direct = Intent(base).apply {
        link.browserPackage
            ?.takeIf { it.isNotBlank() }
            ?.let { setPackage(it) }
    }
    val launched = runCatching { context.startActivity(direct) }.isSuccess
    if (!launched) {
        val fallback = runCatching { context.startActivity(base) }.isSuccess
        if (!fallback) {
            Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun discoverBrowsers(context: Context): List<BrowserOption> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
    }
    return runCatching {
        pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .mapNotNull { packageName ->
                runCatching {
                    val info = pm.getApplicationInfo(packageName, 0)
                    val icon = runCatching {
                        pm.getApplicationIcon(info)
                            .toBitmap(width = 72, height = 72)
                            .asImageBitmap()
                    }.getOrNull()
                    BrowserOption(
                        packageName = packageName,
                        label = pm.getApplicationLabel(info).toString(),
                        icon = icon
                    )
                }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
