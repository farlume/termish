package dev.termish.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.termish.data.AsrProvider
import dev.termish.data.AsrProviderType
import dev.termish.data.newId
import dev.termish.util.monospaceFontFamily
import dev.termish.voice.VolcAsrProtocol

/** 火山引擎语音识别控制台 API Key 管理页。 */
private const val VOLC_ASR_CONSOLE_URL =
    "https://console.volcengine.com/speech/new/setting/apikeys?projectName=default"

/**
 * 语音输入设置二级页：总开关 + **识别服务列表**（可插拔 provider：
 * 添加/编辑/删除/启停，每项独立密钥与参数；未来新增服务类型只需加枚举）。
 */
@Composable
fun SettingsVoiceScreen(
    enabled: Boolean,
    providers: List<AsrProvider>,
    /** 读取某 provider 的 API Key（平台安全存储）。 */
    apiKeyOf: (AsrProvider) -> String,
    onChangeEnabled: (Boolean) -> Unit,
    /** 增/改/删 provider。 */
    onAddProvider: (AsrProvider, apiKey: String) -> Unit,
    onUpdateProvider: (AsrProvider, apiKey: String) -> Unit,
    onDeleteProvider: (AsrProvider) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    // 编辑弹窗状态：null = 关闭；否则编辑该 provider（新建时 id 为空）
    var editing by remember { mutableStateOf<AsrProvider?>(null) }
    var isNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AsrProvider?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.voice.settingsVoiceTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 总开关
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onChangeEnabled(!enabled) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.voice.settingsVoiceEnabled,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = enabled, onCheckedChange = { onChangeEnabled(it) })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 服务列表
            if (providers.isEmpty()) {
                Text(
                    s.voice.providerEmpty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
                )
            } else {
                providers.forEachIndexed { i, p ->
                    AsrProviderRow(
                        provider = p,
                        apiKey = apiKeyOf(p),
                        onToggle = { onUpdateProvider(p.copy(enabled = !p.enabled), apiKeyOf(p)) },
                        onEdit = {
                            editing = p
                            isNew = false
                        },
                        onDelete = { pendingDelete = p },
                    )
                    if (i < providers.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }

            // 添加按钮
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        editing =
                            AsrProvider(
                                id = newId(),
                                type = AsrProviderType.VOLC_STREAMING,
                                name = "",
                                resourceId = VolcAsrProtocol.DEFAULT_RESOURCE_ID,
                                enabled = true,
                            )
                        isNew = true
                    }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    s.voice.providerAdd,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // 使用说明 + 控制台入口
            Text(
                s.voice.settingsVoiceHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            TextButton(onClick = { uriHandler.openUri(VOLC_ASR_CONSOLE_URL) }) {
                Text(
                    s.voice.settingsVoiceConsole,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(Modifier.height(24.dp))
        }
    }

    // 添加 / 编辑弹窗
    editing?.let { provider ->
        AsrProviderEditDialog(
            provider = provider,
            isNew = isNew,
            initialApiKey = if (isNew) "" else apiKeyOf(provider),
            onDismiss = { editing = null },
            onSave = { updated, key ->
                if (isNew) {
                    onAddProvider(updated, key)
                } else {
                    onUpdateProvider(updated, key)
                }
                editing = null
            },
        )
    }

    // 删除确认
    pendingDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(s.voice.providerDeleteConfirmTitle(p.name.ifBlank { providerTypeLabel(p.type, s) })) },
            text = { Text(s.voice.providerDeleteConfirmBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProvider(p)
                        pendingDelete = null
                    },
                ) { Text(s.terminalConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(s.terminalCancel) }
            },
        )
    }
}

/** 单条识别服务：名称/类型/密钥状态 + 开关 + 编辑/删除。 */
@Composable
private fun AsrProviderRow(
    provider: AsrProvider,
    apiKey: String,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalAppStrings.current
    val name = provider.name.ifBlank { providerTypeLabel(provider.type, s) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                providerIcon(provider.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                providerTypeLabel(provider.type, s) + if (apiKey.isBlank()) " · " + s.voice.providerNoKey else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = provider.enabled,
            onCheckedChange = { onToggle() },
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Text(
                s.voice.providerEdit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** 添加 / 编辑弹窗：名称 + API Key + 类型专属参数（火山：资源 ID）。 */
@Composable
private fun AsrProviderEditDialog(
    provider: AsrProvider,
    isNew: Boolean,
    initialApiKey: String,
    onDismiss: () -> Unit,
    onSave: (AsrProvider, String) -> Unit,
) {
    val s = LocalAppStrings.current
    var name by remember { mutableStateOf(provider.name) }
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var resourceId by remember { mutableStateOf(provider.resourceId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) s.voice.providerAdd else s.voice.providerEdit) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 类型（新建时选择；当前仅火山流式，后续扩展下拉）
                Text(
                    s.voice.providerTypeLabel + "：" + providerTypeLabel(provider.type, s),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(s.voice.providerName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(s.voice.settingsVoiceApiKey) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (provider.type == AsrProviderType.VOLC_STREAMING) {
                    OutlinedTextField(
                        value = resourceId,
                        onValueChange = { resourceId = it },
                        label = { Text(s.voice.settingsVoiceResourceId) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 常用资源 ID 快捷选择
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        VolcAsrProtocol.RESOURCE_IDS.forEach { rid ->
                            TextButton(onClick = { resourceId = rid }) {
                                Text(
                                    when (rid) {
                                        "volc.seedasr.sauc.duration" -> "2.0 小时"
                                        "volc.seedasr.sauc.concurrent" -> "2.0 并发"
                                        "volc.bigasr.sauc.duration" -> "1.0 小时"
                                        else -> "1.0 并发"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = apiKey.isNotBlank(),
                onClick = {
                    onSave(
                        provider.copy(
                            name = name.trim(),
                            resourceId = resourceId.trim().ifBlank { VolcAsrProtocol.DEFAULT_RESOURCE_ID },
                        ),
                        apiKey.trim(),
                    )
                },
            ) { Text(s.editSave) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.terminalCancel) }
        },
    )
}

/** provider 类型图标。 */
private fun providerIcon(type: AsrProviderType) =
    when (type) {
        AsrProviderType.VOLC_STREAMING -> Icons.Filled.Mic
    }

/** provider 类型显示名。 */
internal fun providerTypeLabel(
    type: AsrProviderType,
    s: AppStrings,
): String =
    when (type) {
        AsrProviderType.VOLC_STREAMING -> s.voice.providerTypeVolc
    }
