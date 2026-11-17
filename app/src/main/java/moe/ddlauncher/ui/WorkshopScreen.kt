package moe.ddlauncher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ddlauncher.AppViewModel
import moe.ddlauncher.DataPack
import moe.ddlauncher.WorkshopCatalog
import moe.ddlauncher.WorkshopRepo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkshopScreen(
    state: AppViewModel.WorkshopState,
    onQueryChange: (String) -> Unit,
    onOpen: (WorkshopCatalog.Entry) -> Unit,
    onLookup: () -> Unit,
    onSetSort: (WorkshopCatalog.Sort) -> Unit,
    onSetTimeWindow: (WorkshopCatalog.TimeWindow) -> Unit,
    onSetTag: (String?) -> Unit,
    onSetCollection: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onOnlineSearch: () -> Unit,
    onResetSource: () -> Unit,
    onDownload: () -> Unit,
    onCloseDetail: () -> Unit,
    onShowSettings: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onShowLogin: () -> Unit,
    onMySubs: () -> Unit,
    onToggleSubscribe: (Long, String, Boolean) -> Unit,
    onLogout: () -> Unit,
    onSetChunks: (Int) -> Unit,
    onSelfCheck: () -> Unit,
    onCloseSelfCheck: () -> Unit,
    onCancelDownload: () -> Unit,
    updates: Map<String, Long>,
    updateBusy: Boolean,
    updateStatus: String?,
    onCheckUpdates: () -> Unit,
    onUpdateAll: () -> Unit,
    onBack: () -> Unit,
) {
    var keyDraft by remember { mutableStateOf(state.apiKey) }

    LaunchedEffect(state.showSettings) {
        if (state.showSettings) keyDraft = state.apiKey
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.item != null) "工坊详情" else "创意工坊",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.item != null) onCloseDetail() else onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.item == null) {
                        if (!state.loggedIn) {
                            TextButton(onClick = onShowLogin) {
                                Text("登录", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        IconButton(onClick = { onShowSettings(true) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置 API Key")
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner).fillMaxSize()) {
            if (state.item != null) {
                DetailPane(
                    state = state,
                    onDownload = onDownload,
                    onToggleSubscribe = onToggleSubscribe,
                    onShowLogin = onShowLogin,
                    onCancelDownload = onCancelDownload,
                )
            } else {
                BrowsePane(
                    state = state,
                    onQueryChange = onQueryChange,
                    onOpen = onOpen,
                    onLookup = onLookup,
                    onSetSort = onSetSort,
                    onSetTimeWindow = onSetTimeWindow,
                    onSetTag = onSetTag,
                    onSetCollection = onSetCollection,
                    onLoadMore = onLoadMore,
                    onOnlineSearch = onOnlineSearch,
                    onResetSource = onResetSource,
                    onShowLogin = onShowLogin,
                    onMySubs = onMySubs,
                    updates = updates,
                    updateBusy = updateBusy,
                    updateStatus = updateStatus,
                    onCheckUpdates = onCheckUpdates,
                    onUpdateAll = onUpdateAll,
                )
            }
        }
    }

    if (state.showSettings) {
        AlertDialog(
            onDismissRequest = { onShowSettings(false) },
            title = { Text("Steam Web API Key") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "填了就能搜索整个工坊（8000+ 个 mod）。不填也能用——内置了 670 个热门条目，支持本地搜索。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API Key") },
                        placeholder = { Text("32 位十六进制") },
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "怎么拿：在能打开 Steam 社区的网络上访问 steamcommunity.com/dev/apikey，" +
                            "域名随便填（例如 localhost），提交后复制那串 Key。一次填写，长期有效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "注意：Key 只存在本机，不会上传。工坊页面在国内被墙，但查询接口 api.steampowered.com 是通的，" +
                            "所以填好 Key 之后搜索不需要任何梯子。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.loggedIn) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "已登录 Steam：${state.accountName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "登录状态下搜索和读订阅不再需要 API Key。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                onLogout()
                                onShowSettings(false)
                            },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(
                                "退出登录（删除本地 token）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("并发下载线程", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "同时抓几个文件块。默认 4；mod 大、网络好可以调高，网络差调低。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 6, 8).forEach { n ->
                            FilterChip(
                                selected = state.chunkConcurrency == n,
                                onClick = { onSetChunks(n) },
                                label = { Text("$n") },
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(
                        onClick = onSelfCheck,
                        enabled = !state.selfCheckBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.selfCheckBusy) "自检中…" else "工坊自检（三条链路各跑一遍）")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSaveKey(keyDraft) }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { onShowSettings(false) }) { Text("取消") }
            },
        )
    }

    state.selfCheck?.let { lines ->
        AlertDialog(
            onDismissRequest = onCloseSelfCheck,
            title = { Text("工坊自检") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    lines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onCloseSelfCheck) { Text("关闭") } },
        )
    }
}

@Composable
private fun BrowsePane(
    state: AppViewModel.WorkshopState,
    onQueryChange: (String) -> Unit,
    onOpen: (WorkshopCatalog.Entry) -> Unit,
    onLookup: () -> Unit,
    onSetSort: (WorkshopCatalog.Sort) -> Unit,
    onSetTimeWindow: (WorkshopCatalog.TimeWindow) -> Unit,
    onSetTag: (String?) -> Unit,
    onSetCollection: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onOnlineSearch: () -> Unit,
    onResetSource: () -> Unit,
    onShowLogin: () -> Unit,
    onMySubs: () -> Unit,
    updates: Map<String, Long>,
    updateBusy: Boolean,
    updateStatus: String?,
    onCheckUpdates: () -> Unit,
    onUpdateAll: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 滚到底附近自动加载：内置目录是「多显示 36 个」，联网结果要去问下一页
    LaunchedEffect(listState, state.visible, state.total, state.onlineTotal, state.results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val loaded = state.results.size
                val moreOnline = state.onlineSource && loaded < state.onlineTotal
                val moreLocal = !state.onlineSource && state.visible < state.total
                if (last >= loaded - 3 && (moreOnline || moreLocal)) onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("搜标题 / 标签，或粘贴工坊链接") },
                trailingIcon = {
                    // 只有看起来像 ID / 链接时才给「打开」，避免搜关键词时误触
                    if (WorkshopRepo.parseId(state.query) != null) {
                        TextButton(onClick = onLookup, enabled = !state.looking) {
                            Text(if (state.looking) "…" else "打开")
                        }
                    }
                },
            )
        }

        item {
            InstalledUpdateCard(
                updates = updates,
                busy = updateBusy,
                status = updateStatus,
                onCheck = onCheckUpdates,
                onUpdateAll = onUpdateAll,
            )
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(WorkshopCatalog.Sort.entries.toList()) { s ->
                    FilterChip(
                        selected = state.sort == s,
                        onClick = { onSetSort(s) },
                        label = { Text(s.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }

        if (state.sort.supportsTimeWindow) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(WorkshopCatalog.TimeWindow.entries.toList()) { w ->
                        FilterChip(
                            selected = state.timeWindow == w,
                            onClick = { onSetTimeWindow(w) },
                            label = { Text(w.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = state.showSubs,
                    onClick = onMySubs,
                    label = {
                        Text(
                            when {
                                !state.loggedIn -> "我的订阅（需登录）"
                                state.subsLoading -> "读取订阅…"
                                state.subsTotal > 0 -> "我的订阅 ${state.subsTotal}"
                                else -> "我的订阅"
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.showSubs) "订阅列表" else "创意工坊在线结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetSource) {
                    Text("刷新", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        state.showSubs -> "我的订阅 ${state.total} 个"
                        state.onlineSource && state.onlineTotal > 0 ->
                            "匹配 ${state.total} 个 · 已加载 ${state.results.size}/${state.onlineTotal}"
                        state.query.isBlank() && state.tag == null -> "共 ${state.total} 个 mod"
                        else -> "匹配 ${state.total} 个"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.loadingList) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onOnlineSearch, enabled = !state.loadingList) {
                    Text(
                        when {
                            state.loggedIn -> "搜索整个工坊"
                            state.apiKey.isBlank() -> "联网搜（需登录）"
                            else -> "联网搜索"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        if (state.status.isNotBlank()) {
            item {
                Text(
                    state.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        state.error?.let { err ->
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        items(state.shown, key = { it.id }) { entry ->
            ModCard(entry = entry, installedAt = state.installed[entry.id], onClick = { onOpen(entry) })
        }

        if (state.onlineSource && state.results.size < state.onlineTotal) {
            item {
                if (state.onlineLoadingMore || state.loadingList) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinearProgressIndicator(Modifier.width(120.dp))
                    }
                } else {
                    TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                        Text("加载下一页（已 ${state.results.size} / 共 ${state.onlineTotal}）")
                    }
                }
            }
        } else if (!state.onlineSource && state.visible < state.total) {
            item {
                TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                    Text("再显示 36 个（还有 ${state.total - state.visible}）")
                }
            }
        }

        if (state.total == 0 && state.error == null) {
            item {
                Text(
                    if (!state.loggedIn && state.apiKey.isBlank())
                        "工坊内容来自 Steam，需要先登录 Steam（右上角「登录」）——登录后这里会显示热门 mod，也能直接搜。"
                    else "没有匹配的条目。换个词，或者点上面的排序重新加载。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                "内置目录生成于 ${if (state.builtAt > 0) date(state.builtAt) else "—"}。下载走 Steam 的 UGC/depot 流程，" +
                    "和 PC 上订阅后拿到的内容一致，装好会被自动转成手机格式。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModCard(entry: WorkshopCatalog.Entry, installedAt: Long?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (entry.preview.isNotBlank()) {
                    AsyncImage(
                        model = thumb(entry.preview),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append("订阅 ${entry.subs}")
                        if (entry.size > 0) append(" · ${DataPack.gb(entry.size)}")
                        if (entry.updated > 0) append(" · ${date(entry.updated)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.tags.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        entry.tags.joinToString(" · ") { WorkshopCatalog.tagLabel(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (installedAt != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (entry.updated > installedAt) "已装过，工坊有更新" else "已安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.updated > installedAt) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 已安装 mod 的更新检查：从主界面搬过来的，放在工坊页更顺手 */
@Composable
private fun InstalledUpdateCard(
    updates: Map<String, Long>,
    busy: Boolean,
    status: String?,
    onCheck: () -> Unit,
    onUpdateAll: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        busy -> status ?: "处理中…"
                        updates.isNotEmpty() -> "已装的 mod 有 ${updates.size} 个新版本"
                        else -> "检查已装的 mod 在工坊有没有更新"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updates.isNotEmpty()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (updates.isNotEmpty()) {
                TextButton(onClick = onUpdateAll, enabled = !busy) { Text("全部更新") }
            }
            TextButton(onClick = onCheck, enabled = !busy) {
                Text(if (busy) "检查中…" else "检查")
            }
        }
    }
}

@Composable
private fun DetailPane(
    state: AppViewModel.WorkshopState,
    onDownload: () -> Unit,
    onToggleSubscribe: (Long, String, Boolean) -> Unit,
    onShowLogin: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val item = state.item ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column {
                    if (item.previewUrl.isNotBlank()) {
                        AsyncImage(
                            model = item.previewUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(170.dp)
                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(Modifier.padding(14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append(DataPack.gb(item.sizeBytes))
                                append(" · 订阅 ${item.subscriptions}")
                                if (item.updated > 0) append(" · 更新 ${date(item.updated)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (item.tags.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                item.tags.joinToString(" · ") { WorkshopCatalog.tagLabel(it) },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val installedAt = state.installed[item.id]
                        if (installedAt != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (item.updated > installedAt) "已安装，工坊有更新" else "已安装",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (item.updated > installedAt) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (item.description.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 20,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onDownload,
                            enabled = !state.downloading && !state.looking,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    state.downloading -> state.status.ifBlank { "处理中…" }
                                    state.installed.containsKey(item.id) -> "重新下载并覆盖安装"
                                    else -> "下载并安装到 mods"
                                },
                            )
                        }
                        val subscribed = state.subscriptions.any { it.id == item.id }
                        val subBusy = state.subscribing.contains(item.id)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (!state.loggedIn) onShowLogin()
                                else onToggleSubscribe(item.id, item.title, !subscribed)
                            },
                            enabled = !subBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    subBusy -> "处理中…"
                                    subscribed -> "已订阅（点此取消）"
                                    state.loggedIn -> "订阅到 Steam 账号"
                                    else -> "登录后订阅到 Steam"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }

        if (state.downloading || state.progress != null) {
            item {
                DownloadCard(
                    status = state.status,
                    detail = state.detail,
                    progress = state.progress,
                    downloading = state.downloading,
                    onCancel = onCancelDownload,
                )
            }
        }

        state.error?.let { err ->
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        err,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

    }
}

/** 缩略图：索要 256 宽的小图，别把原图拉进内存 */
private fun thumb(url: String): String {
    if (url.isBlank()) return url
    val sep = if (url.contains('?')) "&" else "?"
    return "$url${sep}impolicy=Letterbox&imw=256"
}

private fun date(epochSeconds: Long): String =
    if (epochSeconds <= 0) "—"
    else SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(epochSeconds * 1000))

/** 下载进度卡：只显示「到哪了」，不刷文件名 */
@Composable
private fun DownloadCard(
    status: String,
    detail: String,
    progress: Float?,
    downloading: Boolean,
    onCancel: () -> Unit,
) {
    val bar = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
    )
    val pill = RoundedCornerShape(50)
    val shown by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(420),
        label = "dlProgress",
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloading) {
                    CircularProgressIndicator(
                        Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        status.ifBlank { if (downloading) "下载中" else "已完成" },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                progress?.let {
                    Text(
                        "${(it * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(pill)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (progress == null) {
                    // 还不知道总大小：一小段胶囊来回滑
                    val transition = rememberInfiniteTransition(label = "dl")
                    val phase by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "phase",
                    )
                    Box(
                        Modifier
                            .width(maxWidth * 0.3f)
                            .fillMaxHeight()
                            .offset(x = maxWidth * 0.7f * phase)
                            .clip(pill)
                            .background(bar),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth(shown)
                            .fillMaxHeight()
                            .clip(pill)
                            .background(bar),
                    )
                }
            }

            if (downloading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onCancel,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            "取消下载",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
