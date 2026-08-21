package dev.termish.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.termish.APP_VERSION
import dev.termish.data.AppSettings
import dev.termish.data.HostRepository
import dev.termish.data.SECRET_SERVICE
import dev.termish.data.SecretStore
import dev.termish.data.ThemeMode
import dev.termish.data.asrKeyAccount
import dev.termish.util.TermLog
import dev.termish.generated.resources.Res
import dev.termish.ui.theme.TerminalThemes

// ---- 关于区外链（官网 / 联系邮箱；文档/GitHub 入口待补充） ----
private const val WEBSITE_URL = "https://termish.dev"
private const val WEBSITE_HOST = "termish.dev"
private const val CONTACT_EMAIL = "ttermish@gmail.com"

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
    /** 片段/标签管理页读写仓库。 */
    repository: HostRepository = HostRepository(),
    /** 当前打开的二级页（AppRoot 统一管理：返回链 + 全屏隐藏 tab）。 */
    subPage: SettingsSubPage? = null,
    /** 打开/关闭二级页（null = 关闭）。 */
    onOpenSub: (SettingsSubPage?) -> Unit = {},
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
    var terminalType by remember { mutableStateOf(settings.terminalType) }
    var haptics by remember { mutableStateOf(settings.hapticFeedback) }
    var autoReconnect by remember { mutableStateOf(settings.autoReconnect) }
    var keepalive by remember { mutableStateOf(settings.keepaliveSeconds.toFloat()) }
    var verifyHostKey by remember { mutableStateOf(settings.verifyHostKeyOnFirstUse) }
    var osc52Clipboard by remember { mutableStateOf(settings.osc52Clipboard) }
    var language by remember { mutableStateOf(settings.language) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTerminalTypeDialog by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(settings.notificationEnabled) }
    var notificationDisabledEvents by remember { mutableStateOf(settings.notificationDisabledEvents) }
    var voiceInputEnabled by remember { mutableStateOf(settings.voiceInputEnabled) }
    // 识别服务列表（可插拔 provider）；密钥按 provider 存 SecretStore
    var asrProviders by remember { mutableStateOf(settings.asrProviders) }

    fun persist() = onChange(
        settings.copy(
            theme = theme,
            terminalThemeIndex = terminalThemeIndex,
            terminalFontSize = fontSize.toInt(),
            terminalTargetCols = targetCols.toInt(),
            terminalType = terminalType,
            hapticFeedback = haptics,
            autoReconnect = autoReconnect,
            keepaliveSeconds = keepalive.toInt(),
            verifyHostKeyOnFirstUse = verifyHostKey,
            osc52Clipboard = osc52Clipboard,
            notificationEnabled = notificationEnabled,
            notificationDisabledEvents = notificationDisabledEvents,
            voiceInputEnabled = voiceInputEnabled,
            asrProviders = asrProviders,
            language = language,
        )
    )

    Scaffold(
        topBar = { TermishLargeHeader(title = s.settingsTitle) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // 通用组（原外观 + 原通用合并）：应用主题 / 语言 / 触感反馈
            SettingsGroup(s.settingsGroupGeneral) {
                SettingsOptionItem(s.settingsAppTheme, themeModeLabel(theme, s)) { showThemeDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsLanguage, languageLabel(language, s)) { showLanguageDialog = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsNotifications,
                    if (notificationEnabled) s.settingsOn else s.settingsOff,
                ) { onOpenSub(SettingsSubPage.NOTIFICATION) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsDiagnostics,
                    if (TermLog.diagnosticsEnabled) s.settingsOn else s.settingsOff,
                ) { onOpenSub(SettingsSubPage.DIAGNOSTICS) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsSnippets,
                    "",
                ) { onOpenSub(SettingsSubPage.SNIPPETS) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.voice.settingsVoice,
                    if (voiceInputEnabled) {
                        s.voice.providerCount(asrProviders.count { it.enabled })
                    } else {
                        s.settingsOff
                    },
                ) { onOpenSub(SettingsSubPage.VOICE) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsHaptics, haptics) { haptics = it; persist() }
            }

            SettingsGroup(s.settingsGroupTerminal) {
                SettingsOptionItem(
                    s.settingsTerminalPalette,
                    TerminalThemes.ALL.getOrElse(terminalThemeIndex) { TerminalThemes.ALL[0] }.name,
                ) { onOpenSub(SettingsSubPage.TERMINAL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsTerminalType, terminalType) {
                    showTerminalTypeDialog = true
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
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
                SettingsSwitchItem(s.settingsOsc52Clipboard, osc52Clipboard) { osc52Clipboard = it; persist() }
            }

            SettingsGroup(s.settingsGroupSession) {
                SettingsSliderItem(
                    title = s.settingsKeepalive,
                    valueText = if (keepalive < 1f) s.settingsKeepaliveOff else "${keepalive.toInt()}s",
                    value = keepalive,
                    onValueChange = { keepalive = it; persist() },
                    valueRange = 0f..300f,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsAutoReconnect, autoReconnect) { autoReconnect = it; persist() }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsVerifyHostKey, verifyHostKey) { verifyHostKey = it; persist() }
            }

            // 关于：设置页底部固定生态位（业界惯例：Termius/Blink 等均在底部放
            // 版本 + 官网/文档/仓库入口）。点击跳浏览器，三平台统一走 LocalUriHandler。
            SettingsGroup(s.settingsGroupAbout) {
                SettingsOptionItem(s.settingsVersion, APP_VERSION) {}
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsWebsite, WEBSITE_HOST) { uriHandler.openUri(WEBSITE_URL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsContact, CONTACT_EMAIL) { uriHandler.openUri("mailto:$CONTACT_EMAIL") }
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
    // 二级页面覆盖层：iOS 风格右滑进入/退出（Termius 二级页面同款动效）；
    // 开关状态由 AppRoot 统一管理（返回链 + 全屏隐藏 tab）
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.TERMINAL,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsTerminalScreen(
            currentThemeIndex = terminalThemeIndex,
            currentFontId = settings.terminalFontId,
            onChangeTheme = { terminalThemeIndex = it; persist() },
            onChangeFont = { fontId -> onChange(settings.copy(terminalFontId = fontId)) },
            onBack = { onOpenSub(null) },
        )
    }
    // 通知设置二级页（动画同终端主题页：右滑进入/退出）
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.NOTIFICATION,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsNotificationScreen(
            enabled = notificationEnabled,
            disabledEvents = notificationDisabledEvents,
            onChangeEnabled = {
                notificationEnabled = it
                persist()
            },
            onChangeEvent = { id, disable ->
                notificationDisabledEvents =
                    if (disable) notificationDisabledEvents + id else notificationDisabledEvents - id
                persist()
            },
            onBack = { onOpenSub(null) },
        )
    }
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.DIAGNOSTICS,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsDiagnosticsScreen(
            enabled = TermLog.diagnosticsEnabled,
            onChangeEnabled = { TermLog.diagnosticsEnabled = it },
            onBack = { onOpenSub(null) },
        )
    }
    // 命令片段管理二级页（全局库 + 标签组管理入口）
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.SNIPPETS,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SnippetManageScreen(
            repository = repository,
            onBack = { onOpenSub(null) },
        )
    }
    // 语音输入设置二级页（火山引擎流式语音识别：开关 / API Key / 资源 ID）
    AnimatedVisibility(
        visible = subPage == SettingsSubPage.VOICE,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsVoiceScreen(
            enabled = voiceInputEnabled,
            providers = asrProviders,
            apiKeyOf = { p -> SecretStore.get(SECRET_SERVICE, asrKeyAccount(p.id)) ?: "" },
            onChangeEnabled = {
                voiceInputEnabled = it
                persist()
            },
            onAddProvider = { p, key ->
                asrProviders = asrProviders + p
                SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), key)
                persist()
            },
            onUpdateProvider = { p, key ->
                asrProviders = asrProviders.map { if (it.id == p.id) p else it }
                // 密钥为空 = 保持不变；非空 = 覆盖
                if (key.isNotBlank()) {
                    SecretStore.set(SECRET_SERVICE, asrKeyAccount(p.id), key)
                }
                persist()
            },
            onDeleteProvider = { p ->
                asrProviders = asrProviders.filter { it.id != p.id }
                SecretStore.delete(SECRET_SERVICE, asrKeyAccount(p.id))
                persist()
            },
            onBack = { onOpenSub(null) },
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
    if (showTerminalTypeDialog) {
        // TERM 选项：xterm-256color 默认（匹配模拟器 256 色能力）；
        // 其余为兼容备选（服务器按 terminfo 降级，不新增渲染能力需求）
        val options = listOf("xterm-256color", "xterm", "vt100", "linux")
        SettingsChoiceDialog(
            title = s.settingsTerminalType,
            options = options,
            selected = options.indexOf(terminalType).coerceAtLeast(0),
            onSelect = { terminalType = options[it]; persist() },
            onDismiss = { showTerminalTypeDialog = false },
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
    // top 14dp 与其他行一致；bottom 7dp 补偿 Slider 内部 thumb 下方空间
    // （thumb 18dp 在 32dp 内居中，下缘距 Slider 底约 7dp）——
    // 视觉上 thumb 下缘到下一行的空白与其他行文本底到下一行一致
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 7.dp)) {
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
        // 圆头细轨滑杆（默认竖条 thumb 在列表里太突兀）；
        // height(32.dp)：默认 48dp 触摸目标让行高 ~96dp，远超其他行——
        // 设置页滑杆压缩高度与 OptionItem 行高接近（仍可拖动）
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().requiredHeight(32.dp),
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
    // vertical 8dp：行高 = Switch 32dp + 16 = 48dp，与 OptionItem（文本 20 + 28）一致——
    // 否则 Switch 行距比其他行大 12dp（用户反馈上下间距偏大）
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
