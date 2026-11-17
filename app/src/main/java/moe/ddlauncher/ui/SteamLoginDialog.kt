package moe.ddlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import moe.ddlauncher.AppViewModel

@Composable
fun SteamLoginDialog(
    state: AppViewModel.LoginState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onGuard: (String) -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = {
            Text(
                when (state.stage) {
                    AppViewModel.LoginStage.Credentials -> "登录 Steam"
                    AppViewModel.LoginStage.Guard -> "Steam Guard"
                    AppViewModel.LoginStage.Waiting -> "等待确认"
                }
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (state.stage) {
                    AppViewModel.LoginStage.Credentials -> {
                        Text(
                            "登录后可以搜索整个工坊、读取你的订阅，并且能直接订阅 mod（订阅会同步到你的 Steam 账号）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = user,
                            onValueChange = { user = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Steam 账号名") },
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pass,
                            onValueChange = { pass = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("密码") },
                            enabled = !state.busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "密码用 Steam 的公钥加密后直传，不保存在本机；登录成功后只保存一个 refresh token，" +
                                "可以随时在 Steam「设置 → 安全 → 已授权设备」里撤销。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AppViewModel.LoginStage.Guard -> {
                        Text(state.hint, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("验证码") },
                            enabled = !state.busy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "手机令牌是 App 里显示的 5 位字母数字；邮箱验证码是 5 位字符。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    AppViewModel.LoginStage.Waiting -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(state.hint.ifBlank { "正在确认…" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "如果手机上弹出了登录确认，点「允许」即可。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.error?.let { err ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                AppViewModel.LoginStage.Credentials -> TextButton(
                    onClick = { onLogin(user, pass) },
                    enabled = !state.busy && user.isNotBlank() && pass.isNotEmpty(),
                ) {
                    Text(if (state.busy) "登录中…" else "登录")
                }

                AppViewModel.LoginStage.Guard -> TextButton(
                    onClick = { onGuard(code) },
                    enabled = !state.busy && code.isNotBlank(),
                ) {
                    Text(if (state.busy) "验证中…" else "确认")
                }

                AppViewModel.LoginStage.Waiting -> TextButton(onClick = {}, enabled = false) { Text("请稍候") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.busy) { Text("取消") }
        },
    )
}
