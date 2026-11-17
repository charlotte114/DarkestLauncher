package moe.ddlauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.ddlauncher.Game

/** 使用说明：主界面顶栏那个「i」原来在这，现在从设置页进来。 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这个启动器怎么工作") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                HelpItem(
                    "数据包",
                    "启动器只带游戏运行时（约 11 MB）。游戏本体数据要导入一次：选原版 APK，启动器把 assets/nx 里的 " +
                        "3.5 GB 抽到自己的数据目录，之后启动就是完整游戏。"
                )
                HelpItem(
                    "mod 放哪",
                    "所有 mod 都在 ${Game.modsDir().absolutePath}，每个 mod 一个文件夹，和官方版一模一样。"
                )
                HelpItem(
                    "启用 / 禁用",
                    "游戏只认文件夹里的 project.xml。禁用就是把它改名成 project.xml.off，启用再改回来。" +
                        "改动在下次启动游戏时生效，不用重启启动器。"
                )
                HelpItem(
                    "为什么要转换",
                    "游戏数据是 Switch 版的，mod 需要换纹理、换文件清单。游戏每次启动都会自动转换" +
                        "（写 .port-switch 指纹），所以首次进游戏会慢一点，之后会跳过。"
                )
                HelpItem(
                    "导入 mod",
                    "可以多选。会自动去掉多余目录层级、跳过 __MACOSX、把 UTF-16 的 project.xml 转成 UTF-8、" +
                        "没有 project.xml 的自动补一个；同工坊 ID 的旧版本会被替换掉。"
                )
                HelpItem(
                    "存档",
                    "存档在 ${Game.saveDir().absolutePath}，跟着本应用走。删除数据包不会动存档。"
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("明白了") } },
    )
}

@Composable
private fun HelpItem(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(2.dp))
    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

