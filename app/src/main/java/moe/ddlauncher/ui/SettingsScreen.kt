package moe.ddlauncher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ddlauncher.About
import moe.ddlauncher.AppViewModel
import moe.ddlauncher.DdLog

/**
 * 设置页：Steam 账号、下载、运行日志、关于，集中在一处。
 * 单独一屏而不是对话框，因为内容会越来越多（而且日志/账号都需要横向空间）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppViewModel.Ui,
    ws: AppViewModel.WorkshopState,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onOpenWorkshop: () -> Unit,
    onSetChunks: (Int) -> Unit,
    onClearCache: () -> Unit,
    onLoadLauncherLog: () -> Unit,
    onLoadGameLog: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val ctx = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }
    var logBytes by remember { mutableStateOf(-1L) }

    LaunchedEffect(Unit) {
        logBytes = withContext(Dispatchers.IO) {
            runCatching { java.io.File(DdLog.path(ctx)).length() }.getOrDefault(-1L)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionLabel("STEAM 账号") }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        if (ws.loggedIn) {
                            Text(
                                ws.accountName.ifBlank { "Steam 账号" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "已登录" + if (ws.steamId != 0L) " · SteamID ${ws.steamId}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "搜索、读订阅、装「需拥有游戏」的 mod 都靠这个身份。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onOpenWorkshop) { Text("去创意工坊") }
                                TextButton(onClick = onLogout) {
                                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        } else {
                            Text(
                                "未登录",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "不登录也能用内置目录和下过的东西，但搜不了整个工坊、读不到订阅，" +
                                    "「需拥有游戏」的条目也下不了。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = onLogin) { Text("登录 Steam") }
                        }
                    }
                }
            }

            item { SectionLabel("下载") }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("并发下载线程数", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "越大越快，但容易触发 Steam 限速；4 是稳态值。下次下载生效。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(2, 4, 6, 8).forEach { n ->
                                FilterChip(
                                    selected = ws.chunkConcurrency == n,
                                    onClick = { onSetChunks(n) },
                                    label = { Text("$n", style = MaterialTheme.typography.labelMedium) },
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "元数据语言：简体中文（工坊标题/简介优先给中文，作者没写就退回原文）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onClearCache) { Text("清理下载缓存") }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "只删临时解出来的文件，已装进 mods 的不动。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionLabel("运行日志") }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            "进不去游戏、mod 没生效、下载报错，先看这里，不用连电脑。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onLoadLauncherLog) { Text("启动器日志") }
                            OutlinedButton(onClick = onLoadGameLog) { Text("游戏日志") }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (logBytes < 0) "日志文件还没生成"
                                else "当前大小 ${fmtBytes(logBytes)}，超过 512 KB 会自动转存一份 .old",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    onClearLogs()
                                    logBytes = 0
                                },
                            ) { Text("清空日志") }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "游戏日志里找 `nx: started true` 和 `nx: mods:` 那几行，就能确定游戏有没有起来、" +
                                "哪些 mod 被认出来了。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionLabel("帮助") }
            item {
                Card {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("使用说明", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "数据包、mod 放哪、启用与转换、存档位置",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showHelp = true }) { Text("打开") }
                    }
                }
            }

            item { SectionLabel("关于") }
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(
                            "Darkest 启动器",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            state.versionName.ifBlank { "启动器" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "作者：${About.AUTHOR.ifBlank { "未填写" }}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (About.BILIBILI.isNotBlank()) {
                            Text(
                                "B 站：${About.BILIBILI}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (About.REPO.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "GitHub：",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(
                                    onClick = { openLink(ctx, About.REPO) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text(
                                        About.REPO,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        if (About.NOTE.isNotBlank()) {
                            Text(
                                About.NOTE,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (About.LINK.isNotBlank()) {
                            TextButton(
                                onClick = { openLink(ctx, About.LINK) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            ) {
                                Text(About.LINK, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                        Text("致谢", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "· Apricityx / WorkshopAndroidDownloader —— 工坊协议与下载引擎",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "· ModinMobileSTS / Sts2MobileLauncher —— 存档快照与日志查看的思路（MIT）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "这个启动器不含游戏本体，数据包需要你自己导入。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showHelp) {
        HelpDialog { showHelp = false }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
    )
}

private fun openLink(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/** 日志体积，给「运行日志」那张卡用 */
private fun fmtBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} KB"
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
}
