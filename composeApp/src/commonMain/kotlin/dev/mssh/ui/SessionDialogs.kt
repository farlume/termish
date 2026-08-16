package dev.mssh.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.mssh.ssh.AuthPrompt
import dev.mssh.ssh.HostKeyInfo

/**
 * 会话级弹窗（认证 / 主机密钥确认）。从 TerminalScreen 提升到全局：
 * 首页发起连接等待跳转时也会弹出授权，不必先进入终端页。
 */

@Composable
fun AuthPromptDialog(prompt: AuthPrompt, onResult: (List<String>?) -> Unit) {
    val s = LocalAppStrings.current
    var values by remember { mutableStateOf(List(prompt.prompts.size) { "" }) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(if (prompt.name.isNotEmpty()) prompt.name else s.terminalAuthRequired) },
        text = {
            Column {
                if (prompt.instruction.isNotEmpty()) {
                    Text(prompt.instruction, style = MaterialTheme.typography.bodySmall)
                }
                prompt.prompts.forEachIndexed { i, field ->
                    OutlinedTextField(
                        value = values.getOrElse(i) { "" },
                        onValueChange = { v ->
                            val list = values.toMutableList()
                            list[i] = v
                            values = list
                        },
                        label = { Text(field.label) },
                        visualTransformation = if (field.echo) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onResult(values) }) { Text(s.terminalConfirm) } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text(s.terminalCancel) } },
    )
}

@Composable
fun HostKeyDialog(
    key: HostKeyInfo,
    changed: Boolean,
    previousFingerprint: String?,
    onResult: (Boolean) -> Unit,
) {
    val s = LocalAppStrings.current
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(if (changed) s.terminalHostkeyChanged else s.terminalHostkeyConfirm) },
        text = {
            Column {
                Text(
                    if (changed) s.terminalHostkeyChangedBody
                    else s.terminalHostkeyBody
                )
                if (changed && previousFingerprint != null) {
                    Text(
                        s.terminalHostkeySaved + " " + previousFingerprint,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    (if (changed) s.terminalHostkeyCurrent else key.algorithm) + "\n" + key.fingerprintSha256,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onResult(true) }) { Text(s.terminalTrust) } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text(s.terminalCancel) } },
    )
}
