package dev.termish.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.termish.APP_VERSION
import dev.termish.data.AppSettings
import dev.termish.data.ThemeMode
import dev.termish.ui.theme.TerminalThemes

// ---- 关于区外链（官网；文档/GitHub 待仓库公开后再加回） ----
private const val WEBSITE_URL = "https://termish.dev"
private const val WEBSITE_HOST = "termish.dev"

private fun themeModeLabel(mode: ThemeMode, s: AppStrings): String = when (mode) {
    ThemeMode.DARK -> s.settingsThemeDark
    ThemeMode.LIGHT -> s.settingsThemeLight
    ThemeMode.SYSTEM -> s.settingsThemeSystem
}

private fun languageLabel(code: String, s: AppStrings): String = when (code) {
    "zh" -> s.languageZh
    "en" -> s.languageEn
    else -> s.settingsLanguageSystem
}

@Composable
fun SettingsScreen(
    settings: AppSettings,
    /** 任何修改立即回调（即改即存，无保存按钮）。 */
    onChange: (AppSettings) -> Unit,
    /** tab 内嵌模式下不显示返回箭头。 */
    showBack: Boolean = false,
    onBack: (() -> Unit)? = null,
) {
    val s = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    var theme by remember { mutableStateOf(settings.theme) }
    var terminalThemeIndex by remember { mutableStateOf(settings.terminalThemeIndex) }
    var fontSize by remember { mutableStateOf(settings.terminalFontSize.toFloat()) }
    var targetCols by remember { mutableStateOf(settings.terminalTargetCols.toFloat()) }
    var keyboardToolbar by remember { mutableStateOf(settings.keyboardToolbarVisible) }
    var haptics by remember { mutableStateOf(settings.hapticFeedback) }
    var autoReconnect by remember { mutableStateOf(settings.autoReconnect) }
    var verifyHostKey by remember { mutableStateOf(settings.verifyHostKeyOnFirstUse) }
    var osc52Clipboard by remember { mutableStateOf(settings.osc52Clipboard) }
    var language by remember { mutableStateOf(settings.language) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showTerminalThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    fun persist() = onChange(
        settings.copy(
            theme = theme,
            terminalThemeIndex = terminalThemeIndex,
            terminalFontSize = fontSize.toInt(),
            terminalTargetCols = targetCols.toInt(),
            keyboardToolbarVisible = keyboardToolbar,
            hapticFeedback = haptics,
            autoReconnect = autoReconnect,
            verifyHostKeyOnFirstUse = verifyHostKey,
            osc52Clipboard = osc52Clipboard,
            language = language,
        )
    )

    Scaffold(
        topBar = { TermishLargeHeader(title = s.settingsTitle) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup(s.settingsGroupAppearance) {
                SettingsOptionItem(s.settingsAppTheme, themeModeLabel(theme, s)) { showThemeDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsTerminalPalette,
                    TerminalThemes.ALL.getOrElse(terminalThemeIndex) { TerminalThemes.ALL[0] }.name,
                ) { showTerminalThemeDialog = true }
            }

            SettingsGroup(s.settingsGroupTerminal) {
                SettingsSliderItem(
                    title = s.settingsTerminalCols,
                    valueText = if (targetCols < 1f) s.settingsManualFontSize else targetCols.toInt().toString(),
                    value = targetCols,
                    onValueChange = { targetCols = it; persist() },
                    valueRange = 0f..160f,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSliderItem(
                    title = s.settingsTerminalFontSize,
                    valueText = "${fontSize.toInt()}sp",
                    value = fontSize,
                    onValueChange = { fontSize = it; persist() },
                    valueRange = 6f..32f,
                    enabled = targetCols < 1f,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsShowToolbar, keyboardToolbar) { keyboardToolbar = it; persist() }
            }

            SettingsGroup(s.settingsGroupGeneral) {
                SettingsOptionItem(s.settingsLanguage, languageLabel(language, s)) { showLanguageDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsHaptics, haptics) { haptics = it; persist() }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsAutoReconnect, autoReconnect) { autoReconnect = it; persist() }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsVerifyHostKey, verifyHostKey) { verifyHostKey = it; persist() }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsOsc52Clipboard, osc52Clipboard) { osc52Clipboard = it; persist() }
            }

            // 关于：设置页底部固定生态位（业界惯例：Termius/Blink 等均在底部放
            // 版本 + 官网/文档/仓库入口）。点击跳浏览器，三平台统一走 LocalUriHandler。
            SettingsGroup(s.settingsGroupAbout) {
                SettingsOptionItem(s.settingsVersion, APP_VERSION) {}
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsWebsite, WEBSITE_HOST) { uriHandler.openUri(WEBSITE_URL) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showThemeDialog) {
        SettingsChoiceDialog(
            title = s.settingsAppTheme,
            options = ThemeMode.entries.map { themeModeLabel(it, s) },
            selected = ThemeMode.entries.indexOf(theme),
            onSelect = { theme = ThemeMode.entries[it]; persist() },
            onDismiss = { showThemeDialog = false },
        )
    }
    if (showTerminalThemeDialog) {
        SettingsChoiceDialog(
            title = s.settingsTerminalPalette,
            options = TerminalThemes.ALL.map { it.name },
            selected = terminalThemeIndex,
            onSelect = { terminalThemeIndex = it; persist() },
            onDismiss = { showTerminalThemeDialog = false },
        )
    }
    if (showLanguageDialog) {
        val codes = listOf("", "zh", "en")
        val options = listOf(
            s.settingsLanguageSystem,
            s.languageZh,
            s.languageEn,
        )
        SettingsChoiceDialog(
            title = s.settingsLanguage,
            options = options,
            selected = codes.indexOf(language),
            onSelect = { language = codes[it]; persist() },
            onDismiss = { showLanguageDialog = false },
        )
    }
}

/** 卡片分组：外间距 + 圆角 + 组标题。 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 6.dp),
    )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Column(content = content)
    }
}

/** 选项型设置行：标题 + 当前值 + 右箭头。 */
@Composable
private fun SettingsOptionItem(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

/** 数值型设置行：标题 + 当前值，下方滑杆。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSliderItem(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.85f else 0.4f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.5f else 0.3f),
            )
        }
        // 圆头细轨滑杆（默认竖条 thumb 在列表里太突兀）
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            thumb = {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    ),
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.3f),
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                    ),
                )
            },
        )
    }
}

/** 开关型设置行。 */
@Composable
private fun SettingsSwitchItem(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
        Switch(checked, onCheckedChange)
    }
}

/** 单选弹窗：点选即生效并关闭。 */
@Composable
private fun SettingsChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onSelect(i)
                            onDismiss()
                        }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(LocalAppStrings.current.settingsClose) } },
    )
}
