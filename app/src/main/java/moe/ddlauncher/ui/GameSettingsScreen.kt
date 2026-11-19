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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
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
import moe.ddlauncher.GameFont
import moe.ddlauncher.GameOptions
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
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
    onSetIntroSkip: (Boolean) -> Unit,
    onSetFont: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    var showRaw by remember { mutableStateOf(false) }
    val edits = remember { mutableStateMapOf<String, String>() }

    // 分辨率预设：16:9 几档 + 设备真实屏幕分辨率（引擎 view 尺寸跟它走，
    // 长屏机器想要铺满全屏就去掉黑边，选原生那一档）
    val presets = remember {
        val base = listOf("1920x1080", "2560x1440", "1600x900", "1280x720", "1024x576")
        val native = runCatching {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val b = if (Build.VERSION.SDK_INT >= 30) {
                wm.maximumWindowMetrics.bounds
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
            }
            val w = maxOf(b.width(), b.height())
            val h = minOf(b.width(), b.height())
            if (w > 0 && h > 0) "${w}x${h}" else null
        }.getOrNull()
        (listOfNotNull(native) + base).distinct()
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
            item {
                IntroSkipCard(
                    state = state,
                    onToggle = onSetIntroSkip,
                )
            }
            item {
                FontCard(
                    state = state,
                    onSetFont = onSetFont,
                )
            }

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
private fun FontCard(
    state: AppViewModel.Opts,
    onSetFont: (Int) -> Unit,
) {
    val enabled = state.font.available && !state.fontBusy
    var draft by remember(state.font.percent) {
        mutableFloatStateOf(state.font.percent.toFloat())
    }
    Card {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("游戏字体大小", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.font.available) "只改游戏内文本，下次启动生效" else "先导入数据包",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${draft.roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = {
                    val pct = GameFont.normalize(draft.roundToInt())
                    if (pct != state.font.percent) onSetFont(pct)
                },
                valueRange = GameFont.MIN.toFloat()..GameFont.MAX.toFloat(),
                steps = (GameFont.MAX - GameFont.MIN) / GameFont.STEP - 1,
                enabled = enabled,
            )
            Row(Modifier.fillMaxWidth()) {
                Text("小", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("标准 100%", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Text("大", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                "生成一套独立字体，不改原版文件；调大后个别长文本可能换行更多。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (state.fontBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
                Text(
                    "正在生成字形图集，字体越多需要的时间越长…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            state.fontMessage?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (state.font.percent != GameFont.NORMAL && enabled) {
                TextButton(
                    onClick = { onSetFont(GameFont.NORMAL) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("恢复原版字体")
                }
            }
        }
    }
}

@Composable
private fun IntroSkipCard(
    state: AppViewModel.Opts,
    onToggle: (Boolean) -> Unit,
) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("跳过开屏动画", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        !state.introSkipAvailable ->
                            "没找到数据包里的开屏视频，先导入数据包"
                        state.introSkipEnabled ->
                            "已用 0.5 秒黑屏替换 house_of_ruin / old_road 片头。关闭后恢复原视频。"
                        else ->
                            "跳过 house_of_ruin 和 old_road 两段片头，保留正常主菜单和语言。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = state.introSkipEnabled,
                enabled = state.introSkipAvailable && !state.saving,
                onCheckedChange = onToggle,
            )
        }
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
