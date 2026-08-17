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
import dev.termish.util.TermLog
import dev.termish.generated.resources.Res
import dev.termish.generated.resources.alipay_qr
import dev.termish.generated.resources.wechat_qr
import org.jetbrains.compose.resources.painterResource
import dev.termish.data.ThemeMode
import dev.termish.ui.theme.TerminalThemes

// ---- 关于区外链（官网 / 联系邮箱 / 支付宝收款；文档/GitHub 待仓库公开后再加回） ----
private const val WEBSITE_URL = "https://termish.dev"
private const val WEBSITE_HOST = "termish.dev"
private const val CONTACT_EMAIL = "ttermish@gmail.com"
/** 支付宝收款码链接：Android/iOS 上 qr.alipay.com 为 universal link，直接唤起支付宝 App。 */
private const val ALIPAY_URL = "https://qr.alipay.com/fkx12790skh105nviquzaea"

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
    var terminalType by remember { mutableStateOf(settings.terminalType) }
    var haptics by remember { mutableStateOf(settings.hapticFeedback) }
    var autoReconnect by remember { mutableStateOf(settings.autoReconnect) }
    var keepalive by remember { mutableStateOf(settings.keepaliveSeconds.toFloat()) }
    var verifyHostKey by remember { mutableStateOf(settings.verifyHostKeyOnFirstUse) }
    var osc52Clipboard by remember { mutableStateOf(settings.osc52Clipboard) }
    var language by remember { mutableStateOf(settings.language) }

    var showSupportDialog by remember { mutableStateOf(false) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showTerminalPage by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTerminalTypeDialog by remember { mutableStateOf(false) }
    var showNotificationPage by remember { mutableStateOf(false) }
    var showDiagnosticsPage by remember { mutableStateOf(false) }
    var notificationEnabled by remember { mutableStateOf(settings.notificationEnabled) }
    var notificationDisabledEvents by remember { mutableStateOf(settings.notificationDisabledEvents) }

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
                ) { showNotificationPage = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(
                    s.settingsDiagnostics,
                    if (TermLog.diagnosticsEnabled) s.settingsOn else s.settingsOff,
                ) { showDiagnosticsPage = true }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsSwitchItem(s.settingsHaptics, haptics) { haptics = it; persist() }
            }

            SettingsGroup(s.settingsGroupTerminal) {
                SettingsOptionItem(
                    s.settingsTerminalPalette,
                    TerminalThemes.ALL.getOrElse(terminalThemeIndex) { TerminalThemes.ALL[0] }.name,
                ) { showTerminalPage = true }
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
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                SettingsOptionItem(s.settingsSupport, "Alipay / WeChat") { showSupportDialog = true }
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
    // 二级页面覆盖层：iOS 风格右滑进入/退出（Termius 二级页面同款动效）
    AnimatedVisibility(
        visible = showTerminalPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsTerminalScreen(
            currentThemeIndex = terminalThemeIndex,
            currentFontId = settings.terminalFontId,
            onChangeTheme = { terminalThemeIndex = it; persist() },
            onChangeFont = { fontId -> onChange(settings.copy(terminalFontId = fontId)) },
            onBack = { showTerminalPage = false },
        )
    }
    // 通知设置二级页（动画同终端主题页：右滑进入/退出）
    AnimatedVisibility(
        visible = showNotificationPage,
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
            onBack = { showNotificationPage = false },
        )
    }
    AnimatedVisibility(
        visible = showDiagnosticsPage,
        enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(animationSpec = tween(240)),
        exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(animationSpec = tween(200)),
    ) {
        SettingsDiagnosticsScreen(
            enabled = TermLog.diagnosticsEnabled,
            onChangeEnabled = { TermLog.diagnosticsEnabled = it },
            onBack = { showDiagnosticsPage = false },
        )
    }
    if (showSupportDialog) {
        SupportDialog(onDismiss = { showSupportDialog = false })
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

/** 支持作者弹窗：支付宝 / 微信双收款码。支付宝有 universal link 直链唤起；
 *  微信无公开调起链接，只能保存图片后相册识别（提示文案）。 */
@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val s = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    var tab by remember { mutableStateOf(0) } // 0=支付宝，1=微信
    // 支付宝 tab 才有直链按钮；微信无公开调起链接，confirmButton 渲染空占位。
    // material3 AlertDialog 的 confirmButton 为非空参数，不能传 null。
    val confirmButton: @Composable () -> Unit = if (tab == 0) {
        ({
            Button(onClick = {
                onDismiss()
                uriHandler.openUri(ALIPAY_URL)
            }) { Text(s.settingsOpenAlipay) }
        })
    } else {
        ({})
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.settingsSupport) },
        text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    SupportTab(s.settingsTabAlipay, selected = tab == 0) { tab = 0 }
                    Spacer(Modifier.width(8.dp))
                    SupportTab(s.settingsTabWechat, selected = tab == 1) { tab = 1 }
                }
                Spacer(Modifier.height(12.dp))
                Image(
                    painterResource(if (tab == 0) Res.drawable.alipay_qr else Res.drawable.wechat_qr),
                    contentDescription = s.settingsSupport,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                )
                if (tab == 1) {
                    Text(
                        s.settingsSupportHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        },
        confirmButton = confirmButton,
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.settingsClose) } },
    )
}

/** 支持弹窗内的渠道切换 chip。 */
@Composable
private fun SupportTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
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
