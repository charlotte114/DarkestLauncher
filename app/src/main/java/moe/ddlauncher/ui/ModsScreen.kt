package moe.ddlauncher.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ddlauncher.AppViewModel
import moe.ddlauncher.Conflict
import moe.ddlauncher.DataPack
import moe.ddlauncher.Game
import moe.ddlauncher.ModGroup
import moe.ddlauncher.ModGroups
import moe.ddlauncher.ModInfo
import moe.ddlauncher.R
import moe.ddlauncher.ModRepo
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsApp(
    state: AppViewModel.Ui,
    onGrant: () -> Unit,
    onRefresh: () -> Unit,
    onLaunch: () -> Unit,
    onToggle: (ModInfo, Boolean) -> Unit,
    onDelete: (ModInfo) -> Unit,
    onImport: (List<Uri>) -> Unit,
    onImportPack: (Uri) -> Unit,
    onCheckPack: () -> Unit,
    onRemovePack: () -> Unit,
    onOpenWorkshop: () -> Unit,
    onOpenSettings: () -> Unit,
    onUpdateMod: (String) -> Unit,
    onToggleAllMods: (Boolean) -> Unit,
    onLoadSnapshots: () -> Unit,
    onBackupSaves: () -> Unit,
    onImportSave: (Uri) -> Unit,
    onRestoreSave: (String) -> Unit,
    onDeleteSave: (String) -> Unit,
    onExportSave: (String, Uri?) -> Unit,
    exportNote: String?,
    onLoadLauncherLog: () -> Unit,
    onLoadGameLog: () -> Unit,
    onCloseLog: () -> Unit,
    onOpenGameSettings: () -> Unit,
    onCreateGroup: (String) -> Boolean,
    onRenameGroup: (String, String) -> Boolean,
    onDeleteGroup: (String) -> Unit,
    onMoveGroup: (Int, Int) -> Unit,
    onMoveModsToGroup: (List<String>, String?) -> Unit,
    onReorderGroup: (String?, List<String>) -> Unit,
) {
    var detailId by remember { mutableStateOf<String?>(null) }
    var showGroups by remember { mutableStateOf(false) }
    var showConflicts by remember { mutableStateOf(false) }
    var showSaves by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var confirmRemovePack by remember { mutableStateOf(false) }
    var pendingExportId by remember { mutableStateOf<String?>(null) }

    // 游戏版本以导入的数据包为准（从包里的清单读出来的），不再用 APK 的 versionName 冒充
    val launcherVer = state.versionName.ifBlank { "启动器" }
    val gameVer = when {
        !state.packInstalled -> "数据包未导入"
        state.gameVersion != null -> "游戏 ${state.gameVersion}"
        else -> "游戏版本未知"
    }

    // 分组列表：折叠状态、长按拖动中的 mod
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // 多选：长按不拖动进入，点条目勾选，底部条整体移动到某个组
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val latestSections = rememberUpdatedState(state.sections)
    val latestReorder = rememberUpdatedState(onReorderGroup)
    val latestMove = rememberUpdatedState(onMoveModsToGroup)

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onImport(uris) }

    val packPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportPack(uri) }

    // 存档 zip：有些 ROM 把 zip 标成 octet-stream，多列几种类型不然选择器里看不到
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onImportSave(uri) }

    // 导出存档：让用户挑文件夹；取消就当没点（「导出到 Download」按钮不用这个选择器）
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        pendingExportId?.let { onExportSave(it, uri) }
        pendingExportId = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text(
                            "Darkest 启动器",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "$launcherVer · ${state.mods.size} 个 mod",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWorkshop) {
                        Icon(Icons.Filled.ShoppingCart, contentDescription = "创意工坊")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        floatingActionButton = {
            if (state.hasAccess && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = {
                        picker.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "*/*",
                            )
                        )
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("导入 zip") },
                )
            }
        },
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    count = selected.size,
                    total = state.mods.size,
                    groups = state.groups.groups,
                    onSelectAll = {
                        if (selected.size == state.mods.size) selected.clear()
                        else state.mods.forEach { selected[it.id] = true }
                    },
                    onMove = { gid ->
                        val ids = selected.keys.toList()
                        selected.clear()
                        selecting = false
                        onMoveModsToGroup(ids, gid)
                    },
                    onCancel = {
                        selected.clear()
                        selecting = false
                    },
                )
            }
        },
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!state.hasAccess) {
                item { PermissionCard(onGrant) }
            }

            if (!state.packInstalled) {
                item {
                    DataPackMissingCard(
                        onPick = {
                            packPicker.launch(
                                arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream", "*/*")
                            )
                        },
                        onCheck = onCheckPack,
                    )
                }
            }

            item {
                GameCard(
                    packInstalled = state.packInstalled,
                    packSize = state.packSize,
                    version = gameVer,
                    onLaunch = onLaunch,
                    onRemove = { confirmRemovePack = true },
                )
            }

            item {
                SummaryRow(
                    mods = state.mods,
                    conflicts = state.conflicts,
                    onShowConflicts = { showConflicts = true },
                    onToggleAll = onToggleAllMods,
                )
            }

            item {
                ToolsRow(
                    onGame = onOpenGameSettings,
                    onSaves = {
                        onLoadSnapshots()
                        showSaves = true
                    },
                    onLog = { showLog = true },
                    onGroups = { showGroups = true },
                )
            }

            if (state.hasAccess && state.mods.isEmpty() && !state.loading) {
                item { EmptyHint() }
            }

            val sections = state.sections
            if (state.mods.isNotEmpty()) sections.forEach { section ->
                val key = section.group?.id.orEmpty()
                val isOpen = collapsed[key] != true
                item(key = "hdr:$key") {
                    GroupHeader(
                        title = section.group?.name ?: ModGroups.UNGROUPED,
                        count = section.mods.size,
                        expanded = isOpen,
                        ungrouped = section.group == null,
                        groups = state.groups.groups,
                        onToggle = { collapsed[key] = isOpen },
                        onMoveAll = { target -> latestMove.value(section.mods.map { it.id }, target) },
                    )
                }
                if (isOpen) {
                    items(section.mods, key = { "mod:${it.id}" }) { mod ->
                        ModRow(
                            mod = mod,
                            hasUpdate = state.updates.containsKey(mod.id),
                            busy = state.updateBusy,
                            dragging = draggingId == mod.id,
                            selectionMode = selecting,
                            selected = selected.containsKey(mod.id),
                            modifier = if (selecting) {
                                Modifier
                            } else {
                                Modifier.modDragToSort(
                                    modId = mod.id,
                                    listState = listState,
                                    scope = dragScope,
                                    sections = { latestSections.value },
                                    onReorder = { group, ids -> latestReorder.value(group, ids) },
                                    onDragState = { on -> draggingId = if (on) mod.id else null },
                                    onHold = {
                                        draggingId = null
                                        selected.clear()
                                        selected[mod.id] = true
                                        selecting = true
                                    },
                                )
                            },
                            onToggle = { onToggle(mod, it) },
                            onClick = {
                                if (selecting) {
                                    if (selected.containsKey(mod.id)) selected.remove(mod.id)
                                    else selected[mod.id] = true
                                } else {
                                    detailId = mod.id
                                }
                            },
                            onUpdate = { onUpdateMod(mod.id) },
                        )
                    }
                }
            }
        }
    }

    val detailMod = state.mods.firstOrNull { it.id == detailId }
    if (detailMod != null) {
        ModSheet(
            mod = detailMod,
            groups = state.groups.groups,
            currentGroup = state.groups.groupOf(detailMod.id),
            onMoveToGroup = { gid -> onMoveModsToGroup(listOf(detailMod.id), gid) },
            onDismiss = { detailId = null },
            onToggle = { onToggle(detailMod, it) },
            onDelete = {
                detailId = null
                onDelete(detailMod)
            },
        )
    }

    if (showGroups) {
        GroupManagerDialog(
            groups = state.groups.groups,
            modCounts = state.mods.groupingBy { state.groups.groupOf(it.id) }.eachCount(),
            ungrouped = state.mods.filter { state.groups.groupOf(it.id).isEmpty() }.map { it.id },
            onCreate = onCreateGroup,
            onRename = onRenameGroup,
            onDelete = onDeleteGroup,
            onMoveGroup = onMoveGroup,
            onMoveAll = onMoveModsToGroup,
            onDismiss = { showGroups = false },
        )
    }

    if (showConflicts) {
        ConflictsDialog(state.conflicts) { showConflicts = false }
    }

    if (showSaves) {
        SaveDialog(
            snapshots = state.snapshots,
            busy = state.saveBusy,
            onBackup = onBackupSaves,
            onImportSave = {
                savePicker.launch(
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")
                )
            },
            onRestore = onRestoreSave,
            onDelete = onDeleteSave,
            onExport = { id ->
                pendingExportId = id
                exportPicker.launch(null)
            },
            onExportDefault = { id -> onExportSave(id, null) },
            exportNote = exportNote,
            onDismiss = { showSaves = false },
        )
    }

    if (showLog) {
        LogDialog(
            text = state.logText,
            busy = state.logBusy,
            onLoadLauncher = onLoadLauncherLog,
            onLoadGame = onLoadGameLog,
            onDismiss = {
                showLog = false
                onCloseLog()
            },
        )
    }

    if (confirmRemovePack) {
        AlertDialog(
            onDismissRequest = { confirmRemovePack = false },
            title = { Text("删除数据包？") },
            text = { Text("会删掉 3.5 GB 的游戏本体数据，mod 和存档不动。之后可以再导入。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemovePack = false
                    onRemovePack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemovePack = false }) { Text("取消") }
            },
        )
    }

    state.packProgress?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            icon = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
            title = { Text("正在导入数据包") },
            text = {
                Column {
                    Text("3.5 GB，大概几分钟，别退出。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(progress, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }

    state.busy?.let { BusyDialog(it) }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(
                    "还差一步：授权访问 mod 目录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Android 11 以后，mods 目录在 Android/media 下，被系统锁给了游戏本身。" +
                    "启动器需要「所有文件访问」才能读写它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "授权后如果仍然读不到，去 系统设置 → 应用 → Darkest → 权限 里再确认一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant) { Text("去授权") }
        }
    }
}

@Composable
private fun GameCard(
    packInstalled: Boolean,
    packSize: Long,
    version: String,
    onLaunch: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.linearGradient(listOf(HeroTop, HeroMid, HeroBottom))
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        HeroAccent.copy(alpha = 0.45f),
                        RoundedCornerShape(14.dp),
                    ),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Darkest Dungeon",
                    style = MaterialTheme.typography.titleLarge,
                    color = HeroText,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (packInstalled) "数据包 ${DataPack.gb(packSize)} · $version"
                    else "数据包未导入，点下面导入一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = HeroSub,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onLaunch,
            enabled = packInstalled,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = HeroAccent,
                contentColor = Color(0xFF04302C),
                disabledContainerColor = HeroAccent.copy(alpha = 0.28f),
                disabledContentColor = Color(0xFF04302C).copy(alpha = 0.55f),
            ),
            contentPadding = PaddingValues(vertical = 13.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                "启动游戏",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (packInstalled) {
            Row(
                Modifier.fillMaxWidth().padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "游戏本体数据已就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = HeroSub.copy(alpha = 0.75f),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) {
                    Text("删除数据包", fontSize = 12.sp, color = HeroSub.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun DataPackMissingCard(onPick: () -> Unit, onCheck: () -> Unit) {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "还差游戏数据包",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "这个启动器只带运行时（约 11 MB），游戏本体数据要单独导入一次：选原版的 APK，" +
                    "启动器会把里面 assets/nx 的 3.5 GB 数据抽出来放进自己的数据目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "导入后启动器就是个完整的游戏，mods 和存档路径跟官方版完全一样。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPick, modifier = Modifier.weight(1f)) {
                    Text("导入数据包")
                }
                OutlinedButton(onClick = onCheck, modifier = Modifier.weight(1f)) {
                    Text("检查数据包")
                }
                OutlinedButton(
                    onClick = { openDataPackDownload(ctx) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("下载数据包")
                }
            }
        }
    }
}

/** 浏览器打开游戏 APK 下载页 */
private const val DATA_PACK_DOWNLOAD_URL =
    "https://dl.apkvision.org/darkest-dungeon/Darkest-Dungeon-v2.2.2-APKVISION.apk?v=0b84730f228f4308"

private fun openDataPackDownload(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(DATA_PACK_DOWNLOAD_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun SummaryRow(
    mods: List<ModInfo>,
    conflicts: List<Conflict>,
    onShowConflicts: () -> Unit,
    onToggleAll: (Boolean) -> Unit,
) {
    val enabled = mods.count { it.enabled }
    val allOn = mods.isNotEmpty() && enabled == mods.size
    val total = mods.sumOf { it.sizeBytes }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill("已启用", "$enabled / ${mods.size}", Modifier.weight(1f))
            StatPill("总体积", humanSize(total), Modifier.weight(1f))
            StatPill(
                label = "冲突",
                value = "${conflicts.size}",
                modifier = Modifier.weight(1f),
                alert = conflicts.isNotEmpty(),
                onClick = if (conflicts.isEmpty()) null else onShowConflicts,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MOD 列表",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (mods.isNotEmpty()) {
                TextButton(
                    onClick = { onToggleAll(!allOn) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        if (allOn) "全部关闭" else "全部开启",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alert: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val container = if (alert) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val content = if (alert) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSurface
    val sub = if (alert) content.copy(alpha = 0.75f)
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = sub)
    }
}

/** 游戏设置 / 存档备份 / 分组 / 运行日志 四个入口 */
@Composable
private fun ToolsRow(
    onGame: () -> Unit,
    onSaves: () -> Unit,
    onLog: () -> Unit,
    onGroups: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ToolButton(Icons.Filled.Build, "游戏设置", onGame, Modifier.weight(1f))
        ToolButton(Icons.Filled.DateRange, "存档备份", onSaves, Modifier.weight(1f))
        ToolButton(Icons.Filled.Menu, "分组", onGroups, Modifier.weight(1f))
        ToolButton(Icons.Filled.List, "运行日志", onLog, Modifier.weight(1f))
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModRow(
    mod: ModInfo,
    hasUpdate: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 10.dp else 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else if (mod.enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        border = when {
            dragging -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            selected -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            mod.enabled -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            else -> null
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (selectionMode) 2.dp else 10.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(2.dp))
            }
            ModIcon(mod.iconFile, mod.displayName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    mod.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (mod.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (mod.version.isNotBlank()) append("v${mod.version}")
                        if (mod.workshopId.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append("工坊 ${mod.workshopId}")
                        }
                        if (isEmpty()) append(mod.id)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val state = when {
                    !mod.enabled -> "已禁用"
                    mod.prepared -> "已转换"
                    else -> "进游戏时转换"
                }
                Text(
                    "$state · ${mod.fileCount} 文件 · ${humanSize(mod.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (!mod.enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    else if (mod.prepared) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
                if (hasUpdate) {
                    Text(
                        "工坊有新版本",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (hasUpdate && !selectionMode) {
                TextButton(onClick = onUpdate, enabled = !busy) {
                    Text("更新")
                }
            }
            if (!selectionMode) {
                Switch(checked = mod.enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun ModIcon(file: File?, name: String, size: Int = 52) {
    val bitmap = remember(file?.absolutePath, file?.lastModified(), file?.length()) {
        file?.takeIf { it.isFile }?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1).uppercase(Locale.ROOT),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModSheet(
    mod: ModInfo,
    groups: List<ModGroup>,
    currentGroup: String,
    onMoveToGroup: (String?) -> Unit,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModIcon(mod.iconFile, mod.displayName, 56)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(mod.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        buildString {
                            if (mod.version.isNotBlank()) append("v${mod.version}")
                            if (mod.workshopId.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append("工坊 ID ${mod.workshopId}")
                            }
                            if (isEmpty()) append("本地 mod")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = mod.enabled, onCheckedChange = onToggle)
            }

            Spacer(Modifier.height(10.dp))
            GroupPicker(
                current = currentGroup,
                groups = groups,
                onPick = onMoveToGroup,
            )

            if (mod.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    mod.tags.forEach { tag ->
                        AssistChip(onClick = {}, label = { Text(tag, fontSize = 12.sp) })
                    }
                }
            }

            if (mod.description.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        mod.description,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            InfoLine("目录", mod.id)
            InfoLine("文件", "${mod.fileCount} 个 · ${humanSize(mod.sizeBytes)}")
            if (mod.language.isNotBlank()) InfoLine("语言", mod.language)
            InfoLine(
                "状态",
                when {
                    !mod.enabled -> "已禁用（project.xml 已改名，游戏不会加载）"
                    mod.prepared -> "已转换为手机格式"
                    else -> "等游戏启动时自动转换"
                }
            )
            InfoLine("路径", mod.dir.absolutePath)

            if (mod.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                mod.notes.forEach {
                    Text(
                        "· $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = { onToggle(!mod.enabled) }, modifier = Modifier.weight(1f)) {
                    Text(if (mod.enabled) "禁用这个 mod" else "启用这个 mod")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${mod.displayName}？") },
            text = {
                Text("会删掉整个 mod 文件夹，以及移植层为它生成的转换备份（.port-nx-original）。不可恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(46.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ConflictsDialog(conflicts: List<Conflict>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("覆盖冲突 ${conflicts.size} 处") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "多个 mod 改同一个文件，谁生效没有保证。先禁用其中一个再进游戏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                conflicts.take(60).forEach { c ->
                    Text(
                        c.path,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (c.isData) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        c.mods.joinToString(" / "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (conflicts.size > 60) {
                    Text("…还有 ${conflicts.size - 60} 处", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun EmptyHint() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("还没有 mod", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "点右下角「导入 zip」。工坊下载的包直接选就行；解压过的文件夹请先压成 zip。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BusyDialog(text: String) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text("处理中") },
        text = {
            Column {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        icon = { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) },
    )
}

// ---------------- 分组 ----------------

/** 分组段落标题：点一下折叠，未分组那段还能「全部移入」某个组 */
@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    ungrouped: Boolean,
    groups: List<ModGroup>,
    onToggle: () -> Unit,
    onMoveAll: (String?) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggle() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ungrouped && groups.isNotEmpty() && count > 0) {
                Box {
                    TextButton(
                        onClick = { menu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("全部移入", style = MaterialTheme.typography.labelMedium)
                    }
                    GroupMenu(
                        expanded = menu,
                        groups = groups,
                        showUngrouped = false,
                        onDismiss = { menu = false },
                        onPick = { gid ->
                            menu = false
                            onMoveAll(gid)
                        },
                    )
                }
            }
        }
        if (ungrouped && groups.isEmpty() && count > 0) {
            Text(
                "想分类的话，先点上排「分组」建一个组，再把它们移进去",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp, bottom = 2.dp),
            )
        }
    }
}

/** 分组下拉菜单：未分组 + 所有组 */
@Composable
private fun GroupMenu(
    expanded: Boolean,
    groups: List<ModGroup>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
    showUngrouped: Boolean = true,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showUngrouped) {
            DropdownMenuItem(
                text = { Text(ModGroups.UNGROUPED) },
                onClick = { onPick(null) },
            )
        }
        groups.forEach { g ->
            DropdownMenuItem(
                text = { Text(g.name) },
                onClick = { onPick(g.id) },
            )
        }
    }
}

/** 多选模式下贴底的操作条：全选 / 整体移动到某个组 / 取消 */
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    groups: List<ModGroup>,
    onSelectAll: () -> Unit,
    onMove: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("已选 $count", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onSelectAll,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text(
                    if (count == total && total > 0) "取消全选" else "全选",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Box {
                Button(
                    onClick = { menu = true },
                    enabled = count > 0,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("移动到", style = MaterialTheme.typography.labelMedium)
                }
                GroupMenu(
                    expanded = menu,
                    groups = groups,
                    onDismiss = { menu = false },
                    onPick = { gid ->
                        menu = false
                        onMove(gid)
                    },
                )
            }
            TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("取消", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 单个 mod 换组（mod 详情面板里用） */
@Composable
private fun GroupPicker(current: String, groups: List<ModGroup>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = groups.firstOrNull { it.id == current }?.name ?: ModGroups.UNGROUPED
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "分组",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Box {
            TextButton(onClick = { open = true }) { Text(label) }
            GroupMenu(
                expanded = open,
                groups = groups,
                onDismiss = { open = false },
                onPick = { gid ->
                    open = false
                    onPick(gid)
                },
            )
        }
    }
}

/** 分组管理：新建 / 改名 / 删除 / 未分组批量迁移 */
@Composable
private fun GroupManagerDialog(
    groups: List<ModGroup>,
    modCounts: Map<String, Int>,
    ungrouped: List<String>,
    onCreate: (String) -> Boolean,
    onRename: (String, String) -> Boolean,
    onDelete: (String) -> Unit,
    onMoveGroup: (Int, Int) -> Unit,
    onMoveAll: (List<String>, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<ModGroup?>(null) }
    var deleteTarget by remember { mutableStateOf<ModGroup?>(null) }
    var moveMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分组管理") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "分组只是启动器里的分类，不会动 mods 里的文件夹，游戏照旧读取。" +
                        "列表里长按某个 mod 可以拖动排序。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = {
                            newName = it
                            error = null
                        },
                        label = { Text("新分组名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (onCreate(newName)) {
                                newName = ""
                                error = null
                            } else {
                                error = "名字为空或已经有同名分组"
                            }
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text("创建") }
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (groups.isEmpty()) {
                    Text(
                        "还没有分组，先建一个，再回列表里把 mod 移进去",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.forEachIndexed { index, g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            g.name,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${modCounts[g.id] ?: 0} 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { onMoveGroup(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, "上移", Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { onMoveGroup(index, index + 1) },
                            enabled = index < groups.lastIndex,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, "下移", Modifier.size(16.dp))
                        }
                        IconButton(onClick = { renaming = g }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Edit, "重命名", Modifier.size(16.dp))
                        }
                        IconButton(onClick = { deleteTarget = g }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Filled.Delete,
                                "删除分组",
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (ungrouped.isNotEmpty() && groups.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "未分组 ${ungrouped.size} 个 mod",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Box {
                            TextButton(onClick = { moveMenu = true }) { Text("全部移入…") }
                            GroupMenu(
                                expanded = moveMenu,
                                groups = groups,
                                showUngrouped = false,
                                onDismiss = { moveMenu = false },
                                onPick = { gid ->
                                    moveMenu = false
                                    onMoveAll(ungrouped, gid)
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )

    renaming?.let { g ->
        NameDialog(
            title = "重命名分组",
            initial = g.name,
            onConfirm = { onRename(g.id, it) },
            onDismiss = { renaming = null },
        )
    }

    deleteTarget?.let { g ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分组「${g.name}」？") },
            text = { Text("组里的 mod 不会被删除，只会回到未分组。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(g.id)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("名称") },
                    singleLine = true,
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onConfirm(text)) onDismiss() else error = "名字为空或已经有同名分组"
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 长按：拖动了 = 本组内排序；没动就松手 = 进入多选。
 * 手指滑到哪一行，就把被拖的 mod 插到那一行的位置。
 */
private fun Modifier.modDragToSort(
    modId: String,
    listState: LazyListState,
    scope: CoroutineScope,
    sections: () -> List<ModGroups.Section>,
    onReorder: (String?, List<String>) -> Unit,
    onDragState: (Boolean) -> Unit,
    onHold: () -> Unit,
): Modifier = this.pointerInput(modId) {
    var pointerY = 0f
    var moved = 0f
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "mod:$modId" }
            pointerY = (info?.offset ?: 0) + offset.y
            moved = 0f
            onDragState(true)
        },
        onDragEnd = {
            onDragState(false)
            if (moved < 24f) onHold()
        },
        onDragCancel = { onDragState(false) },
        onDrag = { change, amount ->
            change.consume()
            moved += kotlin.math.abs(amount.x) + kotlin.math.abs(amount.y)
            pointerY += amount.y
            if (moved >= 24f) {
                // 拖到列表上下边缘时自动滚动，长列表也能一路拖到底
                val info = listState.layoutInfo
                val edge = 96f
                when {
                    pointerY < info.viewportStartOffset + edge ->
                        scope.launch { listState.scrollBy(-14f) }

                    pointerY > info.viewportEndOffset - edge ->
                        scope.launch { listState.scrollBy(14f) }
                }
                val hovered = (info.visibleItemsInfo
                    .firstOrNull { pointerY >= it.offset && pointerY <= it.offset + it.size }
                    ?.key as? String)
                    ?.takeIf { it.startsWith("mod:") }
                    ?.removePrefix("mod:")
                if (hovered != null && hovered != modId) {
                    val section = sections().firstOrNull { s -> s.mods.any { it.id == modId } }
                    if (section != null && section.mods.any { it.id == hovered }) {
                        val ids = section.mods.map { it.id }.toMutableList()
                        val from = ids.indexOf(modId)
                        val to = ids.indexOf(hovered)
                        if (from >= 0 && to >= 0) {
                            ids.add(to, ids.removeAt(from))
                            onReorder(section.group?.id, ids)
                        }
                    }
                }
            }
        },
    )
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
