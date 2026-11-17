package moe.ddlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ddlauncher.Game
import moe.ddlauncher.SaveRepo

/**
 * 存档备份面板。存档在 Android/data/<包名> 下，第三方文件管理器碰不到，
 * 所以备份/还原只能由 App 自己做（思路来自 Sts2MobileLauncher 的存档快照）。
 */
@Composable
fun SaveDialog(
    snapshots: List<SaveRepo.Snapshot>,
    busy: Boolean,
    onBackup: () -> Unit,
    onImportSave: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingRestore by remember { mutableStateOf<SaveRepo.Snapshot?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存档备份", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "备份存在 ${SaveRepo.snapDir().absolutePath.replace("/storage/emulated/0", "内部存储")}，" +
                        "卸载 App 也不会跟着删。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "游戏存档：…/files/nx/save",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onBackup,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("立即备份当前存档")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onImportSave,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("从 zip 导入存档")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "选一个存档 zip（别人给的、电脑上拷的、网盘下的都行）。导入前会自动把现在的存档" +
                        "备份一份，导入的包也会留在下面的列表里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (snapshots.isEmpty()) {
                    Text(
                        if (Game.saveDir().isDirectory) "还没有备份过"
                        else "还没开始过游戏（找不到存档目录），进一次游戏再来备份",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(snapshots, key = { it.id }) { s ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.timeText, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${s.reasonText} · ${SaveRepo.sizeText(s.sizeBytes)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(onClick = { pendingRestore = s }, enabled = !busy) {
                                        Text("还原")
                                    }
                                    TextButton(onClick = { onDelete(s.id) }, enabled = !busy) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    pendingRestore?.let { snap ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("还原存档？") },
            text = {
                Text(
                    "会用 ${snap.timeText} 的备份覆盖当前存档。\n" +
                        "覆盖前会自动把现在的存档另存一份，不怕点错。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    onRestore(snap.id)
                }) { Text("还原") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }
}

/** 日志面板：启动器自己的落盘日志 / 本进程的 logcat（`nx:` 那批就在里面） */
@Composable
fun LogDialog(
    text: String?,
    busy: Boolean,
    onLoadLauncher: () -> Unit,
    onLoadGame: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLoadLauncher, enabled = !busy) { Text("启动器日志") }
                    OutlinedButton(onClick = onLoadGame, enabled = !busy) { Text("游戏日志") }
                }
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                ) {
                    Box(Modifier.padding(8.dp)) {
                        if (busy) {
                            Text("读取中…", style = MaterialTheme.typography.bodySmall)
                        } else if (text.isNullOrEmpty()) {
                            Text(
                                "选上面两个按钮之一。排查进不去游戏时，看「游戏日志」里有没有 " +
                                    "nx: the APK carries 0 files of the data 和 nx: started true。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                ),
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
