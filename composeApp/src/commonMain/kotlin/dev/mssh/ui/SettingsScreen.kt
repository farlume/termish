package dev.mssh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mssh.data.AppSettings
import dev.mssh.data.ThemeMode
import dev.mssh.ui.theme.TerminalThemes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onCancel: () -> Unit,
) {
    var theme by remember { mutableStateOf(settings.theme) }
    var terminalThemeIndex by remember { mutableStateOf(settings.terminalThemeIndex) }
    var fontSize by remember { mutableStateOf(settings.terminalFontSize.toFloat()) }
    var targetCols by remember { mutableStateOf(settings.terminalTargetCols.toFloat()) }
    var keyboardToolbar by remember { mutableStateOf(settings.keyboardToolbarVisible) }
    var haptics by remember { mutableStateOf(settings.hapticFeedback) }
    var verifyHostKey by remember { mutableStateOf(settings.verifyHostKeyOnFirstUse) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("取消") } },
                actions = {
                    TextButton(onClick = {
                        onSave(
                            settings.copy(
                                theme = theme,
                                terminalThemeIndex = terminalThemeIndex,
                                terminalFontSize = fontSize.toInt(),
                                terminalTargetCols = targetCols.toInt(),
                                keyboardToolbarVisible = keyboardToolbar,
                                hapticFeedback = haptics,
                                verifyHostKeyOnFirstUse = verifyHostKey,
                            )
                        )
                    }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("应用主题", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == ThemeMode.DARK, { theme = ThemeMode.DARK }); Text("深色")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == ThemeMode.LIGHT, { theme = ThemeMode.LIGHT }); Text("浅色")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == ThemeMode.SYSTEM, { theme = ThemeMode.SYSTEM }); Text("跟随系统")
            }

            Text("终端配色", style = MaterialTheme.typography.labelLarge)
            Column {
                TerminalThemes.ALL.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(terminalThemeIndex == i, { terminalThemeIndex = i })
                        Text(t.name)
                    }
                }
            }

            Text("终端列数：${if (targetCols < 1f) "手动字号" else targetCols.toInt().toString()}（和电脑终端对齐就填 120）", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = targetCols,
                onValueChange = { targetCols = it },
                valueRange = 0f..160f,
            )

            Text("终端字号：${fontSize.toInt()}sp${if (targetCols >= 1f) "（已设列数，此项不生效）" else ""}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it },
                valueRange = 6f..32f,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("显示键盘工具栏")
                Switch(keyboardToolbar, { keyboardToolbar = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("触感反馈")
                Switch(haptics, { haptics = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("首次连接确认主机指纹")
                Switch(verifyHostKey, { verifyHostKey = it })
            }
        }
    }
}
