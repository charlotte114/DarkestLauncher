package moe.ddlauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import moe.ddlauncher.AppViewModel
import moe.ddlauncher.GameOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 游戏内设置：直接编辑游戏的 `persist.options.json`。
 *
 * 为什么不 hook 游戏：这个文件就是引擎自己的设置存储（启动读、退出写），
 * 启动器改它等于改了游戏里的设置，下次启动生效，不用碰游戏代码。
 *
 * 控件原则：能用选择就不用打字 —— 语言/字幕/分辨率都是弹窗选，音量是滑块，
 * 开关是 Switch，只有档位型（gamma/帧率）用 −/+ 步进。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSettingsScreen(
    state: AppViewModel.Opts,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onSaveRaw: (String) -> Unit,
    onRestoreLatest: () -> Unit,
    onDump: () -> Unit,
) {
    val ctx = LocalContext.current
    var showRaw by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }

    // 分辨率预设：只给 16:9 的几档（游戏引擎内部按 16:9 画，别的比例铺不满、只会变形）
    val presets = remember {
        listOf("1920x1080", "2560x1440", "1600x900", "1280x720", "1024x576")
    }
    val presetLabels = remember { emptyMap<String, String>() }

    // 文件重新读了就清空编辑缓存，免得把上一次的脏值带过来
    LaunchedEffect(state.raw, state.modifiedAt) {
        edits.clear()
    }

    val dirty = edits.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("游戏内设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (dirty) {
                        TextButton(onClick = { onSave(edits.toMap()); edits.clear() }) { Text("保存") }
                    }
                },
            )
        },
        bottomBar = {
            if (dirty) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${edits.size} 项改动未保存",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { edits.clear() }) { Text("放弃") }
                        androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                        Button(onClick = { onSave(edits.toMap()); edits.clear() }) { Text("保存") }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { InfoCard(state, onReload, onDump, onRestoreLatest, onShowRaw = { showRaw = true }) }

            state.error?.let { err ->
                item {
                    Card {
                        Text(
                            err,
                            Modifier.fillMaxWidth().padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            state.message?.let { msg ->
                item {
                    Card {
                        Text(
                            msg,
                            Modifier.fillMaxWidth().padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (state.options.isNotEmpty()) {
                state.options.groupBy { it.group }.forEach { (group, list) ->
                    item { SectionLabel(group) }
                    item {
                        Card {
                            Column(Modifier.fillMaxWidth()) {
                                list.forEachIndexed { index, opt ->
                                    OptionRow(
                                        opt = opt,
                                        value = edits[opt.key] ?: opt.text,
                                        presets = presets,
                                        presetLabels = presetLabels,
                                        onChange = { edits[opt.key] = it },
                                    )
                                    if (index != list.lastIndex) {
                                        HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!state.loading) {
                item {
                    Text(
                        "文件里没读到设置项。先进游戏把设置面板打开一次，让引擎把默认值写出来。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(90.dp)) }
        }
    }

    if (showRaw) {
        RawDialog(
            text = state.raw,
            onDismiss = { showRaw = false },
            onApply = {
                showRaw = false
                onSaveRaw(it)
            },
        )
    }
}

@Composable
private fun InfoCard(
    state: AppViewModel.Opts,
    onReload: () -> Unit,
    onDump: () -> Unit,
    onRestoreLatest: () -> Unit,
    onShowRaw: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("改的是游戏自己的设置文件，下次启动游戏生效。", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "游戏还在后台就先划掉再改，否则它退出时会用内存里的值把文件覆盖回来。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    state.loading -> "读取中…"
                    state.modifiedAt > 0 -> "最后修改 " +
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(state.modifiedAt)) +
                        " · ${state.raw.length} 字符"
                    else -> ""
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReload) { Text("重新读取") }
                OutlinedButton(onClick = onRestoreLatest) { Text("还原备份") }
                OutlinedButton(onClick = onDump) { Text("导出") }
            }
            Text(
                "备份在 Documents/DarkestSaves/options（${state.backups.size} 份）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(
                onClick = onShowRaw,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("原始 JSON（高级）", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun OptionRow(
    opt: GameOptions.Option,
    value: String,
    presets: List<String>,
    presetLabels: Map<String, String>,
    onChange: (String) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                if (opt.hint.isNotBlank()) {
                    Text(
                        opt.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when (opt.kind) {
                GameOptions.Kind.TOGGLE -> Switch(
                    checked = value.trim() == "1",
                    onCheckedChange = { onChange(if (it) "1" else "0") },
                )

                GameOptions.Kind.VOLUME -> Text(
                    "$value",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(38.dp),
                )

                GameOptions.Kind.INT -> Stepper(
                    value = value.toIntOrNull() ?: opt.min,
                    min = opt.min,
                    max = opt.max,
                    onChange = { onChange(it.toString()) },
                )

                GameOptions.Kind.TEXT -> PickerButton(
                    text = opt.labelOf(value),
                    onClick = { picking = true },
                )

                GameOptions.Kind.RESOLUTION -> PickerButton(
                    text = value,
                    onClick = { picking = true },
                )

                GameOptions.Kind.COMPLEX -> Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (opt.kind == GameOptions.Kind.VOLUME) {
            Slider(
                value = (value.toIntOrNull() ?: 0).coerceIn(0, 100).toFloat(),
                onValueChange = { onChange(it.roundToInt().toString()) },
                valueRange = 0f..100f,
                steps = 19,
                modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
            )
        }
    }

    if (picking) {
        val choices = when (opt.kind) {
            GameOptions.Kind.RESOLUTION -> presets
            else -> opt.choices
        }
        ChoiceDialog(
            title = opt.label,
            choices = choices,
            selected = value,
            labelOf = { presetLabels[it] ?: opt.labelOf(it) },
            // 当前值不在候选里（比如游戏以后加了新档位），补一行，别把用户的选择弄丢
            extra = value.takeIf { it.isNotBlank() && !choices.contains(it) },
            onPick = {
                picking = false
                onChange(it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun PickerButton(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { if (value > min) onChange(value - 1) }, enabled = value > min) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "$value",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(20.dp),
        )
        TextButton(onClick = { if (value < max) onChange(value + 1) }, enabled = value < max) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<String>,
    selected: String,
    labelOf: (String) -> String,
    extra: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                (listOfNotNull(extra) + choices).forEach { c ->
                    item(key = c) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = c == selected, onClick = { onPick(c) })
                            Text(labelOf(c), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun RawDialog(text: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var draft by remember { mutableStateOf(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("原始 JSON") },
        text = {
            Column {
                Text(
                    "整段替换，写坏了设置会读不出来。保存前会自动备份，出问题点「还原备份」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(draft) }) { Text("写回") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}
