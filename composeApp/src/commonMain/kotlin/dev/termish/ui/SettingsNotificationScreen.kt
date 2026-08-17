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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.termish.notify.NotificationEvent
import dev.termish.notify.openNotificationSettings
import dev.termish.notify.requestNotificationPermission
import dev.termish.util.monospaceFontFamily

/**
 * 通知设置二级页面：总开关 + 事件级开关（统一管理，业务方经
 * NotificationCenter.post 调用）+ 系统通知设置入口。
 */
@Composable
fun SettingsNotificationScreen(
    enabled: Boolean,
    disabledEvents: Set<String>,
    onChangeEnabled: (Boolean) -> Unit,
    onChangeEvent: (String, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val s = LocalAppStrings.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = s.navBack)
            }
            Text(
                s.settingsNotificationsTitle,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = monospaceFontFamily(),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // 总开关：打开时请求系统通知权限（Android 13+ / iOS）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        val next = !enabled
                        onChangeEnabled(next)
                        if (next) requestNotificationPermission()
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.settingsNotificationsEnabled,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        onChangeEnabled(it)
                        if (it) requestNotificationPermission()
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Text(
                s.settingsNotificationsEvents,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            // 事件级开关：统一由 NotificationCenter 读取
            NotificationEvent.entries.forEachIndexed { i, event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onChangeEvent(event.id, event.id in disabledEvents)
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        eventLabel(event, s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = event.id !in disabledEvents,
                        onCheckedChange = { onChangeEvent(event.id, !it) },
                    )
                }
                if (i < NotificationEvent.entries.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }

            // 系统通知设置（权限被拒时的出口）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { openNotificationSettings() }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.settingsNotificationsSystem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun eventLabel(event: NotificationEvent, s: AppStrings): String = when (event) {
    NotificationEvent.CONNECTION_LOST -> s.notificationEventConnectionLost
    NotificationEvent.RECONNECT_FAILED -> s.notificationEventReconnectFailed
    NotificationEvent.TRANSFER_DONE -> s.notificationEventTransferDone
    NotificationEvent.AGENT_TASK -> s.notificationEventAgentTask
}
