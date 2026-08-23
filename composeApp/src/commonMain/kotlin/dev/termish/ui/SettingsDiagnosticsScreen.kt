package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.util.TermLog
import dev.termish.util.monospaceFontFamily
import dev.termish.util.shareDiagnosticLogs

/**
 * 诊断设置二级页：诊断日志开关（release 也可开启，写文件）+ 导出分享。
 */
@Composable
fun SettingsDiagnosticsScreen(
    enabled: Boolean,
    onChangeEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    // TermLog.diagnosticsEnabled 是普通属性（非 Compose state），赋值不触发重组：
    // 必须用本地 state 承接，点击开关才能即时刷新（历史教训：点击后视觉不变，
    // 退出重进页面才更新）。
    var logEnabled by remember { mutableStateOf(enabled) }

    fun setEnabled(value: Boolean) {
        logEnabled = value
        onChangeEnabled(value)
    }
    // 不 remember：每次重组读最新（开关开启后日志路径才出现）
    val logPath = TermLog.logFilePath
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.settingsDiagnosticsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { setEnabled(!logEnabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.settingsDiagnosticsEnabled,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = logEnabled, onCheckedChange = { setEnabled(it) })
            }
            Text(
                s.settingsDiagnosticsHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 导出（仅日志文件存在时可用）
            TextButton(
                onClick = {
                    shareDiagnosticLogs()
                },
                enabled = logEnabled && logPath != null,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(s.settingsDiagnosticsExport)
            }
            if (logPath != null) {
                Text(
                    logPath!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}
